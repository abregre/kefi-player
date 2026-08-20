package com.kefi.player

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@CapacitorPlugin(name = "Ytdlp")
class YtdlpPlugin : Plugin() {

    private val jobs = ConcurrentHashMap<String, Job>()

    override fun load() {
        // A share received while the app is already foregrounded produces no
        // lifecycle change, so the UI has to be told to come and collect it.
        SharedUrlHolder.setListener {
            notifyListeners("sharedUrl", JSObject())
        }
    }

    override fun handleOnDestroy() {
        SharedUrlHolder.setListener(null)
    }

    /** Matches watch?v=, youtu.be/, /shorts/, /embed/ and /live/ forms. */
    private val VIDEO_ID_PATTERN =
        Regex("""(?:v=|youtu\.be/|shorts/|embed/|/live/)([A-Za-z0-9_-]{11})""")

    /**
     * Unpacks yt-dlp/ffmpeg if this is the first launch. Worth calling once at
     * app start so the UI can show a one-time "preparing" state instead of
     * making the first download look like it hung.
     */
    @PluginMethod
    fun prepare(call: PluginCall) {
        App.downloadScope.launch {
            try {
                App.ensureNativeToolsReady()
                call.resolve(JSObject().apply {
                    put("ready", true)
                    put("version", YoutubeDL.getInstance().version(context) ?: "bundled")
                })
            } catch (t: Throwable) {
                call.rejectWith("Could not unpack yt-dlp and ffmpeg: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun getInfo(call: PluginCall) {
        val url = call.getString("url")?.trim()
        if (url.isNullOrEmpty()) {
            call.reject("A url is required")
            return
        }

        App.downloadScope.launch {
            try {
                App.ensureNativeToolsReady()
                val info = YoutubeDL.getInstance().getInfo(singleVideo(url, call.youtubeArgs()))
                call.resolve(JSObject().apply {
                    put("id", info.id)
                    put("title", info.title ?: "Untitled")
                    put("uploader", info.uploader)
                    put("duration", info.duration)
                    put("thumbnail", info.thumbnail)
                    put("webpageUrl", info.webpageUrl ?: url)
                })
            } catch (t: Throwable) {
                call.rejectWith(humanError(t), t)
            }
        }
    }

    /**
     * Resolves with the finished file. Also emits `progress`, `completed`,
     * `failed` and `cancelled` events, so a UI that lost the promise (WebView
     * reload, app backgrounded) can still follow along.
     */
    @PluginMethod
    fun download(call: PluginCall) {
        val url = call.getString("url")?.trim()
        if (url.isNullOrEmpty()) {
            call.reject("A url is required")
            return
        }

        val jobId = call.getString("jobId") ?: UUID.randomUUID().toString()
        val bitrate = call.getString("bitrate") ?: "192"
        val embedThumbnail = call.getBoolean("embedThumbnail") ?: true
        val ytArgs = call.youtubeArgs()
        val verbose = call.getBoolean("verbose") ?: false
        // Opt-in. yt-dlp nightly extracts YouTube without a token, and forcing
        // the web client (which a token requires) lands on YouTube's SABR-only
        // delivery, which yt-dlp cannot download. Kept for when that changes.
        val usePoToken = call.getBoolean("usePoToken") ?: false

        if (jobs.containsKey(jobId)) {
            call.reject("A download with id $jobId is already running")
            return
        }

        // LAZY so the job is registered before it can possibly finish and try to
        // remove itself from the map.
        val job = App.downloadScope.launch(start = CoroutineStart.LAZY) {
            val workDir = File(context.filesDir, "downloads/$jobId")
            try {
                App.ensureNativeToolsReady()
                workDir.mkdirs()
                DownloadService.start(context)

                // YouTube refuses media URLs without a PO token. Minting runs a
                // WebView and takes a few seconds, so it is cached and shared
                // across downloads.
                val resolvedArgs = resolveYoutubeArgs(url, ytArgs, usePoToken)

                // Fetch the title up front so the exported file is named after the
                // video rather than the opaque job id.
                val title = runCatching {
                    YoutubeDL.getInstance().getInfo(singleVideo(url, resolvedArgs)).title
                }.getOrNull()

                val request = singleVideo(url, resolvedArgs).apply {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "${bitrate}K")
                    addOption("--embed-metadata")
                    if (embedThumbnail) addOption("--embed-thumbnail")
                    addOption("--no-mtime")
                    // YouTube intermittently 403s media URLs mid-transfer.
                    // Retrying recovers many of those without user involvement.
                    addOption("--retries", "10")
                    addOption("--fragment-retries", "10")
                    addOption("--extractor-retries", "3")
                    if (verbose) addOption("-v")
                    addOption("-o", File(workDir, "audio.%(ext)s").absolutePath)
                }

                YoutubeDL.getInstance().execute(request, jobId) { progress, etaSeconds, line ->
                    notifyListeners("progress", JSObject().apply {
                        put("jobId", jobId)
                        put("progress", progress.coerceIn(0f, 100f))
                        put("etaSeconds", etaSeconds)
                        put("line", line)
                    })
                }

                val mp3 = workDir.listFiles()?.firstOrNull { it.extension.equals("mp3", true) }
                    ?: throw IllegalStateException("yt-dlp finished but produced no mp3 file")

                val exported = StorageExporter.export(
                    context, mp3, title ?: mp3.nameWithoutExtension
                )

                val payload = JSObject().apply {
                    put("jobId", jobId)
                    put("uri", exported.uri)
                    put("displayName", exported.displayName)
                    put("size", exported.size)
                    put("location", exported.location)
                    put("title", title ?: exported.displayName)
                }
                notifyListeners("completed", payload)
                call.resolve(payload)
            } catch (e: CancellationException) {
                notifyListeners("cancelled", JSObject().apply { put("jobId", jobId) })
                call.reject("Download cancelled", "CANCELLED")
                throw e
            } catch (t: Throwable) {
                // A 403 here usually means the PO token was rejected or aged out,
                // so discard it rather than reusing it on the next attempt.
                if (t.message?.contains("403") == true) {
                    PoTokenGenerator.invalidate()
                }
                val message = humanError(t)
                notifyListeners("failed", JSObject().apply {
                    put("jobId", jobId)
                    put("error", message)
                })
                call.rejectWith(message, t)
            } finally {
                workDir.deleteRecursively()
                jobs.remove(jobId)
                if (jobs.isEmpty()) DownloadService.stop(context)
            }
        }

        jobs[jobId] = job
        job.start()
    }

    /** Mints (or returns the cached) PO token. Useful for a diagnostics screen. */
    @PluginMethod
    fun mintPoToken(call: PluginCall) {
        val force = call.getBoolean("forceRefresh") ?: false
        App.downloadScope.launch {
            try {
                App.ensureNativeToolsReady()
                val binding = call.getString("contentBinding")
                    ?: VIDEO_ID_PATTERN.find(call.getString("url") ?: "")?.groupValues?.get(1)
                    ?: throw IllegalArgumentException(
                        "Pass a url or contentBinding: a PO token is bound to a specific video"
                    )
                val token = PoTokenGenerator.getToken(context, binding, force)
                call.resolve(JSObject().apply {
                    put("poToken", token.poToken)
                    put("visitorData", token.visitorData)
                })
            } catch (t: Throwable) {
                call.rejectWith(t.message ?: "Could not mint a PO token", t)
            }
        }
    }

    /**
     * Compares the installed yt-dlp against the newest release on its channel,
     * without downloading anything. Lets the UI hide the Update button when there
     * is nothing to do.
     */
    @PluginMethod
    fun checkForUpdate(call: PluginCall) {
        val channelName = call.getString("channel")
        App.downloadScope.launch {
            try {
                App.ensureNativeToolsReady()
                val channel = updateChannel(channelName)
                val installed = YoutubeDL.getInstance().version(context)
                val latest = fetchLatestTag(channel.apiUrl)

                call.resolve(JSObject().apply {
                    put("installed", installed)
                    put("latest", latest)
                    // A null installed version means yt-dlp has never self-updated,
                    // so whatever is bundled is older than the channel head.
                    put("updateAvailable", latest != null && latest != installed)
                })
            } catch (t: Throwable) {
                call.rejectWith(
                    "Could not check for updates: ${t.message ?: "no network"}", t
                )
            }
        }
    }

    /**
     * Nightly by default: as of 2026-08 the stable channel cannot download
     * YouTube at all (its web-client formats come back without URLs because
     * YouTube forces SABR streaming), while nightly extracts normally.
     */
    private fun updateChannel(name: String?) = when (name?.lowercase()) {
        "stable" -> YoutubeDL.UpdateChannel._STABLE
        "master" -> YoutubeDL.UpdateChannel._MASTER
        else -> YoutubeDL.UpdateChannel._NIGHTLY
    }

    /** Reads `tag_name` from a GitHub "latest release" endpoint. */
    private fun fetchLatestTag(apiUrl: String): String? {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                // 403 here is normally GitHub's unauthenticated rate limit.
                throw IllegalStateException("GitHub returned ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hands a finished file to whatever audio player the user has, through the
     * system chooser. The URI comes from StorageExporter, so it is a MediaStore
     * content URI this app already holds permission for; the read grant is
     * passed along to the player.
     */
    @PluginMethod
    fun openFile(call: PluginCall) {
        val raw = call.getString("uri")
        if (raw.isNullOrBlank()) {
            call.reject("A uri is required")
            return
        }

        try {
            val target = shareableUri(Uri.parse(raw))
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(target, "audio/mpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // A chooser rather than a direct launch, so the user picks their
            // player instead of us guessing (and so no default is silently set).
            activity.startActivity(Intent.createChooser(view, "Play with"))
            call.resolve()
        } catch (e: ActivityNotFoundException) {
            call.reject("No app on this device can play MP3 files")
        } catch (t: Throwable) {
            call.rejectWith("Could not open the file: ${t.message}", t)
        }
    }

    /**
     * Android 9 and below export to a real path, and handing a file:// URI to
     * another app throws FileUriExposedException, so it is republished through
     * FileProvider. content:// URIs pass straight through.
     */
    private fun shareableUri(uri: Uri): Uri {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: throw IllegalArgumentException("Malformed file URI: $uri")
        return FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(path)
        )
    }

    /**
     * The folder finished MP3s are written to, for a settings screen. Always the
     * fixed default: ytmp3's SAF folder-picker pair (pick/clear) is deliberately
     * not ported, so there is never a custom location to report.
     */
    @PluginMethod
    fun getStorageLocation(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("label", StorageExporter.DEFAULT_LABEL)
            put("isCustom", false)
            put("uri", JSONObject.NULL)
            put("cancelled", false)
        })
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val jobId = call.getString("jobId")
        if (jobId.isNullOrEmpty()) {
            call.reject("A jobId is required")
            return
        }

        // Kill the native process first — cancelling only the coroutine would
        // leave yt-dlp running to completion in the background.
        val killed = runCatching {
            YoutubeDL.getInstance().destroyProcessById(jobId)
        }.getOrDefault(false)
        jobs.remove(jobId)?.cancel()

        call.resolve(JSObject().apply { put("cancelled", killed) })
    }

    /**
     * YouTube changes its extraction surface often; the bundled yt-dlp can update
     * itself in place without shipping a new APK.
     */
    @PluginMethod
    fun updateYtdlp(call: PluginCall) {
        App.downloadScope.launch {
            try {
                App.ensureNativeToolsReady()
                val status = YoutubeDL.getInstance()
                    .updateYoutubeDL(context, updateChannel(call.getString("channel")))
                call.resolve(JSObject().apply {
                    put("status", status?.name ?: "UNKNOWN")
                    put("version", YoutubeDL.getInstance().version(context) ?: "bundled")
                })
            } catch (t: Throwable) {
                call.rejectWith(humanError(t), t)
            }
        }
    }

    /**
     * Picks up a link shared into the app from another app's Share sheet. Returns
     * null when there is nothing pending.
     */
    @PluginMethod
    fun consumeSharedUrl(call: PluginCall) {
        call.resolve(JSObject().apply { put("url", SharedUrlHolder.consume()) })
    }

    /**
     * The Capacitor WebView never grants the async Clipboard API's read permission (the
     * reason the Paste button exists on this native side at all), so the UI reads through
     * here instead of `navigator.clipboard.readText()`. An empty clipboard resolves `""`
     * rather than rejecting — that is a normal state, not a failure.
     */
    @PluginMethod
    fun readClipboard(call: PluginCall) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
        } else {
            ""
        }
        call.resolve(JSObject().apply { put("text", text) })
    }

