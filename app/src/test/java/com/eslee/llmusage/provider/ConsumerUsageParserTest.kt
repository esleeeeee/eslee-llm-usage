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

    @Test fun codexAbbreviatedEnglishResetDoesNotUseBankedExpiry() {
        val text = "Weekly usage limit\n38%\nremaining\nResets Sep 27, 2026 3:10 AM\nUsage limit resets\nAvailable 2\nFull reset\nExpires Oct 4, 1:58 AM"
        val result = ConsumerUsageParser.parse("chatgpt", "account", text, now, ZoneId.of("UTC")) as ProviderResult.Success
        assertEquals(38.0, result.snapshot.buckets.single().remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-27T03:10:00Z").toEpochMilli(), result.snapshot.buckets.single().resetAt)
    }

    @Test fun grokWeeklyBreakdownResetAndCredits() {
        val snapshot = (parsed("grok", "usage_normal") as ProviderResult.Success).snapshot
        assertEquals(42.0, snapshot.buckets.first { it.id == "weekly" }.usedPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-12T09:00:00+09:00").toEpochMilli(), snapshot.buckets.first().resetAt)
        assertEquals(15.0, snapshot.extraCredits!!.amount, 0.0)
        // The product lines are shares of the one weekly limit, not limits of their own.
        assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
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
        assertEquals(3, snapshot.resetCredits.size)
        assertTrue(snapshot.buckets.none { it.id == "resets" })
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

    /** The Korean Codex page lists each banked reset with its own expiry; the count is the list. */
    @Test fun codexKoreanResetCreditsCarryTheirExpiries() {
        val snapshot = (parsed("chatgpt", "usage_codex_korean") as ProviderResult.Success).snapshot
        assertEquals(2, snapshot.resetCredits.size)
        assertEquals("전체 재설정(주간 + 5시간)", snapshot.resetCredits[0].label)
        val zone = localZone
        val first = java.time.ZonedDateTime.of(2026, 9, 21, 7, 41, 0, 0, zone).toInstant().toEpochMilli()
        val second = java.time.ZonedDateTime.of(2026, 10, 4, 9, 50, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(first, snapshot.resetCredits[0].expiresAt)
        assertEquals(second, snapshot.resetCredits[1].expiresAt)
    }

    @Test fun codexEnglishResetCreditsUseTheStatedCountAndTheListedExpiry() {
        val text = "Weekly usage limit\n38%\nremaining\nResets Sep 27, 2026 3:10 AM\nUsage limit resets\nAvailable 2\nFull reset\nExpires Oct 4, 1:58 AM"
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "account", text, now, ZoneId.of("UTC")) as ProviderResult.Success).snapshot
        assertEquals(2, snapshot.resetCredits.size)
        assertEquals(Instant.parse("2026-10-04T01:58:00Z").toEpochMilli(), snapshot.resetCredits[0].expiresAt)
        assertEquals("Full reset", snapshot.resetCredits[0].label)
        assertNull(snapshot.resetCredits[1].expiresAt)
    }

    @Test fun grokOfferedResetIsOneCreditExpiringInADay() {
        val snapshot = (parsed("grok", "usage_weekly_korean") as ProviderResult.Success).snapshot
        assertEquals(1, snapshot.resetCredits.size)
        assertEquals(now + 86_400_000L, snapshot.resetCredits[0].expiresAt)
        // The weekly quota keeps its own reset, not the credit's expiry.
        assertNotEquals(now + 86_400_000L, snapshot.buckets.first { it.id == "weekly" }.resetAt)
    }

    @Test fun aPageWithoutResetCreditsHasNone() {
        val snapshot = (parsed("claude", "usage_normal") as ProviderResult.Success).snapshot
        assertTrue(snapshot.resetCredits.isEmpty())
    }

    /**
     * Reported: Grok showed "Imagine" as a limit of its own beside the weekly one,
     * and its ring read 92 while the page said 52% used. Grok has one weekly limit;
     * the product lines are shares of it and must not become rings.
     */
    @Test fun grokProductSharesAreNotLimitsOfTheirOwn() {
        val page = "매주 SuperGrok 한도\n52%\n중고\n2026년 9월 25일 오후 4:39 초기화\nChat\n44%\n중고\nImagine\n8%\n중고"
        val snapshot = (ConsumerUsageParser.parse("grok", "a", page, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
        assertEquals(52.0, snapshot.buckets.single().usedPercent!!, 0.0)
        assertEquals(48.0, snapshot.buckets.single().remainingPercent!!, 0.0)
    }

    /** The painted text had a product share's figure but not the weekly one; that used to hide the node walk. */
    @Test fun theNodeWalkIsUsedWhenOnlyItHoldsTheMainFigure() {
        val painted = "매주 SuperGrok 한도\n중고\n2026년 9월 25일 오후 4:39 초기화\nImagine\n8%\n중고"
        val walked = "매주 SuperGrok 한도\n52%\n중고\n2026년 9월 25일 오후 4:39 초기화\nImagine\n8%\n중고"
        val result = ConsumerUsageParser.parseBest("grok", "a", painted, walked, now, localZone)
        assertTrue(ConsumerUsageParser.primaryHasNumbers(result))
        assertEquals(52.0, (result as ProviderResult.Success).snapshot.buckets.first { it.id == "weekly" }.usedPercent!!, 0.0)
    }

    @Test fun completenessDependsOnTheMainQuotaOnly() {
        val sideWithoutFigure = "5시간 사용 한도\n45%\n남음\n주간 사용 한도\n2026. 9. 15. 오후 2:18 초기화"
        val complete = (ConsumerUsageParser.parse("chatgpt", "a", sideWithoutFigure, now, localZone) as ProviderResult.Success).snapshot
        assertEquals("session", complete.primaryBucketId)
        assertEquals(SnapshotStatus.SUCCESS, complete.status)
        val mainWithoutFigure = "매주 SuperGrok 한도\n중고\n2026년 9월 25일 오후 4:39 초기화"
        val partial = ConsumerUsageParser.parse("grok", "a", mainWithoutFigure, now, localZone)
        assertFalse(ConsumerUsageParser.primaryHasNumbers(partial))
        assertEquals(SnapshotStatus.PARTIAL, (partial as ProviderResult.Success).snapshot.status)
    }

    @Test fun whenOnlyTheWeeklyLimitIsDrawnItBecomesTheMainQuota() {
        val page = "5시간 사용 한도\n오후 1:10 초기화\n주간 사용 한도\n66%\n남음"
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot
        assertEquals("weekly", snapshot.primaryBucketId)
        assertEquals(SnapshotStatus.SUCCESS, snapshot.status)
    }

    /** A page that never showed the reset section says nothing about the count: unknown, not zero. */
    @Test fun resetCreditsAreUnknownUntilTheSectionIsSeen() {
        val without = (ConsumerUsageParser.parse("chatgpt", "a", "5시간 사용 한도\n45%\n남음", now, localZone) as ProviderResult.Success).snapshot
        assertFalse(without.resetCreditsKnown)
        val with = (parsed("chatgpt", "usage_codex_korean") as ProviderResult.Success).snapshot
        assertTrue(with.resetCreditsKnown)
    }

    /**
     * Reported: two accounts each held three resets and only one showed three. A
     * reset the page lists without printing its expiry still has its button, and a
     * count stated inside the section is what the user has.
     */
    @Test fun resetsWithoutAPrintedExpiryAreCountedByTheirButtons() {
        val page = "5시간 사용 한도\n45%\n남음\n사용량 한도 재설정\n전체 재설정(주간 + 5시간)\n10월 4일 오전 9:50에 만료\n재설정 사용\n" +
            "전체 재설정(주간 + 5시간)\n재설정 사용\n전체 재설정(주간 + 5시간)\n재설정 사용\n자동 충전"
        val credits = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot.resetCredits
        assertEquals(3, credits.size)
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 4, 9, 50, 0, 0, localZone).toInstant().toEpochMilli(), credits.first().expiresAt)
        val stated = "5시간 사용 한도\n45%\n남음\n사용량 한도 재설정\n3개 사용 가능\n전체 재설정(주간 + 5시간)\n10월 4일 오전 9:50에 만료\n재설정 사용"
        assertEquals(3, (ConsumerUsageParser.parse("chatgpt", "a", stated, now, localZone) as ProviderResult.Success).snapshot.resetCredits.size)
    }

    /** Diagnostics start at a quota label, never at a sidebar line that happens to say "usage". */
    @Test fun diagnosticsAnchorOnTheQuotaLabelAndTheResetSection() {
        val lines = listOf("Chats", "My usage question", "매주 SuperGrok 한도", "52%", "중고")
        assertEquals(2, ConsumerUsageParser.quotaLabelIndex("grok", lines))
        assertEquals(-1, ConsumerUsageParser.quotaLabelIndex("grok", listOf("Chats", "usage limits explained")))
        val section = ConsumerUsageParser.resetSection(fixture("chatgpt", "usage_codex_korean"))!!
        assertEquals("사용량 한도 재설정", section.first())
        assertTrue(section.none { it.contains("자동 충전") })
        assertNull(ConsumerUsageParser.resetSection("5시간 사용 한도\n45%\n남음"))
    }

    /** The node walk can put the moment and the word "만료" on separate lines. */
    @Test fun aResetCreditExpirySplitAcrossLinesIsStillRead() {
        val page = "5시간 사용 한도\n45%\n남음\n사용량 한도 재설정\n전체 재설정(주간 + 5시간)\n9월 21일 오전 7:41\n에 만료\n재설정 사용"
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(1, snapshot.resetCredits.size)
        assertEquals(java.time.ZonedDateTime.of(2026, 9, 21, 7, 41, 0, 0, localZone).toInstant().toEpochMilli(), snapshot.resetCredits.single().expiresAt)
    }
}
