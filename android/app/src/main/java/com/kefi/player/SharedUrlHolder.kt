package com.kefi.player

/**
 * Parking spot for a link shared into the app from elsewhere (YouTube's Share
 * sheet, a browser, a chat app). The Activity receives the Intent well before
 * the WebView is ready to be asked about it, so it is held here until the Vue
 * side calls consumeSharedUrl().
 */
object SharedUrlHolder {

    private val URL_PATTERN = Regex("""https?://\S+""")

    @Volatile
    private var pending: String? = null

    /**
     * Notified when a link arrives while the app is already running. Without this
     * the URL would sit here unnoticed until the WebView happened to re-check.
     */
    @Volatile
    private var listener: (() -> Unit)? = null

    fun setListener(callback: (() -> Unit)?) {
        listener = callback
    }

    /**
     * Shared text is often "Check this out: <url> via @YouTube" rather than a
     * bare URL, so pull the first URL out of whatever arrives.
     */
    fun offer(sharedText: String?) {
        val url = sharedText?.let { URL_PATTERN.find(it)?.value }
        if (!url.isNullOrBlank()) {
            pending = url
            listener?.invoke()
        }
    }

    /** Returns the pending URL, if any, and clears it so it is only acted on once. */
    fun consume(): String? {
        val url = pending
        pending = null
        return url
    }
}
