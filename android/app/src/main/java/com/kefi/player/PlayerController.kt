package com.kefi.player

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    /** One live queue row, kept deliberately smaller than the library's Track contract. */
    data class QueueEntry(
        val uri: String,
        val title: String,
        val artist: String?,
    )

    /**
     * One coherent read of the native queue and its currently playing position.
     *
     * The timeline can change between separate controller calls, so callers which render the
     * queue must use this rather than combining a queue read and [snapshot].
     */
    data class QueueSnapshot(
        val entries: List<QueueEntry>,
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
     * invoked on [Dispatchers.Main] by `PlaybackService`'s player listener (which owns error
     * recovery — it is the only observer that survives the app being closed), once per failed
     * item, with the failing item's `uri` (nullable — the item may have no `localConfiguration`
     * in edge cases) and a short `reason` code. T7 forwards each call straight to Capacitor's
     * `notifyListeners("trackUnavailable", ...)`. This is intentionally separate from
     * [onState]/[Snapshot] — the frozen 11-key snapshot contract is not touched by this event.
     */
    var onTrackUnavailable: ((uri: String?, reason: String) -> Unit)? = null

    /**
     * Last `isPlaying` value seen by [listener] (or by a snapshot). Readable without a
     * controller and without touching the main thread, which is exactly what a teardown path
     * needs: [PlayerPlugin.handleOnDestroy] has to decide whether the service may be stopped
     * at a moment when it is also about to drop the controller.
     */
    @Volatile
    var lastKnownIsPlaying: Boolean = false
        private set

    /** [release] posts to the main thread; [MediaController] throws if touched off it. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The JS bridge's view of playback, and NOTHING else.
     *
     * Persistence, the home-screen widget and skip/retry-on-error all used to hang off this
     * listener too. They no longer do — they live on [PlaybackService]'s own player listener,
     * because this controller is released the moment the Activity dies and all three must keep
     * working with the app closed (see `PlaybackService.stateObserver` for the three
     * device-reported bugs that came from getting this wrong). What is left here is genuinely
     * WebView-only: with no WebView there is nobody to notify.
     */
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_PLAYER_ERROR,
                )
            ) {
                broadcastState()
            }
        }
    }

    /**
     * Pushes a fresh snapshot to the WebView. The widget is deliberately NOT redrawn from
     * here: it is driven by [PlaybackService], which is the only observer that outlives the
     * app, and having two writers would make it flicker between two views of the same player.
     */
    private fun broadcastState() {
        val current = controller ?: return
        val snapshot = current.playbackSnapshot()
        lastKnownIsPlaying = snapshot.isPlaying
        onState?.invoke(snapshot)
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

    /**
     * Unbinds from [PlaybackService] and drops the controller. The next call to any public
     * method transparently reconnects, so this is safe to call whenever the UI goes away.
     *
     * Why this has to exist: a bound service cannot be destroyed. `stopSelf()` — including
     * the one inside Media3's own `pauseAllPlayersAndStopSelf()` — only marks the service as
     * stoppable; Android destroys it when the LAST client unbinds. This controller is
     * application-scoped on purpose (it must outlive Activity/WebView recreation, T6/T8), so
     * without an explicit release it stays bound for the whole process lifetime and the
     * service, its foreground state and its notification survive the app being closed — the
     * "notification I can't get rid of" reported from a real device on 2026-08-20.
     *
     * Posted to the main thread rather than run inline: [MediaController] throws if touched
     * off its owning thread, and callers here are Activity/Service lifecycle callbacks.
     */
    fun release() {
        mainHandler.post {
            controller?.let { existing ->
                existing.removeListener(listener)
                existing.release()
            }
            controller = null
        }
    }

    private suspend fun <T> withController(block: (MediaController) -> T): T {
        val ctrl = ensureController()
        return withContext(Dispatchers.Main) { block(ctrl) }
    }

    /** Live snapshot of the controller's current state — never a cached value. */
    suspend fun snapshot(): Snapshot = withController {
        it.playbackSnapshot().also { snap -> lastKnownIsPlaying = snap.isPlaying }
    }

    /** Reads the queue and current position atomically on the controller's owning thread. */
    suspend fun getQueue(): QueueSnapshot = withController { ctrl ->
        QueueSnapshot(
            entries = (0 until ctrl.mediaItemCount).map { index ->
                val item = ctrl.getMediaItemAt(index)
                QueueEntry(
                    uri = item.localConfiguration?.uri?.toString().orEmpty(),
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString(),
                )
            },
            currentIndex = ctrl.currentMediaItemIndex,
        )
    }

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

    /** Incremental queue edits deliberately never call setMediaItems/setQueue. */
    suspend fun moveQueueItem(from: Int, to: Int): Unit = withController { ctrl ->
        check(from > ctrl.currentMediaItemIndex && to > ctrl.currentMediaItemIndex) {
            "Only upcoming queue items may be moved"
        }
        ctrl.moveMediaItem(from, to)
    }

    suspend fun removeQueueItem(index: Int): Unit = withController { ctrl ->
        check(index > ctrl.currentMediaItemIndex) { "Only upcoming queue items may be removed" }
        ctrl.removeMediaItem(index)
    }

    suspend fun jumpToQueueIndex(index: Int): Unit = withController {
        it.seekTo(index, 0L)
        it.play()
    }

    suspend fun addToQueue(item: MediaItem): Unit = withController { it.addMediaItem(item) }

    suspend fun playNext(item: MediaItem): Unit = withController { ctrl ->
        check(ctrl.currentMediaItemIndex in 0 until ctrl.mediaItemCount) {
            "Cannot play next without an existing queue item; start playback or set a queue first"
        }
        ctrl.addMediaItem(ctrl.currentMediaItemIndex + 1, item)
    }

    suspend fun setShuffle(enabled: Boolean): Unit = withController { it.setShuffleModeEnabled(enabled) }

    suspend fun setRepeatMode(repeatMode: Int): Unit = withController { it.setRepeatMode(repeatMode) }
}
