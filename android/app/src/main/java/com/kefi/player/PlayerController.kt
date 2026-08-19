package com.kefi.player

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Application-scoped singleton wrapper around the single [MediaController] that talks to
 * [PlaybackService]'s [androidx.media3.session.MediaSession]. Lives on [App]'s companion
 * object (not an Activity or Plugin field) so playback control survives Activity/WebView
 * recreation (screen rotation, the WebView being reloaded, the user backing out of the app
 * while a track is still playing) exactly like [App.scope] and [PlaybackService] already do.
 *
 * IMPORTANT — first-touch rule: [ensureController] (and therefore every public method here)
 * must only ever be first called from a user-gesture-originated Capacitor plugin call (T7),
 * never from [App.onCreate] or any background callback/broadcast receiver. Connecting a
 * [MediaController] causes the framework to invoke [PlaybackService.onGetSession] and start
 * promoting that service toward foreground-capable state; on API 34+ doing this outside a
 * window Android considers a "user-initiated" context throws
 * `ForegroundServiceStartNotAllowedException`. T7's plugin methods are all invoked in
 * response to a UI action (tapping play, etc.), which satisfies that requirement — do not
 * add a call site anywhere else.
 *
 * All controller access happens on [Dispatchers.Main] — [MediaController] throws
 * `IllegalStateException` when touched off its owning thread. This object never creates its
 * own [kotlinx.coroutines.CoroutineScope]; suspend functions here are meant to be called from
 * [App.scope] (or any other caller's coroutine) and switch to the main dispatcher internally.
 */
object PlayerController {

    /**
     * Live playback snapshot. Field names are part of this task's public contract — T7
     * serialises this straight to JSON for the `getPlayerState` plugin call and the
     * `playerState` JS event, so these key names must be used verbatim (see
     * `.claude/notepads/kefi-player-full-android-player/learnings.md`, `## T6`).
     */
    data class Snapshot(
        val mediaId: String?,
        val uri: String?,
        val title: String?,
        val artist: String?,
        val currentPosition: Long,
        val duration: Long,
        val isPlaying: Boolean,
        val shuffleModeEnabled: Boolean,
        val repeatMode: Int,
        val queueLength: Int,
        val currentIndex: Int,
    )

    @Volatile
    private var controller: MediaController? = null

    private val initLock = Mutex()

    /**
     * Set by [PlayerPlugin][com.kefi.player] (T7) to receive push updates; invoked on
     * [Dispatchers.Main] every time the single [Player.Listener] below fires. T7 forwards
     * each call straight to Capacitor's `notifyListeners`.
     */
    var onState: ((Snapshot) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = broadcastState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = broadcastState()
        override fun onPlaybackStateChanged(playbackState: Int) = broadcastState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = broadcastState()
        override fun onRepeatModeChanged(repeatMode: Int) = broadcastState()

        override fun onPlayerError(error: PlaybackException) {
            // TODO(T11): skip-on-error logic (advance past the failing item). For now just
            // re-broadcast so observers see the resulting playback-state change.
            broadcastState()
        }
    }

    private fun broadcastState() {
        val current = controller ?: return
        onState?.invoke(current.toSnapshot())
    }

    /**
     * Double-checked, mutex-guarded async connect, mirroring the shape of
     * `com.ytmp3.app.App.ensureNativeToolsReady()`. Safe to call concurrently; only the
     * first caller actually builds the [MediaController], everyone else awaits the same
     * result. The whole body runs on [Dispatchers.Main] because [MediaController.Builder]
     * and the resulting controller must be built/used from the thread that owns them.
     */
    private suspend fun ensureController(): MediaController {
        controller?.let { return it }
        return initLock.withLock {
            controller?.let { return@withLock it }
            withContext(Dispatchers.Main) {
                val future = MediaController.Builder(
                    App.instance,
                    SessionToken(App.instance, ComponentName(App.instance, PlaybackService::class.java)),
                ).buildAsync()
                val connected = suspendCancellableCoroutine { cont ->
                    future.addListener(
                        {
                            try {
                                cont.resume(future.get())
                            } catch (t: Throwable) {
                                cont.resumeWithException(t)
                            }
                        },
                        ContextCompat.getMainExecutor(App.instance),
                    )
                    cont.invokeOnCancellation { MediaController.releaseFuture(future) }
                }
                connected.addListener(listener)
                controller = connected
                connected
            }
        }
    }

    private suspend fun <T> withController(block: (MediaController) -> T): T {
        val ctrl = ensureController()
        return withContext(Dispatchers.Main) { block(ctrl) }
    }

    private fun MediaController.toSnapshot(): Snapshot {
        val item = currentMediaItem
        val rawDuration = duration
        return Snapshot(
            mediaId = item?.mediaId?.takeIf { it.isNotEmpty() },
            uri = item?.localConfiguration?.uri?.toString(),
            title = item?.mediaMetadata?.title?.toString(),
            artist = item?.mediaMetadata?.artist?.toString(),
            currentPosition = currentPosition,
            duration = if (rawDuration == C.TIME_UNSET) 0L else rawDuration,
            isPlaying = isPlaying,
            shuffleModeEnabled = shuffleModeEnabled,
            repeatMode = repeatMode,
            queueLength = mediaItemCount,
            currentIndex = currentMediaItemIndex,
        )
    }

    /** Live snapshot of the controller's current state — never a cached value. */
    suspend fun snapshot(): Snapshot = withController { it.toSnapshot() }

    /** Replaces the queue with a single [item] and starts playback immediately. */
    suspend fun play(item: MediaItem): Unit = withController {
        it.setMediaItem(item)
        it.prepare()
        it.play()
    }

    suspend fun pause(): Unit = withController { it.pause() }

    /** Resumes a paused/stopped controller without touching the current queue or position. */
    suspend fun resume(): Unit = withController { it.play() }

    suspend fun seek(positionMs: Long): Unit = withController { it.seekTo(positionMs) }

    suspend fun next(): Unit = withController { it.seekToNext() }

    suspend fun previous(): Unit = withController { it.seekToPrevious() }

    /** Replaces the whole queue and starts playback at [startIndex]. */
    suspend fun setQueue(items: List<MediaItem>, startIndex: Int = 0): Unit = withController {
        it.setMediaItems(items, startIndex, C.TIME_UNSET)
        it.prepare()
        it.play()
    }

    suspend fun setShuffle(enabled: Boolean): Unit = withController { it.setShuffleModeEnabled(enabled) }

    suspend fun setRepeatMode(repeatMode: Int): Unit = withController { it.setRepeatMode(repeatMode) }
}
