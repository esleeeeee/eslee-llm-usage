package com.eslee.llmusage.core.web

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageContextTest {
    /**
     * Reported 2026-11-05: Codex's old usage address opened ChatGPT's home page. A page
     * with no usage word can be that home page, whose sidebar lists the user's own
     * conversation titles, so none of its text is put in the trace.
     */
    @Test fun pageWithoutUsageWordsKeepsItsTextOutOfTheTrace() {
        val page = "ChatGPT\n새 채팅\n내 비밀 프로젝트 계획\n여행 일정 정리"
        assertEquals("no usage label among 4 lines", WebUsageReader.usageContext(page, "chatgpt"))
    }

    @Test fun labelledPageStillStartsAtTheLabel() {
        val page = "Codex\n5시간 사용 한도\n45%\n남음"
        assertEquals("5시간 사용 한도 | 45% | 남음", WebUsageReader.usageContext(page, "chatgpt"))
    }
}
