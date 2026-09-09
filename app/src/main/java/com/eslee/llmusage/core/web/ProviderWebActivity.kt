package com.eslee.llmusage.core.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.SnapshotStatus
import com.eslee.llmusage.provider.ProviderDefinition
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

class ProviderWebActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var verified = false
    private var closing = false
    private var provisionalId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeContent()
        super.onCreate(savedInstanceState)
        val accountId = intent.getStringExtra("accountId") ?: return finish()
        val newAccount = intent.getBooleanExtra("newAccount", false)
        provisionalId = accountId.takeIf { newAccount }
        verified = savedInstanceState?.getBoolean("verified") ?: false
        if (!ProfileSessions.supported()) {
            Toast.makeText(this, R.string.web_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val graph = (application as UsageApplication).graph
            val account = graph.repository.account(accountId) ?: return@launch finish()
            val provider = graph.registry.definition(account.providerId) ?: return@launch finish()
            val profile = account.profileName ?: return@launch finish()
            val start = startUrl(provider.loginUrl, provider.usageUrl, newAccount) ?: return@launch finish()
            if (!WebNavigationPolicy.allows(start, provider.allowedHosts)) return@launch finish()

            val root = LinearLayout(this@ProviderWebActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                clipChildren = true
                clipToPadding = true
                fitsSystemWindows = false
            }
            val toolbar = LinearLayout(this@ProviderWebActivity).apply {
                orientation = LinearLayout.VERTICAL
                elevation = 12f
                isClickable = true
                isFocusable = false
                setBackgroundColor(Color.WHITE)
                setPadding(16, 16, 16, 8)
            }
            val heading = TextView(this@ProviderWebActivity).apply {
                text = getString(R.string.web_session_title, provider.displayName, account.alias)
            }
            val host = TextView(this@ProviderWebActivity)
            val actions = LinearLayout(this@ProviderWebActivity)
            fun addAction(label: Int, action: () -> Unit) {
                actions.addView(
                    Button(this@ProviderWebActivity).apply {
                        setText(label)
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            toolbar.addView(heading)
            toolbar.addView(host)
            toolbar.addView(TextView(this@ProviderWebActivity).apply { setText(R.string.web_email_login_hint) })
            toolbar.addView(actions)

            val webHost = FrameLayout(this@ProviderWebActivity).apply {
                clipChildren = true
                clipToPadding = true
            }
            val web = WebView(this@ProviderWebActivity)
            ProfileSessions.bind(web, profile)
            browser = web
            secureSettings(web)
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    host.text = Uri.parse(url).host.orEmpty()
                    if (WebNavigationPolicy.inspect(url, provider.allowedHosts) != NavigationDecision.ALLOW) view.stopLoading()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return blockIfDisallowed(request.url.toString(), provider.allowedHosts, request.isForMainFrame)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    host.text = Uri.parse(url).host.orEmpty()
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    if (!isUserGesture) return false
                    val popup = WebView(view.context)
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (!blockIfDisallowed(url, provider.allowedHosts, true)) view.loadUrl(url)
                            popup.destroy()
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            web.setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(this@ProviderWebActivity, R.string.web_blocked, Toast.LENGTH_SHORT).show()
            }

            addAction(R.string.web_close) { finish() }
            addAction(R.string.web_login_check) { confirmLogin(web, provider) }
            addAction(R.string.web_usage_page) {
                val usage = provider.usageUrl ?: return@addAction
                if (WebNavigationPolicy.allows(usage, provider.allowedHosts)) web.loadUrl(usage)
            }
            val read = Button(this@ProviderWebActivity).apply { setText(R.string.web_usage_check) }
            read.setOnClickListener {
                val current = web.url.orEmpty()
                if (!WebNavigationPolicy.allows(current, provider.allowedHosts)) return@setOnClickListener
                val sourceUrl = current
                read.isEnabled = false
                web.evaluateJavascript(PAGE_TEXT_JS) { encoded ->
                    val pageText = if (web.url == sourceUrl) runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull() else null
                    lifecycleScope.launch {
                        try {
                            if (pageText.isNullOrBlank()) {
                                Toast.makeText(this@ProviderWebActivity, R.string.web_read_failed, Toast.LENGTH_LONG).show()
                            } else {
                                val before = graph.repository.latest(accountId)?.snapshotId
                                graph.repository.recordWeb(accountId, pageText)
                                val after = graph.repository.latest(accountId)
                                val parsed = after != null && after.snapshotId != before && after.status != SnapshotStatus.PARTIAL
                                if (after != null && after.snapshotId != before) verified = true
                                Toast.makeText(
                                    this@ProviderWebActivity,
                                    if (parsed) R.string.web_read_complete else R.string.web_read_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } finally { read.isEnabled = true }
                    }
                }
            }
            toolbar.addView(read)
            webHost.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.applySafeDrawingInsets()
            setContentView(root)
            toolbar.bringToFront()
            web.loadUrl(start)
        }
    }

    private fun blockIfDisallowed(url: String, hosts: Set<String>, mainFrame: Boolean): Boolean {
        val decision = WebNavigationPolicy.inspect(url, hosts)
        Log.i(NAV_LOG, "nav ${WebNavigationPolicy.redact(url)} decision=$decision")
        if (decision == NavigationDecision.ALLOW) return false
        if (mainFrame) {
            Toast.makeText(
                this,
                if (decision == NavigationDecision.BLOCK_SCHEME) R.string.web_use_email_login else R.string.web_blocked,
                Toast.LENGTH_LONG,
            ).show()
        }
        return true
    }

    private fun confirmLogin(web: WebView, provider: ProviderDefinition) {
        val current = web.url.orEmpty()
        if (!WebNavigationPolicy.allows(current, provider.allowedHosts)) {
            Toast.makeText(this, R.string.web_blocked, Toast.LENGTH_LONG).show()
            return
        }
        web.evaluateJavascript(AUTH_STATE_JS) { encoded ->
            val payload = runCatching { JSONObject(JSONTokener(encoded).nextValue() as String) }.getOrNull()
            val state = PageAuthStateDetector.detect(
                payload?.optString("url").orEmpty().ifBlank { current },
                payload?.optString("text").orEmpty(),
                payload?.optBoolean("hasPassword") == true,
                payload?.optBoolean("hasComposer") == true,
            )
            verified = state == PageAuthState.SIGNED_IN
            val message = when (state) {
                PageAuthState.SIGNED_IN -> R.string.web_login_confirmed
                PageAuthState.SIGNED_OUT -> R.string.web_login_hint
                PageAuthState.UNKNOWN -> R.string.web_login_unknown
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (state == PageAuthState.SIGNED_IN) {
                provider.usageUrl?.takeIf { WebNavigationPolicy.allows(it, provider.allowedHosts) }?.let(web::loadUrl)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("verified", verified)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (closing) return
        closing = true
        val id = provisionalId
        if (id != null && !verified) {
            destroyBrowser()
            lifecycleScope.launch {
                try {
                    (application as UsageApplication).graph.repository.deleteAccount(id)
                } catch (_: Exception) {
                    Toast.makeText(this@ProviderWebActivity, R.string.web_cleanup_failed, Toast.LENGTH_LONG).show()
                }
                super@ProviderWebActivity.finish()
            }
        } else super.finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun secureSettings(web: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
    }

    private fun destroyBrowser() {
        browser?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        browser = null
    }

    override fun onDestroy() {
        destroyBrowser()
        super.onDestroy()
    }

    private companion object {
        const val NAV_LOG = "LlmUsageWeb"
        const val AUTH_STATE_JS =
            "(function(){var t=document.body?document.body.innerText.slice(0,8000):'';return JSON.stringify({url:location.href,text:t,hasPassword:!!document.querySelector('input[type=password]'),hasComposer:!!document.querySelector('textarea,[contenteditable=\"true\"]')});})()"
        const val PAGE_TEXT_JS =
            "(function(){return document.body ? document.body.innerText.slice(0,120000) : '';})()"
    }
}
