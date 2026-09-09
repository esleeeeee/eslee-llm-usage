package com.eslee.llmusage.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class UsageUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unknownUsageNeverRendersZeroPercent() {
        compose.setContent { UsageTheme { BucketContent(UsageBucket("missing", "Quota", confidence = Confidence.UNKNOWN), remaining = true) } }
        compose.onNodeWithText(context.getString(R.string.unknown)).assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("0%").assertDoesNotExist()
    }

    @Test fun failedSyncRetainsLastKnownUsageAndShowsAuthenticationState() {
        compose.setContent { UsageTheme { AccountCard(
            Account("account", "claude", "Personal", AuthMode.WEB_PROFILE, lastErrorCode = "AUTH_REQUIRED"),
            UsageSnapshot("account", "claude", listOf(UsageBucket("weekly", "Weekly", usedPercent = 42.0))),
            "Claude", false, 0, {}, {}) } }
        compose.onNodeWithText("42%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.needs_auth), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun accountDeleteRequiresConfirmationAndCancelPreservesAccount() {
        var deleted = false
        var dismissed = false
        compose.setContent { UsageTheme { ConfirmDelete(R.string.delete_account, R.string.delete_account_body, { dismissed = true }, { deleted = true }) } }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(deleted) }
    }

    @Test fun realZeroUsageIsShownAsZero() {
        compose.setContent { UsageTheme { BucketContent(UsageBucket("known", "Quota", usedPercent = 0.0), remaining = false) } }
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.unknown)).assertDoesNotExist()
    }
}
