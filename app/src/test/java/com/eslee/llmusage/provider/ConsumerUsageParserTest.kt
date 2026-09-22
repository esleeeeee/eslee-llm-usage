package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.SnapshotStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ConsumerUsageParserTest {
    private val now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli()
    private val localZone = ZoneId.of("Asia/Seoul")
    private fun fixture(provider: String, name: String) = requireNotNull(javaClass.getResource("/provider/$provider/$name.txt")).readText()
    private fun parsed(provider: String, name: String): ProviderResult =
        ConsumerUsageParser.parse(provider, "account", fixture(provider, name), now, localZone)

    @Test fun grokSplitPercentAndEnglishResetUseLocalZone() {
        val text = "Weekly SuperGrok Limit\nAbout your included usage\n0\n%\nused\nResets\nSeptember 25, 2026 at 7:39 AM\nExtra Usage Credits\n$0.00"
        val result = ConsumerUsageParser.parse("grok", "account", text, now, ZoneId.of("UTC")) as ProviderResult.Success
        val weekly = result.snapshot.buckets.single()
        assertEquals(0.0, weekly.usedPercent!!, 0.0)
        assertEquals(100.0, weekly.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-25T07:39:00Z").toEpochMilli(), weekly.resetAt)
        val seoul = ConsumerUsageParser.parse("grok", "account", text, now, localZone) as ProviderResult.Success
        assertEquals(Instant.parse("2026-09-24T22:39:00Z").toEpochMilli(), seoul.snapshot.buckets.single().resetAt)
    }

    @Test fun splitPercentStillRequiresMeaningAndValidCalendarDate() {
        val result = ConsumerUsageParser.parse("grok", "account", "Weekly SuperGrok Limit\n35\n%\nResets February 30, 2026 at 7:39 AM", now, localZone)
        assertTrue(result is ProviderResult.Failure)
    }

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
        assertEquals(SnapshotStatus.PARTIAL, snapshot.status)
    }
    @Test fun chatgptCodexDashboardReadsWindowsCreditsAndResets() {
        val snapshot = (parsed("chatgpt", "usage_dashboard") as ProviderResult.Success).snapshot
        assertEquals("ChatGPT Plus", snapshot.planName)
        assertEquals("session", snapshot.primaryBucketId)
        assertEquals(20.0, snapshot.buckets.first { it.id == "session" }.usedPercent!!, 0.0)
        assertEquals(now + 5_400_000, snapshot.buckets.first { it.id == "session" }.resetAt)
        assertEquals(25.0, snapshot.buckets.first { it.id == "weekly" }.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-15T05:18:00Z").toEpochMilli(), snapshot.buckets.first { it.id == "weekly" }.resetAt)
        assertEquals(0.0, snapshot.buckets.first { it.id == "reserve" }.usedPercent!!, 0.0)
        assertEquals(3.0, snapshot.buckets.first { it.id == "resets" }.remaining!!, 0.0)
        assertEquals(0.0, snapshot.extraCredits!!.amount, 0.0)
    }
    @Test fun chatgptKoreanCodexSurfaceReadsMultilineRemainingAndLocalResets() {
        val snapshot = (parsed("chatgpt", "usage_codex_korean") as ProviderResult.Success).snapshot
        val session = snapshot.buckets.first { it.id == "session" }
        val weekly = snapshot.buckets.first { it.id == "weekly" }
        assertEquals(55.0, session.usedPercent!!, 0.0)
        assertEquals(45.0, session.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-09T04:10:00Z").toEpochMilli(), session.resetAt)
        assertEquals(34.0, weekly.usedPercent!!, 0.0)
        assertEquals(66.0, weekly.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-15T05:18:00Z").toEpochMilli(), weekly.resetAt)
        assertEquals(0.0, snapshot.extraCredits!!.amount, 0.0)
        assertEquals(2, snapshot.buckets.size)
    }
    @Test fun chatgptCodexAccessibilityLabelsKeepEnglishRemainingSemantics() {
        val snapshot = (parsed("chatgpt", "usage_codex_aria") as ProviderResult.Success).snapshot
        assertEquals(55.0, snapshot.buckets.first { it.id == "session" }.usedPercent!!, 0.0)
        assertEquals(34.0, snapshot.buckets.first { it.id == "weekly" }.usedPercent!!, 0.0)
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

    /**
     * 76/24 sums to 100, so a bucket swap and a used/remaining inversion look
     * identical there. Uneven quotas keep the two failures distinguishable.
     */
    @Test fun koreanCodexQuotasThatDoNotSumToOneHundredStayWithTheirOwnLimit() {
        val snapshot = (parsed("chatgpt", "usage_codex_korean_uneven") as ProviderResult.Success).snapshot
        val session = snapshot.buckets.first { it.id == "session" }
        val weekly = snapshot.buckets.first { it.id == "weekly" }
        assertEquals("5-hour usage", session.label)
        assertEquals(76.0, session.remainingPercent!!, 0.0)
        assertEquals(24.0, session.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-09T04:10:00Z").toEpochMilli(), session.resetAt)
        assertEquals("Weekly usage", weekly.label)
        assertEquals(31.0, weekly.remainingPercent!!, 0.0)
        assertEquals(69.0, weekly.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-15T05:18:00Z").toEpochMilli(), weekly.resetAt)
        assertEquals(2, snapshot.buckets.size)
    }

    /**
     * Real SuperGrok usage page (Korean). The label is "매주 SuperGrok 한도", not
     * "주간 사용량", and Grok renders "used" as the machine translation "중고";
     * both prevented any bucket from being read before.
     */
    @Test fun grokKoreanWeeklyLimitAndCreditsFromLivePage() {
        val snapshot = (parsed("grok", "usage_weekly_korean") as ProviderResult.Success).snapshot
        val weekly = snapshot.buckets.first { it.id == "weekly" }
        assertEquals(0.0, weekly.usedPercent!!, 0.0)
        assertEquals(100.0, weekly.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-18T07:39:00Z").toEpochMilli(), weekly.resetAt)
        assertEquals(0.0, snapshot.extraCredits!!.amount, 0.0)
        assertEquals("weekly", snapshot.primaryBucketId)
    }

    /** The exact page a user copied on 2026-09-21, after the weekly window rolled over. */
    @Test fun grokKoreanWeeklyPageWithoutTheResetBlock() {
        val result = parsed("grok", "usage_weekly_sep21")
        assertTrue("parse returned $result", result is ProviderResult.Success)
        val snapshot = (result as ProviderResult.Success).snapshot
        val weekly = snapshot.buckets.firstOrNull { it.id == "weekly" }
        assertNotNull("no weekly bucket in ${snapshot.buckets.map { it.id }}", weekly)
        assertEquals("usedPercent", 0.0, weekly!!.usedPercent ?: -1.0, 0.0)
        assertEquals("remainingPercent", 100.0, weekly.remainingPercent ?: -1.0, 0.0)
        assertEquals(Instant.parse("2026-09-25T07:39:00Z").toEpochMilli(), weekly.resetAt)
        assertEquals(0.0, snapshot.extraCredits?.amount ?: -1.0, 0.0)
        assertEquals(SnapshotStatus.SUCCESS, snapshot.status)
    }

    /**
     * The device reported "weekly=novalue+reset": the live page carries more
     * percentages in a section than a hand-copied page shows, and requiring a
     * single reading threw all of them away. The quota's own number is the first
     * one under its label.
     */
    @Test fun extraPercentagesInASectionDoNotEraseTheQuotaValue() {
        val page = """
            매주 SuperGrok 한도
            0%
            중고
            15%
            2026년 9월 25일 오후 4:39 초기화
        """.trimIndent()
        val weekly = (ConsumerUsageParser.parse("grok", "a", page, now, localZone) as ProviderResult.Success)
            .snapshot.buckets.first { it.id == "weekly" }
        assertEquals(0.0, weekly.usedPercent!!, 0.0)
        assertEquals(100.0, weekly.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-25T07:39:00Z").toEpochMilli(), weekly.resetAt)
    }

    @Test fun anUnlabelledPercentDoesNotEstablishUsedOrRemainingSemantics() {
        for (page in listOf("주간 사용량\n42%", "주간 사용량\nfiller\nfiller\nfiller\n42%")) {
            assertTrue(ConsumerUsageParser.parse("grok", "a", page, now, localZone) is ProviderResult.Failure)
        }
        val weekly = (ConsumerUsageParser.parse("grok", "a", "주간 사용량\n42%\nResets in 3 hours", now, localZone) as ProviderResult.Success)
            .snapshot.buckets.single()
        assertNull(weekly.usedPercent)
        assertNull(weekly.remainingPercent)
        assertEquals(now + 3 * 3_600_000L, weekly.resetAt)
    }

    @Test fun codexHeadingDoesNotCreateAnUnrelatedSessionQuota() {
        val page = "Codex\nCode review\n80% remaining\nWeekly limit\n31% remaining"
        val buckets = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot.buckets
        assertEquals(listOf("weekly"), buckets.map { it.id })
        assertEquals(31.0, buckets.single().remainingPercent!!, 0.0)
    }

    /**
     * The structural capture puts every text node on its own line, so a page that
     * styles the moment and the word "초기화" differently splits them apart and the
     * word is left alone on its line.
     */
    @Test fun aResetTimeIsFoundWhenItsWordSitsOnAnotherLine() {
        val split = """
            매주 SuperGrok 한도
            0%
            중고
            2026년 9월 25일 오후 4:39
            초기화
        """.trimIndent()
        val weekly = (ConsumerUsageParser.parse("grok", "a", split, now, localZone) as ProviderResult.Success)
            .snapshot.buckets.first { it.id == "weekly" }
        assertEquals(0.0, weekly.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-25T07:39:00Z").toEpochMilli(), weekly.resetAt)
    }

    @Test fun aRelativeResetSurvivesTheSameSplit() {
        val split = "5시간 사용 한도\n45%\n남음\n3시간 후\n초기화"
        val session = (ConsumerUsageParser.parse("chatgpt", "a", split, now, localZone) as ProviderResult.Success)
            .snapshot.buckets.first { it.id == "session" }
        assertEquals(now + 3 * 3_600_000L, session.resetAt)
    }

    @Test fun aRelativeResetCanFollowItsLabel() {
        for (reset in listOf("Resets in\n3 hours", "초기화\n3시간 후")) {
            val page = "5-hour limit\n45% remaining\n$reset"
            val session = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot.buckets.single()
            assertEquals(now + 3 * 3_600_000L, session.resetAt)
        }
        assertNull(ConsumerUsageParser.parseReset("Banked reset available\nExpires in 1 day", now, localZone))
    }

    /**
     * Reported on v0.2.0: the Grok ring shows its value but no reset. The
     * structural capture gives a progress bar's aria value and a CSS-drawn
     * figure their own lines, which pushes "초기화" past the six lines a quota's
     * figures are read from, so the time was never in the text the reset
     * parser saw.
     */
    @Test fun aResetPushedPastTheFigureWindowByHiddenNodesIsStillFound() {
        val rich = """
            매주 SuperGrok 한도
            0
            0%
            0% 사용됨
            0
            중고
            2026년 9월 25일 오후 4:39
            초기화
            추가 사용 크레딧
            US$0.00
        """.trimIndent()
        val weekly = (ConsumerUsageParser.parse("grok", "a", rich, now, localZone) as ProviderResult.Success)
            .snapshot.buckets.first { it.id == "weekly" }
        assertEquals(0.0, weekly.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-25T07:39:00Z").toEpochMilli(), weekly.resetAt)
    }

    @Test fun aBankedResetExpiryIsNotMistakenForTheQuotaReset() {
        val text = """
            매주 SuperGrok 한도
            0%
            중고
            사용 한도 재설정
            재설정 가능
            1일 후 만료
        """.trimIndent()
        val weekly = (ConsumerUsageParser.parse("grok", "a", text, now, localZone) as ProviderResult.Success)
            .snapshot.buckets.first { it.id == "weekly" }
        assertEquals(null, weekly.resetAt)
    }
}
