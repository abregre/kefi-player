package com.kefi.player

import android.content.Context
import androidx.media3.common.MediaItem
import com.kefi.player.library.LibraryDatabase

object PlaylistContinuation {

    private const val PREFS_NAME = "kefi.player"
    private const val KEY_ENABLED = "playback.continuePlaylists"
    private const val KEY_SOURCE = "playback.sourcePlaylistId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun sourcePlaylistId(context: Context): Long? =
        prefs(context).getLong(KEY_SOURCE, -1L).takeIf { it >= 0L }

    fun setSourcePlaylistId(context: Context, playlistId: Long?) {
        val editor = prefs(context).edit()
        if (playlistId == null) editor.remove(KEY_SOURCE) else editor.putLong(KEY_SOURCE, playlistId)
        editor.apply()
    }

    suspend fun nextQueue(context: Context, currentId: Long): Pair<Long, List<MediaItem>>? {
        val dao = LibraryDatabase.getInstance(context).libraryDao()
        val playlists = dao.listPlaylistsWithCounts()
        val start = playlists.indexOfFirst { it.id == currentId }
        if (start < 0) return null
        if (playlists.none { it.count > 0 }) return null
        val byPath = TrackSource.listTracks(context).associate { track ->
            val path = if (track.folder.isEmpty()) track.displayName else "${track.folder}/${track.displayName}"
            path to track.uri
        }
        for (step in 1..playlists.size) {
            val candidate = playlists[(start + step) % playlists.size]
            if (candidate.count == 0) continue
            val uris = dao.listPlaylistPaths(candidate.id).mapNotNull { byPath[it] }
            if (uris.isEmpty()) continue
            val items = TrackSource.mediaItemsFor(context, uris)
            if (items.isNotEmpty()) return candidate.id to items
        }
        return null
    }
}
