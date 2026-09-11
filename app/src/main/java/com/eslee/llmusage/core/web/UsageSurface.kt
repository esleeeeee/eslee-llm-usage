package com.eslee.llmusage.core.web

import java.net.URI

enum class AuthPageKind { USAGE, LOGIN, GOOGLE_WEBVIEW_BLOCK, DEVICE_VERIFICATION, OTHER }

/** Classifies provider pages without reading cookies, tokens, or credentials. */
object UsageSurface {
    fun canCollect(url: String, usageUrl: String?, text: String, hasPassword: Boolean): Boolean =
        !hasPassword && isProviderPage(url, usageUrl) && classify(url, text) !in
            setOf(AuthPageKind.LOGIN, AuthPageKind.GOOGLE_WEBVIEW_BLOCK, AuthPageKind.DEVICE_VERIFICATION)

    fun isProviderPage(url: String, usageUrl: String?): Boolean {
        val current = runCatching { URI(url) }.getOrNull() ?: return false
        val expected = usageUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return current.scheme.equals("https", true) && current.userInfo == null && current.port in listOf(-1, 443) &&
            current.host?.lowercase()?.removePrefix("www.") == expected.host?.lowercase()?.removePrefix("www.")
    }
    fun isUsagePage(url: String, usageUrl: String?): Boolean {
        val current = runCatching { URI(url) }.getOrNull() ?: return false
        val expected = usageUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        if (!current.scheme.equals("https", true) || current.userInfo != null || current.port !in listOf(-1, 443)) return false
        if (!current.host.equals(expected.host, true) &&
            current.host?.removePrefix("www.") != expected.host?.removePrefix("www.")
        ) return false
        val path = current.path.orEmpty().lowercase()
        val query = current.query.orEmpty().lowercase()
        val expectedPath = expected.path.orEmpty().lowercase()
        val expectedQuery = expected.query.orEmpty().lowercase()
        if (expectedQuery.split('&').contains("_s=usage")) return query.split('&').contains("_s=usage")
        if (expectedPath.endsWith("/settings/usage")) return path.trimEnd('/') == expectedPath.trimEnd('/')
        return path == expectedPath || path.startsWith(expectedPath.trimEnd('/') + "/")
    }

    /**
     * An OAuth flow ends on a page whose only job is to hand control back to the
     * window that opened it: post a message to the opener and close itself. Reached
     * in the main frame there is no opener to tell and no window to close, so the
     * page has nothing left to do and sits on its "completing sign-in" text forever.
     */
    fun isAuthCompletionPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true)) return false
        val path = uri.path.orEmpty().lowercase().trimEnd('/')
        return path.endsWith("/oauth-complete") || path.endsWith("/oauth/complete") ||
            path.endsWith("/auth/complete") || path.endsWith("/oauth-callback") ||
            path.endsWith("/oauth/callback") || path.endsWith("/auth/callback")
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
