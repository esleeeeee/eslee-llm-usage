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

    @Test fun structuralFullExpiryReplacesPaintedDateOnlyWithoutDuplicatingButtons() {
        val header = "주간 사용 한도\n42% 남음\n사용 한도 초기화\n사용 가능\n2\n내역\n"
        val painted = header + "전체 재설정\n10월 23일 만료\n초기화 사용\n전체 재설정\n10월 30일 만료\n초기화 사용"
        val rich = header + "전체 재설정\n10. 23. 오전 4:29 GMT+9 만료\n10월 23일 만료\n초기화 사용 전체 재설정\n초기화 사용\n" +
            "전체 재설정\n10. 30. 오전 1:39 GMT+9 만료\n10월 30일 만료\n초기화 사용 전체 재설정\n초기화 사용"
        val expected = listOf("2026-10-22T19:29:00Z", "2026-10-29T16:39:00Z").map { Instant.parse(it).toEpochMilli() }
        for ((first, second) in listOf(painted to rich, rich to painted)) {
            val snapshot = (ConsumerUsageParser.parseBest("chatgpt", "a", first, second, now, localZone) as ProviderResult.Success).snapshot
            assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
            assertEquals(42.0, snapshot.buckets.single().remainingPercent!!, 0.0)
            assertEquals(expected, snapshot.resetCredits.map { it.expiresAt })
        }
    }

    @Test fun liveWeeklyCountdownDoesNotCreateAFiveHourQuota() {
        val painted = "주간 사용 한도\n초기화까지 5일 5시간 남았습니다\n42% 남음\n크레딧\n사용 한도 초기화"
        val rich = "주간 사용 한도\n2026년 10월 12일 월요일 오전 12시 49분 49초 GMT+9\n초기화까지 5일 5시간 남았습니다\n42% 남음\n남은 사용량\n사용 한도 초기화"
        val snapshot = (ConsumerUsageParser.parseBest("chatgpt", "a", painted, rich, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
        assertEquals("weekly", snapshot.primaryBucketId)
        assertEquals(42.0, snapshot.buckets.single().remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-10-11T15:49:49Z").toEpochMilli(), snapshot.buckets.single().resetAt)
    }

    @Test fun creditAccessibilityTimestampAndPaintedExpiryAreOneItemPerButton() {
        val page = """
            주간 사용 한도
            42% 남음
            사용 한도 초기화
            초기화를 사용해 5시간 한도나 주간 한도, 또는 두 한도를 모두 복원하세요
            사용 가능
            2
            내역
            전체 재설정
            2026년 10월 23일 금요일 오전 4시 29분 0초 GMT+9 만료
            10월 23일 만료
            초기화 사용
            전체 재설정
            2026년 10월 30일 금요일 오전 1시 39분 0초 GMT+9 만료
            10월 30일 만료
            초기화 사용
        """.trimIndent()
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
        assertEquals(2, snapshot.resetCredits.size)
        assertEquals(Instant.parse("2026-10-22T19:29:00Z").toEpochMilli(), snapshot.resetCredits[0].expiresAt)
        assertEquals(Instant.parse("2026-10-29T16:39:00Z").toEpochMilli(), snapshot.resetCredits[1].expiresAt)
        val zero = (ConsumerUsageParser.parse("chatgpt", "a", page.replace("사용 가능\n2", "사용 가능\n0"), now, localZone) as ProviderResult.Success).snapshot
        assertTrue(zero.resetCreditsKnown)
        assertTrue("explicit zero wins over stale duplicate items", zero.resetCredits.isEmpty())
    }

    @Test fun liveKoreanCodexGmtPlusNineDatesKeepTheSameUtcInstants() {
        val capturedAt = Instant.parse("2026-10-06T08:00:00Z").toEpochMilli()
        val page = """
            5시간 단위 한도
            2026년 10월 6일 화요일 오후 7시 25분 19초 GMT+9
            75% 남음
            주간 사용 한도
            2026년 10월 13일 화요일 오후 2시 25분 19초 GMT+9
            96% 남음
            사용 한도 초기화
            사용 가능 2
            전체 재설정(주간 + 5시간)
            10. 23. 오전 4:29 GMT+9 만료
            초기화 사용 전체 재설정(주간 + 5시간)
            전체 재설정(주간 + 5시간)
            10. 30. 오전 1:39 GMT+9 만료
            초기화 사용 전체 재설정(주간 + 5시간)
        """.trimIndent()
        for (offset in listOf("GMT+9", "GMT+09:00")) {
            val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", page.replace("GMT+9", offset), capturedAt, localZone) as ProviderResult.Success).snapshot
            assertEquals(Instant.parse("2026-10-06T10:25:19Z").toEpochMilli(), snapshot.buckets[0].resetAt)
            assertEquals(Instant.parse("2026-10-13T05:25:19Z").toEpochMilli(), snapshot.buckets[1].resetAt)
            assertEquals(2, snapshot.resetCredits.size)
            assertEquals(Instant.parse("2026-10-22T19:29:00Z").toEpochMilli(), snapshot.resetCredits[0].expiresAt)
            assertEquals(Instant.parse("2026-10-29T16:39:00Z").toEpochMilli(), snapshot.resetCredits[1].expiresAt)
        }
    }

    @Test fun explicitNegativeGmtOffsetIsUsedAndInvalidOffsetsStayUnknown() {
        val quota = "5시간 단위 한도\n2026년 10월 6일 화요일 오후 7시 25분 19초 GMT-4:30\n75% 남음"
        val negative = quota + "\n사용 한도 초기화\n사용 가능 1\n전체 재설정\n10. 23. 오전 4:29 GMT-4:30 만료"
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", negative, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(Instant.parse("2026-10-06T23:55:19Z").toEpochMilli(), snapshot.buckets.single().resetAt)
        assertEquals(Instant.parse("2026-10-23T08:59:00Z").toEpochMilli(), snapshot.resetCredits.single().expiresAt)
        for (invalid in listOf("GMT+25", "GMT+09:99", "GMT+9oops", "GMT+")) {
            val text = quota.replace("GMT-4:30", invalid) + "\n초기화\n사용 한도 초기화\n사용 가능 1\n전체 재설정\n10. 23. 오전 4:29 $invalid 만료"
            val parsed = (ConsumerUsageParser.parse("chatgpt", "a", text, now, localZone) as ProviderResult.Success).snapshot
            assertNull("invalid quota offset $invalid", parsed.buckets.single().resetAt)
            assertEquals(1, parsed.resetCredits.size)
            assertNull("invalid expiry offset $invalid", parsed.resetCredits.single().expiresAt)
        }
    }

    @Test fun liveKoreanCodexAccessibilityDatesAndResetCreditsUseExplicitGmt() {
        val capturedAt = Instant.parse("2026-10-06T08:00:00Z").toEpochMilli()
        val page = """
            5시간 단위 한도
            2026년 10월 6일 화요일 오전 10시 25분 19초 GMT
            75% 남음
            주간 사용 한도
            2026년 10월 13일 화요일 오전 5시 25분 19초 GMT
            96% 남음
            사용 한도 초기화
            초기화를 사용해 5시간 한도나 주간 한도, 또는 두 한도를 모두 복원하세요
            사용 한도 재설정
            사용 가능 2
            전체 재설정(주간 + 5시간)
            10. 22. 오후 7:29 GMT 만료
            초기화 사용 전체 재설정(주간 + 5시간)
            전체 재설정(주간 + 5시간)
            10. 29. 오후 4:39 GMT 만료
            초기화 사용 전체 재설정(주간 + 5시간)
            크레딧 사용 내역
        """.trimIndent()
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", page, capturedAt, localZone) as ProviderResult.Success).snapshot
        assertEquals(listOf("session", "weekly"), snapshot.buckets.map { it.id })
        assertEquals(75.0, snapshot.buckets[0].remainingPercent!!, 0.0)
        assertEquals(96.0, snapshot.buckets[1].remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-10-06T10:25:19Z").toEpochMilli(), snapshot.buckets[0].resetAt)
        assertEquals(Instant.parse("2026-10-13T05:25:19Z").toEpochMilli(), snapshot.buckets[1].resetAt)
        assertTrue(snapshot.resetCreditsKnown)
        assertEquals(2, snapshot.resetCredits.size)
        assertEquals(Instant.parse("2026-10-22T19:29:00Z").toEpochMilli(), snapshot.resetCredits[0].expiresAt)
        assertEquals(Instant.parse("2026-10-29T16:39:00Z").toEpochMilli(), snapshot.resetCredits[1].expiresAt)

        val noQuotaDates = page.lines().filterNot { it.contains("요일") }.joinToString("\n")
        val withoutDates = (ConsumerUsageParser.parse("chatgpt", "a", noQuotaDates, capturedAt, localZone) as ProviderResult.Success).snapshot
        assertTrue("credit expiry is never a quota reset", withoutDates.buckets.all { it.resetAt == null })
        assertEquals(listOf("session", "weekly"), withoutDates.buckets.map { it.id })
    }

    @Test fun paintedNumbersKeepPrecedenceWhileStructuralMetadataCompletesTheRead() {
        val painted = "5시간 사용 한도\n45% 남음\n주간 사용 한도\n66% 남음"
        val walked = "5시간 사용 한도\n90% 남음\n오후 1:10\n초기화\n주간 사용 한도\n99% 남음\n2026. 9. 15. 오후 2:18\n초기화\n" +
            "사용량 한도 재설정\n사용 가능\n2\n전체 재설정\n9월 21일 오전 7:41\n에 만료\n재설정 사용"
        val snapshot = (ConsumerUsageParser.parseBest("chatgpt", "a", painted, walked, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(45.0, snapshot.buckets.first { it.id == "session" }.remainingPercent!!, 0.0)
        assertEquals(66.0, snapshot.buckets.first { it.id == "weekly" }.remainingPercent!!, 0.0)
        assertEquals(Instant.parse("2026-09-09T04:10:00Z").toEpochMilli(), snapshot.buckets.first { it.id == "session" }.resetAt)
        assertEquals(Instant.parse("2026-09-15T05:18:00Z").toEpochMilli(), snapshot.buckets.first { it.id == "weekly" }.resetAt)
        assertTrue(snapshot.resetCreditsKnown)
        assertEquals(2, snapshot.resetCredits.size)
        assertEquals(Instant.parse("2026-09-20T22:41:00Z").toEpochMilli(), snapshot.resetCredits.first().expiresAt)
    }

    @Test fun structuralMetadataOnlyCaptureCanConfirmZeroCredits() {
        val painted = "Weekly limit\n31% remaining"
        val snapshot = (ConsumerUsageParser.parseBest("chatgpt", "a", painted, "Usage limit resets\nAvailable\n0", now, localZone) as ProviderResult.Success).snapshot
        assertTrue(snapshot.resetCreditsKnown)
        assertTrue(snapshot.resetCredits.isEmpty())
        assertEquals(31.0, snapshot.buckets.single().remainingPercent!!, 0.0)
        val knownZero = (ConsumerUsageParser.parseBest("chatgpt", "a", "$painted\nUsage limit resets\nAvailable 0",
            "$painted\nUsage limit resets\nAvailable 2", now, localZone) as ProviderResult.Success).snapshot
        assertTrue(knownZero.resetCreditsKnown)
        assertTrue("confirmed painted zero must not be replaced", knownZero.resetCredits.isEmpty())
    }

    @Test fun resetHeaderStillLoadingDoesNotMeanZeroCredits() {
        for (tail in listOf("Usage limit resets", "Usage limit resets\nAvailable")) {
            val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", "Weekly limit\n31% remaining\n$tail", now, localZone) as ProviderResult.Success).snapshot
            assertFalse(snapshot.resetCreditsKnown)
        }
    }

    @Test fun splitKoreanMonthDayResetKeepsItsDateWithoutAnExplicitYear() {
        val page = "주간 사용 한도\n66% 남음\n초기화\n9월 15일\n오후 2:18"
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", page, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(Instant.parse("2026-09-15T05:18:00Z").toEpochMilli(), snapshot.buckets.single().resetAt)
    }

    @Test fun structuralCreditExpiryCompletesCountWithoutBecomingQuotaReset() {
        val painted = "Weekly limit\n31% remaining\nUsage limit resets\nAvailable 2"
        val rich = "Weekly limit\nUsage limit resets\nAvailable 2\nFull reset\nExpires Oct 4, 2026 1:58 AM\nUse reset"
        val snapshot = (ConsumerUsageParser.parseBest("chatgpt", "a", painted, rich, now, localZone) as ProviderResult.Success).snapshot
        assertNull(snapshot.buckets.single().resetAt)
        assertEquals(2, snapshot.resetCredits.size)
        assertNotNull(snapshot.resetCredits.first().expiresAt)
        // No numbers are needed to reproduce the short reset window crossing the header.
        val short = (ConsumerUsageParser.parse("chatgpt", "a", "Weekly limit\n31% remaining\nUsage limit resets\nFull reset\nExpires 2026-10-04T01:58:00Z", now, localZone) as ProviderResult.Success).snapshot
        assertNull(short.buckets.single().resetAt)
    }

    @Test fun metadataMergeDoesNotReplaceKnownResetOrImportAnUnmatchedQuota() {
        val painted = "Weekly limit\n31% remaining\nResets in 2 days"
        val rich = "5-hour limit\n80% remaining\nResets in 3 hours\nWeekly limit\nResets in 5 days"
        val snapshot = (ConsumerUsageParser.parseBest("chatgpt", "a", painted, rich, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
        assertEquals(now + 2 * 86_400_000L, snapshot.buckets.single().resetAt)
    }

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

    /**
     * Reported: Grok never changed and every refresh counted as a success. Usage in
     * a window whose reset has passed is a cache from before the reset, not a reading;
     * an idle window (nothing used) or a figure without a reset time is not judged.
     */
    @Test fun usageInAWindowThatAlreadyResetIsLapsed() {
        val page = "매주 SuperGrok 한도\n52%\n중고\n2026년 9월 25일 오후 4:39 초기화"
        val snapshot = (ConsumerUsageParser.parse("grok", "a", page, now, localZone) as ProviderResult.Success).snapshot
        val reset = snapshot.buckets.single().resetAt!!
        assertFalse(ConsumerUsageParser.lapsed(snapshot, reset - 60_000))
        assertFalse("a window rolls over a little late", ConsumerUsageParser.lapsed(snapshot, reset + 30 * 60_000))
        assertTrue(ConsumerUsageParser.lapsed(snapshot, reset + 2 * 3_600_000))
        val idle = (ConsumerUsageParser.parse("grok", "a", page.replace("52%", "0%"), now, localZone) as ProviderResult.Success).snapshot
        assertFalse(ConsumerUsageParser.lapsed(idle, reset + 2 * 3_600_000))
        val timeless = (ConsumerUsageParser.parse("grok", "a", "매주 SuperGrok 한도\n52%\n중고", now, localZone) as ProviderResult.Success).snapshot
        assertFalse(ConsumerUsageParser.lapsed(timeless, reset + 2 * 3_600_000))
    }

    /**
     * Reported on v0.2.8 with the phone's own trace: Grok showed 2% used while its
     * page said 52%. The page animates its figure one character per node, so the walk
     * reads "5 | 2 | %", and only the "2" beside the sign was taken. The product share
     * between the figure and the date also hid the weekly reset.
     */
    @Test fun grokFigureDrawnOneCharacterPerNodeIsReadWhole() {
        val walked = listOf(
            "매주 SuperGrok 한도", "52%", "5", "2", "%", "중고", "Imagine", "52", "%",
            "2026년 10월 2일 오후 4:39", "초기화", "추가 사용 크레딧", "US\$0.00", "US\$", "0", ".", "0", "0",
        ).joinToString("\n")
        val snapshot = (ConsumerUsageParser.parse("grok", "a", walked, now, localZone) as ProviderResult.Success).snapshot
        val weekly = snapshot.buckets.single()
        assertEquals(52.0, weekly.usedPercent!!, 0.0)
        assertEquals(48.0, weekly.remainingPercent!!, 0.0)
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 2, 16, 39, 0, 0, localZone).toInstant().toEpochMilli(), weekly.resetAt)
        val decimal = (ConsumerUsageParser.parse("grok", "a", "매주 SuperGrok 한도\n5\n2\n.\n5\n%\n중고", now, localZone) as ProviderResult.Success).snapshot
        assertEquals(52.5, decimal.buckets.single().usedPercent!!, 0.0)
    }

    /**
     * Reported on v0.2.8: the Pro account showed a 5-hour limit with no figure. Its
     * page has no 5-hour limit; the banked-reset section's own sentence ("재설정을 사용해
     * 5시간 한도, 주간 한도 …") was taken for one. Its count sits on the line after "사용 가능".
     */
    @Test fun theResetSectionNeverOpensAQuotaAndItsCountIsRead() {
        val pro = listOf(
            "주간 사용 한도", "76%", "남음", "2026. 10. 3. 오후 1:21 초기화",
            "사용량 한도 재설정", "재설정을 사용해 5시간 한도, 주간 한도 또는 둘 다를 복원하세요.", "사용 가능", "3", "내역",
            "전체 재설정", "10월 4일 오전 10:58에 만료", "재설정 사용",
            "전체 재설정", "10월 5일 오후 1:21에 만료", "재설정 사용", "자동 충전",
        ).joinToString("\n")
        val snapshot = (ConsumerUsageParser.parse("chatgpt", "a", pro, now, localZone) as ProviderResult.Success).snapshot
        assertEquals(listOf("weekly"), snapshot.buckets.map { it.id })
        assertEquals(24.0, snapshot.buckets.single().usedPercent!!, 0.0)
        assertEquals("the page says three; two are listed so far", 3, snapshot.resetCredits.size)
        // The Plus page, with its real 5-hour limit and "(주간 + 5시간)" credits, keeps both limits.
        val plus = (parsed("chatgpt", "usage_codex_korean") as ProviderResult.Success).snapshot
        assertEquals(listOf("session", "weekly"), plus.buckets.map { it.id })
        assertEquals(45.0, plus.buckets.first { it.id == "session" }.remainingPercent!!, 0.0)
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
