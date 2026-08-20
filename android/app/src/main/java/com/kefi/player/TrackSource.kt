package com.kefi.player

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device-wide audio listing + artwork lookup, on top of `MediaStore` only (no SAF, no
 * database, no `ContentObserver` — an explicit on-demand query every time, per the plan).
 * Every public function here does its own `withContext(Dispatchers.IO)` hop, so callers
 * (T9's `PlayerPlugin`) never need to think about threading.
 */
object TrackSource {

    /**
     * Field names are the exact contract T15/T16 build the library UI on — see
     * `.claude/notepads/kefi-player-full-android-player/learnings.md`, `## T9`.
     */
    data class Track(
        val id: Long,
        val uri: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val trackNumber: Int?,
        val year: Int?,
        val durationMs: Long,
        val size: Long,
        /**
         * The track's containing directory, as a path the user would recognise
         * (`Music/kefi-qa`, `Download`). Added for the library's Folders grouping —
         * additive to the T9 contract, so nothing that ignores it breaks.
         */
        val folder: String,
    )

    /** In-memory only, at most 64 entries — an explicit Must NOT Have forbids anything durable. */
    private val artworkCache = object : LinkedHashMap<Long, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String?>?): Boolean =
            size > 64
    }

    /** MediaStore's album-art provider. Undocumented but stable, and the cheap path to a
     *  lock-screen cover: it is a uri, so no bitmap is decoded unless something asks for one. */
    private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

    /** MediaStore's placeholder for an untagged artist/album; showing it verbatim is worse
     *  than showing nothing, because the UI already has its own "unknown" strings. */
    private const val UNKNOWN = "<unknown>"

    /**
     * `RELATIVE_PATH` ("Music/kefi-qa/") is the modern, volume-independent answer and the
     * only one scoped storage really wants you to use. It does not exist before API 29, and
     * minSdk here is 24, so the legacy branch reads the absolute `_data` path instead — the
     * two are normalised to the same shape by [folderOf].
     */
    private fun folderColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }

    /**
     * Normalises whichever column [folderColumn] picked into a displayable directory path
     * with no leading or trailing slash. On API 29+ the value is already the directory
     * (`Music/kefi-qa/`); below that it is a full file path, so the file name comes off and
     * the storage prefix (`/storage/emulated/0/`, `/sdcard/`) is trimmed — it is the same
     * for every row and only makes the UI wider.
     */
    private fun folderOf(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val withoutFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            raw
        } else {
            raw.substringBeforeLast('/', "")
        }
        return withoutFile
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")
            .trim('/')
    }

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    suspend fun listTracks(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection = audioCollection()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            folderColumn(),
        )
        // `IS_MUSIC = 1` is necessary but nowhere near sufficient. Android's media scanner
        // indexes by FILE EXTENSION, so anything an app happens to name `*.mp3` — a backup
        // blob, an exported playlist — lands here as `mime_type=audio/mpeg, is_music=1`
        // with a NULL duration. Reported from a real device on 2026-08-20: rows titled
        // "autobackup…"/"autoplaylist…" that showed no length and would not play.
        // Reproduced by scanning a text file named `autobackup_kefi.mp3`.
        //
        // `DURATION > 0` is the filter that matters, and NULL fails the comparison, which is
        // exactly what should happen: without a duration the app cannot render a length, draw
        // a seek bar or scrub, and ExoPlayer has nothing to play. The playlist mime types are
        // denied by name as well — those are real "audio" mime types that are not audio.
        val playlistMimeTypes = listOf(
            "audio/x-mpegurl",
            "audio/mpegurl",
            "application/vnd.apple.mpegurl",
            "audio/x-scpls",
            "application/xspf+xml",
        ).joinToString(",") { "'$it'" }
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.DURATION} > 0 AND " +
            "(${MediaStore.Audio.Media.MIME_TYPE} IS NULL OR " +
            "${MediaStore.Audio.Media.MIME_TYPE} NOT IN ($playlistMimeTypes))"

        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, null)
            ?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumArtistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val folderCol = cursor.getColumnIndexOrThrow(folderColumn())

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(displayNameCol)
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName
                        ?: ""
                    tracks.add(
                        Track(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            title = title,
                            artist = cursor.getString(artistCol),
                            album = cursor.getString(albumCol),
                            albumArtist = cursor.getString(albumArtistCol),
                            trackNumber = if (cursor.isNull(trackCol)) null else cursor.getInt(trackCol),
                            year = if (cursor.isNull(yearCol)) null else cursor.getInt(yearCol),
                            durationMs = cursor.getLong(durationCol),
                            size = cursor.getLong(sizeCol),
                            folder = folderOf(cursor.getString(folderCol)),
                        )
                    )
                }
            }
        tracks
    }

    /**
     * Builds the [MediaItem]s for a queue, each carrying the metadata MediaStore already
     * knows about it.
     *
     * This is not a nicety. A `MediaItem` built from a bare uri has NO metadata, and the
     * media notification / lock screen / Bluetooth head unit all render whatever the
     * *session* reports. With bare items that is empty until ExoPlayer's extractor has read
     * the file's tags, which happens some time AFTER the item becomes current — so at every
     * track change the controls kept showing the previous track (reported from a real device,
     * 2026-08-20). Attaching the metadata up front closes that window entirely: the moment an
     * item becomes current, the session already knows what it is.
     *
     * `artworkUri` points at MediaStore's album-art entry, which Media3's `BitmapLoader`
     * resolves lazily and only when it needs a bitmap — no image bytes are read here.
     * Embedded artwork still wins when present: the loader prefers `artworkData` (which the
     * extractor fills in) over `artworkUri`.
     *
     * Anything that is not a MediaStore audio uri (a `file://` path, a stale row) falls back
     * to a bare item rather than failing the whole queue.
     */
    suspend fun mediaItemsFor(context: Context, uris: List<String>): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val idsByUri = uris.associateWith { mediaStoreIdOf(it) }
            val ids = idsByUri.values.filterNotNull().distinct()
            val metadataById = if (ids.isEmpty()) emptyMap() else metadataForIds(context, ids)
            uris.map { uri ->
                val metadata = idsByUri[uri]?.let { metadataById[it] }
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(uri)
                    .apply { if (metadata != null) setMediaMetadata(metadata) }
                    .build()
            }
        }

    /** @return the trailing `_ID` of a MediaStore uri, or null for anything else. */
    private fun mediaStoreIdOf(uri: String): Long? = try {
        ContentUris.parseId(Uri.parse(uri))
    } catch (t: Throwable) {
        null
    }

    private fun metadataForIds(context: Context, ids: List<Long>): Map<Long, MediaMetadata> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
        )
        val selection = "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",") { "?" }})"
        val args = ids.map { it.toString() }.toTypedArray()
        val result = mutableMapOf<Long, MediaMetadata>()
        context.contentResolver
            .query(audioCollection(), projection, selection, args, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val displayNameCol =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumArtistCol =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(displayNameCol)
                    val albumId = if (cursor.isNull(albumIdCol)) null else cursor.getLong(albumIdCol)
                    // NO setDisplayTitle: the legacy MediaMetadataCompat bridge (Bluetooth
                    // AVRCP, older lock screens, Android Auto) renders the DISPLAY_* triplet
                    // when DISPLAY_TITLE is present and only falls back to title/artist/album
                    // otherwise. Setting just the display title turned the session's
                    // description from "Zeibekiko, Nikos Alexiou, Kefi Vol 1" into
                    // "Zeibekiko, null, null" — verified in `dumpsys media_session`.
                    result[id] = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(cursor.getString(artistCol)?.takeUnless { it == UNKNOWN })
                        .setAlbumTitle(cursor.getString(albumCol)?.takeUnless { it == UNKNOWN })
                        .setAlbumArtist(
                            cursor.getString(albumArtistCol)?.takeUnless { it == UNKNOWN },
                        )
                        // MediaStore packs disc and track into one number as
                        // disc * 1000 + track, so a plain read shows "1001" for track 1.
                        .setTrackNumber(
                            if (cursor.isNull(trackCol)) null else cursor.getInt(trackCol) % 1000,
                        )
                        .setArtworkUri(albumId?.let { ContentUris.withAppendedId(ALBUM_ART, it) })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                }
            }
        return result
    }

    /**
     * Returns a `data:image/jpeg;base64,...` string, or `null` when the track has no
     * artwork. Never returns a `content://` URI — whether the WebView can render one via
     * `convertFileSrc()` is unverified on this stack and must not be load-bearing.
     */
    suspend fun getArtwork(context: Context, trackId: Long): String? = withContext(Dispatchers.IO) {
        synchronized(artworkCache) {
            if (artworkCache.containsKey(trackId)) {
                return@withContext artworkCache[trackId]
            }
        }

        val trackUri = ContentUris.withAppendedId(audioCollection(), trackId)
        val bitmap = loadArtworkBitmap(context, trackUri)
        val dataUrl = bitmap?.let { encodeAsDataUrl(it) }

        synchronized(artworkCache) {
            artworkCache[trackId] = dataUrl
        }
        dataUrl
    }

    /**
     * Artwork as a [Bitmap] for a track uri. The widget needs pixels, not the base64 data
     * URL [getArtwork] hands the WebView — `RemoteViews` can only take a bitmap, and
     * round-tripping through base64 to get one back would be silly.
     */
    suspend fun artworkBitmap(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            loadArtworkBitmap(context, Uri.parse(uri))
        } catch (t: Throwable) {
            null
        }
    }

    private fun loadArtworkBitmap(context: Context, trackUri: Uri): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.loadThumbnail(trackUri, Size(256, 256), null)
            } catch (t: Throwable) {
                // No embedded/available thumbnail for this track.
                null
            }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, trackUri)
                val embedded = retriever.embeddedPicture
                embedded?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (t: Throwable) {
                null
            } finally {
                retriever.release()
            }
        }
    }

    private fun encodeAsDataUrl(bitmap: Bitmap): String {
        val scaled = if (bitmap.width != 256 || bitmap.height != 256) {
            Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
        val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
}
