package com.eslee.llmusage.sync

import android.content.Context
import androidx.work.*
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.widget.finishWidgetRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun allowsForeground(context: Context, settings: AppSettings): Boolean = settings.intervalMinutes != 0L &&
        (!settings.wifiOnly || !context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered)

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
    /** The widget's refresh button: every account, once, however many times it is tapped. */
    fun refreshAll(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("sync_all", ExistingWorkPolicy.KEEP,
            // Run offline too, so a manual tap can report failure and release its indicator.
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
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
        val repository = (applicationContext as UsageApplication).graph.repository
        return completeRefreshPass(
            ownsIndicator = inputData.getString("accountId") == null && inputData.getString("scope") == null,
            finish = { finishWidgetRefresh(applicationContext) },
        ) {
            try {
                val id = inputData.getString("accountId")
                if (id == null) repository.refreshAll(when(inputData.getString("scope")) { "web" -> true; "api" -> false; else -> null })
                else repository.refresh(id)
                val retry = if (id != null) repository.account(id)?.lastErrorCode in setOf("RATE_LIMITED", "NETWORK", "NETWORK_TIMEOUT")
                    else repository.logs().any { it.startedAt >= runStarted && it.resultCode in setOf("RATE_LIMITED", "NETWORK", "NETWORK_TIMEOUT") }
                if (retry && runAttemptCount < 4) Result.retry() else Result.success()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (runAttemptCount < 4) Result.retry() else Result.failure() }
        }
    }
}

/** Release the manual-refresh indicator even when a worker is cancelled or has no accounts. */
internal suspend fun <T> completeRefreshPass(
    ownsIndicator: Boolean,
    finish: suspend () -> Unit,
    collect: suspend () -> T,
): T = try {
    collect()
} finally {
    if (ownsIndicator) withContext(NonCancellable) {
        // Widget host failures must not turn a successfully persisted collection into a retry.
        try { finish() } catch (_: Exception) { }
    }
}
