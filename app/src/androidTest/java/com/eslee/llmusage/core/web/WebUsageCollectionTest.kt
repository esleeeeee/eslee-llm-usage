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
import kotlinx.coroutines.flow.first
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

    @Test fun readButtonStoresCurrentUsageWithoutReloadingRedirectedRoute(): Unit = runBlocking {
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
                                web.loadDataWithBaseURL("https://chatgpt.com/codex/usage", "<html><body>5시간 사용 한도<br>45%<br>남음<br>주간 사용 한도<br>66%<br>남음</body></html>", "text/html", "UTF-8", "https://chatgpt.com/codex/usage")
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

    @Test fun delayedWebPageIsSavedAndRepeatedRefreshChangesWidgetValues(): Unit = runBlocking {
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
                """.trimIndent(), "text/html", "UTF-8", "https://chatgpt.com/codex/usage")
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
            val config = WidgetConfig(appWidgetId = 991, selections = listOf(WidgetSelection(id)))
            assertEquals("45", WidgetStateMapper.slots(context, config, repository).first().number)
            remaining = 40
            repository.refresh(id)
            assertNotEquals(first.snapshotId, repository.latest(id)?.snapshotId)
            assertEquals("40", WidgetStateMapper.slots(context, config, repository).first().number)
            val views = renderWidgetPreview(context, config, WidgetStateMapper.slots(context, config, repository), DpSize(220.dp, 150.dp))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val rendered = views.apply(context, FrameLayout(context))
                assertTrue(descendants(rendered).filterIsInstance<TextView>().any { it.text.toString() == "40" })
            }
            val last = repository.latest(id)?.snapshotId
            assertTrue(repository.recordWeb(id, "Loading") is ProviderResult.Failure)
            assertEquals(last, repository.latest(id)?.snapshotId)
            assertEquals("40", WidgetStateMapper.slots(context, config, repository).first().number)
        } finally { database.close() }
    }

    /**
     * Reported: a Grok read reports complete, yet neither the dashboard nor the
     * account list shows a value. This walks the same path the read button takes
     * -- parse, store, then read back the way the screens do -- so storage and
     * retrieval are ruled in or out without a device.
     */
    @Test fun grokUsageSurvivesFromReadToTheOverviewTheScreensObserve(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as UsageApplication).graph.repository
        val id = repository.addAccount("grok", "Grok storage regression")
        try {
            val page = listOf(
                "사용량", "매주 SuperGrok 한도", "", "0%", "중고",
                "2026년 9월 25일 오후 4:39 초기화", "추가 사용 크레딧", "", "US\$0.00",
            ).joinToString("\n")

            val result = repository.recordWeb(id, page)
            assertTrue("recordWeb returned $result", result is ProviderResult.Success)

            val stored = repository.latest(id)
            assertNotNull("nothing stored for the account after a successful read", stored)
            val weekly = stored!!.buckets.firstOrNull { it.id == "weekly" }
            assertNotNull("stored snapshot has no weekly bucket: ${stored.buckets.map { it.id }}", weekly)
            assertEquals(0.0, weekly!!.usedPercent!!, 0.0)
            assertEquals(100.0, weekly.remainingPercent!!, 0.0)

            // The dashboard and the account list both render from this flow.
            val overview = withTimeout(10_000) {
                var row = repository.accounts.first().firstOrNull { it.account.id == id }
                while (row?.snapshot == null) { delay(100); row = repository.accounts.first().firstOrNull { it.account.id == id } }
                row
            }
            assertNotNull("overview row lost the snapshot the screens read", overview.snapshot)
            assertEquals(id, overview.account.id)
            assertNull("a successful read must clear the error state", overview.account.lastErrorCode)

            // What the card actually draws.
            val primary = com.eslee.llmusage.core.model.UsagePresentation.primary(overview.snapshot!!, overview.account.primaryBucketId)
            assertNotNull("no primary bucket for the card to draw", primary)
            assertEquals(100.0, com.eslee.llmusage.core.model.UsageNormalizer.remainingPercent(primary!!)!!, 0.0)
        } finally {
            repository.deleteAccount(id)
        }
    }

    /**
     * Grok paints its usage labels where innerText can read them and its figures
     * where it cannot, so the visible capture carried "매주 SuperGrok 한도" and
     * "중고" with no percentage at all. The structural capture has the number.
     */
    @Test fun aPageWhoseNumbersInnerTextCannotSeeFallsBackToTheStructuralCapture(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as UsageApplication).graph.repository
        val id = repository.addAccount("grok", "Rich capture regression")
        try {
            val visible = listOf("사용량", "매주 SuperGrok 한도", "중고", "2026년 9월 25일 오후 4:39 초기화").joinToString("\n")
            val rich = listOf("사용량", "매주 SuperGrok 한도", "0%", "중고", "2026년 9월 25일 오후 4:39 초기화").joinToString("\n")

            // The visible capture alone yields a bucket with no figure to show.
            assertFalse(WebUsageReader.carriesNumbers(
                com.eslee.llmusage.provider.ConsumerUsageParser.parse("grok", id, visible)))

            val result = repository.recordWeb(id, visible, rich)
            assertTrue("recordWeb returned $result", result is ProviderResult.Success)
            val weekly = repository.latest(id)!!.buckets.first { it.id == "weekly" }
            assertEquals(0.0, weekly.usedPercent!!, 0.0)
            assertEquals(100.0, weekly.remainingPercent!!, 0.0)
            assertNotNull("the reset from the visible capture must survive", weekly.resetAt)
        } finally {
            repository.deleteAccount(id)
        }
    }

    /** A provider that already parses keeps the text it parses from. */
    @Test fun theStructuralCaptureIsIgnoredWhenTheVisibleTextAlreadyHasFigures(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as UsageApplication).graph.repository
        val id = repository.addAccount("grok", "Visible capture wins")
        try {
            val visible = listOf("주간 사용량", "42%", "used").joinToString("\n")
            val rich = listOf("주간 사용량", "7%", "used").joinToString("\n")
            repository.recordWeb(id, visible, rich)
            assertEquals(42.0, repository.latest(id)!!.buckets.first { it.id == "weekly" }.usedPercent!!, 0.0)
        } finally {
            repository.deleteAccount(id)
        }
    }
}
