package com.eslee.llmusage.core.web

import android.webkit.WebView
import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebStorageCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.UUID
import java.util.WeakHashMap

/** Never uses the default cookie jar, including on older WebView implementations. */
object ProfileSessions {
    private lateinit var deletionPreferences: SharedPreferences
    private val boundViews = WeakHashMap<WebView, String>()
    private fun pending(): Set<String> = deletionPreferences.getStringSet("pending", emptySet())!!.toSet()
    private fun savePending(names: Set<String>) {
        check(deletionPreferences.edit().putStringSet("pending", names).commit()) { "PROFILE_DELETION_NOT_SAVED" }
    }

    /** Run before any WebView loads a profile: loaded profiles cannot be removed. */
    @UiThread
    fun initialize(context: Context) {
        deletionPreferences = context.getSharedPreferences("profile_deletions", Context.MODE_PRIVATE)
        if (!supported()) return
        val store = ProfileStore.getInstance()
        for (name in pending()) {
            requireManaged(name)
            try {
                store.deleteProfile(name)
                savePending(pending() - name)
            } catch (_: IllegalStateException) {
                // Keep the durable tombstone; never reopen a profile awaiting deletion.
            }
        }
    }

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun name(providerId: String, accountId: String): String {
        require(providerId.matches(Regex("[a-z0-9_]+")))
        UUID.fromString(accountId)
        return "llmusage_${providerId}_$accountId"
    }

    @UiThread
    fun create(profileName: String) {
        requireManaged(profileName)
        check(profileName !in pending()) { "PROFILE_DELETED" }
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        ProfileStore.getInstance().getOrCreateProfile(profileName)
    }

    @UiThread
    fun bind(webView: WebView, profileName: String) {
        requireManaged(profileName)
        check(profileName !in pending()) { "PROFILE_DELETED" }
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        WebViewCompat.setProfile(webView, profileName)
        boundViews[webView] = profileName
    }

    /**
     * WebView keeps cookies in memory and writes them out on its own schedule, so a
     * sign-in that is followed by the WebView being destroyed can be lost and the
     * account asked to authenticate from scratch. Flushing at the points where a
     * session has just changed makes the profile survive the process.
     */
    @UiThread
    fun flush(webView: WebView) {
        if (!supported()) return
        runCatching { WebViewCompat.getProfile(webView).cookieManager.flush() }
    }

    /**
     * Retires the profile immediately and clears its cookies. Older WebViews queue
     * JavaScript storage removal without a completion callback; the tombstone
     * prevents reuse while this runs. Directories are removed next process start.
     */
    suspend fun delete(profileName: String): Boolean = withContext(Dispatchers.Main + NonCancellable) {
        requireManaged(profileName)
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        savePending(pending() + profileName)
        boundViews.entries.filter { it.value == profileName }.map { it.key }.forEach { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
            boundViews.remove(web)
        }
        val store = ProfileStore.getInstance()
        try {
            val deleted = store.deleteProfile(profileName)
            // Keep this process's name retired even when physical deletion succeeded.
            deleted
        } catch (loaded: IllegalStateException) {
            val profile = store.getProfile(profileName) ?: throw loaded
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                suspendCancellableCoroutine { continuation ->
                    WebStorageCompat.deleteBrowsingData(profile.webStorage) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            } else {
                // Use this profile's managers, never the default/global instances.
                // Unlike deleteBrowsingData, this older API has no done callback.
                profile.webStorage.deleteAllData()
                suspendCancellableCoroutine { continuation ->
                    profile.cookieManager.removeAllCookies {
                        // The Boolean says whether cookies existed, not success/failure.
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            profile.cookieManager.flush()
            true
        }
    }

    suspend fun deleteAll(): Boolean = withContext(Dispatchers.Main) {
        check(supported()) { "MULTI_PROFILE_UNSUPPORTED" }
        val store = ProfileStore.getInstance()
        store.allProfileNames.filter { it.startsWith("llmusage_") }
            .map { delete(it) }.all { it }
    }

    private fun requireManaged(name: String) {
        require(name.matches(Regex("llmusage_[a-z0-9_]+_[0-9a-fA-F-]{36}")))
    }
}
