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

/**
 * Owns the single [ExoPlayer] + [MediaSession] pair for the whole app. Media3's
 * [MediaSessionService] base class handles the foreground-service lifecycle, the
 * media-style notification (via [DefaultMediaNotificationProvider]) and media-button
 * routing; this class only builds the player/session and wires the notification
 * channel.
 *
 * Deliberately does NOT override [onTaskRemoved]: MediaSessionService's base
 * implementation already does
 * `if (!isPlaybackOngoing() || !isAnySessionPlaying()) pauseAllPlayersAndStopSelf();`,
 * which is the correct behaviour (stop when nothing is playing, keep running in the
 * background when something is). Re-implementing this would only reproduce upstream
 * logic, worse. Do not "fix" this by adding one back.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        if (intent == null && (player?.mediaItemCount ?: 0) == 0) {
            Log.i("KefiApp", "PlaybackService restarted with nothing to play, stopping cleanly")
            stopSelf()
            return START_NOT_STICKY
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
