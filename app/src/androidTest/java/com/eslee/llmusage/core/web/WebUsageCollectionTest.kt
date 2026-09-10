package com.eslee.llmusage.core.web

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.usage.UsageRepository
import com.eslee.llmusage.widget.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebUsageCollectionTest {
    private lateinit var previousSettings: AppSettings
    @Before fun pauseScheduledJobs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context)
        previousSettings = settings.current()
        settings.update(previousSettings.copy(intervalMinutes = 0))
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        Unit
    }
    @After fun restoreSettings() = runBlocking {
        SettingsStore(ApplicationProvider.getApplicationContext()).update(previousSettings)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun readButtonStoresCurrentUsageWithoutReloadingRedirectedRoute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as UsageApplication).graph.repository
        val id = repository.addAccount("chatgpt", "Read button regression")
        try {
            ActivityScenario.launch<ProviderWebActivity>(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", id)).use { scenario ->
                var installed = false
                withTimeout(10_000) {
                    while (!installed) {
                        scenario.onActivity { activity ->
                            val web = descendants(activity.window.decorView).filterIsInstance<WebView>().firstOrNull()
                            if (web != null) {
                                web.stopLoading()
                                web.loadDataWithBaseURL("https://chatgpt.com/codex/usage", "<html><body>5시간 사용 한도<br>45%<br>남음<br>주간 사용 한도<br>66%<br>남음</body></html>", "text/html", "UTF-8", null)
                                installed = true
                            }
                        }
                        delay(100)
                    }
                }
                delay(3_000)
                val before = repository.latest(id)?.snapshotId
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.text == activity.getString(R.string.web_usage_check) }.performClick()
                }
                withTimeout(10_000) { while (repository.latest(id)?.snapshotId == before) delay(100) }
                assertEquals(45.0, repository.latest(id)!!.buckets.first { it.id == "session" }.remainingPercent!!, 0.0)
                scenario.onActivity { activity ->
                    assertEquals("https://chatgpt.com/codex/usage", descendants(activity.window.decorView).filterIsInstance<WebView>().first().url)
                }
            }
        } finally { repository.deleteAccount(id) }
    }

    @Test fun delayedWebPageIsSavedAndRepeatedRefreshChangesWidgetValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        var remaining = 45
        var seeded = false
        val reader = WebUsageReader(context) { web, _ ->
            val content = "5시간 사용 한도<br>$remaining%<br>남음<br>주간 사용 한도<br>66%<br>남음"
            // A redirected route and delayed hydration reproduce the previous read-button failure.
            val load = {
                web.loadDataWithBaseURL("https://chatgpt.com/codex/usage", """
                    <html><body>Loading<script>setTimeout(function(){document.body.innerHTML=document.cookie.indexOf('usage_fixture=ready')>=0?'$content':'Sign in';},1200);</script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            if (!seeded) {
                seeded = true
                WebViewCompat.getProfile(web).cookieManager.setCookie("https://chatgpt.com", "usage_fixture=ready; Secure; SameSite=Lax") { load() }
            } else load()
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        try {
            val id = repository.addAccount("chatgpt", "WebView regression")
            repository.refreshAll()
            val first = requireNotNull(repository.latest(id))
            assertEquals(45.0, first.buckets.first { it.id == "session" }.remainingPercent!!, 0.0)
            val config = WidgetConfig(appWidgetId = 991, selections = listOf(WidgetSelection(id)), remaining = true)
            assertTrue(WidgetStateMapper.rows(context, config, repository).first().values.first().text.contains("45"))
            remaining = 40
            repository.refresh(id)
            assertNotEquals(first.snapshotId, repository.latest(id)?.snapshotId)
            assertTrue(WidgetStateMapper.rows(context, config, repository).first().values.first().text.contains("40"))
            val views = renderWidgetPreview(context, config, WidgetStateMapper.rows(context, config, repository), DpSize(220.dp, 150.dp))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val rendered = views.apply(context, FrameLayout(context))
                assertTrue(descendants(rendered).filterIsInstance<TextView>().any { it.text.contains("40%") })
            }
            val last = repository.latest(id)?.snapshotId
            assertTrue(repository.recordWeb(id, "Loading") is ProviderResult.Failure)
            assertEquals(last, repository.latest(id)?.snapshotId)
            assertTrue(WidgetStateMapper.rows(context, config, repository).first().values.first().text.contains("40"))
        } finally { database.close() }
    }
}
