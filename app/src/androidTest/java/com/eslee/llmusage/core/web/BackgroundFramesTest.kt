package com.eslee.llmusage.core.web

import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.usage.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume

/**
 * Reported on v0.2.7: Grok's figure never changed however often it was refreshed,
 * and every refresh reported success. The background reader's WebView is never
 * attached to a window, so nothing draws it, and a page that is never drawn stops
 * getting frames. These tests hold the reader to what a page on screen would do.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundFramesTest {
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
     * What a detached page gets with and without the reader drawing it. Both are
     * written to qa/background-frames.txt as evidence of the engine's behaviour;
     * only the drawn page is held to delivering frames.
     */
    @Test fun aDetachedPageGetsFramesWhileTheReaderDrawsIt() = runBlocking {
        val bare = observe(pump = false)
        val drawn = observe(pump = true)
        val engine = WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" }
        File(File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }, "background-frames.txt")
            .writeText("webview: $engine\nnot drawn: $bare\ndrawn: $drawn\n")
        assertTrue("a frame callback asked for after load never ran: $drawn", drawn.getBoolean("late"))
        assertTrue("an element in view was never reported visible: $drawn", drawn.getBoolean("top"))
        assertTrue("an element scrolled into view was never reported visible: $drawn", drawn.getBoolean("bottom"))
    }

    /** A page that paints a cached figure and replaces it from a frame callback, the way SWR revalidates. */
    @Test fun aFigureThePageRefreshesOnAFrameReplacesTheCachedOne(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val reader = WebUsageReader(context) { web, _ ->
            web.loadDataWithBaseURL("https://grok.com/?_s=usage", """
                <html><body><h2>매주 SuperGrok 한도</h2>
                <div id="used">12%</div><div>중고</div>
                <div>2026년 10월 2일 오후 4:39 초기화</div>
                <script>
                  setTimeout(function(){ requestAnimationFrame(function(){ document.getElementById('used').textContent = '52%'; }); }, 1500);
                </script></body></html>
            """.trimIndent(), "text/html", "UTF-8", "https://grok.com/?_s=usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("grok", "Frame-refreshed figure")
        try {
            repository.refresh(id)
            val weekly = requireNotNull(repository.latest(id)).buckets.single { it.id == "weekly" }
            assertEquals(52.0, weekly.usedPercent!!, 0.0)
            assertEquals(48.0, weekly.remainingPercent!!, 0.0)
        } finally { repository.deleteAccount(id); database.close() }
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

    private suspend fun observe(pump: Boolean): JSONObject = withContext(Dispatchers.Main) {
        // Set up as the reader sets up its own: laid out, never attached.
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.layout(0, 0, 1080, 2400)
        val frames = WebUsageReader.FramePump(web)
        try {
            web.loadDataWithBaseURL("https://frames.test/", FRAME_PAGE, "text/html", "UTF-8", null)
            if (pump) frames.advance(2_000) else delay(2_000)
            web.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight)", null)
            if (pump) frames.advance(2_000) else delay(2_000)
            JSONObject(evaluate(web, "JSON.stringify(Object.assign(window.observed || {}, {visibilityEnd: document.visibilityState}))"))
        } finally {
            frames.release()
            web.destroy()
        }
    }

    private suspend fun evaluate(web: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { encoded -> continuation.resume(JSONTokener(encoded).nextValue() as? String ?: "{}") }
    }

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
    }
}
