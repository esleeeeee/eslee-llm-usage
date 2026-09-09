package com.eslee.llmusage.provider

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConsumerUsageParserTest {
    private val now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli()
    private fun fixture(provider: String, name: String) = requireNotNull(javaClass.getResource("/provider/$provider/$name.txt")).readText()
    private fun parsed(provider: String, name: String): ProviderResult = ConsumerUsageParser.parse(provider, "account", fixture(provider, name), now)

    @Test fun grokWeeklyBreakdownResetAndCredits() {
        val snapshot = (parsed("grok", "usage_normal") as ProviderResult.Success).snapshot
        assertEquals(42.0, snapshot.buckets.first { it.id == "weekly" }.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-12T09:00:00+09:00").toEpochMilli(), snapshot.buckets.first().resetAt)
        assertEquals(15.0, snapshot.extraCredits!!.amount, 0.0)
        assertEquals(10.0, snapshot.buckets.first { it.id == "chat" }.usedPercent!!, 0.0)
    }
    @Test fun claudeSessionAndWeeklyRemainIndependent() {
        val buckets = (parsed("claude", "usage_normal") as ProviderResult.Success).snapshot.buckets
        assertEquals(35.0, buckets.first { it.id == "session" }.usedPercent!!, 0.0)
        assertEquals(now + 14_400_000, buckets.first { it.id == "session" }.resetAt)
        assertEquals(72.0, buckets.first { it.id == "weekly" }.remainingPercent!!, 0.0)
    }
    @Test fun chatgptKnownSurfaceAndResetOnly() {
        assertTrue(parsed("chatgpt", "usage_normal") is ProviderResult.Success)
        val snapshot = (parsed("chatgpt", "usage_partial") as ProviderResult.Success).snapshot
        assertNull(snapshot.buckets.first().usedPercent)
        assertNotNull(snapshot.buckets.first().resetAt)
    }
    @Test fun unknownChangedAndLoginNeverBecomeZeroSuccess() {
        for (provider in listOf("grok", "claude", "chatgpt")) {
            for (fixture in listOf("usage_unknown", "parser_changed")) assertTrue(parsed(provider, fixture) is ProviderResult.Failure)
            assertEquals(ProviderErrorCode.AUTH_REQUIRED, (parsed(provider, "login_page") as ProviderResult.Failure).code)
            assertTrue(parsed(provider, "usage_partial") is ProviderResult.Success)
        }
    }
    @Test fun unrelatedPromotionalPercentIsNotUsage() {
        assertTrue(ConsumerUsageParser.parse("grok", "a", "Special offer\nSave 50% today\n123 messages", now) is ProviderResult.Failure)
        assertTrue(ConsumerUsageParser.parse("grok", "a", "Weekly usage\nUnknown\nChat\nUnknown\nUpgrade\nSave 50%", now) is ProviderResult.Failure)
    }
}
