package com.kefi.player

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    }
}
