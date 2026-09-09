package com.eslee.llmusage.core.web

import java.net.URI

enum class AuthPageKind { USAGE, LOGIN, GOOGLE_WEBVIEW_BLOCK, DEVICE_VERIFICATION, OTHER }

/** Classifies provider pages without reading cookies, tokens, or credentials. */
object UsageSurface {
    fun isUsagePage(url: String, usageUrl: String?): Boolean {
        val current = runCatching { URI(url) }.getOrNull() ?: return false
        val expected = usageUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        if (!current.host.equals(expected.host, true) &&
            current.host?.removePrefix("www.") != expected.host?.removePrefix("www.")
        ) return false
        val path = current.path.orEmpty().lowercase()
        val query = current.query.orEmpty().lowercase()
        val expectedPath = expected.path.orEmpty().lowercase()
        val expectedQuery = expected.query.orEmpty().lowercase()
        if (expectedQuery.contains("_s=usage")) return query.contains("_s=usage")
        if (expectedPath.contains("/codex/settings/usage")) return path.contains("/codex/settings/usage")
        if (expectedPath.contains("/settings/usage")) return path.contains("/settings/usage")
        return path == expectedPath || path.startsWith(expectedPath.trimEnd('/') + "/")
    }

    fun classify(url: String, visibleText: String): AuthPageKind {
        val text = visibleText.lowercase()
        if (Regex("disallowed_useragent|this browser or app may not be secure|couldn't sign you in|could not sign you in")
                .containsMatchIn(text)
        ) return AuthPageKind.GOOGLE_WEBVIEW_BLOCK
        if (Regex("verifying your device|completing sign-in|keep your account secure")
                .containsMatchIn(text)
        ) return AuthPageKind.DEVICE_VERIFICATION
        if (PageAuthStateDetector.detect(url, visibleText, false) == PageAuthState.SIGNED_OUT) {
            return AuthPageKind.LOGIN
        }
        return AuthPageKind.OTHER
    }
}
