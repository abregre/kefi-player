package com.kefi.player

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mints YouTube PO tokens by running Google's BotGuard VM in an offscreen WebView.
 *
 * YouTube will not serve media URLs without a PO token — extraction succeeds and
 * the transfer then returns HTTP 403. The token can only be produced by executing
 * an obfuscated JS program in a real browser engine, so neither yt-dlp's Python
 * nor Kotlin can do it alone. The protocol work is handled by the bgutils-js
 * bundle in assets/potoken/potoken.js.
 *
 * A GVS token is bound to one identifier, and YouTube currently binds it to the
 * video ID, so a token cannot be shared between videos. The expensive BotGuard
 * handshake is therefore done once per session and the resulting minter is reused
 * to produce a cheap token per video.
 */
object PoTokenGenerator {

    /** A PO token is only valid for the identifier it was minted against. */
    data class Token(val poToken: String, val visitorData: String)

    private const val ASSET_PATH = "potoken/potoken.js"
    private const val INIT_TIMEOUT_MILLIS = 60_000L
    private const val MINT_TIMEOUT_MILLIS = 30_000L
    private const val PAGE_TIMEOUT_MILLIS = 30_000L

    /** Re-handshake early rather than racing an integrity token that expires. */
    private const val EXPIRY_MARGIN_MILLIS = 5 * 60 * 1000L

    /**
     * BotGuard fingerprints the browser, so present as an ordinary desktop Chrome
     * rather than an Android WebView.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Serialises everything: there is only ever one pending JS call. */
    private val mutex = Mutex()

    private var webView: WebView? = null
    private var expiresAtMillis: Long = 0L
    private var pageLoaded: CompletableDeferred<Unit>? = null
    private var pending: CancellableContinuation<JSONObject>? = null

    /**
     * Returns a token bound to [contentBinding] — normally the video ID. Reuses
     * the existing BotGuard session unless it has expired.
     */
    suspend fun getToken(
        context: Context,
        contentBinding: String,
        forceRefresh: Boolean = false,
    ): Token {
        require(contentBinding.isNotBlank()) { "contentBinding must not be blank" }

        mutex.withLock {
            if (forceRefresh || webView == null || System.currentTimeMillis() >= expiresAtMillis) {
                openSession(context)
            }

            val result = evaluate(
                "window.__ytmp3Mint(${JSONObject.quote(contentBinding)})",
                MINT_TIMEOUT_MILLIS,
            )
            return Token(
                poToken = result.getString("poToken"),
                visitorData = result.getString("visitorData"),
            )
        }
    }

    /**
     * Marks the session stale so the next request re-handshakes. Deliberately does
     * not touch the WebView, since this is called from background threads and
     * WebViews may only be used on the main thread.
     */
    fun invalidate() {
        expiresAtMillis = 0L
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun openSession(context: Context) {
        val script = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }

        // The bundle must be inlined: the page is served under a youtube.com base
        // URL, so a <script src> pointing at file:///android_asset would be a
        // blocked cross-origin request.
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"></head>
            <body><script>$script</script></body></html>
        """.trimIndent()

        withContext(Dispatchers.Main) {
            destroyWebViewOnMain()

            val loaded = CompletableDeferred<Unit>()
            pageLoaded = loaded

            val view = WebView(context)
            webView = view

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
            }
            view.addJavascriptInterface(Bridge, "AndroidPoToken")
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    loaded.complete(Unit)
                }
            }
            view.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)

            try {
                withTimeout(PAGE_TIMEOUT_MILLIS) { loaded.await() }
            } catch (e: TimeoutCancellationException) {
                destroyWebViewOnMain()
                throw IllegalStateException("PO token WebView failed to load", e)
            }
        }

        val info = try {
            evaluate("window.__ytmp3Init()", INIT_TIMEOUT_MILLIS)
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) { destroyWebViewOnMain() }
            throw t
        }

        val ttlMillis = info.optLong("ttlSeconds", 3600L) * 1000L
        expiresAtMillis =
            System.currentTimeMillis() + (ttlMillis - EXPIRY_MARGIN_MILLIS).coerceAtLeast(60_000L)
    }

    /** Runs [js] and waits for the bridge to report a result. */
    private suspend fun evaluate(js: String, timeoutMillis: Long): JSONObject =
        withContext(Dispatchers.Main) {
            val view = webView ?: throw IllegalStateException("PO token WebView is not open")
            try {
                withTimeout(timeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        pending = continuation
                        view.evaluateJavascript(js, null)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                pending = null
                throw IllegalStateException(
                    "PO token step timed out after ${timeoutMillis / 1000}s", e
                )
            }
        }

    private fun destroyWebViewOnMain() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        expiresAtMillis = 0L
    }

    /** Invoked by the WebView on a worker thread. */
    private object Bridge {
        @JavascriptInterface
        fun onResult(json: String) {
            val continuation = pending ?: return
            pending = null
            try {
                continuation.resume(JSONObject(json))
            } catch (t: Throwable) {
                continuation.resumeWithException(
                    IllegalStateException("Malformed PO token payload: $json", t)
                )
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            val continuation = pending ?: return
            pending = null
            continuation.resumeWithException(IllegalStateException("PO token error: $message"))
        }
    }
}
