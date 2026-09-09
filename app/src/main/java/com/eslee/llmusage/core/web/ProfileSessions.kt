package com.eslee.llmusage.core.web

import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/** Never uses the default cookie jar, including on older WebView implementations. */
object ProfileSessions {
    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun name(providerId: String, accountId: String): String {
        require(providerId.matches(Regex("[a-z0-9_]+")))
        UUID.fromString(accountId)
        return "llmusage_${providerId}_$accountId"
    }

    @UiThread
    fun create(profileName: String) {
        requireManaged(profileName)
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        ProfileStore.getInstance().getOrCreateProfile(profileName)
    }

    @UiThread
    fun bind(webView: WebView, profileName: String) {
        requireManaged(profileName)
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        WebViewCompat.setProfile(webView, profileName)
    }

    @UiThread
    fun delete(profileName: String): Boolean {
        requireManaged(profileName)
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        return ProfileStore.getInstance().deleteProfile(profileName)
    }

    @UiThread
    fun deleteAll(): Boolean {
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        val store = ProfileStore.getInstance()
        return store.allProfileNames.filter { it.startsWith("llmusage_") }
            .map { store.deleteProfile(it) }.all { it }
    }

    private fun requireManaged(name: String) {
        require(name.matches(Regex("llmusage_[a-z0-9_]+_[0-9a-fA-F-]{36}")))
    }
}
