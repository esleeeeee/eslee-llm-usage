package com.eslee.llmusage.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.core.model.Account
import com.eslee.llmusage.core.model.SyncMode
import com.eslee.llmusage.core.model.UsageSnapshot
import com.eslee.llmusage.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Loads the official page with this account's existing session; never imports credentials. */
class WebUsageReader(
    private val context: Context,
    private val loadPage: (WebView, String) -> Unit = { web, url -> web.loadUrl(url) },
) {
    // A few pages at once: accounts no longer queue behind each other's full load,
    // and the one renderer every WebView in the app shares is not flooded.
    private val browsers = Semaphore(PARALLEL_PAGES)

    suspend fun fetch(account: Account, provider: ProviderDefinition): ProviderResult = browsers.withPermit {
        withContext(Dispatchers.Main) { read(account, provider) }
    }

    private suspend fun read(account: Account, provider: ProviderDefinition): ProviderResult {
        val usageUrl = provider.usageUrl
        val profile = account.profileName
        if (!ProfileSessions.supported() || profile == null || usageUrl == null) {
            return ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED, "WEBVIEW_UNSUPPORTED")
        }
        val started = SystemClock.elapsedRealtime()
        // Set on WebView's network thread for every request the page starts.
        val lastRequest = AtomicLong(started)
        // Requests started after the first figures showed: a page refreshing what it cached asks again.
        val figuresShownAt = AtomicLong(0)
        val laterRequests = AtomicInteger(0)
        var mainFrameError: String? = null
        val web = WebView(context)
        val frames = FramePump(web)
        try {
            ProfileSessions.bind(web, profile)
            configure(web)
            // Figures are text. Images only cost load time on a page nobody sees.
            web.settings.blockNetworkImage = true
            web.layout(0, 0, 1080, 2400)
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !WebNavigationPolicy.allows(request.url.toString(), provider.allowedHosts)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    lastRequest.set(SystemClock.elapsedRealtime())
                    if (figuresShownAt.get() != 0L) laterRequests.incrementAndGet()
                    return null
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) mainFrameError = "net ${error.errorCode}"
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 500) mainFrameError = "http ${response.statusCode}"
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
            }
            loadPage(web, usageUrl)

            var best: ProviderResult.Success? = null
            var first: ProviderResult.Success? = null
            var partial: ProviderResult? = null
            var signature: String? = null
            var stableSince = 0L
            var firstFigures = 0L
            var signInSince = 0L
            var verifying = false
            var reloaded = false
            var rich = false
            var scrolledAt = 0L
            var lastPage: Page? = null
            val listsResets = provider.id in BANKED_RESETS
            val deadline = started + TIMEOUT
            while (SystemClock.elapsedRealtime() < deadline) {
                frames.advance(POLL)
                val now = SystemClock.elapsedRealtime()
                val error = mainFrameError
                if (error != null && best == null) {
                    mainFrameError = null
                    // One reload rides out a dropped connection; a second failure is the answer.
                    if (!reloaded) { reloaded = true; WebTrace.record("bg-reload", "${provider.id} $error"); web.reload(); continue }
                    return finish(account, provider, started, ProviderResult.Failure(ProviderErrorCode.NETWORK, "사용량 페이지를 불러오지 못했습니다 ($error)"), lastPage, null, 0)
                }
                val page = capture(web, rich) ?: continue
                lastPage = page
                val kind = UsageSurface.classify(page.url, page.text)
                if (page.hasPassword || kind == AuthPageKind.LOGIN || kind == AuthPageKind.GOOGLE_WEBVIEW_BLOCK) {
                    if (signInSince == 0L) signInSince = now
                    // Redirects can pass a sign-in page on the way; one that stays is the answer.
                    if (now - signInSince >= SIGN_IN_SETTLE) break
                    continue
                }
                signInSince = 0L
                // A verification challenge can clear itself, so it is waited out rather than failed at once.
                verifying = kind == AuthPageKind.DEVICE_VERIFICATION
                if (verifying || !UsageSurface.canCollect(page.url, usageUrl, page.text, page.hasPassword)) continue
                val result = ConsumerUsageParser.parseBest(account.providerId, account.id, page.text, page.rich.takeIf { rich })
                if (!ConsumerUsageParser.primaryHasNumbers(result)) {
                    // Some pages draw their figures where innerText cannot see them: walk the nodes from now on.
                    rich = true
                    if (partial == null || ConsumerUsageParser.carriesNumbers(result)) partial = result
                    continue
                }
                val snapshot = (result as ProviderResult.Success).snapshot
                val next = signatureOf(snapshot)
                if (next != signature) { signature = next; stableSince = now }
                best = ProviderResult.Success(snapshot.copy(syncMode = SyncMode.BACKGROUND))
                if (firstFigures == 0L) { firstFigures = now; figuresShownAt.set(now); first = best }
                // Banked resets sit at the bottom of the Codex page and can arrive after the
                // limits, or render only once scrolled to. Reported: an account holding three
                // showed none, because the read ended before the list was there.
                val awaitingResets = listsResets && snapshot.resetCredits.isEmpty() && now - firstFigures < RESETS_WAIT
                if (scrolledAt == 0L || (awaitingResets && now - scrolledAt >= RESCROLL)) {
                    // Sections further down can render only once reached.
                    scrolledAt = now
                    web.evaluateJavascript("window.scrollTo(0,document.body?document.body.scrollHeight:0)", null)
                }
                // A page can paint numbers it cached on an earlier visit and replace them once
                // its own request returns. Stopping at the first numbers kept that old value
                // forever, because the page was destroyed before it could refresh them. So the
                // read waits until the numbers have held still and the page has stopped asking
                // for data, with a cap for pages that never stop polling.
                val quiet = now - lastRequest.get() >= QUIET
                val settled = (now - stableSince >= STABLE && now - firstFigures >= SETTLE_MIN && quiet) || now - stableSince >= STABLE_CAP
                if (settled && !awaitingResets) break
            }
            val outcome: ProviderResult = best ?: when {
                signInSince != 0L || verifying -> ProviderResult.Failure(ProviderErrorCode.AUTH_REQUIRED, "계정 로그인 확인이 필요합니다.")
                partial != null -> ProviderResult.Failure(ProviderErrorCode.PARSE_FAILED, "대표 항목의 수치를 찾지 못했습니다.")
                else -> ProviderResult.Failure(ProviderErrorCode.NETWORK_TIMEOUT, "사용량 페이지 응답 시간 초과")
            }
            return finish(account, provider, started, outcome, lastPage, first, laterRequests.get())
        } finally {
            frames.release()
            // Providers rotate session cookies on each load; an unflushed rotation
            // would silently expire the account and force a fresh sign-in.
            ProfileSessions.flush(web)
            web.stopLoading()
            web.destroy()
        }
    }

    /**
     * Leaves a line in the diagnostics for every background read, so a failure on a
     * phone has evidence: the figures read, the ones the page showed first when they
     * differ, and how many requests the page made after first showing figures.
     */
    private fun finish(
        account: Account, provider: ProviderDefinition, started: Long, outcome: ProviderResult,
        page: Page?, first: ProviderResult.Success?, laterRequests: Int,
    ): ProviderResult {
        val elapsed = SystemClock.elapsedRealtime() - started
        val shown = first?.let { describeBuckets(it.snapshot) }
        val changed = if (outcome is ProviderResult.Success && shown != describeBuckets(outcome.snapshot)) " was[$shown]" else ""
        val requests = if (outcome is ProviderResult.Success) " net+$laterRequests" else ""
        WebTrace.record("bg-read", "${provider.id} \"${account.alias}\" ${elapsed}ms ${describe(outcome)}$changed$requests")
        if (page == null) return outcome
        if (outcome is ProviderResult.Failure && page.text.isNotBlank()) WebTrace.record("bg-context", usageContext(page.text, provider.id))
        if (outcome is ProviderResult.Success) {
            // Where a figure only the node walk reaches came from, so a wrong one can be traced to its node.
            val walked = page.rich.isNotBlank() &&
                !ConsumerUsageParser.primaryHasNumbers(ConsumerUsageParser.parse(provider.id, account.id, page.text))
            if (walked) WebTrace.record("bg-source", "${provider.id} ${usageContext(page.rich, provider.id)}")
            if (provider.id in BANKED_RESETS) {
                val section = ConsumerUsageParser.resetSection(page.text)?.take(RESET_LINES)?.joinToString(" | ")
                WebTrace.record("bg-resets", "${provider.id} \"${account.alias}\" ${section ?: "no section"}")
            }
        }
        return outcome
    }

    /**
     * A WebView that is never attached to a window is never drawn, and Chromium
     * does not start a page's next frame until the last one has been drawn. After
     * its first few frames the page gets no more: requestAnimationFrame callbacks
     * and IntersectionObserver notifications stop arriving, and a page that
     * refreshes the figures it cached from one of them keeps showing the cached
     * ones, or never renders a list waiting to be scrolled to. Drawing the view
     * into a one-pixel canvas completes each frame the way a screen would, without
     * touching the page's scripts.
     */
    internal class FramePump(private val web: WebView) {
        private val bitmap = createBitmap(1, 1)
        private val canvas = Canvas(bitmap)
        private var failed = false

        fun draw() {
            if (failed) return
            try {
                web.draw(canvas)
            } catch (error: RuntimeException) {
                failed = true
                WebTrace.record("bg-frame", error.javaClass.simpleName)
            }
        }

        /** Waits [millis], completing a frame every [FRAME] ms meanwhile. */
        suspend fun advance(millis: Long) {
            var waited = 0L
            while (waited < millis) {
                val step = minOf(FRAME, millis - waited)
                delay(step)
                waited += step
                draw()
            }
        }

        fun release() = bitmap.recycle()
    }

    companion object {
        const val PARALLEL_PAGES = 3
        const val POLL = 500L
        /** How often a page that nobody sees is given a frame. */
        const val FRAME = 250L
        const val TIMEOUT = 45_000L
        /** The figures must hold this long, and the page must be this quiet, before they are taken. */
        const val STABLE = 2_000L
        const val QUIET = 1_500L
        /** Never take the very first numbers: a cached value gets this long to be replaced. */
        const val SETTLE_MIN = 3_000L
        /** A page that polls forever is read once its figures have held this long. */
        const val STABLE_CAP = 6_000L
        const val SIGN_IN_SETTLE = 6_000L
        /** Providers whose usage page lists banked resets below the limits. */
        val BANKED_RESETS = setOf("chatgpt")
        /** How long after the first figures a read keeps waiting for that list. */
        const val RESETS_WAIT = 7_000L
        const val RESCROLL = 2_000L
        private const val RESET_LINES = 12

        data class Page(val url: String, val text: String, val hasPassword: Boolean, val rich: String = "")

        /** True when parsing found an actual figure, not just a label or a reset time. */
        fun carriesNumbers(result: ProviderResult): Boolean = ConsumerUsageParser.carriesNumbers(result)

        /** Everything a reader shows: two captures that agree on this are the same reading. */
        internal fun signatureOf(snapshot: UsageSnapshot): String = buildString {
            snapshot.buckets.sortedBy { it.id }.forEach { bucket ->
                append(bucket.id).append(':').append(bucket.usedPercent).append('/').append(bucket.remainingPercent)
                    .append('/').append(bucket.used).append('/').append(bucket.remaining).append('/').append(bucket.resetAt?.div(60_000)).append(';')
            }
            append(snapshot.extraCredits?.amount).append(';').append(snapshot.resetCreditsKnown).append(';')
            snapshot.resetCredits.forEach { append(it.expiresAt?.div(60_000)).append(',') }
        }

        internal fun describeBuckets(snapshot: UsageSnapshot): String = snapshot.buckets.joinToString(" ") { bucket ->
            val value = bucket.usedPercent?.let { "${it.toInt()}%used" }
                ?: bucket.remainingPercent?.let { "${it.toInt()}%left" }
                ?: bucket.used?.let { "${it.toInt()}${bucket.unit.name}" }
                ?: "novalue"
            "${bucket.id}=$value${if (bucket.resetAt != null) "+reset" else ""}"
        }

        internal fun describe(result: ProviderResult): String = when (result) {
            is ProviderResult.Success -> describeBuckets(result.snapshot) + " resets=" +
                (if (result.snapshot.resetCreditsKnown) result.snapshot.resetCredits.size.toString() else "?")
            is ProviderResult.Failure -> "failed ${result.code}"
        }

        /**
         * The lines from the provider's first quota label on: enough to see why a figure
         * was or was not read, and nothing else of the page. Without a known label the
         * anchor is the first line naming usage or a limit.
         */
        fun usageContext(text: String, providerId: String? = null): String {
            val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val quota = providerId?.let { ConsumerUsageParser.quotaLabelIndex(it, lines) } ?: -1
            val anchor = if (quota >= 0) quota else lines.indexOfFirst { Regex("한도|limit|usage|사용량", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            if (anchor < 0) return "no usage label among ${lines.size} lines"
            return lines.subList(anchor, minOf(lines.size, anchor + 16)).joinToString(" | ")
        }

        suspend fun capture(web: WebView, rich: Boolean = true): Page? = withContext(Dispatchers.Main) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    web.evaluateJavascript(if (rich) CAPTURE_JS else CAPTURE_TEXT_JS) { encoded ->
                        val page = runCatching {
                            val json = JSONObject(JSONTokener(encoded).nextValue() as String)
                            Page(json.getString("url"), json.getString("text"), json.optBoolean("hasPassword"), json.optString("rich"))
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

        /**
         * `innerText` reports what the layout paints, and a provider can draw its
         * numbers where that cannot reach them: SVG chart text, a shadow root, or
         * content the engine skipped. Grok's usage page renders every label but no
         * figure that way. So a second, structural reading walks the tree for text
         * nodes in document order. The walk styles every element, which is costly
         * on a large page, so a background read asks for it only once the painted
         * text has shown it lacks the main figure.
         */
        private fun captureScript(walkNodes: Boolean) = """
            (function(){
              var text=document.body?document.body.innerText:'';
              document.querySelectorAll('[role="progressbar"],progress,meter').forEach(function(e){
                if(!e.getClientRects().length)return;
                var label=e.getAttribute('aria-label')||'';
                var value=e.getAttribute('aria-valuetext')||'';
                if(label&&value)text+='\n'+label+'\n'+value;
              });
              var rich=[];
              function digits(v){return v&&/[0-9]/.test(v);}
              function pseudo(el,which){
                try{
                  var c=window.getComputedStyle(el,which).content;
                  if(!c||c==='none'||c==='normal')return '';
                  return c.replace(/^["']|["']${'$'}/g,'');
                }catch(err){return '';}
              }
              function walk(n){
                if(!n)return;
                if(n.nodeType===3){var t=n.nodeValue.replace(/\s+/g,' ').trim();if(t)rich.push(t);return;}
                if(n.nodeType!==1&&n.nodeType!==11)return;
                var tag=n.tagName?String(n.tagName).toUpperCase():'';
                if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='TEMPLATE')return;
                if(n.nodeType===1){
                  try{var s=window.getComputedStyle(n);if(s&&(s.display==='none'||s.visibility==='hidden'))return;}catch(err){}
                  // A figure can live outside the text tree entirely: drawn by CSS
                  // content, or exposed only to assistive technology. Keep those that
                  // carry a digit so a value is never lost, without flooding the text.
                  var before=pseudo(n,'::before');if(digits(before))rich.push(before.trim());
                  var attrs=['aria-valuetext','aria-valuenow','aria-label','title'];
                  for(var a=0;a<attrs.length;a++){
                    var v=n.getAttribute?n.getAttribute(attrs[a]):null;
                    if(digits(v)){rich.push(String(v).replace(/\s+/g,' ').trim());break;}
                  }
                  // Form controls hold their text in value, not in a child node.
                  if((tag==='INPUT'||tag==='TEXTAREA')&&digits(n.value))rich.push(String(n.value).trim());
                  if(n.shadowRoot)walk(n.shadowRoot);
                }
                for(var c=n.firstChild;c;c=c.nextSibling)walk(c);
                if(n.nodeType===1){var after=pseudo(n,'::after');if(digits(after))rich.push(after.trim());}
              }
              if($walkNodes){try{walk(document.body);}catch(err){}}
              return JSON.stringify({url:location.href,text:text.slice(0,120000),
                rich:rich.join('\n').slice(0,120000),hasPassword:!!document.querySelector('input[type=password]')});
            })()
        """.trimIndent()

        val CAPTURE_JS = captureScript(walkNodes = true)
        val CAPTURE_TEXT_JS = captureScript(walkNodes = false)
    }
}
