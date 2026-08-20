package com.kefi.player

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PermissionState
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Capacitor bridge between the WebView and [PlayerController]/[TrackSource]. T7 wired up
 * live state + transport (playTrack/pause/resume/seek). T9 added device-wide audio listing
 * and artwork lookup, gated behind the `audio` (API 33+) / `audioLegacy` (API < 33)
 * permission aliases. T10 adds queueing, next/previous, shuffle, and repeat, and fixes the
 * `seek` argument-coercion bug T9 found but deliberately left out of its own scope. T11 adds
 * the `trackUnavailable` push event, forwarding [PlayerController.onTrackUnavailable] the same
 * way [PlayerController.onState] is forwarded as `playerState`.
 */
@CapacitorPlugin(
    name = "Player",
    permissions = [
        Permission(alias = "audio", strings = [Manifest.permission.READ_MEDIA_AUDIO]),
        Permission(alias = "audioLegacy", strings = [Manifest.permission.READ_EXTERNAL_STORAGE]),
    ],
)
class PlayerPlugin : Plugin() {

    override fun load() {
        // Only set the push callbacks here. PlayerController must be first connected from
        // a user-gesture-originated plugin call (see its header comment) — never from
        // load(), which can run before any user interaction.
        PlayerController.onState = { snapshot ->
            notifyListeners("playerState", snapshot.toJSObject())
        }
        PlayerController.onTrackUnavailable = { uri, reason ->
            notifyListeners(
                "trackUnavailable",
                JSObject().apply {
                    put("uri", uri ?: JSONObject.NULL)
                    put("reason", reason)
                },
            )
        }
    }

    override fun handleOnDestroy() {
        PlayerController.onState = null
        PlayerController.onTrackUnavailable = null
    }

