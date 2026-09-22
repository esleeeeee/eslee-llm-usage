package com.eslee.llmusage.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.core.view.WindowCompat
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real activity journeys; no provider credentials or live provider requests. */
class EmulatorJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = (context as UsageApplication).graph
    private lateinit var previous: AppSettings
    private var scenario: ActivityScenario<MainActivity>? = null
    private val accountIds = mutableListOf<String>()

    @Before fun prepare() = runBlocking {
        previous = graph.settings.current()
        graph.settings.update(previous.copy(intervalMinutes = 0, theme = "LIGHT"))
        WorkManager.getInstance(context).cancelAllWork().result.get()
        Unit
    }

    @After fun cleanup() = runBlocking {
        scenario?.close()
        // These tests own only their QA accounts, including ones created through the UI.
        graph.repository.accounts.first().filter { it.account.alias.startsWith("QA ") }.forEach {
            graph.repository.deleteAccount(it.account.id)
        }
        accountIds.forEach { graph.repository.deleteAccount(it) }
        graph.settings.update(previous)
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    private fun label(id: Int) = context.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun accountJourneyRefreshThemeRenameAndDelete(): Unit = runBlocking {
        val id = graph.repository.addAccount("demo", "QA Personal")
        accountIds += id
        graph.repository.refresh(id)
        launch()
        compose.onNodeWithText("QA Personal").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.remaining_percent, 62)).assertIsDisplayed()
        screenshot("01-home-light")
        val oldSnapshot = graph.repository.latest(id)!!.snapshotId
        compose.onNodeWithContentDescription(label(R.string.refresh_all)).performClick()
        compose.waitUntil(10_000) { runBlocking { graph.repository.latest(id)?.snapshotId != oldSnapshot } }

        compose.onNodeWithContentDescription(label(R.string.settings)).performClick()
        compose.onNodeWithText(label(R.string.dark)).performScrollTo().performClick()
        compose.waitUntil { runBlocking { graph.settings.current().theme == "DARK" } }
        compose.waitForIdle()
        scenario!!.onActivity { activity ->
            assertFalse(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
        }
        screenshot("02-settings-dark")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        screenshot("03-home-dark")
        compose.onNodeWithText("QA Personal").performClick()
        screenshot("04-account-detail")
        compose.onNodeWithContentDescription(label(R.string.more)).performClick()
        compose.onNodeWithText(label(R.string.rename)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("QA Renamed")
        compose.onNodeWithText(label(R.string.save)).performClick()
        compose.onNodeWithText("QA Renamed").assertIsDisplayed()

        compose.onNodeWithContentDescription(label(R.string.more)).performClick()
        compose.onNodeWithText(label(R.string.delete_account)).performClick()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertNotNull(graph.repository.account(id))
        compose.onNodeWithContentDescription(label(R.string.more)).performClick()
        compose.onNodeWithText(label(R.string.delete_account)).performClick()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        compose.waitUntil(10_000) { runBlocking { graph.repository.account(id) == null } }
        compose.onNodeWithText(label(R.string.empty_title)).assertIsDisplayed()
        screenshot("05-empty-state")
    }

    @Test fun apiAccountFormCanBeSavedWithKeyboardOnSmallScreen(): Unit = runBlocking {
        launch()
        compose.onNodeWithText(label(R.string.add_account)).performClick()
        compose.onNodeWithText("OpenAI API").performClick()
        compose.onNodeWithText(label(R.string.alias)).performScrollTo().performClick().performTextInput("QA API")
        compose.onNodeWithText(label(R.string.credential)).performScrollTo().performClick().performTextInput("local-qa-placeholder")
        // No network validation: this checks keyboard, scrolling and local credential persistence.
        compose.onNodeWithText(label(R.string.save_unvalidated)).performScrollTo().assertIsDisplayed()
        screenshot("06-form-keyboard")
        compose.onNodeWithText(label(R.string.save_unvalidated)).performClick()
        compose.waitUntil(10_000) { runBlocking { graph.repository.accounts.first().any { it.account.alias == "QA API" } } }
        compose.onNodeWithText("QA API").assertIsDisplayed()
        val account = graph.repository.accounts.first().single { it.account.alias == "QA API" }.account
        assertEquals("NEEDS_VALIDATION", account.lastErrorCode)
        assertNull(graph.repository.latest(account.id))
        screenshot("07-unvalidated-account")
    }
}
