package com.eslee.llmusage.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WidgetRefreshIntegrationTest {
    @Test fun boundWidgetRefreshRunsWorkerAndUpdatesBothSelectedAccounts(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as UsageApplication).graph
        val repository = graph.repository
        val work = WorkManager.getInstance(context)
        // A global refresh deliberately visits every enabled account. Require an isolated fixture
        // instead of disabling or contacting a user's real accounts during this integration test.
        assertTrue("Run on a test installation without enabled real accounts",
            repository.accounts.first().none { it.account.enabled && it.account.providerId != "demo" })
        suspend fun workInfos() = withContext(Dispatchers.IO) {
            work.getWorkInfosForUniqueWork("sync_all").get(10, TimeUnit.SECONDS)
        }
        assertTrue("A pre-existing manual refresh is running", workInfos().all { it.state.isFinished })
        val previous = graph.settings.current()
        val host = AppWidgetHost(context, 0x45534C45)
        val accountIds = mutableListOf<String>()
        var widgetId: Int? = null
        val ownedWork = mutableSetOf<java.util.UUID>()
        try {
            graph.settings.update(previous.copy(intervalMinutes = 0))
            SyncScheduler.schedule(context, previous.copy(intervalMinutes = 0))
            withContext(Dispatchers.IO) {
                work.cancelUniqueWork("llm_usage_periodic_sync").result.get(10, TimeUnit.SECONDS)
                work.cancelUniqueWork("llm_usage_consumer_sync").result.get(10, TimeUnit.SECONDS)
            }
            repeat(2) { accountIds += repository.addAccount("demo", "Widget integration ${it + 1}") }
            accountIds.forEach { repository.refresh(it) }
            val manager = AppWidgetManager.getInstance(context)
            val allocated = host.allocateAppWidgetId()
            widgetId = allocated
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            try {
                assertTrue("AppWidgetManager refused the test host binding",
                    manager.bindAppWidgetIdIfAllowed(allocated, ComponentName(context, UsageWidgetReceiver::class.java)))
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            val info = requireNotNull(manager.getAppWidgetInfo(allocated))
            instrumentation.runOnMainSync {
                host.startListening()
                host.createView(context, allocated, info).updateAppWidgetSize(Bundle(), 220, 150, 220, 150)
            }
            WidgetConfigStore(context).save(WidgetConfig(allocated, accountIds.map { WidgetSelection(it, "weekly") }))
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(allocated)
            UsageGlanceWidget().update(context, glanceId)
            assertEquals(accountIds, repository.widgetAccounts(allocated))

            // Exercise the production callback twice, including KEEP scheduling and persisted state.
            repeat(2) {
                val snapshotsBefore = accountIds.associateWith { repository.latest(it)!!.snapshotId }
                val workBefore = workInfos().map { it.id }.toSet()
                RefreshAllAction().onAction(context, glanceId, actionParametersOf())
                withTimeout(60_000) {
                    while (true) {
                        val runs = workInfos().filter { it.id !in workBefore }
                        ownedWork.addAll(runs.map { it.id })
                        if (runs.isNotEmpty() && runs.all { it.state.isFinished }) {
                            assertTrue("Refresh worker failed: ${runs.map { it.state }}",
                                runs.all { it.state == WorkInfo.State.SUCCEEDED })
                            break
                        }
                        delay(100)
                    }
                }
                accountIds.forEach { id ->
                    assertNotEquals("Worker did not refresh $id", snapshotsBefore[id], repository.latest(id)?.snapshotId)
                    assertNull(repository.account(id)?.lastErrorCode)
                }
                val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
                assertFalse("Completed worker left widget refreshing", state[booleanPreferencesKey("refreshing")] == true)
                assertEquals(listOf("62", "62"), WidgetStateMapper.slots(context, WidgetConfigStore(context).get(allocated)).map { it.number })
            }
        } finally {
            try {
                ownedWork.forEach { work.cancelWorkById(it).result.get(10, TimeUnit.SECONDS) }
                instrumentation.runOnMainSync { host.stopListening() }
                widgetId?.let { host.deleteAppWidgetId(it); WidgetConfigStore(context).delete(it) }
                accountIds.forEach { repository.deleteAccount(it) }
            } finally {
                graph.settings.update(previous)
                SyncScheduler.schedule(context, previous)
            }
        }
    }
}
