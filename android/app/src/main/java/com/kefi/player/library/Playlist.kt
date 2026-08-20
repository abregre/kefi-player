package com.kefi.player.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-created playlist: its name and when it was created.
 *
 * The ordered membership itself lives in [PlaylistItem], one row per track — this row is only
 * the list's identity (id + name), never a track list. Smart lists (Favorites, Recently added)
 * are NOT rows in this table: per the playlists-tab plan's decisions 8/9, they are computed on
 * the JS side from [FavoriteTrack] and the track contract's `dateAdded` field respectively, and
 * cannot be renamed or reordered. Only playlists the user explicitly created live here.
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
)
