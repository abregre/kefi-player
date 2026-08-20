package com.kefi.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * yt-dlp needs a plain filesystem path to write to, but app-private storage is
 * invisible to music players and is wiped on uninstall. So downloads land in
 * private storage first and get published into the shared music library here.
 */
object MediaStoreExporter {

    private const val ALBUM_DIR = "YTMP3"

    /** Characters that are illegal in FAT/ext filenames or meaningful to MediaStore paths. */
    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")
    private val WHITESPACE_RUN = Regex("\\s+")

    data class Exported(val uri: String, val displayName: String, val size: Long)

    fun exportMp3(context: Context, source: File, title: String): Exported {
        val displayName = "${sanitize(title)}.mp3"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, source, displayName)
        } else {
            exportViaLegacyFile(context, source, displayName)
        }
    }

    private fun exportViaMediaStore(context: Context, source: File, displayName: String): Exported {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$ALBUM_DIR")
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, 1)
            // Hides the row from music players until the bytes are actually there.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore refused to create $displayName")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open $uri for writing")

            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (t: Throwable) {
            // Don't leave a half-written pending row behind.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        return Exported(uri.toString(), displayName, source.length())
    }

    private fun exportViaLegacyFile(context: Context, source: File, displayName: String): Exported {
        @Suppress("DEPRECATION")
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            ALBUM_DIR
        )
        musicDir.mkdirs()

        val target = uniqueFile(musicDir, displayName)
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }

        // Pre-Q there is no MediaStore insert, so nudge the scanner to index it.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/mpeg"), null)

        return Exported(Uri.fromFile(target).toString(), target.name, target.length())
    }

    /** MediaStore de-duplicates names itself on Q+; pre-Q we have to do it. */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName.removeSuffix(".mp3")
        var candidate = File(dir, displayName)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).mp3")
            n++
        }
        return candidate
    }

    /**
     * Video titles routinely contain characters that are illegal in filenames or
     * meaningful to MediaStore's path handling.
     */
    internal fun sanitize(title: String): String {
        val cleaned = title
            .filter { it.code >= 0x20 }
            .replace(ILLEGAL_FILENAME_CHARS, "_")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .trim('.')
        // Leave headroom under the common 255-byte filename limit for the
        // extension and any " (1)" de-duplication suffix.
        val truncated = if (cleaned.length > 120) cleaned.take(120).trim() else cleaned
        return truncated.ifBlank { "audio" }
    }
}
