package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import org.junit.Assert.*
import org.junit.Test

class UsageModelsTest {
    @Test fun normalizationPreservesUnknownAndZero() {
        assertNull(UsageNormalizer.usedPercent(UsageBucket("a", "A", used = 10.0)))
        assertNull(UsageNormalizer.usedPercent(UsageBucket("a", "A", used = 0.0, limit = 0.0)))
        assertEquals(0.0, UsageNormalizer.usedPercent(UsageBucket("a", "A", used = 0.0, limit = 10.0))!!, 0.0)
        assertEquals(25.0, UsageNormalizer.usedPercent(UsageBucket("a", "A", used = 5.0, limit = 20.0))!!, 0.0)
    }
    @Test fun reportedPercentageWinsAndOverflowIsPreserved() {
        val bucket = UsageNormalizer.normalize(UsageBucket("a", "A", used = 1.0, limit = 10.0, usedPercent = 125.0))
        assertEquals(125.0, bucket.usedPercent!!, 0.0)
        assertEquals(0.0, bucket.remainingPercent!!, 0.0)
        assertNull(UsageNormalizer.remainingPercent(UsageBucket("a", "A", usedPercent = 30.0, confidence = Confidence.UNKNOWN)))
        assertNull(UsageNormalizer.usedPercent(UsageBucket("a", "A", usedPercent = Double.NaN)))
        assertNull(UsageNormalizer.usedPercent(UsageBucket("a", "A", used = Double.MAX_VALUE, limit = Double.MIN_VALUE)))
    }
    @Test fun staleDefaultsAndFutureClockAreDefensive() {
        val snapshot = UsageSnapshot("a", "grok", emptyList(), fetchedAt = 100, source = SnapshotSource.VISIBLE_PAGE)
        assertFalse(UsagePresentation.isStale(snapshot, 7_200_100))
        assertTrue(UsagePresentation.isStale(snapshot, 21_600_100))
        assertTrue(UsagePresentation.isStale(snapshot, 99))
        assertTrue(UsagePresentation.isStale(snapshot, 110, 10))
        assertTrue(UsagePresentation.isStale(snapshot.copy(source = SnapshotSource.OFFICIAL_API), 7_200_100))
    }
    @Test fun primarySelectionAndCountdown() {
        val raw = UsageBucket("raw", "Raw", used = 4.0)
        val percent = UsageBucket("percent", "Percent", usedPercent = 20.0)
        val snapshot = UsageSnapshot("a", "p", listOf(raw, percent))
        assertEquals(percent, UsagePresentation.primary(snapshot))
        assertEquals(raw, UsagePresentation.primary(snapshot, "raw"))
        assertEquals(raw, UsagePresentation.primary(snapshot.copy(primaryBucketId = "raw")))
        assertNull(UsagePresentation.primary(snapshot.copy(buckets = emptyList())))
        assertEquals(0L, UsagePresentation.countdown(100, 101))
        assertNull(UsagePresentation.countdown(null, 100))
        assertEquals("in 4h 0m", UsagePresentation.formatCountdown(14_400_100, 100))
        assertEquals("in 59m", UsagePresentation.formatCountdown(3_540_100, 100))
    }
    @Test fun demoIsAbsentFromReleaseRegistry() {
        assertNull(ProviderRegistry(false).definition("demo"))
        assertNull(ProviderRegistry(false).adapter("demo"))
        assertNotNull(ProviderRegistry(true).adapter("demo"))
    }
}
