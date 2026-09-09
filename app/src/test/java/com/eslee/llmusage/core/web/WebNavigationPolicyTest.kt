package com.eslee.llmusage.core.web

import org.junit.Assert.*
import org.junit.Test

class WebNavigationPolicyTest {
    @Test fun validatesExactHttpsOrigin() {
        val hosts = setOf("claude.ai")
        assertTrue(WebNavigationPolicy.allows("https://claude.ai/settings/usage",hosts))
        listOf("http://claude.ai", "https://claude.ai.evil.com", "https://evil.com/claude.ai", "javascript:alert(1)", "file:///tmp", "https://user@claude.ai", "https://claude.ai:444").forEach {
            assertFalse(it,WebNavigationPolicy.allows(it,hosts))
        }
    }
}
