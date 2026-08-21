package com.kefi.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Owns sound-processing effects bound to PlaybackService's current ExoPlayer audio session.
 *
 * Equalizer and LoudnessEnhancer are insert effects: Android requires an app to create each one
 * for the specific AudioTrack/MediaPlayer session it should process. They must never attach to
 * session 0 (the deprecated global mix), and their instances must be discarded whenever ExoPlayer
 * changes sessions. This singleton has no route back to PlayerController, so opening Settings can
 * save preferences but cannot create a controller or start foreground playback.
 */
object AudioEffectsManager {

    private const val TAG = "KefiApp"

    private var context: Context? = null
    private var attachedSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    data class Band(
        val index: Int,
        val lowerFrequencyMilliHz: Int,
        val upperFrequencyMilliHz: Int,
        val levelMb: Int,
    )

    data class Preset(val id: Int, val name: String)

    data class Snapshot(
        val settings: SoundEffectsStore.Settings,
        val equalizerReady: Boolean,
        val equalizerLevelMinMb: Int?,
        val equalizerLevelMaxMb: Int?,
        val equalizerBands: List<Band>,
        val equalizerPresets: List<Preset>,
        val volumeBoosterReady: Boolean,
    )

    /**
     * Effects can only report their real bands/presets after an ExoPlayer audio session exists.
     * Until then callers receive persisted settings with `*Ready == false`; they should invite
     * the user to start a track rather than guessing device capabilities.
     */
    fun snapshot(applicationContext: Context): Snapshot {
        val settings = SoundEffectsStore.load(applicationContext)
        val effect = equalizer
        if (effect == null) {
            return Snapshot(settings, false, null, null, emptyList(), emptyList(), loudnessEnhancer != null)
        }
        return try {
            val levelRange = effect.bandLevelRange
            val bands = (0 until effect.numberOfBands.toInt()).map { index ->
                val frequencyRange = effect.getBandFreqRange(index.toShort())
                Band(
                    index = index,
                    lowerFrequencyMilliHz = frequencyRange[0],
                    upperFrequencyMilliHz = frequencyRange[1],
                    levelMb = effect.getBandLevel(index.toShort()).toInt(),
                )
            }
            val presets = (0 until effect.numberOfPresets.toInt()).map { index ->
                Preset(index, effect.getPresetName(index.toShort()))
            }
            Snapshot(
                settings = settings,
                equalizerReady = true,
                equalizerLevelMinMb = levelRange[0].toInt(),
                equalizerLevelMaxMb = levelRange[1].toInt(),
                equalizerBands = bands,
                equalizerPresets = presets,
                volumeBoosterReady = loudnessEnhancer != null,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read equalizer capabilities", error)
            Snapshot(settings, false, null, null, emptyList(), emptyList(), loudnessEnhancer != null)
        }
    }

    /**
     * Attaches effects to [audioSessionId], or releases them for an unset/non-positive id.
     * Called only by PlaybackService, on its main thread.
     */
    fun attach(applicationContext: Context, audioSessionId: Int) {
        context = applicationContext.applicationContext
        if (audioSessionId <= 0) {
            releaseEffects()
            attachedSessionId = null
            return
        }
        if (attachedSessionId == audioSessionId) {
            applyPersistedSettings()
            return
        }

        releaseEffects()
        attachedSessionId = audioSessionId

        equalizer = try {
            Equalizer(/* priority = */ 0, audioSessionId)
        } catch (error: Throwable) {
            Log.i(TAG, "Equalizer unavailable for audio session $audioSessionId", error)
            null
        }
        loudnessEnhancer = try {
            LoudnessEnhancer(audioSessionId)
        } catch (error: Throwable) {
            Log.i(TAG, "Loudness enhancer unavailable for audio session $audioSessionId", error)
            null
        }
        applyPersistedSettings()
    }

    /** Called by PlaybackService before it releases ExoPlayer. Safe to call repeatedly. */
    fun detach() {
        releaseEffects()
        attachedSessionId = null
        context = null
    }

    fun setEqualizerEnabled(applicationContext: Context, enabled: Boolean) {
        val appContext = applicationContext.applicationContext
        context = appContext
        SoundEffectsStore.saveEqualizerEnabled(appContext, enabled)
        applyPersistedSettings()
    }

    fun setEqualizerPreset(applicationContext: Context, preset: Int) {
        val appContext = applicationContext.applicationContext
        context = appContext
        SoundEffectsStore.saveEqualizerPreset(appContext, preset)
        applyPersistedSettings()
    }

    fun setEqualizerBandLevels(applicationContext: Context, levels: List<Int>) {
        val appContext = applicationContext.applicationContext
        context = appContext
        SoundEffectsStore.saveEqualizerBandLevels(appContext, levels)
        applyPersistedSettings()
    }

    fun setVolumeBoosterEnabled(applicationContext: Context, enabled: Boolean) {
        val appContext = applicationContext.applicationContext
        context = appContext
        SoundEffectsStore.saveVolumeBoosterEnabled(appContext, enabled)
        applyPersistedSettings()
    }

    fun setVolumeBoosterGain(applicationContext: Context, gainMb: Int) {
        val appContext = applicationContext.applicationContext
        context = appContext
        SoundEffectsStore.saveVolumeBoosterGain(appContext, gainMb)
        applyPersistedSettings()
    }

    private fun applyPersistedSettings() {
        val appContext = context ?: return
        val settings = SoundEffectsStore.load(appContext)

        equalizer?.let { effect ->
            try {
                // Disable before programming to avoid an audible intermediate state while bands
                // or a preset are being replaced.
                effect.enabled = false
                if (settings.equalizerPreset != SoundEffectsStore.CUSTOM_PRESET) {
                    effect.usePreset(settings.equalizerPreset.toShort())
                } else {
                    val range = effect.bandLevelRange
                    val min = range[0].toInt()
                    val max = range[1].toInt()
                    val bands = effect.numberOfBands.toInt()
                    settings.equalizerBandLevels.take(bands).forEachIndexed { index, level ->
                        effect.setBandLevel(index.toShort(), level.coerceIn(min, max).toShort())
                    }
                }
                effect.enabled = settings.equalizerEnabled
            } catch (error: Throwable) {
                Log.w(TAG, "Could not apply equalizer settings", error)
                runCatching { effect.enabled = false }
            }
        }

        loudnessEnhancer?.let { effect ->
            try {
                effect.enabled = false
                effect.setTargetGain(settings.volumeBoosterGainMb)
                effect.enabled = settings.volumeBoosterEnabled
            } catch (error: Throwable) {
                Log.w(TAG, "Could not apply volume booster settings", error)
                runCatching { effect.enabled = false }
            }
        }
    }

    private fun releaseEffects() {
        equalizer?.let { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
        loudnessEnhancer?.let { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
        equalizer = null
        loudnessEnhancer = null
    }
}
