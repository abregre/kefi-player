package com.kefi.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kefi.player.library.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The browse tree + command resolution a car head unit needs, and the reason it exists.
 *
 * A plain [androidx.media3.session.MediaSessionService] is enough for anything that talks to
 * the session that is *already playing*: the notification, the lock screen, a Bluetooth
 * headset's AVRCP buttons. All of those were working. What was NOT working was Honda
 * RoadSync on the bike — it showed the track but its play/pause and next did nothing.
 *
 * Car front-ends (Android Auto, and the Auto-derived ones like RoadSync) do not drive the
 * active session they happen to observe. They enumerate media apps that publish an
 * `android.media.browse.MediaBrowserService`, connect to that app as a *browser*, and send
 * their transport commands down that browser connection. With no browse service to connect
 * to, RoadSync fell back to reading the session's metadata for display — which is exactly
 * the symptom: the player appears, the buttons are dead.
 *
 * So [PlaybackService] is a `MediaLibraryService` and this is its callback. Two things in
 * here are load-bearing beyond "return some items":
 *
 *  - [onAddMediaItems]. A [MediaItem] handed to a browser loses its `localConfiguration`
 *    (the actual `content://` uri) when it crosses the IPC boundary — only `mediaId`
 *    survives. When the car asks to play one back, Media3's default implementation sees a
 *    null `localConfiguration` and fails the request outright. Every browsable app must
 *    re-resolve ids here; ours are uris already ([TrackSource.mediaItemsFor]).
 *  - [onSetMediaItems]. Tapping a track in a car list sends that ONE item. Accepting it
 *    verbatim gives a one-song queue, so next/previous are unavailable immediately after —
 *    the same dead buttons, for a different reason. [lastBrowsedUris] remembers the list the
 *    track was tapped in and restores it as the queue around the chosen track.
 */
@OptIn(UnstableApi::class)
class MediaBrowseTree(private val context: Context) : MediaLibrarySession.Callback {

    companion object {
        const val ROOT_ID = "kefi_root"
        const val TRACKS_ID = "kefi_tracks"
        const val FAVORITES_ID = "kefi_favorites"
        const val PLAYLISTS_ID = "kefi_playlists"
        const val PLAYLIST_PREFIX = "kefi_playlist_"
    }

    /**
     * The uris of the last playable list served to a browser, in browse order. Written on
     * every [onGetChildren] that returns tracks and read by [onSetMediaItems]; see the class
     * doc for why a single tapped item is not enough to build a queue from.
     *
     * A one-entry cache rather than a map keyed by parent id because the mediaId a browser
     * plays back carries no parent — browse-then-tap is strictly sequential, so the last list
     * served is the list the tap came from. Worst case (a stale cache after the car browsed
     * elsewhere) the guard in [onSetMediaItems] fails to find the uri and we fall back to the
     * single item, which is Media3's own behaviour.
     */
    @Volatile
    private var lastBrowsedUris: List<String> = emptyList()

    @Volatile
    private var lastBrowsedPlaylistId: Long? = null

    private fun dao() = LibraryDatabase.getInstance(context).libraryDao()

    // ---- Connection ------------------------------------------------------

