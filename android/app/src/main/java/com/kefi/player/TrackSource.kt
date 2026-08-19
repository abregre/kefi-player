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
    )

    /** In-memory only, at most 64 entries — an explicit Must NOT Have forbids anything durable. */
    private val artworkCache = object : LinkedHashMap<Long, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String?>?): Boolean =
            size > 64
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
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0"

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
                        )
                    )
                }
            }
        tracks
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
