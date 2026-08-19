package com.kefi.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Capacitor bridge between the WebView and [PlayerController]. This task only wires up
 * enough to prove the JS -> Kotlin -> Media3 path works end to end: reading live state,
 * starting a track, and pause/resume/seek. Queueing, shuffle/repeat, listing and
 * permissions belong to later tasks (T9/T10) and are deliberately left unwrapped here.
 */
@CapacitorPlugin(name = "Player")
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
}
