package com.kefi.player

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the single [ExoPlayer] + [MediaSession] pair for the whole app. Media3's
 * [MediaSessionService] base class handles the foreground-service lifecycle, the
 * media-style notification (via [DefaultMediaNotificationProvider]) and media-button
 * routing; this class only builds the player/session and wires the notification
 * channel.
 *
 * [onTaskRemoved] IS overridden, contrary to the plan's original Must Have — see the
 * method for the device-reported bug that forced it. The override keeps upstream's decision
 * (keep running only while something is actually playing) and adds the one thing upstream
 * cannot do for us: dropping our own application-scoped controller binding, without which
 * `stopSelf()` can never take effect.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private companion object {
        /** See the [setForegroundServiceTimeoutMs] call in [onCreate]. */
        const val FOREGROUND_TIMEOUT_AFTER_PAUSE_MS = 30_000L
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

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

        val mediaSession = MediaSession.Builder(this, exoPlayer).build()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        session?.let { removeSession(it) }
        player?.release()
        player = null
        session?.release()
        session = null
        super.onDestroy()
    }
}
