package com.eslee.llmusage.core.web

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When Google refuses sign-in in the sign-in screen itself, as it can in an app's
 * browser, the refusal is explained and the way back to email sign-in is offered.
 */
@RunWith(AndroidJUnit4::class)
class GoogleSignInTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun googleRefusingTheSignInScreenIsExplained(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as UsageApplication).graph.repository
        val id = repository.addAccount("claude", "Google sign-in explanation")
        try {
            ActivityScenario.launch<ProviderWebActivity>(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", id)).use { scenario ->
                // Google's refusal, as a phone set to Korean shows it.
                val google = "https://accounts.google.com/v3/signin/identifier"
                var sent = false
                withTimeout(10_000) {
                    while (!sent) {
                        scenario.onActivity { activity ->
                            descendants(activity.window.decorView).filterIsInstance<WebView>().firstOrNull()?.let { web ->
                                web.stopLoading()
                                web.loadDataWithBaseURL(google, "<html><body><h1>로그인할 수 없음</h1><p>이 브라우저 또는 앱이 안전하지 않을 수 있습니다.</p></body></html>", "text/html", "UTF-8", google)
                                sent = true
                            }
                        }
                        delay(100)
                    }
                }
                val title = context.getString(R.string.web_google_dialog_title)
                withTimeout(10_000) {
                    while (runCatching { onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed())) }.isFailure) delay(200)
                }
                onView(withText(context.getString(R.string.web_google_dialog_email))).inRoot(isDialog()).perform(click())
                withTimeout(15_000) {
                    var host = ""
                    while (host != "claude.ai") {
                        scenario.onActivity { activity ->
                            host = android.net.Uri.parse(descendants(activity.window.decorView).filterIsInstance<WebView>().first().url.orEmpty()).host.orEmpty()
                        }
                        delay(200)
                    }
                }
                assertTraced()
            }
        } finally {
            repository.deleteAccount(id)
        }
    }

    private fun assertTraced() {
        org.junit.Assert.assertTrue("the attempt must be in the diagnostics", WebTrace.snapshot().any { it.contains("google-signin · claude blocked") })
    }
}