    /**
     * Logged, not restricted. The default [MediaSession.Callback.onConnect] already grants
     * every available player and library command to every caller, which is what a car
     * front-end needs; the log line is here because "did RoadSync connect at all" is the
     * first question any future report of this bug has to answer, and it is invisible
     * otherwise.
     */
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Log.i("KefiApp", "MediaSession connect from ${controller.packageName} (uid=${controller.uid})")
        return super.onConnect(session, controller)
    }

    // ---- Browsing --------------------------------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(browsable(ROOT_ID, context.getString(R.string.app_name)), params),
        )

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val fixed = when (mediaId) {
            ROOT_ID -> browsable(ROOT_ID, context.getString(R.string.app_name))
            TRACKS_ID -> browsable(TRACKS_ID, context.getString(R.string.browse_all_tracks))
            FAVORITES_ID -> browsable(FAVORITES_ID, context.getString(R.string.browse_favorites))
            PLAYLISTS_ID -> browsable(PLAYLISTS_ID, context.getString(R.string.browse_playlists))
            else -> null
        }
        if (fixed != null) return Futures.immediateFuture(LibraryResult.ofItem(fixed, null))

        return resolveAsync {
            val track = TrackSource.listTracks(context).firstOrNull { it.uri == mediaId }
                ?: return@resolveAsync LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            LibraryResult.ofItem(playable(track), null)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = resolveAsync {
        val children: List<MediaItem> = when {
            parentId == ROOT_ID -> listOf(
                browsable(TRACKS_ID, context.getString(R.string.browse_all_tracks)),
                browsable(FAVORITES_ID, context.getString(R.string.browse_favorites)),
                browsable(PLAYLISTS_ID, context.getString(R.string.browse_playlists)),
            )

            parentId == TRACKS_ID -> TrackSource.listTracks(context).map { playable(it) }
                .also { rememberBrowsed(it) }

            parentId == FAVORITES_ID -> tracksForPaths(dao().listFavoritePaths())
                .also { rememberBrowsed(it) }

            parentId == PLAYLISTS_ID -> dao().listPlaylistsWithCounts().map {
                browsable(PLAYLIST_PREFIX + it.id, it.name)
            }

            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val id = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                if (id == null) {
                    emptyList()
                } else {
                    tracksForPaths(dao().listPlaylistPaths(id)).also { rememberBrowsed(it, id) }
                }
            }

            // An unknown parent is an empty folder, not an error: Media3's own contract for
            // onGetChildren says to return an empty list rather than an error code for "no
            // children", and a car that gets an error here can drop the whole app from its
            // list instead of just showing nothing under one node.
            else -> emptyList()
        }
        // Paginate AFTER building: page/pageSize are the browser's window onto the full list,
        // and a car asks for page 0 of a large size first. drop/take is safe past the end.
        val window = children.drop(page * pageSize).take(pageSize)
        LibraryResult.ofItemList(ImmutableList.copyOf(window), params)
    }

    // ---- Playback requests from a browser --------------------------------

    /**
     * Turns browser-supplied ids back into playable items. See the class doc: an item that
     * crossed IPC has no uri, so without this every "play this" from the car fails.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        // Items that still carry a uri came from inside the app (PlayerController), not over
        // IPC — resolving those again would be a pointless MediaStore round-trip.
        if (mediaItems.all { it.localConfiguration != null }) {
            return Futures.immediateFuture(mediaItems)
        }
        val future = SettableFuture.create<List<MediaItem>>()
        App.scope.launch {
            try {
                val resolved = TrackSource.mediaItemsFor(context, mediaItems.map { it.mediaId })
                future.set(resolved)
            } catch (t: Throwable) {
                Log.w("KefiApp", "Could not resolve browsed media items", t)
                future.setException(t)
            }
        }
        return future
    }

    /**
     * Expands a single tapped track into the list it was tapped in, so next/previous exist
     * the moment playback starts from a car. See [lastBrowsedUris].
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val browsed = lastBrowsedUris
        val browsedPlaylistId = lastBrowsedPlaylistId
        val tappedId = mediaItems.singleOrNull()?.mediaId
        val indexInBrowsed = if (tappedId == null) -1 else browsed.indexOf(tappedId)
        if (indexInBrowsed < 0) {
            return super.onSetMediaItems(
                mediaSession, controller, mediaItems, startIndex, startPositionMs,
            )
        }

        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        App.scope.launch {
            try {
                val items = TrackSource.mediaItemsFor(context, browsed)
                PlaylistContinuation.setSourcePlaylistId(context, browsedPlaylistId)
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        items, indexInBrowsed, startPositionMs,
                    ),
                )
            } catch (t: Throwable) {
                Log.w("KefiApp", "Could not expand browsed queue", t)
                future.setException(t)
            }
        }
        return future
    }

    /**
     * The car (or a headset) pressed play while the service was cold. Media3 starts us,
     * calls this, and plays whatever comes back — so this must hand over the same persisted
     * queue [PlaybackService.restorePlaybackState] would have restored, or the press is a
     * no-op and the head unit looks broken again.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        App.scope.launch {
            val restored = PlaybackStateStore.restore(context)
            if (restored == null || restored.items.isEmpty()) {
                future.setException(UnsupportedOperationException("Nothing to resume"))
            } else {
                Log.i("KefiApp", "Playback resumption: ${restored.items.size} item(s)")
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        restored.items, restored.index, restored.positionMs,
                    ),
                )
            }
        }
        return future
    }

    // ---- Helpers ---------------------------------------------------------

    private fun rememberBrowsed(items: List<MediaItem>, playlistId: Long? = null) {
        lastBrowsedUris = items.map { it.mediaId }
        lastBrowsedPlaylistId = playlistId
    }

    /**
     * Playlists and favourites store `folder/displayName` paths, not uris (the JS side owns
     * that mapping). Resolving them means one MediaStore listing and a lookup, and a path
     * whose file is gone is dropped rather than surfaced as an unplayable row.
     */
    private suspend fun tracksForPaths(paths: List<String>): List<MediaItem> {
        if (paths.isEmpty()) return emptyList()
        val byPath = TrackSource.listTracks(context).associateBy { "${it.folder}/${it.displayName}" }
        return paths.mapNotNull { byPath[it] }.map { playable(it) }
    }

    private fun browsable(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    /**
     * No `setUri` on purpose: a uri does not survive the trip to a browser anyway (see
     * [onAddMediaItems]), and setting one here would only make the local and remote shapes
     * of the same item differ for no benefit. [MediaItem.mediaId] is the uri string, matching
     * what [TrackSource.mediaItemsFor] already uses everywhere else in the app.
     */
    private fun playable(track: TrackSource.Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    /**
     * Bridges a suspending body to the [ListenableFuture] the Media3 callbacks return. The
     * work is MediaStore/Room I/O, so it runs on [App.scope] ([Dispatchers.IO]); a browser
     * call must never block the main thread waiting on a full audio listing.
     */
    private fun <T : Any> resolveAsync(
        block: suspend () -> LibraryResult<T>,
    ): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        App.scope.launch {
            try {
                future.set(withContext(Dispatchers.IO) { block() })
            } catch (t: Throwable) {
                Log.w("KefiApp", "Browse request failed", t)
                future.set(LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return future
    }
}
