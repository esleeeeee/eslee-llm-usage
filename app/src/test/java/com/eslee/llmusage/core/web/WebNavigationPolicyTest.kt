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

    @Test fun oauthHostsAreAllowedOnConsumerLogin() {
        val chatgpt = WebNavigationPolicy.withOAuth(setOf("chatgpt.com", "auth.openai.com"))
        val grok = WebNavigationPolicy.withOAuth(setOf("grok.com", "accounts.x.ai"))
        assertTrue(WebNavigationPolicy.allows("https://accounts.google.com/o/oauth2/v2/auth", chatgpt))
        assertTrue(WebNavigationPolicy.allows("https://accounts.google.com/o/oauth2/auth", grok))
        assertTrue(WebNavigationPolicy.allows("https://appleid.apple.com/auth/authorize", chatgpt))
        assertTrue(WebNavigationPolicy.allows("https://x.com/i/oauth2/authorize", grok))
        assertEquals(NavigationDecision.BLOCK_SCHEME, WebNavigationPolicy.inspect("intent://accounts.google.com#Intent;end", chatgpt))
        assertEquals(NavigationDecision.BLOCK_SCHEME, WebNavigationPolicy.inspect("market://details?id=com.google.android.gms", grok))
        assertFalse(WebNavigationPolicy.redact("https://accounts.google.com/o/oauth2/auth?code=SECRET").contains("SECRET"))
        assertEquals("https://accounts.google.com/o/oauth2/auth", WebNavigationPolicy.redact("https://accounts.google.com/o/oauth2/auth?code=SECRET"))
    }
}
