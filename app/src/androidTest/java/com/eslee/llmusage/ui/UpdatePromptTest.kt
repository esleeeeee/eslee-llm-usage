package com.eslee.llmusage.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UpdateChecker
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reported twice, on 0.2.6 -> 0.2.7 and on 0.2.8 -> 0.2.9: a release was out and the
 * app did not offer it. Each time an earlier look had found nothing newer, and that
 * answer kept the next look from happening. The release source is replaced here, so the
 * test does not depend on what GitHub has published.
 */
class UpdatePromptTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = (context as UsageApplication).graph
    private lateinit var previous: AppSettings
    private lateinit var askGitHub: suspend (String) -> UpdateChecker.Outcome
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() = runBlocking {
        previous = graph.settings.current()
        askGitHub = graph.updateCheck
        // A minute ago the app asked and was told it had the latest build.
        graph.settings.update(previous.copy(intervalMinutes = 0, theme = "LIGHT", updatePrompts = true, updateSkipped = "",
            updateCheckedAt = System.currentTimeMillis() - 60_000))
        WorkManager.getInstance(context).cancelAllWork().result.get()
        Unit
    }

    @After fun cleanup() = runBlocking {
        scenario?.close()
        graph.updateCheck = askGitHub
        graph.settings.update(previous)
    }

    @Test fun aReleaseMadeSinceTheLastLookIsOfferedEveryTimeTheAppComesToTheFront(): Unit = runBlocking {
        val asked = AtomicInteger()
        graph.updateCheck = {
            asked.incrementAndGet()
            UpdateChecker.Outcome.Newer(UpdateChecker.Available("9.9.9", "https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v9.9.9", null))
        }
        val title = context.getString(R.string.update_available_title, "9.9.9")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.update_later)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }

        // Leave the app and come back: it asks again and offers the release again.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("asked ${asked.get()} times", asked.get() >= 2)
    }
}
