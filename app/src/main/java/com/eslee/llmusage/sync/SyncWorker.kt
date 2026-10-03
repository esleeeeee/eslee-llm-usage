package com.eslee.llmusage.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.web.WebTrace
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.widget.finishWidgetRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun allowsForeground(context: Context, settings: AppSettings): Boolean = allowsAutomaticSync(
        settings,
        context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered,
    )

    fun schedule(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        if (settings.intervalMinutes == 0L) {
            manager.cancelUniqueWork("llm_usage_periodic_sync")
            manager.cancelUniqueWork("llm_usage_consumer_sync")
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setInputData(workDataOf("scope" to "api"))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("llm_usage_periodic_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
        val consumers = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("scope" to "web"))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("llm_usage_consumer_sync", ExistingPeriodicWorkPolicy.UPDATE, consumers)
    }

    /**
     * The widget's refresh button: every account, once, however many times it is
     * tapped. Expedited, because a tap is a request to start now: ordinary work
     * from an app the system has put in a low standby bucket can wait for hours.
     * Offline too, so the pass can report the failure and release its indicator.
     */
    fun refreshAll(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("sync_all", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build())
    }

    fun refresh(context: Context, id: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("sync_$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("accountId" to id))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runStarted = System.currentTimeMillis()
        val clock = SystemClock.elapsedRealtime()
        val repository = (applicationContext as UsageApplication).graph.repository
        val id = inputData.getString("accountId")
        val scope = inputData.getString("scope")
        val manual = id == null && scope == null
        var failed = 0
        return completeRefreshPass(
            ownsIndicator = manual,
            finish = { completed -> finishWidgetRefresh(applicationContext, ok = completed && failed == 0) },
        ) {
            try {
                if (id == null) failed = repository.refreshAll(when (scope) { "web" -> true; "api" -> false; else -> null })
                else {
                    repository.refresh(id)
                    if (repository.account(id)?.lastErrorCode != null) failed = 1
                }
                // One line per pass, kept across restarts, is how a user can tell whether the schedule runs at all.
                WebTrace.record("pass", "${scope ?: if (manual) "manual" else "account"} failed=$failed ${SystemClock.elapsedRealtime() - clock}ms attempt=$runAttemptCount")
                // A pass someone asked for reports once, on the button. Retrying it later,
                // out of sight, left the button's colour saying nothing about the numbers.
                if (manual) return@completeRefreshPass Result.success()
                val retry = if (id != null) repository.account(id)?.lastErrorCode in RETRYABLE
                    else repository.logs().any { it.startedAt >= runStarted && it.resultCode in RETRYABLE }
                if (retry && runAttemptCount < 4) Result.retry() else Result.success()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failed = maxOf(failed, 1)
                WebTrace.record("pass", "${scope ?: if (manual) "manual" else "account"} error ${error.javaClass.simpleName}")
                if (!manual && runAttemptCount < 4) Result.retry() else Result.failure()
            }
        }
    }

    /** Only used below Android 12, where expedited work runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.sync_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(applicationContext.getString(R.string.sync_notification))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        val RETRYABLE = setOf("RATE_LIMITED", "NETWORK", "NETWORK_TIMEOUT")
        const val CHANNEL = "sync"
        const val NOTIFICATION_ID = 0x4C4C
    }
}

/** Release the manual-refresh indicator even when a worker is cancelled or has no accounts. */
internal suspend fun <T> completeRefreshPass(
    ownsIndicator: Boolean,
    finish: suspend (completed: Boolean) -> Unit,
    collect: suspend () -> T,
): T {
    var completed = false
    return try {
        collect().also { completed = true }
    } finally {
        if (ownsIndicator) withContext(NonCancellable) {
            // Cancellation still releases the indicator, but cannot claim a completed collection.
            // Widget host failures must not turn a successfully persisted collection into a retry.
            try { finish(completed) } catch (_: Exception) { }
        }
    }
}

/** Cost policy, not a Wi-Fi transport test. Keep the stored wifiOnly key for compatibility. */
internal fun allowsAutomaticSync(settings: AppSettings, isMetered: Boolean): Boolean =
    settings.intervalMinutes != 0L && (!settings.wifiOnly || !isMetered)
