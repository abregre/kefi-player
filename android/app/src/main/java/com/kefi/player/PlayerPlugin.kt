package com.kefi.player

import android.Manifest
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
 * live state + transport (playTrack/pause/resume/seek). This task (T9) adds device-wide
 * audio listing and artwork lookup, gated behind the `audio` (API 33+) / `audioLegacy`
 * (API < 33) permission aliases. Queueing, shuffle/repeat belong to T10.
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
        // Only set the push callback here. PlayerController must be first connected from
        // a user-gesture-originated plugin call (see its header comment) — never from
        // load(), which can run before any user interaction.
        PlayerController.onState = { snapshot ->
            notifyListeners("playerState", snapshot.toJSObject())
        }
    }

    override fun handleOnDestroy() {
        PlayerController.onState = null
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
        val positionMs = call.getLong("positionMs")
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
        call.resolve(JSObject().put("granted", hasAudioPermission()))
    }

    @PluginMethod
    fun requestAudioPermission(call: PluginCall) {
        if (hasAudioPermission()) {
            call.resolve(JSObject().put("granted", true))
            return
        }
        requestPermissionForAlias(audioPermissionAlias(), call, "audioPermissionCallback")
    }

    @PermissionCallback
    private fun audioPermissionCallback(call: PluginCall) {
        call.resolve(JSObject().put("granted", hasAudioPermission()))
    }

    /**
     * `READ_MEDIA_AUDIO` (API 33+) replaced `READ_EXTERNAL_STORAGE` for reading audio;
     * minSdk is 24, so the legacy branch is reachable and mandatory, not defensive.
     */
    private fun audioPermissionAlias(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "audio" else "audioLegacy"

    private fun hasAudioPermission(): Boolean =
        getPermissionState(audioPermissionAlias()) == PermissionState.GRANTED

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
     * present regardless of whether a track is loaded.
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
        put("repeatMode", repeatMode)
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
