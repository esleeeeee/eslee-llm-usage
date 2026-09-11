package com.eslee.llmusage.widget

import com.eslee.llmusage.core.model.UsageBucket
import com.eslee.llmusage.core.model.UsageUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Reported defect: the Codex page stated "5시간 76% 남음 / 주간 24% 남음" while the
 * widget showed the two numbers swapped. The app screens were correct, so the
 * cause is the widget reading the complement without saying so.
 */
class WidgetValueSemanticsTest {
    private val words = WidgetValueWords(used = "사용", remaining = "남음", unknown = "알 수 없음")
    private val session = UsageBucket("session", "5시간 사용 한도", unit = UsageUnit.PERCENT, remainingPercent = 76.0)
    private val weekly = UsageBucket("weekly", "주간 사용 한도", unit = UsageUnit.PERCENT, remainingPercent = 24.0)

    private fun text(bucket: UsageBucket, remaining: Boolean) =
        WidgetStateMapper.display(bucket, remaining, words, Locale.KOREAN).text

    @Test fun widgetWithoutOwnChoiceFollowsAppSettingInsteadOfShowingTheComplement() {
        val config = WidgetConfig(appWidgetId = 1)
        val remaining = WidgetStateMapper.effectiveRemaining(config, settingRemaining = true)
        assertTrue(remaining)
        assertEquals("76% 남음", text(session, remaining))
        assertEquals("24% 남음", text(weekly, remaining))
    }

    @Test fun pinnedUsedWidgetStatesThatItShowsUsedRatherThanBareNumbers() {
        val remaining = WidgetStateMapper.effectiveRemaining(WidgetConfig(1, remaining = false), settingRemaining = true)
        assertFalse(remaining)
        // The complement is a legitimate reading, but never as an unlabelled number.
        assertEquals("24% 사용", text(session, remaining))
        assertEquals("76% 사용", text(weekly, remaining))
    }

    @Test fun pinnedRemainingWidgetIgnoresAnAppSettingOfUsed() {
        val remaining = WidgetStateMapper.effectiveRemaining(WidgetConfig(1, remaining = true), settingRemaining = false)
        assertTrue(remaining)
        assertEquals("76% 남음", text(session, remaining))
    }

    @Test fun quotasThatDoNotSumToOneHundredKeepTheirOwnValues() {
        val weekly31 = weekly.copy(remainingPercent = 31.0)
        assertEquals("76% 남음", text(session, remaining = true))
        assertEquals("31% 남음", text(weekly31, remaining = true))
        assertEquals("24% 사용", text(session, remaining = false))
        assertEquals("69% 사용", text(weekly31, remaining = false))
    }

    @Test fun unknownBucketStaysUnknownAndCarriesNoProgress() {
        val display = WidgetStateMapper.display(UsageBucket("session", "5시간 사용 한도"), true, words, Locale.KOREAN)
        assertEquals("알 수 없음", display.text)
        assertNull(display.percent)
    }

    @Test fun countedRemainderKeepsItsUnitAndMeaning() {
        val resets = UsageBucket("resets", "Banked resets", remaining = 3.0, unit = UsageUnit.REQUESTS)
        assertEquals("3.00 REQUESTS 남음", text(resets, remaining = true))
    }

    @Test fun progressFractionMatchesTheDisplayedNumber() {
        assertEquals(0.76f, WidgetStateMapper.display(session, true, words, Locale.KOREAN).percent!!, 0.0001f)
        assertEquals(0.24f, WidgetStateMapper.display(session, false, words, Locale.KOREAN).percent!!, 0.0001f)
    }

    /**
     * Widgets saved before this fix never wrote `remaining=false`, because the
     * old default was false and defaults are not encoded. A stored `true` is
     * therefore the only deliberate choice that must survive the migration.
     */
    @Test fun storedWidgetsMigrateWithoutLosingADeliberateRemainingChoice() {
        val json = Json { ignoreUnknownKeys = true }
        val legacyDefault = json.decodeFromString<WidgetConfig>("""{"appWidgetId":1,"showReset":true}""")
        assertNull(legacyDefault.remaining)
        assertTrue(WidgetStateMapper.effectiveRemaining(legacyDefault, settingRemaining = true))

        val legacyRemaining = json.decodeFromString<WidgetConfig>("""{"appWidgetId":2,"remaining":true}""")
        assertEquals(true, legacyRemaining.remaining)
    }

    @Test fun anExplicitUsedChoiceIsPersistedNowThatItIsNoLongerTheDefault() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(WidgetConfig.serializer(), WidgetConfig(3, remaining = false))
        assertTrue(encoded, encoded.contains("\"remaining\":false"))
        assertEquals(false, json.decodeFromString<WidgetConfig>(encoded).remaining)

        val unset = json.encodeToString(WidgetConfig.serializer(), WidgetConfig(4))
        assertFalse(unset, unset.contains("remaining"))
    }
}