    @PluginMethod
    fun getPlayerState(call: PluginCall) {
        App.scope.launch {
            try {
                // Always a live read off the controller — never a cached value.
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not read player state: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun playTrack(call: PluginCall) {
        val uri = call.getString("uri")
        if (uri.isNullOrEmpty()) {
            call.reject("A uri is required")
            return
        }
        val title = call.getString("title")
        val artist = call.getString("artist")

        App.scope.launch {
            try {
                val metadata = MediaMetadata.Builder()
                    .apply {
                        if (!title.isNullOrEmpty()) setTitle(title)
                        if (!artist.isNullOrEmpty()) setArtist(artist)
                    }
                    .build()
                val item = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(uri)
                    .setMediaMetadata(metadata)
                    .build()
                PlayerController.play(item)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not play track: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.pause()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not pause: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun resume(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.resume()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not resume: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun seek(call: PluginCall) {
        // Same PluginCall.getLong/org.json.Integer-boxing bug T9 found for trackId (see
        // getArtwork below): every realistic positionMs fits in an Int, so org.json boxes it
        // as Integer and a bare getLong("positionMs") silently returns null, rejecting every
        // real call. Same fallback fix.
        val positionMs = call.getLong("positionMs") ?: call.getInt("positionMs")?.toLong()
        if (positionMs == null) {
            call.reject("A positionMs is required")
            return
        }

        App.scope.launch {
            try {
                PlayerController.seek(positionMs)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not seek: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setQueue(call: PluginCall) {
        val uris = call.getArray("uris")
        if (uris == null || uris.length() == 0) {
            call.reject("A non-empty uris array is required")
            return
        }
        // startIndex is a small ordinal (queue position), never near Int.MAX_VALUE — getInt
        // with a default is correct here, unlike positionMs/trackId above which are boxed
        // ambiguously by org.json but still need Long on the Kotlin side.
        val startIndex = call.getInt("startIndex", 0) ?: 0

        App.scope.launch {
            try {
                val items = (0 until uris.length()).map { i ->
                    val uri = uris.getString(i)
                    // Mirrors playTrack's MediaItem construction above so mediaId stays
                    // consistent (mediaId == uri) across both the single-track and queue paths.
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(uri)
                        .build()
                }
                PlayerController.setQueue(items, startIndex)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not set queue: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun next(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.next()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not skip to next: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun previous(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.previous()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not skip to previous: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setShuffle(call: PluginCall) {
        val enabled = call.getBoolean("enabled")
        if (enabled == null) {
            call.reject("An enabled boolean is required")
            return
        }

        App.scope.launch {
            try {
                PlayerController.setShuffle(enabled)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not set shuffle: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setRepeatMode(call: PluginCall) {
        val mode = call.getString("mode")
        if (mode == null || repeatModeFromString(mode) == null) {
            call.reject("A mode of off|one|all is required")
            return
        }

        App.scope.launch {
            try {
                PlayerController.setRepeatMode(repeatModeFromString(mode)!!)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not set repeat mode: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun listTracks(call: PluginCall) {
        if (!hasAudioPermission()) {
            call.reject("Audio permission is required to list tracks")
            return
        }

        App.scope.launch {
            try {
                val tracks = TrackSource.listTracks(context)
                val array = JSArray()
                tracks.forEach { array.put(it.toJSObject()) }
                call.resolve(JSObject().put("tracks", array))
            } catch (t: Throwable) {
                call.rejectWith("Could not list tracks: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun getArtwork(call: PluginCall) {
        // PluginCall.getLong only matches a value already boxed as Long; org.json's parser
        // boxes small numbers (e.g. MediaStore _id values here) as Integer instead, so
        // getLong("trackId") alone silently returns null for every realistic trackId.
        val trackId = call.getLong("trackId") ?: call.getInt("trackId")?.toLong()
        if (trackId == null) {
            call.reject("A trackId is required")
            return
        }

        App.scope.launch {
            try {
                val dataUrl = TrackSource.getArtwork(context, trackId)
                call.resolve(JSObject().put("dataUrl", dataUrl ?: JSONObject.NULL))
            } catch (t: Throwable) {
                call.rejectWith("Could not load artwork: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun checkAudioPermission(call: PluginCall) {
        call.resolve(audioPermissionResult())
    }

    @PluginMethod
    fun requestAudioPermission(call: PluginCall) {
        if (hasAudioPermission()) {
            call.resolve(audioPermissionResult())
            return
        }
        requestPermissionForAlias(audioPermissionAlias(), call, "audioPermissionCallback")
    }

    @PermissionCallback
    private fun audioPermissionCallback(call: PluginCall) {
        call.resolve(audioPermissionResult())
    }

    /**
     * Opens this app's own page in system Settings. Needed by T19's denied state: once the
     * user has picked "Don't allow" twice, Android refuses to show the dialog again, so the
     * only honest thing the UI can offer is a way to the toggle that is actually blocking it.
     * `FLAG_ACTIVITY_NEW_TASK` because the Settings activity is started from an application
     * context, not from an Activity task.
     */
    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        call.resolve()
    }

    /**
     * `granted` is the answer the UI acts on; `state` is the extra detail T19's three states
     * need. Capacitor already tracks the distinction Android itself exposes — `prompt`
     * (never asked) and `prompt-with-rationale` (declined once, asking is still allowed) both
     * mean "the button should ask", while `denied` means the dialog will not appear again and
     * only system Settings can undo it. Guessing that from `granted: false` alone is
     * impossible, which is why the field is here rather than a locally-remembered "have we
     * asked yet" flag.
     */
    private fun audioPermissionResult(): JSObject = JSObject()
        .put("granted", hasAudioPermission())
        .put("state", getPermissionState(audioPermissionAlias()).toString())

    /**
     * `READ_MEDIA_AUDIO` (API 33+) replaced `READ_EXTERNAL_STORAGE` for reading audio;
     * minSdk is 24, so the legacy branch is reachable and mandatory, not defensive.
     */
    private fun audioPermissionAlias(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "audio" else "audioLegacy"

    private fun hasAudioPermission(): Boolean =
        getPermissionState(audioPermissionAlias()) == PermissionState.GRANTED

    /**
     * `PlayerController.Snapshot.repeatMode` is Media3's raw `Player.REPEAT_MODE_*` Int
     * (T6). The JS-facing contract is the STRING `off|one|all` instead (T18's repeat toggle
     * depends on this), so the mapping is applied only here, at the plugin boundary.
     */
    private fun repeatModeToString(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    /** Inverse of [repeatModeToString]; returns null for any string other than the 3 allowed. */
    private fun repeatModeFromString(mode: String): Int? = when (mode) {
        "off" -> Player.REPEAT_MODE_OFF
        "one" -> Player.REPEAT_MODE_ONE
        "all" -> Player.REPEAT_MODE_ALL
        else -> null
    }

    /**
     * PluginCall.reject only accepts Exception, but coroutine failures (and
     * PlayerController's suspend functions) surface as Throwable.
     */
    private fun PluginCall.rejectWith(message: String, cause: Throwable) {
        reject(message, cause as? Exception ?: Exception(cause))
    }

    /**
     * Serialises straight to the field names T6 documented as this task's public contract
     * (`.claude/notepads/kefi-player-full-android-player/learnings.md`, `## T6`). Used for
     * both the `getPlayerState` resolve payload and the `playerState` push event, so pull
     * and push are interchangeable on the JS side. Nullable fields are written as explicit
     * JSON `null` (via `JSONObject.NULL`) rather than omitted, so every key is always
     * present regardless of whether a track is loaded. `repeatMode` crosses the bridge as
     * the string `off|one|all` (see [repeatModeToString]), not Media3's raw Int.
     */
    private fun PlayerController.Snapshot.toJSObject(): JSObject = JSObject().apply {
        put("mediaId", mediaId ?: JSONObject.NULL)
        put("uri", uri ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("artist", artist ?: JSONObject.NULL)
        put("currentPosition", currentPosition)
        put("duration", duration)
        put("isPlaying", isPlaying)
        put("shuffleModeEnabled", shuffleModeEnabled)
        put("repeatMode", repeatModeToString(repeatMode))
        put("queueLength", queueLength)
        put("currentIndex", currentIndex)
    }

    /**
     * Exact key set T15/T16 build the library UI on (`.claude/notepads/
     * kefi-player-full-android-player/learnings.md`, `## T9`): `id`, `uri`, `title`,
     * `artist`, `album`, `albumArtist`, `trackNumber`, `year`, `durationMs`, `size`.
     */
    private fun TrackSource.Track.toJSObject(): JSObject = JSObject().apply {
        put("id", id)
        put("uri", uri)
        put("title", title)
        put("artist", artist ?: JSONObject.NULL)
        put("album", album ?: JSONObject.NULL)
        put("albumArtist", albumArtist ?: JSONObject.NULL)
        put("trackNumber", trackNumber ?: JSONObject.NULL)
        put("year", year ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("size", size)
    }
}
