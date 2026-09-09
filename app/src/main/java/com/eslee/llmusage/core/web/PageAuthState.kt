package com.eslee.llmusage.core.web

import java.net.URI

enum class PageAuthState { SIGNED_IN, SIGNED_OUT, UNKNOWN }

object PageAuthStateDetector {
    private val signedOutPath = Regex("/(auth|login|log-in|signin|sign-in)(/|$)", RegexOption.IGNORE_CASE)
    private val logoutCue = Regex("log out|sign out|로그아웃", RegexOption.IGNORE_CASE)
    private val loginCue = Regex("\\b(log in|sign in|로그인)\\b", RegexOption.IGNORE_CASE)
    private val authHosts = setOf("auth.openai.com", "auth0.openai.com", "accounts.x.ai")

    fun detect(
        url: String,
        visibleText: String,
        hasPasswordField: Boolean,
        hasComposer: Boolean = false,
    ): PageAuthState {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val path = uri?.path.orEmpty()
        if (hasPasswordField) return PageAuthState.SIGNED_OUT
        if (host in authHosts || signedOutPath.containsMatchIn(path)) return PageAuthState.SIGNED_OUT
        if (logoutCue.containsMatchIn(visibleText)) return PageAuthState.SIGNED_IN
        if (loginCue.containsMatchIn(visibleText)) return PageAuthState.SIGNED_OUT
        if (hasComposer && host in setOf("chatgpt.com", "www.chatgpt.com", "chat.openai.com", "grok.com", "www.grok.com")) {
            return PageAuthState.SIGNED_IN
        }
        if (path.contains("/codex/settings/usage") || queryContainsUsage(uri)) return PageAuthState.SIGNED_IN
        return PageAuthState.UNKNOWN
    }

    private fun queryContainsUsage(uri: URI?): Boolean =
        uri?.query.orEmpty().lowercase().split('&').any { it == "_s=usage" || it.startsWith("_s=usage") }
}

fun startUrl(loginUrl: String?, usageUrl: String?, newAccount: Boolean): String? =
    if (newAccount) loginUrl ?: usageUrl else usageUrl ?: loginUrl
