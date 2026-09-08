package com.kefi.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import org.json.JSONArray

/**
 * Persists the playback queue/index/position/shuffle/repeat across process death.
 *
 * One SharedPreferences file for this whole feature area, keys namespaced with a `player.`
 * prefix — mirrors the single-prefs-file convention of
 * `com.ytmp3.app.StorageLocation` (`/mnt/X/myProjects/ytmp3/android/app/src/main/java/com/ytmp3/app/StorageLocation.kt:40-83`:
 * `context.getSharedPreferences(<one file>, MODE_PRIVATE)`). Backed by `org.json.JSONArray`
 * only — no ORM, no key-value library, no embedded database: an explicit plan-level
 * Must NOT Have.
 */
object PlaybackStateStore {

    private const val PREFS_NAME = "kefi.player"
    private const val KEY_QUEUE = "player.queue"
    private const val KEY_INDEX = "player.index"
    private const val KEY_POSITION = "player.positionMs"
    private const val KEY_SHUFFLE = "player.shuffle"
    private const val KEY_REPEAT = "player.repeat"
    private const val KEY_SAVED_AT = "player.savedAt"

    /** What [restore] hands back to [PlaybackService.onCreate] once every uri is validated. */
    data class RestoredQueue(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Called from [PlaybackService]'s own `Player.Listener` every time
     * queue/index/playback-state/shuffle/repeat changes, plus a throttled heartbeat while
     * playing (see `PlaybackService.kt`). Deliberately NOT driven from [PlayerController]:
     * that controller only exists while a WebView does, so persistence hung off it went stale
     * the moment the app was closed — and the next cold service start then restored that stale
     * queue, which is what made external next/previous appear to ignore the real queue. `SharedPreferences.Editor.apply()` commits to disk
     * asynchronously, so this is safe to call directly from the main thread.
     */
    fun save(
        context: Context,
        uris: List<String>,
        index: Int,
        positionMs: Long,
        shuffle: Boolean,
        repeatMode: Int,
    ) {
        if (uris.isEmpty()) return
        val queueJson = JSONArray().apply { uris.forEach { put(it) } }.toString()
        prefs(context).edit()
            .putString(KEY_QUEUE, queueJson)
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, positionMs)
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putInt(KEY_REPEAT, repeatMode)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Reads the persisted queue, drops any uri that no longer resolves (deleted MediaStore
     * row / revoked permission — the same two failure modes T11's
     * `PlayerController.describeError` classifies for a live `PlaybackException`, checked
     * here directly against the `ContentResolver` up front instead of waiting for a failed
     * `prepare()`), and remaps [RestoredQueue.index]/[RestoredQueue.positionMs] onto the
     * surviving items. Returns `null` (after clearing the stored state) when NONE of the
     * stored uris resolve. Runs entirely on [Dispatchers.IO] — safe to call from
     * [PlaybackService.onCreate] (main thread) without blocking it.
     */
    /**
     * The uri of whatever was current when the app was last alive, without touching the
     * player. The widget renders from this when the process starts cold (a reboot, or after
     * the service stopped): it can show the last track, paused, instead of an empty strip.
     */
    suspend fun peekCurrentUri(context: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val queueJson = p.getString(KEY_QUEUE, null) ?: return@withContext null
        try {
            val array = JSONArray(queueJson)
            val index = p.getInt(KEY_INDEX, 0).coerceIn(0, (array.length() - 1).coerceAtLeast(0))
            if (array.length() == 0) null else array.getString(index)
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun restore(context: Context): RestoredQueue? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val queueJson = p.getString(KEY_QUEUE, null) ?: return@withContext null
        val storedUris = try {
            val array = JSONArray(queueJson)
            (0 until array.length()).map { array.getString(it) }
        } catch (t: Throwable) {
            Log.w("KefiApp", "PlaybackStateStore: stored queue was not valid JSON, clearing", t)
            clear(context)
            return@withContext null
        }
        if (storedUris.isEmpty()) {
            clear(context)
            return@withContext null
        }

        val survivingIndices = storedUris.indices.filter { isResolvable(context, storedUris[it]) }
        if (survivingIndices.isEmpty()) {
            Log.i(
                "KefiApp",
                "PlaybackStateStore: none of ${storedUris.size} stored uri(s) resolve, clearing state",
            )
            clear(context)
            return@withContext null
        }

        val storedIndex = p.getInt(KEY_INDEX, 0)
        // Through TrackSource so a restored queue carries the same metadata a freshly
        // queued one does — otherwise the notification that comes back after process death
        // would be a nameless "Now playing" until something else refreshed it.
        val items = TrackSource.mediaItemsFor(context, survivingIndices.map { storedUris[it] })
        // Remap the saved current-item index onto the filtered list. If the item that was
        // actually current got dropped, fall back to the first surviving item at position 0
        // rather than guessing which neighbour the user meant.
        val remappedIndex = survivingIndices.indexOf(storedIndex)
        val index = if (remappedIndex >= 0) remappedIndex else 0
        val positionMs = if (remappedIndex >= 0) p.getLong(KEY_POSITION, 0L) else 0L

        RestoredQueue(
            items = items,
            index = index,
            positionMs = positionMs,
            shuffle = p.getBoolean(KEY_SHUFFLE, false),
            repeatMode = p.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
        )
    }

    /**
     * Direct `ContentResolver` resolvability check — the same two failure modes T11's
     * `PlayerController.describeError` classifies for a live playback error
     * (`FileNotFoundException` for a deleted MediaStore row, `SecurityException` for a
     * revoked permission), applied here up front instead of waiting for a failed `prepare()`.
     */
    private fun isResolvable(context: Context, uriString: String): Boolean {
        val uri = try {
            Uri.parse(uriString)
        } catch (t: Throwable) {
            return false
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            Log.w("KefiApp", "PlaybackStateStore: dropping unresolvable uri=$uriString reason=file_not_found")
            false
        } catch (e: SecurityException) {
            Log.w("KefiApp", "PlaybackStateStore: dropping unresolvable uri=$uriString reason=permission_denied")
            false
        } catch (t: Throwable) {
            Log.w(
                "KefiApp",
                "PlaybackStateStore: dropping unresolvable uri=$uriString reason=${t.javaClass.simpleName}",
            )
            false
        }
    }
}
