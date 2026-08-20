package com.kefi.player.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One track's membership in one [Playlist], at one ordinal [position] within that playlist.
 *
 * [path] is this app's identity for a track everywhere it needs one: `folder + '/' +
 * displayName` (see the `PlayerTrack.displayName`/`folder` contract documented in
 * `src/plugins/player.js`). This was the user's EXPLICIT choice over MediaStore's numeric
 * `_ID`, which a rescan is free to reassign — a path-keyed favourite or playlist entry survives
 * that, at the accepted cost that moving or renaming the underlying file changes its path and
 * silently drops it out of every playlist and out of favourites. No content hashing or fuzzy
 * re-matching is done to chase a moved file back into membership (playlists-tab plan, decision 3
 * and Must NOT Have 7) — the same reasoning that ruled out `MediaStore.IS_FAVORITE` for
 * favourites applies here.
 *
 * [position] orders the items within a playlist for listing and playback. See [LibraryDao]'s
 * KDoc for the invariant every mutator must preserve: positions must stay unique per playlist
 * (never two items sharing one) but need not be gap-free (a deleted item's gap is harmless,
 * since sort order alone determines playback order, not the numeric spacing between values).
 *
 * `onDelete = CASCADE` on the foreign key to [Playlist.id] means deleting a playlist row deletes
 * every one of its items in the same SQLite operation — no orphaned item rows, no separate
 * app-level cleanup step required when [LibraryDao] deletes a playlist.
 *
 * A UNIQUE index on `(playlistId, path)` enforces, at the schema level, that a given path
 * appears at most once within a given playlist — this is the JS contract's documented
 * guarantee (`src/plugins/player.js`'s `addToPlaylist`: "Idempotent per path: a path already
 * in the playlist is not appended a second time"). [LibraryDao.appendToPlaylist] and
 * [LibraryDao.reorderPlaylist] both already filter/dedupe before inserting, so in normal
 * operation this index never actually rejects a row; it exists so that invariant cannot be
 * silently broken by some future method that forgets to filter — the schema itself refuses a
 * duplicate rather than relying on every caller remembering to check.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "position"]),
        Index(value = ["playlistId", "path"], unique = true),
    ],
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,
    val path: String,
    val position: Int,
)
