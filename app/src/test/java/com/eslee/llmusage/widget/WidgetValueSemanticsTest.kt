package com.eslee.llmusage.widget

import com.eslee.llmusage.core.model.UsageBucket
import com.eslee.llmusage.core.model.UsageNormalizer
import com.eslee.llmusage.core.model.UsageUnit
import com.eslee.llmusage.ui.gauge.GaugeGeometry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring is a battery: it fills with what is left and its number says the
 * same thing. The v0.1.6 defect -- a widget quietly showing the complement of
 * the page -- cannot recur because there is no longer a second reading.
 */
class WidgetValueSemanticsTest {
    private val session = UsageBucket("session", "5시간 사용 한도", unit = UsageUnit.PERCENT, remainingPercent = 76.0)
    private val weekly = UsageBucket("weekly", "주간 사용 한도", unit = UsageUnit.PERCENT, remainingPercent = 24.0)

    @Test fun numberAndRingBothReadTheShareLeft() {
        assertEquals("76", GaugeGeometry.numberText(UsageNormalizer.remainingPercent(session)))
        assertEquals("24", GaugeGeometry.numberText(UsageNormalizer.remainingPercent(weekly)))
        assertEquals(0.76f, GaugeGeometry.fraction(UsageNormalizer.remainingPercent(session))!!, 0.0001f)
        assertEquals(0.24f, GaugeGeometry.fraction(UsageNormalizer.remainingPercent(weekly))!!, 0.0001f)
    }

    @Test fun aPageThatReportsUsedIsStillShownAsWhatIsLeft() {
        // Grok reported 0% used, which is a full battery.
        val grokFull = UsageBucket("weekly", "주간", unit = UsageUnit.PERCENT, usedPercent = 0.0)
        assertEquals("100", GaugeGeometry.numberText(UsageNormalizer.remainingPercent(grokFull)))
        assertEquals(GaugeGeometry.SWEEP_ANGLE, GaugeGeometry.sweep(UsageNormalizer.remainingPercent(grokFull)), 0.001f)
        val claudeUsed = UsageBucket("session", "Current session", unit = UsageUnit.PERCENT, usedPercent = 42.0)
        assertEquals("58", GaugeGeometry.numberText(UsageNormalizer.remainingPercent(claudeUsed)))
    }

    @Test fun zeroLeftIsAnEmptyRingWithAZeroNotAnUnknown() {
        val spent = session.copy(remainingPercent = 0.0)
        assertEquals("0", GaugeGeometry.numberText(UsageNormalizer.remainingPercent(spent)))
        assertEquals(0f, GaugeGeometry.sweep(UsageNormalizer.remainingPercent(spent)), 0f)
        assertEquals(GaugeGeometry.Level.LOW, GaugeGeometry.level(0.0))
    }

    @Test fun unknownStaysADashWithNoFillAndNoColour() {
        val unknown = UsageBucket("session", "5시간 사용 한도")
        assertNull(UsageNormalizer.remainingPercent(unknown))
        assertEquals("—", GaugeGeometry.numberText(null))
        assertEquals(0f, GaugeGeometry.sweep(null), 0f)
        assertEquals(GaugeGeometry.Level.UNKNOWN, GaugeGeometry.level(null))
        assertEquals(0, GaugeGeometry.fillArgb(GaugeGeometry.Level.UNKNOWN, dark = true))
    }

    @Test fun colourFollowsTheBatteryThresholds() {
        assertEquals(GaugeGeometry.Level.HIGH, GaugeGeometry.level(100.0))
        assertEquals(GaugeGeometry.Level.HIGH, GaugeGeometry.level(36.0))
        assertEquals(GaugeGeometry.Level.MEDIUM, GaugeGeometry.level(35.0))
        assertEquals(GaugeGeometry.Level.MEDIUM, GaugeGeometry.level(16.0))
        assertEquals(GaugeGeometry.Level.LOW, GaugeGeometry.level(15.0))
        assertEquals(GaugeGeometry.Level.LOW, GaugeGeometry.level(5.0))
    }

    @Test fun outOfRangeValuesAreClampedRatherThanDrawnPastTheRing() {
        assertEquals("100", GaugeGeometry.numberText(140.0))
        assertEquals(GaugeGeometry.SWEEP_ANGLE, GaugeGeometry.sweep(140.0), 0.001f)
        assertEquals("0", GaugeGeometry.numberText(-3.0))
        assertEquals("—", GaugeGeometry.numberText(Double.NaN))
    }

    /**
     * Widgets saved by earlier versions carry keys for layouts, tap actions and a
     * used/remaining choice that no longer exist. They must still decode, keeping
     * their accounts, and fall back to the ring's own defaults for the rest.
     */
    @Test fun storedWidgetsFromEarlierVersionsKeepTheirAccountsAndIgnoreRetiredKeys() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<WidgetConfig>(
            """{"appWidgetId":7,"selections":[{"accountId":"a","bucketSelector":"ALL_PRIMARY"}],"style":"BAR","remaining":false,"showReset":true,"tap":"REFRESH","theme":"DARK"}""",
        )
        assertEquals(7, legacy.appWidgetId)
        assertEquals(listOf(WidgetSelection("a", "ALL_PRIMARY")), legacy.selections)
        assertEquals(WidgetTheme.DARK, legacy.theme)
        assertEquals(WidgetBackground.TRANSLUCENT, legacy.background)
    }

    @Test fun aSavedWidgetRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val config = WidgetConfig(3, listOf(WidgetSelection("a"), WidgetSelection("b", "ALL")), WidgetTheme.LIGHT, WidgetBackground.NONE)
        val encoded = json.encodeToString(WidgetConfig.serializer(), config)
        assertTrue(encoded, encoded.contains("\"background\":\"NONE\""))
        assertEquals(config, json.decodeFromString<WidgetConfig>(encoded))
    }
}
