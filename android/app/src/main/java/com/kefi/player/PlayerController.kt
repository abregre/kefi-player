package com.kefi.player

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import java.io.FileNotFoundException
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

    /**
     * Set by [PlayerPlugin][com.kefi.player] (T11) to receive skip-on-error notifications;
     * invoked on [Dispatchers.Main] from [onPlayerError] below, once per failed item, with the
     * failing item's `uri` (nullable — the item may have no `localConfiguration` in edge
     * cases) and a short `reason` code. T7 forwards each call straight to Capacitor's
     * `notifyListeners("trackUnavailable", ...)`. This is intentionally separate from
     * [onState]/[Snapshot] — the frozen 11-key snapshot contract is not touched by this event.
     */
    var onTrackUnavailable: ((uri: String?, reason: String) -> Unit)? = null

    /**
     * Bounds the skip-on-error loop (T11): incremented on every [onPlayerError], reset to 0
     * the moment playback actually starts again ([onIsPlayingChanged] fires `true`). Guards
     * against an all-broken queue looping forever — this would otherwise be a real risk under
     * `Player.REPEAT_MODE_ALL`, where [MediaController.hasNextMediaItem] keeps reporting
     * `true` by wrapping back to index 0 forever. Once this count reaches the queue length
     * (every item has now failed once since the last success), the controller stops instead
     * of attempting yet another skip.
     */
    @Volatile
    private var consecutiveErrorCount = 0

    /**
     * T12: throttled position-persistence heartbeat. Media3's `Player.Listener` never fires
     * on a bare position tick (confirmed empirically in T8 — see the notepad's T8 entry, and
     * confirmed again by decompiling `MediaControllerImplBase` for T12: the periodic
     * position refresh Media3 already runs internally, `notifyPeriodicSessionPositionInfoChanged`,
     * only updates the controller's own cached position silently and never invokes
     * `Player.Listener`), so with zero periodic component the persisted position would only
     * ever be as fresh as the last discrete transition — stale by however long a track has
     * been playing uninterrupted. [startPositionHeartbeat]/[stopPositionHeartbeat] are
     * started/stopped directly from [onIsPlayingChanged] below (an existing callback, not a
     * new observer), and [POSITION_SAVE_INTERVAL_MS] is the same 5s figure the task caps
     * position-write frequency at — this is that cap, not an additional unbounded loop.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionHeartbeat = object : Runnable {
        override fun run() {
            persistState()
            mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    private fun startPositionHeartbeat() {
        mainHandler.removeCallbacks(positionHeartbeat)
        mainHandler.postDelayed(positionHeartbeat, POSITION_SAVE_INTERVAL_MS)
    }

    private fun stopPositionHeartbeat() {
        mainHandler.removeCallbacks(positionHeartbeat)
    }

    /**
     * T12: writes queue/index/position/shuffle/repeat to [PlaybackStateStore]. Called from
     * every [listener] branch that already calls [broadcastState] (so persisted state never
     * lags the JS-facing snapshot) plus [positionHeartbeat] above. A no-op before any queue
     * exists (nothing to persist yet). `SharedPreferences.Editor.apply()` commits
     * asynchronously, so this is safe to call directly on the main thread.
     */
    private fun persistState() {
        val ctrl = controller ?: return
        val uris = (0 until ctrl.mediaItemCount).mapNotNull {
            ctrl.getMediaItemAt(it).localConfiguration?.uri?.toString()
        }
        if (uris.isEmpty()) return
        PlaybackStateStore.save(
            context = App.instance,
            uris = uris,
            index = ctrl.currentMediaItemIndex,
            positionMs = ctrl.currentPosition,
            shuffle = ctrl.shuffleModeEnabled,
            repeatMode = ctrl.repeatMode,
        )
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrorCount = 0
            if (isPlaying) startPositionHeartbeat() else stopPositionHeartbeat()
            persistState()
            broadcastState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persistState()
            broadcastState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            persistState()
            broadcastState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            persistState()
            broadcastState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            persistState()
            broadcastState()
        }

        override fun onPlayerError(error: PlaybackException) {
            val ctrl = controller ?: return
            val failedUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
            val reason = describeError(error)
            Log.w(
                "KefiApp",
                "Track unavailable: uri=$failedUri reason=$reason errorCode=${error.errorCode} " +
                    "errorCodeName=${error.errorCodeName} cause=${error.cause}",
            )

            consecutiveErrorCount++
            if (consecutiveErrorCount < ctrl.mediaItemCount && ctrl.hasNextMediaItem()) {
                // Skip past the failing item and try the next one. The player is in
                // STATE_IDLE after an error, so seeking alone does not resume loading —
                // prepare()+play() are required to actually attempt the next item.
                ctrl.seekToNextMediaItem()
                ctrl.prepare()
                ctrl.play()
            } else {
                // Either there is no next item, or every item in the queue has now failed
                // once since the last successful play (bounded by mediaItemCount) — stop
                // instead of looping forever (relevant under REPEAT_MODE_ALL, where
                // hasNextMediaItem() would otherwise keep wrapping around indefinitely).
                consecutiveErrorCount = 0
                ctrl.stop()
            }

            onTrackUnavailable?.invoke(failedUri, reason)
            persistState()
            broadcastState()
        }
    }

    /** T12: position-persistence heartbeat cap (see [positionHeartbeat] above). */
    private const val POSITION_SAVE_INTERVAL_MS = 5_000L

    /**
     * Media3 surfaces file-availability failures as a [PlaybackException] with a stable
     * public [PlaybackException.errorCode]; that is checked first. [Throwable.cause] is
     * inspected as a fallback in case a future Media3 version (or a different `DataSource`)
     * throws the raw exception without setting a matching `errorCode`.
     */
    private fun describeError(error: PlaybackException): String = when {
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.cause is FileNotFoundException -> "file_not_found"
        error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.cause is SecurityException -> "permission_denied"
        else -> error.errorCodeName
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
