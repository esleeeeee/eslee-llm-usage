package com.eslee.llmusage.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.usage.AccountOverview
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class UsageUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unknownUsageNeverRendersZeroPercent() {
        compose.setContent { UsageTheme { BucketContent(UsageBucket("missing", "Quota", confidence = Confidence.UNKNOWN)) } }
        compose.onNodeWithText(context.getString(R.string.unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.remaining_percent, 0)).assertDoesNotExist()
    }

    @Test fun failedSyncRetainsLastKnownUsageAndShowsAuthenticationState() {
        compose.setContent { UsageTheme { AccountCard(
            AccountOverview(
                Account("account", "claude", "Personal", AuthMode.WEB_PROFILE, lastErrorCode = "AUTH_REQUIRED"),
                UsageSnapshot("account", "claude", listOf(UsageBucket("weekly", "Weekly", usedPercent = 42.0))),
            ),
            "Claude", 0, {}) } }
        // The ring reads what is left: 42% used is 58 on the battery.
        compose.onNodeWithText("58", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.needs_auth), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun accountDeleteRequiresConfirmationAndCancelPreservesAccount() {
        var deleted = false
        var dismissed = false
        compose.setContent { UsageTheme { ConfirmDelete(R.string.delete_account, R.string.delete_account_body, { dismissed = true }, { deleted = true }) } }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(deleted) }
    }

    @Test fun accountDeleteConfirmationInvokesDelete() {
        var deleted = false
        compose.setContent { UsageTheme { ConfirmDelete(R.string.delete_account, R.string.delete_account_body, {}, { deleted = true }) } }
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    @Test fun realZeroUsageIsShownAsFullyLeft() {
        compose.setContent { UsageTheme { BucketContent(UsageBucket("known", "Quota", usedPercent = 0.0)) } }
        compose.onNodeWithText(context.getString(R.string.remaining_percent, 100) + " · " + context.getString(R.string.used_percent, 0)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.unknown)).assertDoesNotExist()
    }
}
