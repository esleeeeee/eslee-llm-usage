package com.eslee.llmusage.sync

import android.content.Context
import androidx.work.*
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.settings.AppSettings
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun schedule(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        if (settings.intervalMinutes == 0L) { manager.cancelUniqueWork("llm_usage_periodic_sync"); return }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("llm_usage_periodic_sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun refresh(context: Context, id: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("sync_$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("accountId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as UsageApplication).graph.repository
        return try {
            val id = inputData.getString("accountId")
            if (id == null) repository.refreshAll() else repository.refresh(id)
            val retry = if (id != null) repository.account(id)?.lastErrorCode in setOf("RATE_LIMITED", "NETWORK", "NETWORK_TIMEOUT")
                else repository.logs().take(20).any { it.startedAt >= System.currentTimeMillis() - 60_000 && it.resultCode in setOf("RATE_LIMITED", "NETWORK", "NETWORK_TIMEOUT") }
            if (retry && runAttemptCount < 4) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 4) Result.retry() else Result.failure() }
    }
}
