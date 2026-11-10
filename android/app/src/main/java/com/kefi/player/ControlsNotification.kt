package com.kefi.player

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

object ControlsNotification {

    private const val PREFS_NAME = "kefi.player"
    private const val KEY_ENABLED = "playback.controlsNotification"
    private const val CHANNEL_ID = "controls"
    private const val NOTIFICATION_ID = 3001

    private const val REQUEST_TOGGLE = 12
    private const val REQUEST_NEXT = 13
    private const val REQUEST_PREVIOUS = 14

    private data class State(
        val title: String?,
        val artist: String?,
        val uri: String?,
        val isPlaying: Boolean,
    )

    @Volatile
    private var lastState: State? = null

    @Volatile
    private var artUri: String? = null

    @Volatile
    private var art: Bitmap? = null

    private val renderSequence = AtomicLong(0)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) refresh(context) else cancel(context)
    }

    fun onPlayerState(context: Context, snapshot: PlayerController.Snapshot) {
        lastState = if (snapshot.uri == null) {
            null
        } else {
            State(snapshot.title, snapshot.artist, snapshot.uri, snapshot.isPlaying)
        }
        refresh(context)
    }

    fun cancel(context: Context) {
        renderSequence.incrementAndGet()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun onServiceDestroyed(context: Context) {
        lastState = null
        cancel(context)
    }

    private fun refresh(context: Context) {
        val state = lastState
        if (!isEnabled(context) || !canPost(context) || state == null) {
            cancel(context)
            return
        }
        val app = context.applicationContext
        val ticket = renderSequence.incrementAndGet()
        App.scope.launch {
            val bitmap = artworkFor(app, state.uri)
            withContext(Dispatchers.Main) {
                if (ticket != renderSequence.get()) return@withContext
                post(app, state, bitmap)
            }
        }
    }

    private suspend fun artworkFor(context: Context, uri: String?): Bitmap? {
        if (uri == null) return null
        if (uri == artUri) return art
        val bitmap = TrackSource.artworkBitmap(context, uri)
        artUri = uri
        art = bitmap
        return bitmap
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.controls_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun post(context: Context, state: State, bitmap: Bitmap?) {
        if (!canPost(context)) return
        ensureChannel(context)
        val title = state.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_idle)
        val small = views(context, R.layout.notification_controls_small, state, title, null)
        val big = views(context, R.layout.notification_controls, state, title, bitmap)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_play)
            .setContentTitle(title)
            .setContentText(state.artist.orEmpty())
            .setLargeIcon(bitmap)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(PlayerWidget.openApp(context))
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun views(
        context: Context,
        layout: Int,
        state: State,
        title: String,
        bitmap: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.controls_title, title)
        views.setTextViewText(R.id.controls_artist, state.artist.orEmpty())
        views.setViewVisibility(
            R.id.controls_artist,
            if (state.artist.isNullOrBlank()) View.GONE else View.VISIBLE,
        )
        if (layout == R.layout.notification_controls) {
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.controls_art, bitmap)
            } else {
                views.setImageViewResource(R.id.controls_art, R.drawable.ic_widget_disc)
            }
        }
        views.setImageViewResource(
            R.id.controls_toggle,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setContentDescription(
            R.id.controls_toggle,
            context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
        )
        views.setOnClickPendingIntent(
            R.id.controls_toggle,
            PlayerWidget.mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, REQUEST_TOGGLE),
        )
        views.setOnClickPendingIntent(
            R.id.controls_previous,
            PlayerWidget.command(context, PlayerWidget.ACTION_PREVIOUS, REQUEST_PREVIOUS),
        )
        views.setOnClickPendingIntent(
            R.id.controls_next,
            PlayerWidget.command(context, PlayerWidget.ACTION_NEXT, REQUEST_NEXT),
        )
        return views
    }
}
