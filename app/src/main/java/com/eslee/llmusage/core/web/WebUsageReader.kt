package com.eslee.llmusage.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.core.model.Account
import com.eslee.llmusage.core.model.SnapshotStatus
import com.eslee.llmusage.core.model.SyncMode
import com.eslee.llmusage.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Loads the official page with this account's existing session; never imports credentials. */
class WebUsageReader(
    private val context: Context,
    private val loadPage: (WebView, String) -> Unit = { web, url -> web.loadUrl(url) },
) {
    private val browserLock = Mutex()

    suspend fun fetch(account: Account, provider: ProviderDefinition): ProviderResult = browserLock.withLock {
        withContext(Dispatchers.Main) {
            if (!ProfileSessions.supported() || account.profileName == null || provider.usageUrl == null) {
                return@withContext ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED, "WEBVIEW_UNSUPPORTED")
            }
            val web = WebView(context)
            try {
                ProfileSessions.bind(web, account.profileName)
                configure(web)
                web.layout(0, 0, 1080, 1920)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !WebNavigationPolicy.allows(request.url.toString(), provider.allowedHosts)
                    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
                }
                loadPage(web, provider.usageUrl)
                var last: ProviderResult = ProviderResult.Failure(ProviderErrorCode.NETWORK_TIMEOUT, "사용량 페이지 응답 시간 초과")
                withTimeoutOrNull(40_000L) {
                    while (true) {
                        delay(1_000L)
                        val page = capture(web)
                        if (page != null && (page.hasPassword || UsageSurface.classify(page.url, page.text) in
                                setOf(AuthPageKind.LOGIN, AuthPageKind.GOOGLE_WEBVIEW_BLOCK, AuthPageKind.DEVICE_VERIFICATION))) {
                            last = ProviderResult.Failure(ProviderErrorCode.AUTH_REQUIRED, "계정 로그인 확인이 필요합니다.")
                            continue
                        }
                        if (page != null && UsageSurface.canCollect(page.url, provider.usageUrl, page.text, page.hasPassword)) {
                            val result = ConsumerUsageParser.parse(account.providerId, account.id, page.text)
                            if (result is ProviderResult.Success) {
                                last = ProviderResult.Success(result.snapshot.copy(syncMode = SyncMode.BACKGROUND))
                                if (result.snapshot.status == SnapshotStatus.SUCCESS) break
                            } else if (last !is ProviderResult.Success) last = result
                        }
                    }
                }
                last
            } finally {
                // Providers rotate session cookies on each load; an unflushed rotation
                // would silently expire the account and force a fresh sign-in.
                ProfileSessions.flush(web)
                web.stopLoading()
                web.destroy()
            }
        }
    }

    companion object {
        data class Page(val url: String, val text: String, val hasPassword: Boolean)

        suspend fun capture(web: WebView): Page? = withContext(Dispatchers.Main) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    web.evaluateJavascript(CAPTURE_JS) { encoded ->
                        val page = runCatching {
                            val json = JSONObject(JSONTokener(encoded).nextValue() as String)
                            Page(json.getString("url"), json.getString("text"), json.optBoolean("hasPassword"))
                        }.getOrNull()
                        if (continuation.isActive) continuation.resume(page)
                    }
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun configure(web: WebView) {
            check(ProfileSessions.supported())
            WebViewCompat.getProfile(web).cookieManager.apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(web, true)
            }
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                setGeolocationEnabled(false)
                setSupportMultipleWindows(true)
                // OAuth and device verification open their window after a redirect.
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = true
            }
        }

        val CAPTURE_JS = """
            (function(){
              var text=document.body?document.body.innerText:'';
              document.querySelectorAll('[role="progressbar"],progress,meter').forEach(function(e){
                if(!e.getClientRects().length)return;
                var label=e.getAttribute('aria-label')||'';
                var value=e.getAttribute('aria-valuetext')||'';
                if(label&&value)text+='\n'+label+'\n'+value;
              });
              return JSON.stringify({url:location.href,text:text.slice(0,120000),hasPassword:!!document.querySelector('input[type=password]')});
            })()
        """.trimIndent()
    }
}
