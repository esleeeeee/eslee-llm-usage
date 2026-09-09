package com.eslee.llmusage.core.web

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import android.net.Uri
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.SnapshotStatus
import kotlinx.coroutines.launch
import org.json.JSONTokener

class ProviderWebActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var verified = false
    private var closing = false
    private var provisionalId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accountId = intent.getStringExtra("accountId") ?: return finish()
        provisionalId = accountId.takeIf { intent.getBooleanExtra("newAccount",false) }
        verified = savedInstanceState?.getBoolean("verified") ?: false
        if (!ProfileSessions.supported()) {
            Toast.makeText(this, R.string.web_unsupported, Toast.LENGTH_LONG).show()
            finish(); return
        }
        lifecycleScope.launch {
            val graph = (application as UsageApplication).graph
            val account = graph.repository.account(accountId) ?: return@launch finish()
            val provider = graph.registry.definition(account.providerId) ?: return@launch finish()
            val profile = account.profileName ?: return@launch finish()
            val initial = provider.usageUrl ?: provider.loginUrl ?: return@launch finish()
            if (!WebNavigationPolicy.allows(initial, provider.allowedHosts)) return@launch finish()
            val layout = LinearLayout(this@ProviderWebActivity).apply { orientation = LinearLayout.VERTICAL }
            val heading = TextView(this@ProviderWebActivity).apply { text = "${provider.displayName} · ${account.alias}"; setPadding(16,16,16,8) }
            val host = TextView(this@ProviderWebActivity).apply { setPadding(16,0,16,8) }
            val actions = LinearLayout(this@ProviderWebActivity)
            fun button(label: Int, action: () -> Unit) { actions.addView(Button(this@ProviderWebActivity).apply { setText(label); setOnClickListener { action() } }, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)) }
            layout.addView(heading); layout.addView(host); layout.addView(actions)
            val web = WebView(this@ProviderWebActivity)
            ProfileSessions.bind(web,profile)
            browser = web
            secureSettings(web)
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    host.text = Uri.parse(url).host.orEmpty()
                    if(!WebNavigationPolicy.allows(url,provider.allowedHosts)) view.stopLoading()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = WebNavigationPolicy.allows(request.url.toString(),provider.allowedHosts)
                    if (!allowed && request.isForMainFrame) Toast.makeText(this@ProviderWebActivity,R.string.web_blocked,Toast.LENGTH_LONG).show()
                    return !allowed
                }
                override fun onPageFinished(view: WebView, url: String) { host.text = Uri.parse(url).host.orEmpty() }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin,false,false) }
            }
            web.setDownloadListener { _,_,_,_,_ -> Toast.makeText(this@ProviderWebActivity,R.string.web_blocked,Toast.LENGTH_SHORT).show() }
            button(R.string.web_close) { finish() }
            button(R.string.web_login_check) {
                if (WebNavigationPolicy.allows(web.url.orEmpty(),setOfNotNull(Uri.parse(initial).host))) {
                    web.evaluateJavascript("(function(){var t=document.body?document.body.innerText:'';return !document.querySelector('input[type=password]') && /log out|sign out|로그아웃/i.test(t);})()") { result ->
                        if(result == "true") verified = true
                        Toast.makeText(this@ProviderWebActivity,if(verified) R.string.web_login_confirmed else R.string.web_login_hint,Toast.LENGTH_LONG).show()
                    }
                }
            }
            button(R.string.web_usage_page) { web.loadUrl(initial) }
            val read = Button(this@ProviderWebActivity).apply { setText(R.string.web_usage_check) }
            read.setOnClickListener {
                if (!WebNavigationPolicy.allows(web.url.orEmpty(),setOfNotNull(Uri.parse(initial).host))) return@setOnClickListener
                val sourceUrl = web.url
                read.isEnabled = false
                web.evaluateJavascript("(function(){return document.body ? document.body.innerText.slice(0,120000) : '';})()") { encoded ->
                    val pageText = if(web.url == sourceUrl) runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull() else null
                    lifecycleScope.launch {
                        try {
                            if (pageText.isNullOrBlank()) Toast.makeText(this@ProviderWebActivity,R.string.web_read_failed,Toast.LENGTH_LONG).show()
                            else {
                                val before = graph.repository.latest(accountId)?.snapshotId
                                graph.repository.recordWeb(accountId,pageText)
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
            layout.addView(read)
            layout.addView(web,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
            setContentView(layout)
            web.loadUrl(initial)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("verified",verified)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if(closing) return
        closing = true
        val id = provisionalId
        if(id != null && !verified) {
            browser?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
            browser = null
            lifecycleScope.launch {
                try {
                    (application as UsageApplication).graph.repository.deleteAccount(id)
                } catch (_: Exception) {
                    Toast.makeText(this@ProviderWebActivity,R.string.web_cleanup_failed,Toast.LENGTH_LONG).show()
                }
                super@ProviderWebActivity.finish()
            }
        } else super.finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun secureSettings(web: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(false); setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
    }
    override fun onDestroy() {
        browser?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        browser = null
        super.onDestroy()
    }
}
