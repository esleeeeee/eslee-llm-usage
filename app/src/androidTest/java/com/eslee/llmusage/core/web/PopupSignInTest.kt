package com.eslee.llmusage.core.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sign-in windows a page opens with window.open, as Claude's "Continue with Google"
 * does. Reported on v0.2.14: the window stayed white. The trace showed only the
 * window's empty starting document; the app stopped that "disallowed" address, which
 * cancelled the page the window was loading. Pages are served by the test, no network.
 */
@RunWith(AndroidJUnit4::class)
class PopupSignInTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The core case: the opener touches its window while the window's page is still on the way. */
    @Test fun aWindowItsOpenerTouchesWhileItLoadsStillLoads() = signInScreen(
        served = mapOf(PAGE to "<html><body>Popup page</body></html>"), holdUntilReleased = true,
    ) { scenario, web, release, mark ->
        open(scenario, web, PAGE)
        await("the window opened", mark) { webViews(scenario).size == 2 }
        // After the window is handed over, as Google's sign-in script does: WebView then
        // reports the window's empty starting document while the held page is loading.
        scenario.onActivity { web.evaluateJavascript("w.document.body; w.closed; 1", null) }
        await("WebView reported the window's empty starting document; the case under test did not happen", mark) {
            traceSince(mark).any { "start(popup) · about://none/" in it }
        }
        release.countDown()
        await("the window's page arrived instead of being cancelled", mark) { traceSince(mark).any { "start(popup) · $PAGE" in it } }
        assertTrue(dump(mark), traceSince(mark).none { "stopped(popup)" in it })
    }

    /** Google's refusal shown in the window is explained, and the way back is email sign-in on Claude. */
    @Test fun googleRefusingTheWindowIsExplained() = signInScreen(
        served = mapOf(GOOGLE to "<html><body><h1>액세스 차단됨</h1><p>오류 403: disallowed_useragent</p></body></html>"),
    ) { scenario, web, _, mark ->
        open(scenario, web, GOOGLE)
        val title = context.getString(R.string.web_google_dialog_title)
        await("Google's refusal was explained", mark) {
            runCatching { onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess
        }
        onView(withText(context.getString(R.string.web_google_dialog_email))).inRoot(isDialog()).perform(click())
        await("the Google window closed, leaving Claude's sign-in page", mark) {
            webViews(scenario).size == 1 && host(scenario) == "claude.ai"
        }
        assertTrue(dump(mark), traceSince(mark).any { "google-signin · claude blocked" in it })
    }

    /** Google moves to its refusal screen without loading a new page; that is explained too. */
    @Test fun googleRefusingOnALaterScreenIsExplained() = signInScreen(
        served = mapOf(GOOGLE to """
            <html><body><h1>Sign in</h1><script>
              setTimeout(function () {
                history.pushState({}, '', '/v3/signin/rejected');
                document.body.innerHTML = '<h1>로그인할 수 없음</h1><p>이 브라우저 또는 앱이 안전하지 않을 수 있습니다.</p>';
              }, 1500);
            </script></body></html>
        """.trimIndent()),
    ) { scenario, web, _, mark ->
        open(scenario, web, GOOGLE)
        val title = context.getString(R.string.web_google_dialog_title)
        await("Google's refusal on a later screen was explained", mark) {
            runCatching { onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess
        }
        assertTrue(dump(mark), traceSince(mark).any { "screen(popup)" in it && "GOOGLE_WEBVIEW_BLOCK" in it })
    }

    /** A window whose first page the app refuses is closed rather than left as a white sheet. */
    @Test fun aWindowWhoseFirstPageIsRefusedCloses() = signInScreen(
        served = mapOf(REFUSED to "<html><body>Help</body></html>"),
    ) { scenario, web, _, mark ->
        open(scenario, web, REFUSED)
        await("the refused window closed", mark) {
            traceSince(mark).any { "popup-open" in it } && webViews(scenario).size == 1
        }
        assertTrue(dump(mark), traceSince(mark).any { "popup-close · blank" in it })
    }

    private fun signInScreen(
        served: Map<String, String>,
        holdUntilReleased: Boolean = false,
        test: suspend (ActivityScenario<ProviderWebActivity>, WebView, CountDownLatch, String) -> Unit,
    ): Unit = runBlocking {
        val repository = (context.applicationContext as UsageApplication).graph.repository
        val id = repository.addAccount("claude", "Popup sign-in")
        val release = CountDownLatch(if (holdUntilReleased) 1 else 0)
        try {
            ActivityScenario.launch<ProviderWebActivity>(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", id)).use { scenario ->
                var web: WebView? = null
                withTimeout(10_000) {
                    while (web == null) {
                        scenario.onActivity { activity ->
                            descendants(activity.window.decorView).filterIsInstance<WebView>().firstOrNull()?.let { main ->
                                main.stopLoading()
                                // Windows inherit this client, so they are served too.
                                main.webViewClient = Serving(main.webViewClient, served, release)
                                main.loadDataWithBaseURL(LOGIN, OPENER, "text/html", "UTF-8", LOGIN)
                                web = main
                            }
                        }
                        delay(100)
                    }
                }
                val main = web!!
                // Short: the trace scrubs any long token as a possible secret, and a scrubbed mark cannot be found.
                val mark = "popup-test " + UUID.randomUUID().toString().take(8)
                await("the sign-in page loaded", null) {
                    var ready = false
                    val done = CountDownLatch(1)
                    scenario.onActivity { main.evaluateJavascript("typeof openIt") { ready = it.contains("function"); done.countDown() } }
                    done.await(2, TimeUnit.SECONDS)
                    ready
                }
                WebTrace.record("test", mark)
                test(scenario, main, release, mark)
            }
        } finally {
            release.countDown()
            repository.deleteAccount(id)
        }
    }

    private fun open(scenario: ActivityScenario<ProviderWebActivity>, web: WebView, url: String) {
        scenario.onActivity { web.evaluateJavascript("openIt('$url', 'w${url.hashCode()}')", null) }
    }

    private suspend fun await(what: String, mark: String?, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(200) }
        } catch (_: TimeoutCancellationException) {
            fail("$what\n${mark?.let(::dump).orEmpty()}")
        }
    }

    /** This test's lines only; earlier tests and earlier runs leave theirs in the persisted trace. */
    private fun traceSince(mark: String): List<String> = WebTrace.snapshot().let { lines ->
        val at = lines.indexOfLast { it.endsWith(mark) }
        if (at < 0) fail("the test's trace mark is missing, so its lines cannot be told from older ones")
        lines.drop(at + 1)
    }

    private fun dump(mark: String) = "trace:\n" + traceSince(mark).joinToString("\n")

    private fun webViews(scenario: ActivityScenario<ProviderWebActivity>): List<WebView> {
        var found = emptyList<WebView>()
        scenario.onActivity { found = descendants(it.window.decorView).filterIsInstance<WebView>() }
        return found
    }

    private fun host(scenario: ActivityScenario<ProviderWebActivity>): String {
        var host = ""
        scenario.onActivity { activity ->
            host = android.net.Uri.parse(descendants(activity.window.decorView).filterIsInstance<WebView>().first().url.orEmpty()).host.orEmpty()
        }
        return host
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    /** Answers the [served] addresses itself, each held until [release] opens, and leaves everything else to the app's client. */
    private class Serving(private val app: WebViewClient, private val served: Map<String, String>, private val release: CountDownLatch) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val body = served[request.url.toString()] ?: return app.shouldInterceptRequest(view, request)
            release.await(30, TimeUnit.SECONDS)
            return WebResourceResponse("text/html", "UTF-8", body.byteInputStream())
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = app.shouldOverrideUrlLoading(view, request)
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = app.onPageStarted(view, url, favicon)
        override fun onPageFinished(view: WebView, url: String) = app.onPageFinished(view, url)
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = app.doUpdateVisitedHistory(view, url, isReload)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = app.onReceivedError(view, request, error)
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) = app.onReceivedHttpError(view, request, response)
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = app.onReceivedSslError(view, handler, error)
    }

    private companion object {
        const val LOGIN = "https://claude.ai/login"
        const val PAGE = "https://claude.ai/popup-page"
        const val GOOGLE = "https://accounts.google.com/o/oauth2/v2/auth"
        const val REFUSED = "https://refused.example/help"
        /** Claude's sign-in page reduced to the one thing under test: opening a sign-in window. */
        val OPENER = """
            <html><body>Claude login
            <script>function openIt(url, name) { window.w = window.open(url, name, 'width=500,height=600'); return 1; }</script>
            </body></html>
        """.trimIndent()
    }
}
