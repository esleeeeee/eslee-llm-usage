package com.eslee.llmusage.core.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
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
            main { requireNotNull(first).destroy() }; first = null
            assertTrue(main { ProfileSessions.delete(a) })
            assertEquals("account=B",cookieB.getCookie("https://isolation.example"))
            assertEquals("\"B\"",script(requireNotNull(second),"localStorage.getItem('account')"))
        } finally {
            main {
                first?.destroy(); second?.destroy()
                val store = ProfileStore.getInstance()
                if(a in store.allProfileNames) ProfileSessions.delete(a)
                if(b in store.allProfileNames) ProfileSessions.delete(b)
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

