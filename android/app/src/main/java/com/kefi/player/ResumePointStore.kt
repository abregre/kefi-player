package com.kefi.player

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Per-track resume points: where each individual song was left off, so playing that song again
 * picks up from there instead of from the top.
 *
 * Deliberately separate from [PlaybackStateStore], which answers a different question.
 * [PlaybackStateStore] remembers ONE thing — the queue the app was last playing and where it
 * was in it — so the app can come back to life after process death. This remembers MANY things,
 * one per track, and outlives any particular queue: the same song reached from a playlist, a
 * folder, a search result or a car head unit resumes from the same point.
 *
 * Native-owned, like [SoundEffectsStore], and for the stronger version of the same reason. The
 * whole point of the feature is that it works with no screen and no WebView — the user drives
 * playback from a car's buttons over Bluetooth — so both the setting and the saved points have
 * to be readable by [PlaybackService] with nothing else running.
 *
 * Stored as ONE JSON object under a single preferences key rather than a key per uri: with a key
 * per uri there is no way to find the oldest entries, and this map has to be prunable — a
 * library of thousands of tracks would otherwise grow an unbounded preferences file. Kept in an
 * in-memory cache because [PlaybackService] reads it on every track transition and writes it on
 * a 5s heartbeat.
 */
object ResumePointStore {

    private const val PREFS_NAME = "kefi.player"
    private const val KEY_ENABLED = "playback.resumeTracks"
    private const val KEY_POINTS = "playback.resumePoints"

    /**
     * A track has to be at least this far in before a resume point is worth keeping. Below it,
     * "where you left off" and "the beginning" are the same place, and remembering every
     * two-second sample of a track the user skipped past would fill the map with noise.
     */
    private const val MIN_RESUME_POSITION_MS = 20_000L

    /**
     * ...and at least this much has to be left. A track abandoned in its last few seconds has
     * effectively been listened to; resuming there would play a heartbeat of audio and move on,
     * which is never what the user meant.
     */
    private const val MIN_REMAINING_MS = 20_000L

    /**
     * Prune threshold. Generous enough that a normal listening history never hits it, small
     * enough that the preferences file stays a few tens of KB in the worst case.
     */
    private const val MAX_ENTRIES = 500

    private data class Point(val positionMs: Long, val updatedAt: Long)

    @Volatile
    private var cache: MutableMap<String, Point>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether resume points are recorded and applied at all. Default ON: this is the behaviour
     * the feature was asked for, and the switch exists so it can be turned off, not so it has
     * to be turned on.
     */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /**
     * Turning the feature OFF also drops every stored point. Leaving them behind would mean a
     * user who turns it off, listens for a month, and turns it back on gets thrown into
     * positions from a month ago — stale state pretending to be memory.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) {
            editor.remove(KEY_POINTS)
            cache = mutableMapOf()
        }
        editor.apply()
    }

    /** Where [uri] was left off, or 0 when there is nothing worth resuming to. */
    fun positionFor(context: Context, uri: String): Long {
        if (!isEnabled(context)) return 0L
        return points(context)[uri]?.positionMs ?: 0L
    }

    /**
     * Records (or clears) [uri]'s resume point from a live sample.
     *
     * Clearing is as important as saving and is why this takes a duration: a track played to the
     * end must start from the beginning next time, and the natural end of playback arrives here
     * as a sample sitting near [durationMs]. Callers do not decide which case they are in —
     * they hand over what they measured and this classifies it.
     *
     * @param durationMs 0 when the player does not know the duration yet, in which case only
     *   the "too early to bother" half of the classification can be applied.
     */
    fun remember(context: Context, uri: String, positionMs: Long, durationMs: Long) {
        if (!isEnabled(context)) return
        val finished = durationMs > 0L && positionMs >= durationMs - MIN_REMAINING_MS
        if (finished || positionMs < MIN_RESUME_POSITION_MS) {
            forget(context, uri)
            return
        }
        val map = points(context)
        map[uri] = Point(positionMs, System.currentTimeMillis())
        if (map.size > MAX_ENTRIES) {
            // Oldest-touched first. `size - MAX_ENTRIES` is 1 in every realistic case — this
            // runs on each save, so the map cannot drift far above the cap.
            map.entries.sortedBy { it.value.updatedAt }
                .take(map.size - MAX_ENTRIES)
                .forEach { map.remove(it.key) }
        }
        persist(context, map)
    }

    fun forget(context: Context, uri: String) {
        val map = points(context)
        if (map.remove(uri) != null) persist(context, map)
    }

    private fun points(context: Context): MutableMap<String, Point> {
        cache?.let { return it }
        val parsed = parse(prefs(context).getString(KEY_POINTS, null))
        cache = parsed
        return parsed
    }

    private fun parse(serialized: String?): MutableMap<String, Point> {
        val map = mutableMapOf<String, Point>()
        val json = serialized ?: return map
        try {
            val root = JSONObject(json)
            root.keys().forEach { uri ->
                val entry = root.optJSONObject(uri) ?: return@forEach
                val positionMs = entry.optLong("p", 0L)
                if (positionMs > 0L) {
                    map[uri] = Point(positionMs, entry.optLong("t", 0L))
                }
            }
        } catch (t: Throwable) {
            Log.w("KefiApp", "ResumePointStore: stored points were not valid JSON, dropping", t)
        }
        return map
    }

    /**
     * `SharedPreferences.Editor.apply()` commits asynchronously, so this is safe to call from
     * the main thread — which is where PlaybackService's player callbacks run.
     */
    private fun persist(context: Context, map: Map<String, Point>) {
        val root = JSONObject()
        map.forEach { (uri, point) ->
            root.put(uri, JSONObject().put("p", point.positionMs).put("t", point.updatedAt))
        }
        prefs(context).edit().putString(KEY_POINTS, root.toString()).apply()
    }
}
