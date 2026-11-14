package com.kefi.player.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Data access for [Playlist]/[PlaylistItem]/[FavoriteTrack]. Every method is `suspend` — this
 * DAO does no synchronous I/O — because its only caller ([com.kefi.player.PlayerPlugin]'s
 * playlist/favourite methods, added in the playlists-tab plan's T4) already runs on
 * `App.scope.launch { }`, a coroutine scope. No method returns `Flow`: nothing in this app
 * observes the database reactively, and a `Flow` would need a lifecycle owner that does not
 * exist on the plugin side (see the plan's Must NOT Have for this task).
 *
 * **The `position` invariant.** [PlaylistItem.position] must stay UNIQUE within a playlist —
 * two items sharing a position makes their relative order arbitrary, which is a real bug, not
 * a cosmetic one. It does NOT need to stay gap-free: deleting an item ([deleteItem]) leaves a
 * hole in the sequence, but `ORDER BY position ASC` ([listPlaylistPaths]) still returns the
 * remaining items in their original relative order, so a gap is harmless. The two methods that
 * write positions — [appendToPlaylist] and [reorderPlaylist] — are the ones responsible for
 * upholding uniqueness; see each one's KDoc for how.
 *
 * **A second, independent invariant**: a given path appears at most once within a given
 * playlist ([appendToPlaylist] is documented idempotent per path, matching the JS contract in
 * `src/plugins/player.js`). This is backed by a UNIQUE index on `(playlistId, path)` in
 * [PlaylistItem] — a schema-level guarantee, not just a convention both mutators happen to
 * follow.
 */
@Dao
interface LibraryDao {

    // ---- Playlists -------------------------------------------------------

    /**
     * Every user playlist with its track count, via a LEFT JOIN + COUNT/GROUP BY. A LEFT JOIN
     * is required, not an INNER JOIN: an INNER JOIN drops any playlist with zero items from the
     * result entirely (there is no `playlist_items` row to join against), silently hiding a
     * brand-new empty playlist from the Playlists tab — that would be a real bug, not a
     * cosmetic one. `COUNT(playlist_items.id)` (counting the joined column, not `COUNT(*)`)
     * correctly yields 0 for that case instead of counting one phantom NULL row.
     *
     * Ordered by [Playlist.createdAt] ascending (oldest first) — the plan does not mandate an
     * order, so creation order was chosen as the least surprising default (playlists appear in
     * the order the user made them, and a freshly created one appears at the end).
     */
    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name,
               COUNT(playlist_items.id) AS count
        FROM playlists
        LEFT JOIN playlist_items ON playlist_items.playlistId = playlists.id
        GROUP BY playlists.id
        ORDER BY playlists.createdAt ASC
        """
    )
    suspend fun listPlaylistsWithCounts(): List<PlaylistWithCount>

    /** Inserts a new playlist and returns its generated row id. */
    @Insert
    suspend fun insertPlaylist(playlist: Playlist): Long

    /** Renames the playlist with the given id. A no-op if no such id exists. */
    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    /**
     * Deletes the playlist with the given id. Its items are removed automatically by the
     * `ON DELETE CASCADE` foreign key on [PlaylistItem] — no separate item-cleanup call needed.
     * A no-op if no such id exists.
     */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    // ---- Playlist items ----------------------------------------------------

    /**
     * Appends [paths] to the end of playlist [playlistId], in the order given.
     *
     * **Idempotent per path** — this is a contract requirement, not a nice-to-have: the JS
     * contract of record (`src/plugins/player.js`'s `addToPlaylist` doc: "Idempotent per path:
     * a path already in the playlist is not appended a second time") and the web mock
     * (`player.web.js`, `includes()`-checked before pushing) both already promise this
     * behaviour, so the native DAO must match it exactly — a caller (T4's plugin methods) must
     * see the same outcome whether it runs against this DAO or the mock. Two sources of
     * duplication are both handled: a path already stored in the playlist, and a path repeated
     * more than once within this single [paths] call. Both are resolved by keeping only the
     * FIRST occurrence and silently dropping the rest — read [listPlaylistPaths] into a
     * `MutableSet`, then walk [paths] once, adding to that set and keeping only the paths that
     * were not already in it. This mirrors the mock's own "check membership, then push" logic
     * one level up (a whole-batch dedupe against current state, rather than one `includes` call
     * per path), so the resulting membership is identical to calling the mock's `addToPlaylist`
     * once per path in order.
     *
     * Positions for the surviving (non-duplicate) paths are computed as `MAX(position) + 1,
     * +2, +3, …` starting from whatever the highest existing position is (or `-1` if the
     * playlist is empty, so the first item lands at position 0). Because this whole method runs
     * inside one `@Transaction`, the read of existing paths, the read of the current max, and
     * the subsequent insert are all atomic with respect to any other writer of this DAO —
     * nothing else can insert between those reads and the write, so no race can reintroduce a
     * duplicate or cause two items to land on the same position. The `(playlistId, path)`
     * unique index on [PlaylistItem] (see its KDoc) backs this same guarantee at the schema
     * level, so a future bug in this filtering logic would fail loudly (a constraint violation)
     * rather than silently double-inserting.
     */
    @Transaction
    suspend fun appendToPlaylist(playlistId: Long, paths: List<String>) {
        if (paths.isEmpty()) return
        val seen = listPlaylistPaths(playlistId).toMutableSet()
        val toInsert = paths.filter { seen.add(it) }
        if (toInsert.isEmpty()) return
        val start = (maxPosition(playlistId) ?: -1) + 1
        val items = toInsert.mapIndexed { offset, path ->
            PlaylistItem(playlistId = playlistId, path = path, position = start + offset)
        }
        insertItems(items)
    }

    /** Highest existing [PlaylistItem.position] in [playlistId], or `null` if it has no items. */
    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    /**
     * Raw multi-row insert, composed into by [appendToPlaylist] and [reorderPlaylist] — both of
     * which already filter/dedupe [items] to at most one row per `(playlistId, path)` before
     * calling this. `OnConflictStrategy.IGNORE` is a schema-level backstop, not the primary
     * dedupe mechanism: if some future caller of this method skipped that filtering, the
     * `(playlistId, path)` unique index (see [PlaylistItem]'s KDoc) would make a duplicate
     * insert silently no-op that one row rather than crash the whole batch, since a plain
     * (default `ABORT`) insert would throw and roll back every row in [items], not just the
     * offending one.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<PlaylistItem>)

    /** Removes one item, identified by the (playlistId, path) pair. A no-op if it is absent. */
    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND path = :path")
    suspend fun deleteItem(playlistId: Long, path: String)

    /**
     * Rewrites playlist [playlistId]'s full order to match [paths] (the complete ordered list,
     * not a from/to index pair — simpler and less error-prone for the caller, per the plan).
     *
     * **Approach and why it cannot leave a half-written order behind**: delete every existing
     * item row for this playlist, then re-insert [paths] with `position = index in the list`.
     * Both steps run inside this single `@Transaction`. SQLite (and Room's transaction wrapper
     * around it) guarantees a transaction is all-or-nothing: if the process dies, an exception
     * is thrown, or anything else interrupts the method between the delete and the re-insert,
     * the transaction rolls back in full and the playlist is left exactly as it was before the
     * call — there is no committed state in which the delete happened but the re-insert did
     * not, or vice versa. Delete-then-reinsert was chosen over an in-place UPDATE-per-row
     * rewrite because it is simpler to reason about (no risk of two rows transiently sharing a
     * position mid-rewrite, which an UPDATE-in-place approach has to sequence carefully to
     * avoid) and this table stores nothing per-item besides `playlistId`/`path`/`position`, so
     * there is no other column value that re-inserting would lose.
     *
     * **Defensively deduped, same as [appendToPlaylist]**: if [paths] somehow contained the
     * same path twice (it should not — the caller is expected to send back exactly the ordered
     * membership this DAO itself last handed out via [listPlaylistPaths] — but this DAO owns
     * the "no duplicate path per playlist" rule, per the `(playlistId, path)` unique index on
     * [PlaylistItem], and must not trust a caller to uphold it), only the FIRST occurrence of
     * each path is kept, in the order given. Without this, a duplicate in [paths] would either
     * violate that unique index (and abort the whole transaction) or, if a future change ever
     * relaxed [insertItems] to `REPLACE`, silently drop an unrelated row — neither is
     * acceptable for what is meant to be a same-membership reorder.
     */
    @Transaction
    suspend fun reorderPlaylist(playlistId: Long, paths: List<String>) {
        deleteAllItems(playlistId)
        val seen = mutableSetOf<String>()
        val deduped = paths.filter { seen.add(it) }
        val items = deduped.mapIndexed { index, path ->
            PlaylistItem(playlistId = playlistId, path = path, position = index)
        }
        insertItems(items)
    }

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteAllItems(playlistId: Long)

    /** A playlist's paths, in playback/display order. */
    @Query("SELECT path FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun listPlaylistPaths(playlistId: Long): List<String>

    // ---- Favourites --------------------------------------------------------

    /**
     * All favourited paths. Ordered by path only for a deterministic result set — the
     * Favorites smart list is actually sorted by title in JS (decision 9), over track objects
     * this DAO never sees, so this DAO-level order is not user-facing.
     */
    @Query("SELECT path FROM favorites ORDER BY path ASC")
    suspend fun listFavoritePaths(): List<String>

    /**
     * Marks [favorite] as favourited. `OnConflictStrategy.REPLACE` makes this idempotent: since
     * [FavoriteTrack.path] is the primary key, favouriting an already-favourited path just
     * overwrites its `addedAt` rather than throwing a constraint-violation exception — the
     * plugin layer's `setFavorite({ path, favorite: true })` can be called repeatedly without
     * needing to check first whether the path is already a favourite.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteTrack)

    /** Removes a favourite by path. A no-op if it was not favourited. */
    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun deleteFavorite(path: String)

    /**
     * Removes [path] from every playlist and from favourites in one transaction, then returns
     * the updated playlist list (same shape as [listPlaylistsWithCounts]) so the caller can
     * patch JS-side state without a second round trip.
     *
     * Called from [com.kefi.player.PlayerPlugin.removeFromAllPlaylists] after a successful
     * device-file deletion, so that playlist counts and favourite state reflect reality
     * immediately — without this, a deleted track stays in every playlist's Room row until the
     * user manually removes it, and the playlist detail screen shows a permanent "1 missing"
     * notification that can never be dismissed.
     */
    @Transaction
    suspend fun removePathEverywhere(path: String): List<PlaylistWithCount> {
        deleteItemsByPath(path)
        deleteFavorite(path)
        return listPlaylistsWithCounts()
    }

    @Query("DELETE FROM playlist_items WHERE path = :path")
    suspend fun deleteItemsByPath(path: String)
}

/**
 * Projection for [LibraryDao.listPlaylistsWithCounts] — [count] is `0`, not a missing row, for
 * a playlist that currently has no items (see that method's KDoc on why the query is a LEFT
 * JOIN rather than an INNER JOIN).
 */
data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val count: Int,
)
