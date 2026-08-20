package com.kefi.player.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A favourited track, keyed by [path] — see [PlaylistItem.path]'s KDoc for why a relative path
 * (`folder + '/' + displayName`) is this app's identity for a track instead of MediaStore's
 * numeric id, and what that choice costs (membership lost on move/rename, accepted).
 *
 * [addedAt] is recorded for completeness (when the user favourited the track) but is NOT what
 * Favorites is sorted by today — decision 9 in the playlists-tab plan sorts Favorites by title
 * in JS, since it is a smart list with a fixed, non-reorderable order. Storing [addedAt] anyway
 * costs nothing and keeps the door open for a future sort option without a schema change.
 *
 * This table intentionally stores nothing about the track besides its path and when it was
 * favourited — no cached title/artist/duration. The MediaStore query
 * (`TrackSource.kt`/`useLibrary.js`) is the single source of truth for track metadata; caching
 * any of it here would create a second copy that can drift out of sync with the real file.
 */
@Entity(tableName = "favorites")
data class FavoriteTrack(
    @PrimaryKey
    val path: String,
    val addedAt: Long,
)
