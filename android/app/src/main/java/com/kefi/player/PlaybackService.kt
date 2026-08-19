package com.kefi.player

import android.content.Intent
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
