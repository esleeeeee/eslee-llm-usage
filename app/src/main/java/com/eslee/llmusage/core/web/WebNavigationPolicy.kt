package com.eslee.llmusage.core.web

import java.net.URI

enum class NavigationDecision { ALLOW, BLOCK_HOST, BLOCK_SCHEME }

object WebNavigationPolicy {
    val oauthHosts: Set<String> = setOf(
        "accounts.google.com", "accounts.google.co.kr", "accounts.google.co.uk",
        "accounts.google.de", "accounts.google.fr", "accounts.google.co.jp",
        "www.google.com", "google.com", "oauth2.googleapis.com", "accounts.youtube.com",
        "appleid.apple.com",
        "login.microsoftonline.com", "login.live.com",
        "x.com", "www.x.com", "twitter.com", "www.twitter.com", "api.x.com", "api.twitter.com",
        "auth.x.ai",
    )

    fun withOAuth(hosts: Set<String>): Set<String> = hosts + oauthHosts

    fun inspect(url: String, allowedHosts: Set<String>): NavigationDecision = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme != "https") return NavigationDecision.BLOCK_SCHEME
        if (uri.rawUserInfo != null) return NavigationDecision.BLOCK_HOST
        if (uri.port != -1 && uri.port != 443) return NavigationDecision.BLOCK_HOST
        val host = uri.host.orEmpty()
        if (allowedHosts.any { it.equals(host, true) }) NavigationDecision.ALLOW
        else NavigationDecision.BLOCK_HOST
    }.getOrDefault(NavigationDecision.BLOCK_SCHEME)

    fun allows(url: String, allowedHosts: Set<String>): Boolean =
        inspect(url, allowedHosts) == NavigationDecision.ALLOW

    fun redact(url: String): String = runCatching {
        val uri = URI(url)
        buildString {
            append(uri.scheme ?: "unknown")
            append("://")
            append(uri.host ?: "none")
            append(uri.path.orEmpty().ifBlank { "/" })
        }
    }.getOrDefault("unparseable")
}
