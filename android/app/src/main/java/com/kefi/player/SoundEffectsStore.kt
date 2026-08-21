package com.kefi.player

import android.content.Context
import org.json.JSONArray

/**
 * Persistent, native-owned preferences for playback sound processing.
 *
 * Keeping these outside the WebView means a user can change a setting while playback is alive,
 * lose the WebView, and still have the same effects re-applied when the player gets a new audio
 * session. This deliberately shares the app's existing small SharedPreferences file but keeps
 * every key in the `sound.` namespace.
 */
object SoundEffectsStore {

    const val CUSTOM_PRESET = -1
    const val MAX_BOOST_GAIN_MB = 600 // 6 dB; a deliberately conservative UI ceiling.

    data class Settings(
        val equalizerEnabled: Boolean = false,
        val equalizerPreset: Int = CUSTOM_PRESET,
        val equalizerBandLevels: List<Int> = emptyList(),
        val volumeBoosterEnabled: Boolean = false,
        val volumeBoosterGainMb: Int = 0,
    )

    private const val PREFS_NAME = "kefi.player"
    private const val KEY_EQUALIZER_ENABLED = "sound.equalizer.enabled"
    private const val KEY_EQUALIZER_PRESET = "sound.equalizer.preset"
    private const val KEY_EQUALIZER_BANDS = "sound.equalizer.bands"
    private const val KEY_BOOSTER_ENABLED = "sound.booster.enabled"
    private const val KEY_BOOSTER_GAIN_MB = "sound.booster.gainMb"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val preferences = prefs(context)
        return Settings(
            equalizerEnabled = preferences.getBoolean(KEY_EQUALIZER_ENABLED, false),
            equalizerPreset = preferences.getInt(KEY_EQUALIZER_PRESET, CUSTOM_PRESET)
                .takeIf { it >= CUSTOM_PRESET } ?: CUSTOM_PRESET,
            equalizerBandLevels = parseBandLevels(preferences.getString(KEY_EQUALIZER_BANDS, null)),
            volumeBoosterEnabled = preferences.getBoolean(KEY_BOOSTER_ENABLED, false),
            volumeBoosterGainMb = preferences.getInt(KEY_BOOSTER_GAIN_MB, 0)
                .coerceIn(0, MAX_BOOST_GAIN_MB),
        )
    }

    fun saveEqualizerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EQUALIZER_ENABLED, enabled).apply()
    }

    fun saveEqualizerPreset(context: Context, preset: Int) {
        prefs(context).edit()
            .putInt(KEY_EQUALIZER_PRESET, preset.coerceAtLeast(CUSTOM_PRESET))
            .apply()
    }

    fun saveEqualizerBandLevels(context: Context, levels: List<Int>) {
        val json = JSONArray().apply { levels.forEach { put(it) } }.toString()
        prefs(context).edit()
            .putInt(KEY_EQUALIZER_PRESET, CUSTOM_PRESET)
            .putString(KEY_EQUALIZER_BANDS, json)
            .apply()
    }

    fun saveVolumeBoosterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOSTER_ENABLED, enabled).apply()
    }

    fun saveVolumeBoosterGain(context: Context, gainMb: Int) {
        prefs(context).edit()
            .putInt(KEY_BOOSTER_GAIN_MB, gainMb.coerceIn(0, MAX_BOOST_GAIN_MB))
            .apply()
    }

    private fun parseBandLevels(serialized: String?): List<Int> = try {
        val array = JSONArray(serialized ?: return emptyList())
        (0 until array.length()).map { index -> array.getInt(index) }
    } catch (_: Throwable) {
        emptyList()
    }
}
