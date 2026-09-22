package com.eslee.llmusage.core.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProfileIsolationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    @Test fun independentCookiesStorageAndDeletion() {
        assumeTrue(main { ProfileSessions.supported() })
        val a = ProfileSessions.name("test",UUID.randomUUID().toString())
        val b = ProfileSessions.name("test",UUID.randomUUID().toString())
        var first: WebView? = null
        var second: WebView? = null
        try {
            first = main { WebView(instrumentation.targetContext).also { ProfileSessions.bind(it,a) } }
            second = main { WebView(instrumentation.targetContext).also { ProfileSessions.bind(it,b) } }
            val cookieA = main { WebViewCompat.getProfile(requireNotNull(first)).cookieManager }
            val cookieB = main { WebViewCompat.getProfile(requireNotNull(second)).cookieManager }
            val cookieLatch = CountDownLatch(2)
            main {
                cookieA.setCookie("https://isolation.example", "account=A; Secure") { cookieLatch.countDown() }
                cookieB.setCookie("https://isolation.example", "account=B; Secure") { cookieLatch.countDown() }
            }
            assertTrue(cookieLatch.await(10,TimeUnit.SECONDS))
            assertEquals("account=A",cookieA.getCookie("https://isolation.example"))
            assertEquals("account=B",cookieB.getCookie("https://isolation.example"))
            load(requireNotNull(first)); load(requireNotNull(second))
            assertEquals("\"A\"",script(requireNotNull(first),"localStorage.setItem('account','A');localStorage.getItem('account')"))
            assertEquals("null",script(requireNotNull(second),"localStorage.getItem('account')"))
            assertEquals("\"B\"",script(requireNotNull(second),"localStorage.setItem('account','B');localStorage.getItem('account')"))
            assertEquals("\"A\"",script(requireNotNull(first),"localStorage.getItem('account')"))
            // Delete while A is still attached: production must close it before purging.
            assertTrue(runBlocking { ProfileSessions.delete(a) })
            first = null
            assertNull(cookieA.getCookie("https://isolation.example"))
            assertTrue(main { runCatching { ProfileSessions.create(a) }.exceptionOrNull() is IllegalStateException })
            val rejected = main { WebView(instrumentation.targetContext) }
            try {
                assertTrue(main { runCatching { ProfileSessions.bind(rejected, a) }.exceptionOrNull() is IllegalStateException })
            } finally { main { rejected.destroy() } }
            // Bypass the app's tombstone only to inspect the underlying cleared data.
            val inspection = main { WebView(instrumentation.targetContext).also { WebViewCompat.setProfile(it, a) } }
            try {
                load(inspection)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var stored = script(inspection, "localStorage.getItem('account')")
                while (stored != "null" && System.nanoTime() < deadline) {
                    Thread.sleep(50)
                    stored = script(inspection, "localStorage.getItem('account')")
                }
                assertEquals("Deleted profile retained localStorage", "null", stored)
            } finally { main { inspection.destroy() } }
            assertEquals("account=B",cookieB.getCookie("https://isolation.example"))
            assertEquals("\"B\"",script(requireNotNull(second),"localStorage.getItem('account')"))
        } finally {
            main {
                first?.destroy(); second?.destroy()
            }
            runBlocking {
                if (main { a in ProfileStore.getInstance().allProfileNames }) ProfileSessions.delete(a)
                if (main { b in ProfileStore.getInstance().allProfileNames }) ProfileSessions.delete(b)
            }
        }
    }
    private fun load(web: WebView) {
        val latch = CountDownLatch(1)
        main {
            web.settings.javaScriptEnabled=true
            web.settings.domStorageEnabled=true
            web.webViewClient=object: WebViewClient() { override fun onPageFinished(view: WebView,url: String) { latch.countDown() } }
            web.loadDataWithBaseURL("https://isolation.example","<html><body>Isolation fixture</body></html>","text/html","UTF-8",null)
        }
        assertTrue(latch.await(10,TimeUnit.SECONDS))
    }
    private fun script(web: WebView,js: String): String {
        val latch=CountDownLatch(1)
        val result=AtomicReference<String>()
        main { web.evaluateJavascript(js) { result.set(it); latch.countDown() } }
        assertTrue(latch.await(10,TimeUnit.SECONDS))
        return result.get()
    }
}

