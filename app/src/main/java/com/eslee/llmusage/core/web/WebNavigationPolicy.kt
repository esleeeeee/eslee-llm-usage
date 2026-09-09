package com.eslee.llmusage.core.web

import java.net.URI

object WebNavigationPolicy {
    fun allows(url: String, allowedHosts: Set<String>): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            allowedHosts.any { it.equals(uri.host, true) }
    }.getOrDefault(false)
}