    /**
     * PluginCall.reject only accepts Exception, but coroutine failures surface as
     * Throwable (an OutOfMemoryError on a huge conversion, for instance).
     */
    private fun PluginCall.rejectWith(message: String, cause: Throwable) {
        reject(message, cause as? Exception ?: Exception(cause))
    }

    /**
     * YouTube now requires a PO token for media URLs; without one, extraction
     * succeeds but the transfer 403s. yt-dlp cannot mint one itself, so the token
     * (and the matching visitor_data) has to be supplied from outside.
     */
    private data class YoutubeArgs(
        val poToken: String? = null,
        val visitorData: String? = null,
        val playerClient: String? = null,
    )

    /**
     * Fills in a PO token unless the caller supplied one.
     *
     * YouTube currently binds the GVS token to the video ID (yt-dlp reports
     * "Detected experiment to bind GVS PO Token to video ID"), so a token minted
     * against anything else is rejected and the formats come back without URLs.
     * Non-YouTube links have no video ID to bind to and need no token, so they
     * skip minting entirely.
     */
    private suspend fun resolveYoutubeArgs(
        url: String,
        args: YoutubeArgs,
        usePoToken: Boolean,
    ): YoutubeArgs {
        if (!usePoToken || !args.poToken.isNullOrBlank()) return args
        val videoId = VIDEO_ID_PATTERN.find(url)?.groupValues?.get(1) ?: return args

        val token = PoTokenGenerator.getToken(context, videoId)
        return args.copy(
            // yt-dlp expects CLIENT.CONTEXT+TOKEN; "gvs" is the context that
            // gates the media URLs themselves.
            poToken = "web.gvs+${token.poToken}",
            visitorData = token.visitorData,
            playerClient = args.playerClient ?: "web",
        )
    }

