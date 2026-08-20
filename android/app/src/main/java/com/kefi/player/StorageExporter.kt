package com.kefi.player

import android.content.Context
import java.io.File

/**
 * Publishes a finished MP3 to its final home.
 *
 * yt-dlp always writes to app-private storage first, because it needs a plain
 * filesystem path. This moves the result out into the shared music library via
 * [MediaStoreExporter].
 *
 * ytmp3's SAF folder-picker branch (a user-chosen tree URI via `DocumentFile`)
 * was deliberately NOT ported: a file written into a user-picked SAF tree never
 * enters MediaStore, so it would never show up in this app's own library — the
 * one thing every download here is supposed to do. This object is therefore a
 * thin wrapper over [MediaStoreExporter] with a fixed destination.
 */
object StorageExporter {

    /** Matches ytmp3's default-label constant; shown in the UI. */
    const val DEFAULT_LABEL = "Music/YTMP3"

    data class Exported(
        val uri: String,
        val displayName: String,
        val size: Long,
        /** Human-readable destination, for the UI. */
        val location: String,
    )

    fun export(context: Context, source: File, title: String): Exported {
        val exported = MediaStoreExporter.exportMp3(context, source, title)
        return Exported(exported.uri, exported.displayName, exported.size, DEFAULT_LABEL)
    }
}
