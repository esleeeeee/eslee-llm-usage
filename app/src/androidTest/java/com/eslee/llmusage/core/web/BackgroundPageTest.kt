package com.eslee.llmusage.core.web

import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderErrorCode
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.usage.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reported on v0.2.7: Grok's figure never changed however often it was refreshed,
 * and every refresh reported success. The background reader's WebView is never on
 * screen. These tests hold it to what a page on screen would see, and record in
 * qa/background-page.txt what the engine actually does for a page nobody sees.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundPageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var previousSettings: AppSettings

    @Before fun pauseScheduledJobs() = runBlocking {
        val settings = SettingsStore(context)
        previousSettings = settings.current()
        settings.update(previousSettings.copy(intervalMinutes = 0))
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        Unit
    }

    @After fun restoreSettings() = runBlocking { SettingsStore(context).update(previousSettings) }

    /**
     * v0.2.8 first assumed a page that is never drawn stops getting frames. It does
     * not: frame callbacks and intersection observers keep running, so the reader
     * does not draw the page. This keeps that finding true for the engine in use.
     */
    @Test fun aPageNobodySeesStillGetsFrames() = runBlocking {
        val seen = onDetachedPage(FRAME_PAGE) { web ->
            delay(2_000)
            web.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight)", null)
            delay(2_000)
        }
        record("frames without drawing: $seen")
        assertTrue("a frame callback asked for after load never ran: $seen", seen.getBoolean("late"))
        assertTrue("an element in view was never reported visible: $seen", seen.getBoolean("top"))
        assertTrue("an element scrolled into view was never reported visible: $seen", seen.getBoolean("bottom"))
    }

    /** What the page hears when the reader tells it the user came back: hidden, then shown again. */
    @Test fun wakingThePageTellsItTheUserCameBack() = runBlocking {
        val seen = onDetachedPage(EVENT_PAGE) { web ->
            delay(1_500)
            WebUsageReader.wake(web)
            delay(1_000)
        }
        record("wake: $seen")
        assertTrue("the page never heard it was hidden and shown again: $seen", seen.getInt("shown") >= 1 && seen.getInt("hidden") >= 1)
        assertEquals("visible", seen.getString("visibilityEnd"))
    }

    /** A page that paints a cached figure and replaces it from a frame callback, the way SWR revalidates on mount. */
    @Test fun aFigureThePageRefreshesOnAFrameReplacesTheCachedOne(): Unit = runBlocking {
        val lastWeek = moment(-3)
        val thisWeek = moment(3)
        val refreshed = readGrok("""
            setTimeout(function(){ requestAnimationFrame(function(){ fresh('${korean(thisWeek)}'); }); }, 1500);
        """, cached = lastWeek)
        assertEquals(52.0, refreshed.usedPercent!!, 0.0)
        assertEquals(thisWeek.toInstant().toEpochMilli(), refreshed.resetAt)
    }

    /**
     * A page that keeps its cached figure until the user comes back to the tab, the
     * way SWR does with revalidation on mount turned off. Only the reader's wake-up
     * brings the fresh figure; without it the read would end as a stale page.
     */
    @Test fun aFigureThePageRefreshesWhenTheUserComesBackReplacesTheCachedOne(): Unit = runBlocking {
        val thisWeek = moment(3)
        val refreshed = readGrok("""
            function back(){ if (document.visibilityState === 'visible') fresh('${korean(thisWeek)}'); }
            document.addEventListener('visibilitychange', back);
        """, cached = moment(-3))
        assertEquals(52.0, refreshed.usedPercent!!, 0.0)
        assertEquals(thisWeek.toInstant().toEpochMilli(), refreshed.resetAt)
    }

    /** Reported: an account with three banked resets showed none. A list that renders once scrolled into view is read. */
    @Test fun resetsThatRenderOnlyInViewAreRead(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val reader = WebUsageReader(context) { web, _ ->
            web.loadDataWithBaseURL("https://chatgpt.com/codex/settings/usage", """
                <html><body>5시간 사용 한도<br>45%<br>남음<br>주간 사용 한도<br>66%<br>남음
                <div style="height:6000px"></div>
                <div id="resets" style="min-height:20px"></div>
                <script>
                  new IntersectionObserver(function(entries){
                    var list = document.getElementById('resets');
                    if (!entries[0].isIntersecting || list.innerHTML) return;
                    list.innerHTML = '사용량 한도 재설정<br>' + [4, 5, 6].map(function(day){
                      return '전체 재설정(주간 + 5시간)<br>10월 ' + day + '일 오전 9:50에 만료<br>재설정 사용';
                    }).join('<br>');
                  }).observe(document.getElementById('resets'));
                </script></body></html>
            """.trimIndent(), "text/html", "UTF-8", "https://chatgpt.com/codex/settings/usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("chatgpt", "Resets below the fold")
        try {
            repository.refresh(id)
            val snapshot = requireNotNull(repository.latest(id))
            assertEquals(45.0, snapshot.buckets.first { it.id == "session" }.remainingPercent!!, 0.0)
            assertTrue(snapshot.resetCreditsKnown)
            assertEquals(3, snapshot.resetCredits.size)
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** A page that only ever shows the period before its reset is not saved as a reading, and the last one stays. */
    @Test fun aPageStuckBeforeItsResetIsNotASuccess(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context))
        val id = repository.addAccount("grok", "Lapsed page")
        try {
            val current = repository.recordWeb(id, "매주 SuperGrok 한도\n30%\n중고\n${korean(moment(3))} 초기화")
            assertTrue("current page: $current", current is ProviderResult.Success)
            val kept = repository.latest(id)?.snapshotId
            val stale = repository.recordWeb(id, "매주 SuperGrok 한도\n12%\n중고\n${korean(moment(-3))} 초기화")
            assertEquals(ProviderErrorCode.STALE_PAGE, (stale as ProviderResult.Failure).code)
            assertEquals(kept, repository.latest(id)?.snapshotId)
            assertEquals("STALE_PAGE", repository.account(id)?.lastErrorCode)
        } finally { repository.deleteAccount(id); database.close() }
    }

    /**
     * Reads a Grok-like page through the background reader. It first paints 12% used
     * with last week's reset, as a cache from before the reset would; [script] decides
     * when the page calls fresh(), which paints 52% with this week's reset.
     */
    private suspend fun readGrok(script: String, cached: java.time.ZonedDateTime): com.eslee.llmusage.core.model.UsageBucket {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val reader = WebUsageReader(context) { web, _ ->
            web.loadDataWithBaseURL("https://grok.com/?_s=usage", """
                <html><body><h2>매주 SuperGrok 한도</h2>
                <div id="used">12%</div><div>중고</div>
                <div id="reset">${korean(cached)} 초기화</div>
                <script>
                  function fresh(reset){
                    document.getElementById('used').textContent = '52%';
                    document.getElementById('reset').textContent = reset + ' 초기화';
                  }
                  $script
                </script></body></html>
            """.trimIndent(), "text/html", "UTF-8", "https://grok.com/?_s=usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("grok", "Cached figure")
        try {
            repository.refresh(id)
            val snapshot = requireNotNull(repository.latest(id)) { "no reading saved; the account ended as ${repository.account(id)?.lastErrorCode}" }
            return snapshot.buckets.single { it.id == "weekly" }
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** Loads [page] into a WebView set up as the reader sets up its own (laid out, never attached), runs [act], and returns what the page recorded. */
    private suspend fun onDetachedPage(page: String, act: suspend (WebView) -> Unit): JSONObject = withContext(Dispatchers.Main) {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.layout(0, 0, 1080, 2400)
        try {
            web.loadDataWithBaseURL("https://page.test/", page, "text/html", "UTF-8", null)
            act(web)
            JSONObject(requireNotNull(WebUsageReader.probe(web, "JSON.stringify(Object.assign(window.observed || {}, {visibilityEnd: document.visibilityState, focusEnd: document.hasFocus()}))")))
        } finally {
            web.destroy()
        }
    }

    private fun record(line: String) {
        val engine = WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" }
        File(File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }, "background-page.txt").appendText("[$engine] $line\n")
    }

    /** 4:39 PM on the day [days] from today, local time: reset times stay on the right side of now as the calendar moves. */
    private fun moment(days: Long): java.time.ZonedDateTime =
        java.time.LocalDate.now().plusDays(days).atTime(16, 39).atZone(java.time.ZoneId.systemDefault())

    /** The way Grok's Korean page writes a moment: "2026년 10월 2일 오후 4:39". */
    private fun korean(at: java.time.ZonedDateTime): String =
        "${at.year}년 ${at.monthValue}월 ${at.dayOfMonth}일 ${if (at.hour < 12) "오전" else "오후"} ${(at.hour + 11) % 12 + 1}:${"%02d".format(at.minute)}"

    private companion object {
        /** Counts frames, asks for one well after load, and watches one element in view and one far below it. */
        val FRAME_PAGE = """
            <!doctype html><html><body style="margin:0">
            <div id="top" style="height:40px">top</div>
            <div style="height:6000px"></div>
            <div id="bottom" style="height:40px">bottom</div>
            <script>
              var o = window.observed = {visibility: document.visibilityState, frames: 0, late: false, top: false, bottom: false};
              (function tick(){ o.frames++; requestAnimationFrame(tick); })();
              function watch(id){ new IntersectionObserver(function(entries){ entries.forEach(function(e){ if (e.isIntersecting) o[id] = true; }); }).observe(document.getElementById(id)); }
              watch('top'); watch('bottom');
              setTimeout(function(){ requestAnimationFrame(function(){ o.late = true; }); }, 1500);
            </script></body></html>
        """.trimIndent()

        /** Counts what a page hears when the user leaves and comes back: visibility changes and focus. */
        val EVENT_PAGE = """
            <!doctype html><html><body>
            <script>
              var o = window.observed = {visibility: document.visibilityState, focusStart: document.hasFocus(), hidden: 0, shown: 0, focus: 0};
              document.addEventListener('visibilitychange', function(){ if (document.visibilityState === 'hidden') o.hidden++; else o.shown++; });
              window.addEventListener('focus', function(){ o.focus++; });
            </script></body></html>
        """.trimIndent()
    }
}
