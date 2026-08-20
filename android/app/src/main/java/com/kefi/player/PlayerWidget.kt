package com.kefi.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home-screen widget: current track, artwork, and previous / play-pause / next.
 *
 * It is a VIEW of the one player, never a second one. Nothing here owns playback state; it
 * renders whatever [PlayerController] last broadcast, falls back to [PlaybackStateStore]
 * when the process starts cold (a reboot, or after the service stopped), and sends every
 * command back through the same paths the notification uses.
 */
class PlayerWidget : AppWidgetProvider() {

    /** Sent when a widget is added or resized, on locale/theme changes, and after a reboot. */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NEXT, ACTION_PREVIOUS -> transport(context, intent.action!!)
        }
    }

    /**
     * Next/previous cannot go through [MediaButtonReceiver]: since API 26 it deliberately
     * ignores every key that is not play/play-pause, because a service started for a
     * non-play command would never promote itself to the foreground and the system would
     * kill it with `ForegroundServiceDidNotStartInTimeException`. So these two go straight
     * to [PlayerController], which BINDS to the session rather than starting a foreground
     * service — safe from a widget tap in any state, and a no-op worth nothing when there
     * is no queue to move through.
     */
    private fun transport(context: Context, action: String) {
        App.scope.launch {
            try {
                if (action == ACTION_NEXT) PlayerController.next() else PlayerController.previous()
            } catch (t: Throwable) {
                Log.w("KefiApp", "widget $action failed", t)
            }
        }
    }

    companion object {
        private const val ACTION_NEXT = "com.kefi.player.widget.NEXT"
        private const val ACTION_PREVIOUS = "com.kefi.player.widget.PREVIOUS"

        /** Last state pushed by the controller, so a redraw never has to ask the player. */
        @Volatile
        private var lastState: WidgetState? = null

        /** One-entry artwork cache: the widget only ever shows the current track. */
        @Volatile
        private var artUri: String? = null

        @Volatile
        private var art: Bitmap? = null

        private data class WidgetState(
            val title: String?,
            val artist: String?,
            val uri: String?,
            val isPlaying: Boolean,
        )

        /** Called from [PlayerController.broadcastState] on every real state change. */
        fun onPlayerState(context: Context, snapshot: PlayerController.Snapshot) {
            lastState = WidgetState(
                title = snapshot.title,
                artist = snapshot.artist,
                uri = snapshot.uri,
                isPlaying = snapshot.isPlaying,
            )
            refresh(context)
        }

        /** Redraws every placed widget from the last known state. Safe to call anywhere. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            // Nobody has placed the widget: not a single bitmap gets decoded.
            if (ids.isEmpty()) return

            App.scope.launch {
                val state = lastState ?: coldState(context)
                val bitmap = artworkFor(context, state?.uri)
                withContext(Dispatchers.Main) {
                    manager.updateAppWidget(ids, render(context, state, bitmap))
                }
            }
        }

        /**
         * What to show when this process has never seen the player: the last persisted track,
         * paused. Reuses [TrackSource.mediaItemsFor], so the widget's title/artist come from
         * exactly the same MediaStore lookup the queue itself uses.
         */
        private suspend fun coldState(context: Context): WidgetState? {
            val uri = PlaybackStateStore.peekCurrentUri(context) ?: return null
            val metadata = TrackSource.mediaItemsFor(context, listOf(uri)).firstOrNull()?.mediaMetadata
            return WidgetState(
                title = metadata?.title?.toString(),
                artist = metadata?.artist?.toString(),
                uri = uri,
                isPlaying = false,
            )
        }

        private suspend fun artworkFor(context: Context, uri: String?): Bitmap? {
            if (uri == null) return null
            if (uri == artUri) return art
            val bitmap = TrackSource.artworkBitmap(context, uri)
            artUri = uri
            art = bitmap
            return bitmap
        }

        private fun render(context: Context, state: WidgetState?, bitmap: Bitmap?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.player_widget)

            views.setTextViewText(
                R.id.widget_title,
                state?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_idle),
            )
            views.setTextViewText(R.id.widget_artist, state?.artist.orEmpty())
            views.setViewVisibility(
                R.id.widget_artist,
                if (state?.artist.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE,
            )

            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_art, bitmap)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_disc)
            }

            val playing = state?.isPlaying == true
            views.setImageViewResource(
                R.id.widget_toggle,
                if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(if (playing) R.string.widget_pause else R.string.widget_play),
            )

            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            // Play/pause is a real media button: it works when the session is alive AND when
            // nothing is running at all, because MediaButtonReceiver is allowed to start the
            // service for a play command (that is the one exemption the platform grants).
            views.setOnClickPendingIntent(
                R.id.widget_toggle,
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, REQUEST_TOGGLE),
            )
            views.setOnClickPendingIntent(R.id.widget_previous, command(context, ACTION_PREVIOUS, REQUEST_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_next, command(context, ACTION_NEXT, REQUEST_NEXT))
            return views
        }

        private const val REQUEST_OPEN = 1
        private const val REQUEST_TOGGLE = 2
        private const val REQUEST_NEXT = 3
        private const val REQUEST_PREVIOUS = 4

        private fun openApp(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun mediaButton(context: Context, keyCode: Int, requestCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, MediaButtonReceiver::class.java)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun command(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, PlayerWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
