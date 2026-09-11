package com.eslee.llmusage.core.web

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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.provider.ProviderDefinition
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import org.json.JSONObject
import org.json.JSONTokener

class ProviderWebActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var verified = false
    private var closing = false

    private var autoReadToken = 0
    private var reading = false
    private val popups = mutableListOf<WebView>()
    private var monitorToken = 0
    private var attemptedUsageNavigation = false
    private var statusLine: TextView? = null
    private var resumeMonitoring: (() -> Unit)? = null
    private var updateAuthAction: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeContent()
        super.onCreate(savedInstanceState)
        val accountId = intent.getStringExtra("accountId") ?: return finish()
        val newAccount = intent.getBooleanExtra("newAccount", false)

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
            statusLine = host
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
            // Everything except the status line collapses, so the provider's own
            // sign-in form is not pushed off the screen on a phone.
            val details = LinearLayout(this@ProviderWebActivity).apply { orientation = LinearLayout.VERTICAL }
            val handle = TextView(this@ProviderWebActivity).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, 12, 0, 4)
                isClickable = true
            }
            fun setExpanded(expanded: Boolean) {
                details.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
                handle.setText(if (expanded) R.string.web_toolbar_collapse else R.string.web_toolbar_expand)
            }
            handle.setOnTouchListener(object : android.view.View.OnTouchListener {
                private val slop = android.view.ViewConfiguration.get(this@ProviderWebActivity).scaledTouchSlop
                private var startY = 0f
                private var dragged = false
                override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { startY = event.rawY; dragged = false }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val delta = event.rawY - startY
                            if (!dragged && kotlin.math.abs(delta) > slop) { dragged = true; setExpanded(delta > 0) }
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (!dragged) setExpanded(details.visibility != android.view.View.VISIBLE)
                            v.performClick()
                        }
                        else -> return false
                    }
                    return true
                }
            })
            details.addView(heading)
            details.addView(TextView(this@ProviderWebActivity).apply { setText(R.string.web_email_login_hint) })
            details.addView(actions)
            toolbar.addView(host)
            toolbar.addView(details)

            val webHost = FrameLayout(this@ProviderWebActivity).apply {
                clipChildren = true
                clipToPadding = true
            }
            val web = WebView(this@ProviderWebActivity)
            ProfileSessions.bind(web, profile)
            browser = web
            secureSettings(web)
            resumeMonitoring = { monitorSession(web, accountId, graph, provider, host) }
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    autoReadToken++
                    WebTrace.url(if (view === web) "start" else "start(popup)", url)
                    host.text = Uri.parse(url).host.orEmpty()
                    if (WebNavigationPolicy.isGoogleSignIn(url)) host.setText(R.string.web_google_signin_warning)
                    if (WebNavigationPolicy.inspect(url, provider.allowedHosts) != NavigationDecision.ALLOW) view.stopLoading()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return blockIfDisallowed(request.url.toString(), provider.allowedHosts, request.isForMainFrame)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    WebTrace.url("neterr", request.url.toString(), "code=${error.errorCode} main=${request.isForMainFrame}")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    WebTrace.url("http", request.url.toString(), "status=${response.statusCode} main=${request.isForMainFrame}")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    host.text = Uri.parse(url).host.orEmpty()
                    if (view === web) {
                        WebTrace.url("finish", url)
                        monitorSession(web, accountId, graph, provider, host)
                        if (UsageSurface.isUsagePage(url, provider.usageUrl)) scheduleUsageRead(web, accountId, graph, provider)
                    } else view.evaluateJavascript(AUTH_STATE_JS) { encoded ->
                        if (closing || view !in popups || view.url != url) return@evaluateJavascript
                        val payload = runCatching { JSONObject(JSONTokener(encoded).nextValue() as String) }.getOrNull()
                        val text = payload?.optString("text").orEmpty()
                        val state = PageAuthStateDetector.detect(url, text, payload?.optBoolean("hasPassword") == true, payload?.optBoolean("hasComposer") == true)
                        val kind = UsageSurface.classify(url, text)
                        WebTrace.url("finish(popup)", url, "kind=$kind state=$state")
                        when (kind) {
                            AuthPageKind.GOOGLE_WEBVIEW_BLOCK -> host.setText(R.string.web_google_webview_block)
                            AuthPageKind.DEVICE_VERIFICATION -> host.setText(R.string.web_device_verification)
                            else -> if (state == PageAuthState.SIGNED_IN && WebNavigationPolicy.allows(url, provider.allowedHosts)) {
                                verified = true
                                ProfileSessions.flush(view)
                                web.webChromeClient?.onCloseWindow(view)
                                provider.usageUrl?.let(web::loadUrl)
                            }
                        }
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    WebTrace.record("sslerr", "primary=${error.primaryError}")
                    handler.cancel()
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    // Sign-in and device verification open their next window after a
                    // redirect rather than under the tap, so refusing windows without a
                    // gesture left those flows waiting on a window that never appeared.
                    WebTrace.record("popup-open", "gesture=$isUserGesture dialog=$isDialog")
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
                    updateAuthAction()
                    return true
                }
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress == 100 || newProgress % 25 == 0) {
                        WebTrace.record(if (view === web) "progress" else "progress(popup)", "$newProgress%")
                    }
                }
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        WebTrace.record("console", message.message())
                    }
                    return true
                }
                override fun onCloseWindow(window: WebView) {
                    if (popups.remove(window)) {
                        WebTrace.record("popup-close")
                        (window.parent as? ViewGroup)?.removeView(window)
                        window.destroy()
                        updateAuthAction()
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
                readUsage(web, accountId, graph, provider, toast = true) { saved ->
                    if (!saved && !UsageSurface.isUsagePage(web.url.orEmpty(), provider.usageUrl) && !attemptedUsageNavigation) {
                        attemptedUsageNavigation = true
                        provider.usageUrl?.let(web::loadUrl)
                    }
                }
            }
            details.addView(read)
            // The old button silently did nothing whenever the stall was in the main
            // window rather than an OAuth popup, which is most of the time. Make it
            // say which escape it offers and always offer one.
            val authAction = Button(this@ProviderWebActivity)
            updateAuthAction = {
                authAction.setText(if (popups.isEmpty()) R.string.web_restart_login else R.string.web_close_auth_window)
            }
            authAction.setOnClickListener {
                val popup = popups.lastOrNull()
                if (popup != null) web.webChromeClient?.onCloseWindow(popup)
                else {
                    WebTrace.record("restart-login")
                    attemptedUsageNavigation = false
                    provider.loginUrl?.takeIf { WebNavigationPolicy.allows(it, provider.allowedHosts) }?.let(web::loadUrl)
                        ?: web.reload()
                }
            }
            updateAuthAction()
            details.addView(authAction)
            toolbar.addView(handle)
            setExpanded(true)
            webHost.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.applySafeDrawingInsets()
            setContentView(root)
            toolbar.bringToFront()
            web.loadUrl(start)
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    while (true) {
                        delay(5 * 60_000L)
                        if (verified && popups.isEmpty() && com.eslee.llmusage.sync.SyncScheduler.allowsForeground(this@ProviderWebActivity, graph.settings.current())) web.reload()
                    }
                }
            }
        }
    }

    private fun blockIfDisallowed(url: String, hosts: Set<String>, mainFrame: Boolean): Boolean {
        val decision = WebNavigationPolicy.inspect(url, hosts)
        Log.i(NAV_LOG, "nav ${WebNavigationPolicy.redact(url)} decision=$decision main=$mainFrame")
        val blocked = WebNavigationPolicy.blocks(url, hosts, mainFrame)
        if (blocked && mainFrame) {
            Toast.makeText(
                this,
                if (decision == NavigationDecision.BLOCK_SCHEME) R.string.web_use_email_login else R.string.web_blocked,
                Toast.LENGTH_LONG,
            ).show()
        }
        return blocked
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
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
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

        fun poll() {
            if (closing || token != monitorToken || browser !== web || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
            val url = web.url.orEmpty()
            if (!WebNavigationPolicy.allows(url, provider.allowedHosts)) return
            web.evaluateJavascript(AUTH_STATE_JS) { encoded ->
                if (closing || token != monitorToken) return@evaluateJavascript
                if (web.url != url) { web.postDelayed({ poll() }, 500L); return@evaluateJavascript }
                val payload = runCatching { JSONObject(JSONTokener(encoded).nextValue() as String) }.getOrNull()
                val text = payload?.optString("text").orEmpty()
                val kind = UsageSurface.classify(url, text)
                val state = PageAuthStateDetector.detect(url, text, payload?.optBoolean("hasPassword") == true, payload?.optBoolean("hasComposer") == true)
                if (kind == AuthPageKind.GOOGLE_WEBVIEW_BLOCK || kind == AuthPageKind.DEVICE_VERIFICATION) {
                    status.setText(if (kind == AuthPageKind.GOOGLE_WEBVIEW_BLOCK) R.string.web_google_webview_block else R.string.web_device_verification)
                } else if (popups.isEmpty() && UsageSurface.isProviderPage(url, provider.usageUrl)) {
                    if (state == PageAuthState.SIGNED_IN && !verified) { verified = true; ProfileSessions.flush(web) }
                    if (text != previous && text.isNotBlank()) {
                        previous = text
                        readUsage(web, accountId, graph, provider, toast = false) { saved ->
                            if (!saved && state == PageAuthState.SIGNED_IN && !attemptedUsageNavigation &&
                                !UsageSurface.isUsagePage(url, provider.usageUrl)) {
                                attemptedUsageNavigation = true
                                provider.usageUrl?.let(web::loadUrl)
                            }
                        }
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
        if (reading || closing) { done(false); return }
        reading = true
        if (toast) statusLine?.setText(R.string.web_reading)
        lifecycleScope.launch {
            var saved = false
            try {
                val page = WebUsageReader.capture(web)
                if (page != null && UsageSurface.canCollect(page.url, provider.usageUrl, page.text, page.hasPassword)) {
                    val result = graph.repository.recordWeb(accountId, page.text)
                    saved = result is ProviderResult.Success
                    if (saved) {
                        verified = true
                        ProfileSessions.flush(web)
                        statusLine?.text = getString(R.string.web_saved_at, java.text.DateFormat.getTimeInstance().format(java.util.Date()))
                    } else if (toast) statusLine?.setText(R.string.web_read_failed)
                } else if (toast) statusLine?.setText(R.string.web_login_hint)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusLine?.setText(R.string.web_read_failed)
            } finally {
                reading = false
            }
            if (toast) Toast.makeText(this@ProviderWebActivity, if (saved) R.string.web_read_complete else R.string.web_read_failed, Toast.LENGTH_LONG).show()
            done(saved)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("verified", verified)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (closing) return
        closing = true
        super.finish()
    }
    private fun secureSettings(web: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        WebUsageReader.configure(web)
    }
    private fun destroyBrowser() {
        browser?.let(ProfileSessions::flush)
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
        resumeMonitoring = null
        destroyBrowser()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        resumeMonitoring?.invoke()
    }

    override fun onStop() {
        browser?.let(ProfileSessions::flush)
        monitorToken++
        autoReadToken++
        super.onStop()
    }

    private companion object {
        const val NAV_LOG = "LlmUsageWeb"
        const val AUTH_STATE_JS =
            "(function(){var t=document.body?document.body.innerText.slice(0,8000):'';return JSON.stringify({url:location.href,text:t,hasPassword:!!document.querySelector('input[type=password]'),hasComposer:!!document.querySelector('textarea,[contenteditable=\"true\"]')});})()"

    }
}
