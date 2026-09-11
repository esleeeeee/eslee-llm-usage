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
        assertTrue(WebNavigationPolicy.allows("https://chatgpt.com/codex/settings/usage", chatgpt))
        assertTrue(WebNavigationPolicy.allows("https://grok.com/?_s=usage", grok))
        assertTrue(WebNavigationPolicy.allows("https://accounts.google.com/o/oauth2/v2/auth", chatgpt))
        assertTrue(WebNavigationPolicy.allows("https://accounts.google.com/o/oauth2/auth", grok))
        assertTrue(WebNavigationPolicy.allows("https://appleid.apple.com/auth/authorize", chatgpt))
        assertTrue(WebNavigationPolicy.allows("https://x.com/i/oauth2/authorize", grok))
        assertEquals(NavigationDecision.BLOCK_SCHEME, WebNavigationPolicy.inspect("intent://accounts.google.com#Intent;end", chatgpt))
        assertEquals(NavigationDecision.BLOCK_SCHEME, WebNavigationPolicy.inspect("market://details?id=com.google.android.gms", grok))
        assertFalse(WebNavigationPolicy.redact("https://accounts.google.com/o/oauth2/auth?code=SECRET").contains("SECRET"))
        assertEquals("https://accounts.google.com/o/oauth2/auth", WebNavigationPolicy.redact("https://accounts.google.com/o/oauth2/auth?code=SECRET"))
    }

    /**
     * Grok hung forever on "Completing sign-in, verifying your device to keep your
     * account secure". The challenge that sign-in waits on runs in a subframe on a
     * host no provider allowlist names, and every such frame was refused.
     */
    @Test fun verificationSubframesLoadWhileMainFrameStaysOnTheProvider() {
        val grok = WebNavigationPolicy.withOAuth(setOf("grok.com", "accounts.x.ai", "x.ai"))
        listOf(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://client-api.arkoselabs.com/fc/assets/",
            "https://newassets.hcaptcha.com/captcha/v1/frame",
        ).forEach {
            assertTrue("$it must load in a subframe", !WebNavigationPolicy.blocks(it, grok, mainFrame = false))
            assertTrue("$it must not take over the main frame", WebNavigationPolicy.blocks(it, grok, mainFrame = true))
        }
        assertFalse(WebNavigationPolicy.blocks("https://grok.com/?_s=usage", grok, mainFrame = true))
        assertFalse(WebNavigationPolicy.blocks("https://accounts.x.ai/sign-in", grok, mainFrame = true))
    }

    @Test fun nonHttpsIsRefusedInEveryFrame() {
        val grok = WebNavigationPolicy.withOAuth(setOf("grok.com"))
        listOf("intent://grok.com#Intent;end", "market://details?id=x", "http://grok.com", "javascript:alert(1)").forEach {
            assertTrue(it, WebNavigationPolicy.blocks(it, grok, mainFrame = false))
            assertTrue(it, WebNavigationPolicy.blocks(it, grok, mainFrame = true))
        }
    }

    @Test fun googleSignInIsRecognisedSoTheUserIsWarnedBeforeTheDeadEnd() {
        listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x",
            "https://accounts.google.co.kr/signin/v2/identifier",
            "https://www.accounts.google.com/signin",
        ).forEach { assertTrue(it, WebNavigationPolicy.isGoogleSignIn(it)) }
        listOf(
            "https://chatgpt.com/auth/login",
            "https://grok.com/sign-in",
            "https://www.google.com/search?q=a",
            "not a url",
        ).forEach { assertFalse(it, WebNavigationPolicy.isGoogleSignIn(it)) }
    }
}
