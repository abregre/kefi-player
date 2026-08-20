package com.kefi.player

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Application-scoped singleton. Playback (PlaybackService / PlayerController, added in
 * T5/T6) must keep running and keep its state across Activity/WebView recreation
 * (screen rotation, the WebView being reloaded, the user backing out of the app while
 * a track is still playing), so its coroutine work is tied to this class' lifetime
 * rather than any single Activity's.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("KefiApp", "KefiApp onCreate")
    }

    companion object {
        lateinit var instance: App
            private set

        /** Outlives Activity/WebView recreation; playback must not be cancelled by either. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Separate from [scope] on purpose, even though both are just
         * `SupervisorJob() + Dispatchers.IO`: a `SupervisorJob` isolates individual child
         * failures, but it does not isolate a `scope.cancel()` blast radius. Downloads need
         * a "cancel everything downloading" operation that can never reach into playback's
         * jobs, and playback must never be able to cancel an in-flight download either.
         * Two scopes keep those blast radii apart.
         */
        val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val initLock = Mutex()

        @Volatile
        var isReady: Boolean = false
            private set

        /**
         * Unpacks the bundled Python runtime, yt-dlp and ffmpeg out of the APK.
         * The first call costs a few seconds and ~40MB of internal storage, so it
         * stays off the main thread; later calls are free. Safe to call concurrently.
         *
         * Deliberately NOT called from onCreate() or anywhere else in this file: it must
         * stay lazy so a user who never unlocks the hidden downloader tab never pays the
         * unpack cost. Only the downloader plugin calls this.
         */
        suspend fun ensureNativeToolsReady() {
            if (isReady) return
            initLock.withLock {
                if (isReady) return
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(instance)
                    FFmpeg.getInstance().init(instance)
                }
                isReady = true
            }
        }
    }
}
