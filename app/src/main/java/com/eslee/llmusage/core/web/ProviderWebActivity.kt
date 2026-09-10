package com.eslee.llmusage.core.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.SnapshotStatus
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.provider.ConsumerUsageParser
import com.eslee.llmusage.provider.ProviderDefinition
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

class ProviderWebActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var verified = false
    private var closing = false
    private var provisionalId: String? = null
    private var autoReadToken = 0
    private var reading = false
    private val popups = mutableListOf<WebView>()
    private var monitorToken = 0

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
                    autoReadToken++
                    host.text = Uri.parse(url).host.orEmpty()
                    if (WebNavigationPolicy.inspect(url, provider.allowedHosts) != NavigationDecision.ALLOW) view.stopLoading()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return blockIfDisallowed(request.url.toString(), provider.allowedHosts, request.isForMainFrame)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    host.text = Uri.parse(url).host.orEmpty()
                    if (view === web) {
                        monitorSession(web, accountId, graph, provider, host)
                        if (UsageSurface.isUsagePage(url, provider.usageUrl)) scheduleUsageRead(web, accountId, graph, provider)
                    } else view.evaluateJavascript(AUTH_STATE_JS) { encoded ->
                        if (closing || view !in popups || view.url != url) return@evaluateJavascript
                        val payload = runCatching { JSONObject(JSONTokener(encoded).nextValue() as String) }.getOrNull()
                        val text = payload?.optString("text").orEmpty()
                        val state = PageAuthStateDetector.detect(url, text, payload?.optBoolean("hasPassword") == true, payload?.optBoolean("hasComposer") == true)
                        when (UsageSurface.classify(url, text)) {
                            AuthPageKind.GOOGLE_WEBVIEW_BLOCK -> host.setText(R.string.web_google_webview_block)
                            AuthPageKind.DEVICE_VERIFICATION -> host.setText(R.string.web_device_verification)
                            else -> if (state == PageAuthState.SIGNED_IN && WebNavigationPolicy.allows(url, provider.allowedHosts)) {
                                verified = true
                                web.webChromeClient?.onCloseWindow(view)
                                provider.usageUrl?.let(web::loadUrl)
                            }
                        }
                    }
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
                    // Keep the opener and the account's cookie profile throughout OAuth.
                    ProfileSessions.bind(popup, profile)
                    secureSettings(popup)
                    popup.webViewClient = web.webViewClient
                    popup.webChromeClient = this
                    popups.add(popup)
                    webHost.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
                override fun onCloseWindow(window: WebView) {
                    if (popups.remove(window)) {
                        (window.parent as? ViewGroup)?.removeView(window)
                        window.destroy()
                        monitorSession(web, accountId, graph, provider, host)
                    }
                }
            }
            web.setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(this@ProviderWebActivity, R.string.web_blocked, Toast.LENGTH_SHORT).show()
            }

            onBackPressedDispatcher.addCallback(this@ProviderWebActivity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val popup = popups.lastOrNull()
                    if (popup != null) {
                        if (popup.canGoBack()) popup.goBack() else web.webChromeClient?.onCloseWindow(popup)
                    } else finish()
                }
            })
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
                val usage = provider.usageUrl
                if (usage != null && !UsageSurface.isUsagePage(current, usage) && WebNavigationPolicy.allows(usage, provider.allowedHosts)) {
                    web.loadUrl(usage)
                    return@setOnClickListener
                }
                readUsage(web, accountId, graph, provider, toast = true)
            }
            toolbar.addView(read)
            toolbar.addView(Button(this@ProviderWebActivity).apply {
                setText(R.string.web_close_auth_window)
                setOnClickListener {
                    popups.lastOrNull()?.let { popup -> web.webChromeClient?.onCloseWindow(popup) }
                }
            })
            val recovery = LinearLayout(this@ProviderWebActivity)
            recovery.addView(Button(this@ProviderWebActivity).apply {
                setText(R.string.web_open_browser)
                setOnClickListener {
                    val officialUrl = provider.usageUrl ?: return@setOnClickListener
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(officialUrl)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        .onFailure { Toast.makeText(this@ProviderWebActivity, R.string.web_blocked, Toast.LENGTH_LONG).show() }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            recovery.addView(Button(this@ProviderWebActivity).apply {
                setText(R.string.web_import_text)
                setOnClickListener {
                    val input = EditText(this@ProviderWebActivity).apply { minLines = 4; maxLines = 8; setHint(R.string.web_import_hint) }
                    val dialog = AlertDialog.Builder(this@ProviderWebActivity)
                        .setTitle(R.string.web_import_text).setMessage(R.string.web_import_explanation).setView(input)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.web_usage_check, null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val text = input.text.toString()
                            val result = ConsumerUsageParser.parse(account.providerId, accountId, text)
                            if (result !is ProviderResult.Success || result.snapshot.status == SnapshotStatus.PARTIAL) {
                                input.error = getString(R.string.web_read_failed)
                            } else lifecycleScope.launch {
                                graph.repository.recordWeb(accountId, text, userEntered = true)
                                verified = true
                                dialog.dismiss()
                                Toast.makeText(this@ProviderWebActivity, R.string.web_read_complete, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    dialog.show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toolbar.addView(recovery)
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
            verified = verified || state == PageAuthState.SIGNED_IN
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

    private fun scheduleUsageRead(
        web: WebView,
        accountId: String,
        graph: AppGraph,
        provider: ProviderDefinition,
        attempt: Int = 0,
    ) {
        val token = ++autoReadToken
        web.postDelayed({
            if (token != autoReadToken || closing) return@postDelayed
            readUsage(web, accountId, graph, provider, toast = attempt >= 3) { success ->
                if (!success && attempt < 4 && token == autoReadToken) {
                    scheduleUsageRead(web, accountId, graph, provider, attempt + 1)
                }
            }
        }, if (attempt == 0) 1_200L else 3_000L)
    }

    private fun monitorSession(web: WebView, accountId: String, graph: AppGraph, provider: ProviderDefinition, status: TextView) {
        val token = ++monitorToken
        var previous = ""
        var redirected = false
        fun poll() {
            if (closing || token != monitorToken || browser !== web) return
            val url = web.url.orEmpty()
            if (!WebNavigationPolicy.allows(url, provider.allowedHosts)) return
            web.evaluateJavascript(AUTH_STATE_JS) { encoded ->
                if (closing || token != monitorToken || web.url != url) return@evaluateJavascript
                val payload = runCatching { JSONObject(JSONTokener(encoded).nextValue() as String) }.getOrNull()
                val text = payload?.optString("text").orEmpty()
                val kind = UsageSurface.classify(url, text)
                val state = PageAuthStateDetector.detect(url, text, payload?.optBoolean("hasPassword") == true, payload?.optBoolean("hasComposer") == true)
                if (kind == AuthPageKind.GOOGLE_WEBVIEW_BLOCK || kind == AuthPageKind.DEVICE_VERIFICATION) {
                    status.setText(if (kind == AuthPageKind.GOOGLE_WEBVIEW_BLOCK) R.string.web_google_webview_block else R.string.web_device_verification)
                } else if (popups.isEmpty() && state == PageAuthState.SIGNED_IN) {
                    verified = true
                    if (UsageSurface.isUsagePage(url, provider.usageUrl)) {
                        if (text != previous && text.isNotBlank()) {
                            previous = text
                            readUsage(web, accountId, graph, provider, toast = false)
                        }
                    } else if (!redirected) {
                        redirected = true
                        provider.usageUrl?.let(web::loadUrl)
                    }
                }
                web.postDelayed({ poll() }, 2_000L)
            }

        }
        poll()
    }

    private fun readUsage(
        web: WebView,
        accountId: String,
        graph: AppGraph,
        provider: ProviderDefinition,
        toast: Boolean,
        done: (Boolean) -> Unit = {},
    ) {
        val current = web.url.orEmpty()
        if (!WebNavigationPolicy.allows(current, provider.allowedHosts) || !UsageSurface.isUsagePage(current, provider.usageUrl)) {
            done(false)
            return
        }
        if (reading) {
            done(false)
            return
        }
        reading = true
        val sourceUrl = current
        web.evaluateJavascript(PAGE_TEXT_JS) { encoded ->
            val pageText = if (web.url == sourceUrl) runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull() else null
            lifecycleScope.launch {
                try {
                    if (pageText.isNullOrBlank()) {
                        if (toast) Toast.makeText(this@ProviderWebActivity, R.string.web_read_failed, Toast.LENGTH_LONG).show()
                        done(false)
                    } else {
                        val before = graph.repository.latest(accountId)?.snapshotId
                        graph.repository.recordWeb(accountId, pageText)
                        val after = graph.repository.latest(accountId)
                        val stored = after != null && after.snapshotId != before
                        if (stored) verified = true
                        val parsed = stored && after.status != SnapshotStatus.PARTIAL
                        if (toast) {
                            Toast.makeText(
                                this@ProviderWebActivity,
                                if (parsed) R.string.web_read_complete else R.string.web_read_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        done(parsed)
                    }
                } finally { reading = false }
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
        WebViewCompat.getProfile(web).cookieManager.setAcceptCookie(true)
        WebViewCompat.getProfile(web).cookieManager.setAcceptThirdPartyCookies(web, true)
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
        monitorToken++
        autoReadToken++
        popups.toList().forEach {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        popups.clear()
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
        val PAGE_TEXT_JS = """
            (function(){
              if(!document.body)return '';
              var text=document.body.innerText;
              document.querySelectorAll('[role="progressbar"],progress,meter').forEach(function(e){
                if(!e.getClientRects().length)return;
                var label=e.getAttribute('aria-label')||'';
                var value=e.getAttribute('aria-valuetext')||'';
                if(label&&value)text+='\n'+label+'\n'+value;
              });
              return text.slice(0,120000);
            })()
        """.trimIndent()
    }
}
