package com.eslee.llmusage.core.web

import com.eslee.llmusage.provider.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAuthStateTest {
    @Test fun loginPagesAreSignedOut() {
        assertEquals(PageAuthState.SIGNED_OUT, PageAuthStateDetector.detect("https://chatgpt.com/auth/login", "Log in to start chatting", false))
        assertEquals(PageAuthState.SIGNED_OUT, PageAuthStateDetector.detect("https://auth.openai.com/log-in", "Continue", false))
        assertEquals(PageAuthState.SIGNED_OUT, PageAuthStateDetector.detect("https://accounts.x.ai/sign-in", "Sign in", false))
        assertEquals(PageAuthState.SIGNED_OUT, PageAuthStateDetector.detect("https://grok.com/sign-in", "Sign in to Grok", false))
        assertEquals(PageAuthState.SIGNED_OUT, PageAuthStateDetector.detect("https://chatgpt.com/", "Welcome", true))
    }

    @Test fun logoutCopyAndComposerMarkSignedIn() {
        assertEquals(PageAuthState.SIGNED_IN, PageAuthStateDetector.detect("https://chatgpt.com/", "Log out", false))
        assertEquals(PageAuthState.SIGNED_IN, PageAuthStateDetector.detect("https://chatgpt.com/", "Ask anything", false, hasComposer = true))
        assertEquals(PageAuthState.SIGNED_IN, PageAuthStateDetector.detect("https://grok.com/", "로그아웃", false))
    }

    @Test fun consumerAddStartsAtOfficialLogin() {
        val registry = ProviderRegistry(false)
        val chatgpt = registry.definition("chatgpt")!!
        val grok = registry.definition("grok")!!
        assertEquals("https://chatgpt.com/auth/login", startUrl(chatgpt.loginUrl, chatgpt.usageUrl, true))
        assertEquals("https://grok.com/sign-in", startUrl(grok.loginUrl, grok.usageUrl, true))
        assertEquals("https://chatgpt.com/codex/settings/usage", startUrl(chatgpt.loginUrl, chatgpt.usageUrl, false))
        assertEquals("https://grok.com/?_s=usage", startUrl(grok.loginUrl, grok.usageUrl, false))
        assertTrue(UsageSurface.isUsagePage("https://chatgpt.com/codex/settings/usage", chatgpt.usageUrl))
        assertTrue(UsageSurface.isUsagePage("https://grok.com/?_s=usage", grok.usageUrl))
        assertEquals("https://chatgpt.com/codex/settings/usage", chatgpt.usageUrl)
        assertEquals("https://grok.com/?_s=usage", grok.usageUrl)
        assertEquals(PageAuthState.SIGNED_IN, PageAuthStateDetector.detect("https://chatgpt.com/codex/settings/usage", "5-hour limit", false))
        assertEquals(PageAuthState.SIGNED_IN, PageAuthStateDetector.detect("https://grok.com/?_s=usage", "Weekly usage", false))
        assertEquals(AuthPageKind.GOOGLE_WEBVIEW_BLOCK, UsageSurface.classify("https://accounts.google.com/signin", "This browser or app may not be secure"))
        assertEquals(AuthPageKind.DEVICE_VERIFICATION, UsageSurface.classify("https://grok.com/", "Completing sign-in. Verifying your device to keep your account secure."))
        assertTrue(WebNavigationPolicy.allows(chatgpt.loginUrl!!, chatgpt.allowedHosts))
        assertTrue(WebNavigationPolicy.allows(grok.loginUrl!!, grok.allowedHosts))
        assertTrue(WebNavigationPolicy.allows("https://auth.openai.com/authorize", chatgpt.allowedHosts))
        assertTrue(WebNavigationPolicy.allows("https://accounts.x.ai/sign-in", grok.allowedHosts))
        assertTrue(WebNavigationPolicy.allows("https://accounts.google.com/o/oauth2/auth", chatgpt.allowedHosts))
        assertTrue(WebNavigationPolicy.allows("https://accounts.google.com/o/oauth2/auth", grok.allowedHosts))
    }
}
