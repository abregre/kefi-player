package com.kefi.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Owns the single [ExoPlayer] + [MediaSession] pair for the whole app. Media3's
 * [MediaLibraryService] base class handles the foreground-service lifecycle, the
 * media-style notification (via [DefaultMediaNotificationProvider]) and media-button
 * routing; this class builds the player/session, wires the notification channel, and owns
 * [stateObserver] — the app's only observer of playback that outlives the WebView.
 *
 * [MediaLibraryService] rather than the plainer `MediaSessionService` it started as. The two
 * are identical for everything that talks to the live session — notification, lock screen,
 * a Bluetooth headset's AVRCP buttons, all of which already worked — but only the library
 * variant publishes the `MediaBrowserService` a car front-end connects to. Honda RoadSync on
 * the bike showed the current track and had dead play/pause and next buttons purely because
 * there was no browser to send them to (reported 2026-09-19). [MediaBrowseTree] is the
 * callback that serves that side; the manifest carries the matching second intent-filter
 * action and the `com.google.android.gms.car.application` descriptor.
 *
 * [onTaskRemoved] IS overridden, contrary to the plan's original Must Have — see the
 * method for the device-reported bug that forced it. The override keeps upstream's decision
 * (keep running only while something is actually playing) and adds the one thing upstream
 * cannot do for us: dropping our own application-scoped controller binding, without which
 * `stopSelf()` can never take effect.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private companion object {
        /** See the [setForegroundServiceTimeoutMs] call in [onCreate]. */
        const val FOREGROUND_TIMEOUT_AFTER_PAUSE_MS = 30_000L

        /** How often [positionHeartbeat] rewrites the persisted position while playing. */
        const val POSITION_SAVE_INTERVAL_MS = 5_000L

        /**
         * Errors that mean the file itself is gone or unreadable for good. Anything NOT in
         * this set is treated as possibly transient and earns the failing item one retry
         * from where it stopped (see [onPlayerError]).
         */
        val PERMANENT_ERRORS = setOf("file_not_found", "permission_denied")
    }

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    /**
     * Android insert effects bind to one audio session, not to ExoPlayer permanently. The player
     * can replace that session underneath us, so the effects manager must be told about each new
     * id rather than leaving an equalizer/booster attached to a stale output.
     */
    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioEffectsManager.attach(applicationContext, audioSessionId)
        }
    }

    /**
     * The app's ONE durable observer of playback, attached to the [ExoPlayer] itself.
     *
     * It used to live on [PlayerController]'s app-side `MediaController` instead, and that was
     * wrong in a way that produced three separate device-reported bugs at once. That controller
     * is released the moment the Activity dies (`PlayerPlugin.handleOnDestroy`), by design —
     * it must be, or the bound service can never stop. So with the app closed and music still
     * playing (phone in a pocket, head unit in a car) NOTHING was watching the player:
     *
     *  - the home-screen widget kept rendering whatever [PlayerWidget.lastState] happened to
     *    hold when the app was last alive, so pressing next on a headset or car head unit left
     *    it stuck on the previous track;
     *  - [PlaybackStateStore] stopped being written, so the persisted queue/index/position went
     *    stale — and the next time the service was restarted cold (a car's play button waking
     *    it through `MediaButtonReceiver`) it restored that stale queue, which is why external
     *    next/previous appeared to walk a queue the user had already replaced in the app;
     *  - [onPlayerError] never ran, so a transient read failure part-way through a track just
     *    ended playback in silence with nothing to skip or retry it.
     *
     * Attached to the player, all three keep working with no WebView, no Activity and no
     * controller. [PlayerController]'s own listener still exists, but now does exactly one
     * thing: push snapshots to the JS bridge while a WebView is there to receive them.
     */
    private val stateObserver = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) {
                    // A track that actually reached "playing" has recovered; let the next
                    // failure have its own retry and its own skip budget.
                    consecutiveErrorCount = 0
                    retriedItemIndex = -1
                    startPositionHeartbeat()
                } else {
                    stopPositionHeartbeat()
                }
            }
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                persistState(player)
                publishWidgetState(player)
            } else if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                // The extractor reads a file's tags a beat AFTER playback starts, so a title
                // can arrive on its own. Nothing worth persisting changed — redraw only.
                publishWidgetState(player)
            }
        }

        /**
         * The per-track resume feature's one hook, and the reason it lives here rather than in
         * the WebView: this fires for a track started from the library, from the notification,
         * from the widget, or from a car's next button with the screen off and the app closed.
         * All four have to behave the same.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Whatever we had been sampling belongs to the OUTGOING track — write it before
            // the fields below start describing the incoming one.
            commitResumePoint()
            resumeSampleUri = mediaItem?.localConfiguration?.uri?.toString()
            resumeSamplePositionMs = 0L
            resumeSampleDurationMs = 0L
            applyResumePoint(mediaItem, reason)
        }

        /**
         * Logged, not acted on. `reason` is the single most useful line in a logcat capture of
         * "it just went quiet in the car": PLAYBACK_SUPPRESSION / AUDIO_FOCUS_LOSS /
         * AUDIO_BECOMING_NOISY / REMOTE each point at a completely different culprit, and none
         * of them is distinguishable after the fact from state alone.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.i("KefiApp", "playWhenReady=$playWhenReady reason=$reason")
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = player ?: return
            val index = current.currentMediaItemIndex
            val positionMs = current.currentPosition
            val failedUri = current.currentMediaItem?.localConfiguration?.uri?.toString()
            val reason = describeError(error)
            Log.w(
                "KefiApp",
                "Playback error: uri=$failedUri reason=$reason errorCode=${error.errorCode} " +
                    "errorCodeName=${error.errorCodeName} cause=${error.cause}",
            )

            // A transient read failure part-way through a track — a stalled MediaStore/SD read,
            // a decoder hiccup — surfaces here exactly like a permanently missing file. Give
            // the item ONE retry from where it stopped before writing it off: without this a
            // single hiccup ends playback outright whenever the failing track is the last (or
            // only) one in the queue, which is precisely the "a long song just stopped" report.
            if (reason !in PERMANENT_ERRORS && retriedItemIndex != index) {
                Log.i("KefiApp", "Retrying item $index at ${positionMs}ms after $reason")
                retriedItemIndex = index
                current.seekTo(index, positionMs)
                current.prepare()
                current.play()
                return
            }

            consecutiveErrorCount++
            if (consecutiveErrorCount < current.mediaItemCount && current.hasNextMediaItem()) {
                // Skip past the failing item and try the next one. The player is in
                // STATE_IDLE after an error, so seeking alone does not resume loading —
                // prepare()+play() are required to actually attempt the next item.
                current.seekToNextMediaItem()
                current.prepare()
                current.play()
            } else {
                // Either there is no next item, or every item in the queue has now failed
                // once since the last successful play (bounded by mediaItemCount) — stop
                // instead of looping forever (relevant under REPEAT_MODE_ALL, where
                // hasNextMediaItem() would otherwise keep wrapping around indefinitely).
                consecutiveErrorCount = 0
                current.stop()
            }

            PlayerController.onTrackUnavailable?.invoke(failedUri, reason)
            persistState(current)
            publishWidgetState(current)
        }
    }

    /**
     * Bounds the skip-on-error loop: incremented on every unrecoverable [onPlayerError], reset
     * the moment playback actually starts again. Guards against an all-broken queue looping
     * forever under `REPEAT_MODE_ALL`, where `hasNextMediaItem()` keeps reporting `true` by
     * wrapping back to index 0.
     */
    private var consecutiveErrorCount = 0

    /** Index of the item that has already used up its one transient-error retry, or -1. */
    private var retriedItemIndex = -1

    /**
     * Position-persistence heartbeat. Media3's `Player.Listener` never fires on a bare
     * position tick, so with no periodic component the persisted position would only ever be
     * as fresh as the last discrete transition — stale by however long a track has been
     * playing uninterrupted.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionHeartbeat = object : Runnable {
        override fun run() {
            player?.let { persistState(it) }
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
     * Writes queue/index/position/shuffle/repeat to [PlaybackStateStore]. A no-op before any
     * queue exists. `SharedPreferences.Editor.apply()` commits asynchronously, so this is safe
     * to call directly on the main thread.
     */
    private fun persistState(target: Player) {
        sampleResumePoint(target)
        val uris = target.queueUris()
        if (uris.isEmpty()) return
        PlaybackStateStore.save(
            context = applicationContext,
            uris = uris,
            index = target.currentMediaItemIndex,
            positionMs = target.currentPosition,
            shuffle = target.shuffleModeEnabled,
            repeatMode = target.repeatMode,
        )
    }

    /*
     * Per-track resume points (see [ResumePointStore]).
     *
     * These three fields are a rolling sample of the CURRENT track, not a second source of
     * truth. Writing straight from the live player at commit time does not work: by the moment
     * a transition is observable the player already reports the NEXT track's position (0), so a
     * naive "save where we are" would record a zero for the song that just ended and — worse —
     * wipe the resume point of the song about to start. Sampling continuously and committing
     * the last sample keeps each write attached to the track it was measured on.
     */
    private var resumeSampleUri: String? = null
    private var resumeSamplePositionMs = 0L
    private var resumeSampleDurationMs = 0L

    private fun sampleResumePoint(target: Player) {
        val uri = target.currentMediaItem?.localConfiguration?.uri?.toString()
        if (uri != resumeSampleUri) {
            // A transition we have not seen the callback for yet. Start a fresh sample rather
            // than attributing this position to the previous track.
            resumeSampleUri = uri
            resumeSamplePositionMs = 0L
            resumeSampleDurationMs = 0L
            return
        }
        resumeSamplePositionMs = target.currentPosition
        resumeSampleDurationMs = target.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    /**
     * Writes the last sample. A zero sample is skipped rather than stored: it means nothing has
     * been measured on this track yet, which is not the same as "the user is at the start", and
     * treating the two alike would erase a real resume point every time a track begins.
     */
    private fun commitResumePoint() {
        val uri = resumeSampleUri ?: return
        if (resumeSamplePositionMs <= 0L) return
        ResumePointStore.remember(
            context = applicationContext,
            uri = uri,
            positionMs = resumeSamplePositionMs,
            durationMs = resumeSampleDurationMs,
        )
    }

    /**
     * Seeks a freshly started track to where it was last left off.
     *
     * Two guards, both load-bearing. REPEAT is the player looping the SAME item under
     * `REPEAT_MODE_ONE` — the user asked for the track again, from the top, and jumping them
     * back to an old resume point would make repeat-one unusable. And a non-zero position means
     * someone has already decided where this track should start (a queue restored by
     * [restorePlaybackState] at its saved position, or the user seeking): a resume point must
     * never overrule a more specific intent.
     */
    private fun applyResumePoint(item: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        val current = player ?: return
        if (current.currentPosition > 1_000L) return
        val uri = item?.localConfiguration?.uri?.toString() ?: return
        val savedMs = ResumePointStore.positionFor(applicationContext, uri)
        if (savedMs <= 0L) return
        Log.i("KefiApp", "Resuming $uri at ${savedMs}ms")
        current.seekTo(savedMs)
    }

    /**
     * The home-screen widget follows the same state changes the WebView does, and for the same
     * reason: it is a second view of one player, not a second player. Cheap — the widget bails
     * immediately when none is placed.
     */
    private fun publishWidgetState(target: Player) {
        PlayerWidget.onPlayerState(applicationContext, target.playbackSnapshot())
    }

    /**
     * Media3 surfaces file-availability failures as a [PlaybackException] with a stable public
     * [PlaybackException.errorCode]; that is checked first. [Throwable.cause] is inspected as a
     * fallback in case a future Media3 version (or a different `DataSource`) throws the raw
     * exception without setting a matching `errorCode`.
     */
    private fun describeError(error: PlaybackException): String = when {
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.cause is FileNotFoundException -> "file_not_found"
        error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.cause is SecurityException -> "permission_denied"
        else -> error.errorCodeName
    }

    // T12: guards the interaction between the OS's null-intent auto-restart (see
    // onStartCommand below) and the async restore kicked off at the end of onCreate() — a
    // naive "stop if intent == null && queue is empty" check would race a restore that is
    // still in flight and stop the service before it ever gets to populate the queue. See
    // restorePlaybackState()'s doc comment and the T12 notepad entry.
    @Volatile private var restoreInProgress = false
    @Volatile private var sawNullIntentDuringRestore = false

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Local files only — playback never needs the network, so a WiFi/partial
            // wake lock (WAKE_MODE_NETWORK) would be pointless; WAKE_MODE_LOCAL keeps
            // the CPU awake for local decode/output without an unnecessary radio lock.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exoPlayer
        exoPlayer.addListener(audioSessionListener)
        exoPlayer.addListener(stateObserver)
        AudioEffectsManager.attach(applicationContext, exoPlayer.audioSessionId)

        val mediaSession = MediaLibrarySession.Builder(this, exoPlayer, MediaBrowseTree(this))
            // Gives a car front-end (and the notification) something to open when the user
            // taps the track title rather than a button. Without it the head unit's "open
            // app" affordance is dead, which reads as another broken control.
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        session = mediaSession
        // Without this, the base class never wires this session into its internal
        // notification/foreground-service manager — the player will happily play
        // audio, but the service never promotes itself to a real foreground
        // service and no notification ever appears.
        addSession(mediaSession)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("playback")
                .setChannelName(R.string.playback_channel_name)
                // 1001 is Media3's own DefaultMediaNotificationProvider default (confirmed
                // by decompiling media3-session-1.11.0); pinned here explicitly so it is
                // visible rather than implicit. DownloadService deliberately uses 2001,
                // NOT this id: notification ids are per-package, not per-service, and the
                // two foreground services can be running at the same time (music playing
                // while a download runs) — sharing an id would make one notification
                // silently replace the other in the shade.
                .setNotificationId(1001)
                .build()
        )

        // Media3 keeps the service in the FOREGROUND for this long after playback stops, and
        // a foreground service's notification carries FLAG_NO_CLEAR — it cannot be swiped
        // away. The default is 10 minutes (MediaSessionService.DEFAULT_FOREGROUND_SERVICE_-
        // TIMEOUT_MS = 600_000), which on a real device reads as "a notification that never
        // goes away and I can't dismiss by hand". 30s is long enough to cover pausing to take
        // a call, or scrubbing between tracks, and short enough that a user who stops
        // listening is not stuck with it.
        setForegroundServiceTimeoutMs(FOREGROUND_TIMEOUT_AFTER_PAUSE_MS)

        restorePlaybackState(exoPlayer)
    }

    /**
     * T12: restores the last-persisted queue/index/position/shuffle/repeat, paused. The
     * SharedPreferences read + per-uri resolvability check ([PlaybackStateStore.restore])
     * runs on [Dispatchers.IO] via [App.scope], so onCreate() (main thread) is never blocked
     * on disk/ContentResolver I/O; only the actual [ExoPlayer] mutation hops back to
     * [Dispatchers.Main] — touching a Media3 player off its owning thread throws. Never calls
     * `play()`: restoring into a playing state without a user gesture is exactly what the
     * plan forbids, and an API 34+ background foreground-service start would be refused
     * anyway.
     */
    private fun restorePlaybackState(exoPlayer: ExoPlayer) {
        restoreInProgress = true
        App.scope.launch {
            val restored = PlaybackStateStore.restore(applicationContext)
            withContext(Dispatchers.Main) {
                if (restored != null) {
                    exoPlayer.setMediaItems(restored.items, restored.index, restored.positionMs)
                    exoPlayer.shuffleModeEnabled = restored.shuffle
                    exoPlayer.repeatMode = restored.repeatMode
                    exoPlayer.prepare()
                    Log.i(
                        "KefiApp",
                        "PlaybackService restored ${restored.items.size} item(s) at " +
                            "index=${restored.index} positionMs=${restored.positionMs}, paused",
                    )
                }
                restoreInProgress = false
                // Mirrors the zombie-restart guard in onStartCommand below: if a null-intent
                // restart arrived WHILE this restore was in flight, finish that guard's
                // decision now instead of racing it (see onStartCommand).
                if (sawNullIntentDuringRestore && exoPlayer.mediaItemCount == 0) {
                    Log.i("KefiApp", "PlaybackService restored nothing to play, stopping cleanly")
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    /**
     * The user swiped the app away. Upstream's default is
     * `if (!isPlaybackOngoing() || !isAnySessionPlaying()) pauseAllPlayersAndStopSelf()`,
     * whose intent is right — keep playing music the user chose to keep playing, otherwise
     * shut down — but which cannot finish the job here, and upstream says so in its own
     * javadoc: *"the service can't be stopped until all media controllers have been
     * unbound"*.
     *
     * [PlayerController] holds exactly such a controller, application-scoped, for the whole
     * process lifetime (that is what lets playback survive Activity/WebView recreation).
     * So `stopSelf()` marked the service stoppable and nothing ever stopped it: the service,
     * its foreground state and its undismissable notification outlived the app. Reported from
     * a real device on 2026-08-20 as "I close the app and the thing never goes away, I can't
     * even remove it by hand".
     *
     * Releasing our binding first is the missing half. When something IS playing this does
     * nothing at all, which is the behaviour a music player must have.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.isPlaying == true) {
            Log.i("KefiApp", "PlaybackService task removed while playing, staying alive")
            return
        }
        Log.i("KefiApp", "PlaybackService task removed while not playing, stopping")
        PlayerController.release()
        pauseAllPlayersAndStopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null `intent` here is the documented signal that the OS is
        // auto-restarting us after our *process* (not just the separate WebView
        // renderer sandbox) previously crashed — e.g. an unhandled
        // onRenderProcessGone taking the whole app down before MainActivity's
        // recovery existed, or any other process-level kill. Confirmed on-device:
        // this restart path always arrives with `intent == null` (measured
        // `flags=0` here too — START_FLAG_RETRY is NOT set on this build, so it
        // is not a usable signal; `intent == null` is). A restart like this never
        // carries a reconnecting controller: onCreate() has already built a
        // brand new empty ExoPlayer and addSession() has already unconditionally
        // promoted us to a foreground service with a generic notification, even
        // though there is nothing queued and nobody left to play it. Left alone
        // this sits forever as a zombie "Now Playing" notification with
        // `state=NONE`. Stop cleanly instead — a real client (MainActivity →
        // PlayerController.ensureController()) will start us again properly,
        // with actual content, when it needs to.
        if (intent == null) {
            if (restoreInProgress) {
                // T12: the restore kicked off at the end of onCreate() (running
                // concurrently with this call) hasn't finished yet, so mediaItemCount == 0
                // right now doesn't mean "nothing to play" — it may just mean "not
                // restored yet". Defer the decision to restorePlaybackState()'s completion
                // instead of stopping prematurely and racing it.
                sawNullIntentDuringRestore = true
                return START_NOT_STICKY
            }
            if ((player?.mediaItemCount ?: 0) == 0) {
                Log.i("KefiApp", "PlaybackService restarted with nothing to play, stopping cleanly")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        stopPositionHeartbeat()
        player?.let { sampleResumePoint(it) }
        commitResumePoint()
        // One last write before the player goes away, so a service that is stopped mid-track
        // (paused, app closed) resumes from where it actually was rather than from the last
        // heartbeat up to 5s earlier.
        player?.let { persistState(it) }
        player?.removeListener(stateObserver)
        player?.removeListener(audioSessionListener)
        AudioEffectsManager.detach()
        session?.let { removeSession(it) }
        player?.release()
        player = null
        session?.release()
        session = null
        super.onDestroy()
    }
}
