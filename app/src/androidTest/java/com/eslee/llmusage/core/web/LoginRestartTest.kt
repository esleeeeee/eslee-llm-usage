package com.eslee.llmusage.core.web

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.room.Room
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.provider.ProviderErrorCode
import com.eslee.llmusage.usage.UsageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LoginRestartTest {
    private lateinit var previousSettings: AppSettings

    @Before fun pauseScheduledJobs(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        previousSettings = app.graph.settings.current()
        app.graph.settings.update(previousSettings.copy(intervalMinutes = 0))
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    @After fun restoreSettings(): Unit = runBlocking {
        ApplicationProvider.getApplicationContext<UsageApplication>().graph.settings.update(previousSettings)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun logoutCancelsBlockedAccountWithoutCancellingRefreshAll(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, UsageDatabase::class.java).build()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = UsageRepository(app, database, ProviderRegistry(false), CredentialStore(app), app.graph.settings) { account, _ ->
            if (account.alias == "Blocked") {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            } else ProviderResult.Failure(ProviderErrorCode.NETWORK, "Synthetic second account result")
        }
        val blocked = repository.addAccount("chatgpt", "Blocked")
        val other = repository.addAccount("chatgpt", "Other")
        val original = repository.account(blocked)!!.profileName
        val all = async { repository.refreshAll() }
        try {
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) {
                repository.logout(blocked)
                cancelled.await()
                all.await()
            }
            assertFalse(all.isCancelled)
            assertNotEquals(original, repository.account(blocked)!!.profileName)
            assertEquals("AUTH_REQUIRED", repository.account(blocked)!!.lastErrorCode)
            assertEquals("NETWORK", repository.account(other)!!.lastErrorCode)
        } finally {
            all.cancelAndJoin()
            repository.deleteAccount(blocked)
            repository.deleteAccount(other)
            database.close()
        }
    }

    @Test fun restartRecreatesBrowserWithFreshIsolatedAccountProfile(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        val repository = app.graph.repository
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { assumeTrue(ProfileSessions.supported()) }
        val id = repository.addAccount("chatgpt", "Restart regression")
        val other = repository.addAccount("chatgpt", "Other session regression")
        var otherWeb: WebView? = null
        try {
            val oldProfile = requireNotNull(repository.account(id)?.profileName)
            val otherProfile = requireNotNull(repository.account(other)?.profileName)
            instrumentation.runOnMainSync {
                otherWeb = WebView(app).also { ProfileSessions.bind(it, otherProfile) }
            }
            ActivityScenario.launch<ProviderWebActivity>(Intent(app, ProviderWebActivity::class.java).putExtra("accountId", id)).use { scenario ->
                var oldWeb: WebView? = null
                withTimeout(10_000) {
                    while (oldWeb == null) {
                        scenario.onActivity { activity ->
                            oldWeb = descendants(activity.window.decorView).filterIsInstance<WebView>().firstOrNull()?.also { it.stopLoading() }
                        }
                        delay(50)
                    }
                }
                val cookies = CountDownLatch(2)
                scenario.onActivity {
                    WebViewCompat.getProfile(requireNotNull(oldWeb)).cookieManager
                        .setCookie("https://restart.example", "failed_session=1; Secure") { cookies.countDown() }
                    WebViewCompat.getProfile(requireNotNull(otherWeb)).cookieManager
                        .setCookie("https://restart.example", "other_session=1; Secure") { cookies.countDown() }
                }
                assertTrue(cookies.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertEquals("failed_session=1", WebViewCompat.getProfile(requireNotNull(oldWeb)).cookieManager.getCookie("https://restart.example"))
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.text == activity.getString(R.string.web_restart_login) }.performClick()
                }
                var replacement: WebView? = null
                withTimeout(15_000) {
                    while (replacement == null) {
                        scenario.onActivity { activity ->
                            replacement = descendants(activity.window.decorView).filterIsInstance<WebView>()
                                .firstOrNull { it !== oldWeb }
                        }
                        delay(50)
                    }
                }
                val freshProfile = requireNotNull(repository.account(id)?.profileName)
                assertNotEquals(oldProfile, freshProfile)
                assertEquals(otherProfile, repository.account(other)?.profileName)
                assertEquals("Restart regression", repository.account(id)?.alias)
                scenario.onActivity { activity ->
                    val profile = WebViewCompat.getProfile(requireNotNull(replacement))
                    assertEquals(freshProfile, profile.name)
                    assertNull(profile.cookieManager.getCookie("https://restart.example"))
                    assertEquals("other_session=1", WebViewCompat.getProfile(requireNotNull(otherWeb)).cookieManager.getCookie("https://restart.example"))
                    assertEquals(app.graph.registry.definition("chatgpt")?.loginUrl, replacement?.originalUrl)
                    replacement?.stopLoading()
                }
            }
        } finally {
            instrumentation.runOnMainSync { otherWeb?.let(ProfileSessions::destroy) }
            repository.deleteAccount(id)
            repository.deleteAccount(other)
        }
    }
}
