package com.kefi.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Holds the process in the foreground while downloads run. It does no work
 * itself — the download lives in [App.downloadScope] — it just stops Android
 * from killing us mid-conversion when the user leaves the app or the screen
 * sleeps.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Converting to MP3")
            .setContentText("Downloading and encoding in the background")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "downloads"

        // 2001, NOT ytmp3's 1001: PlaybackService pins its media notification to
        // 1001 explicitly (Media3's DefaultMediaNotificationProvider's own default,
        // see the .setNotificationId(1001) call in PlaybackService.onCreate()).
        // Notification ids are per-package, not per-service, and both foreground
        // services can be running at once (music playing while a download runs),
        // so reusing 1001 here would make the download notification and the media
        // notification fight over the same slot in the shade.
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, DownloadService::class.java)
            // A foreground service start can legitimately fail (background start
            // restrictions, notifications disabled). That should degrade the
            // download to a normal one, not crash it.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a video is being converted to MP3"
                    setShowBadge(false)
                }
            )
        }
    }
}