    private fun PluginCall.youtubeArgs() = YoutubeArgs(
        poToken = getString("poToken"),
        visitorData = getString("visitorData"),
        playerClient = getString("playerClient"),
    )

    private fun singleVideo(url: String, args: YoutubeArgs = YoutubeArgs()) =
        YoutubeDLRequest(url).apply {
            // Without this, pasting a "watch?v=...&list=..." link downloads the
            // entire playlist.
            addOption("--no-playlist")
            addOption("--no-warnings")

            // yt-dlp needs a JavaScript runtime to solve YouTube's signature
            // challenges. Without one, the web client's formats are skipped and
            // extraction fails with "Requested format is not available". The
            // library ships QuickJS as libqjs.so, but yt-dlp only auto-enables
            // deno, so it has to be pointed at the binary explicitly.
            val quickJs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
            if (quickJs.canExecute()) {
                addOption("--js-runtimes", "quickjs:${quickJs.absolutePath}")
            }

            val extractorArgs = buildList {
                args.playerClient?.takeIf { it.isNotBlank() }?.let { add("player_client=$it") }
                args.poToken?.takeIf { it.isNotBlank() }?.let { add("po_token=$it") }
                args.visitorData?.takeIf { it.isNotBlank() }?.let { add("visitor_data=$it") }
            }
            if (extractorArgs.isNotEmpty()) {
                addOption("--extractor-args", "youtube:" + extractorArgs.joinToString(";"))
            }
        }

    /**
     * yt-dlp failures arrive as a wall of Python traceback. Pull out the parts a
     * user can act on and keep the rest for the log view.
     */
    private fun humanError(t: Throwable): String {
        val raw = t.message ?: return "Download failed"
        return when {
            raw.contains("Video unavailable", true) ->
                "This video is unavailable."
            raw.contains("Private video", true) ->
                "This video is private."
            raw.contains("members-only", true) ->
                "This video is members-only."
            raw.contains("age", true) && raw.contains("confirm", true) ->
                "This video is age-restricted and needs a signed-in account."
            raw.contains("Sign in to confirm", true) ->
                "YouTube is asking for sign-in (bot check). Try again later."
            raw.contains("Unable to download webpage", true) ||
                raw.contains("Temporary failure in name resolution", true) ->
                "No network connection."
            raw.contains("is not a valid URL", true) || raw.contains("Unsupported URL", true) ->
                "That does not look like a supported video link."
            else -> raw.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith("ERROR:") }
                ?.removePrefix("ERROR:")
                ?.trim()
                ?: "Download failed"
        }
    }
}
