package com.kefi.player

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PermissionState
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.kefi.player.library.FavoriteTrack
import com.kefi.player.library.LibraryDatabase
import com.kefi.player.library.Playlist
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Capacitor bridge between the WebView and [PlayerController]/[TrackSource]. T7 wired up
 * live state + transport (playTrack/pause/resume/seek). T9 added device-wide audio listing
 * and artwork lookup, gated behind the `audio` (API 33+) / `audioLegacy` (API < 33)
 * permission aliases. T10 adds queueing, next/previous, shuffle, and repeat, and fixes the
 * `seek` argument-coercion bug T9 found but deliberately left out of its own scope. T11 adds
 * the `trackUnavailable` push event, forwarding [PlayerController.onTrackUnavailable] the same
 * way [PlayerController.onState] is forwarded as `playerState`.
 */
@CapacitorPlugin(
    name = "Player",
    permissions = [
        Permission(alias = "audio", strings = [Manifest.permission.READ_MEDIA_AUDIO]),
        Permission(alias = "audioLegacy", strings = [Manifest.permission.READ_EXTERNAL_STORAGE]),
    ],
)
class PlayerPlugin : Plugin() {

    override fun load() {
        // Only set the push callbacks here. PlayerController must be first connected from
        // a user-gesture-originated plugin call (see its header comment) — never from
        // load(), which can run before any user interaction.
        PlayerController.onState = { snapshot ->
            notifyListeners("playerState", snapshot.toJSObject())
        }
        PlayerController.onTrackUnavailable = { uri, reason ->
            notifyListeners(
                "trackUnavailable",
                JSObject().apply {
                    put("uri", uri ?: JSONObject.NULL)
                    put("reason", reason)
                },
            )
        }
    }

    override fun handleOnDestroy() {
        PlayerController.onState = null
        PlayerController.onTrackUnavailable = null
        // Unbind from PlaybackService. Without this the app-scoped MediaController keeps the
        // service bound for the whole process lifetime, and a bound service cannot be
        // destroyed — so closing the app left a foreground notification nobody could dismiss.
        // Playback itself is unaffected: while something is playing the service is a started
        // foreground service and survives the unbind; the controller reconnects lazily on the
        // next plugin call (which is also what makes T8's Activity recreation work).
        val wasPlaying = PlayerController.lastKnownIsPlaying
        PlayerController.release()

        // Nothing playing and no UI left: there is nothing for the service — or its media
        // notification — to be for. Backing out of the app at /library destroys this Activity
        // without removing the task, so `onTaskRemoved` never fires and the paused
        // notification would otherwise sit in the shade indefinitely (Android will not let a
        // media notification with an active session be swiped away).
        //
        // Safe against the Activity-recreation path this same callback serves (T8's renderer
        // death): a stopped service is restarted by the next plugin call, and T12's persisted
        // queue/index/position comes straight back — paused, which is what it already was.
        if (!wasPlaying) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    @PluginMethod
    fun getPlayerState(call: PluginCall) {
        App.scope.launch {
            try {
                // Always a live read off the controller — never a cached value.
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not read player state: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun playTrack(call: PluginCall) {
        val uri = call.getString("uri")
        if (uri.isNullOrEmpty()) {
            call.reject("A uri is required")
            return
        }
        val title = call.getString("title")
        val artist = call.getString("artist")

        App.scope.launch {
            try {
                // Start from what MediaStore knows (title/artist/album/artwork), so the
                // notification and lock screen are right from the first frame, then let an
                // explicitly passed title/artist win over it.
                val base = TrackSource.mediaItemsFor(context, listOf(uri)).first()
                val item = if (title.isNullOrEmpty() && artist.isNullOrEmpty()) {
                    base
                } else {
                    base.buildUpon()
                        .setMediaMetadata(
                            base.mediaMetadata.buildUpon()
                                .apply {
                                    if (!title.isNullOrEmpty()) setTitle(title)
                                    if (!artist.isNullOrEmpty()) setArtist(artist)
                                }
                                .build(),
                        )
                        .build()
                }
                PlayerController.play(item)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not play track: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.pause()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not pause: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun resume(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.resume()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not resume: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun seek(call: PluginCall) {
        // Same PluginCall.getLong/org.json.Integer-boxing bug T9 found for trackId (see
        // getArtwork below): every realistic positionMs fits in an Int, so org.json boxes it
        // as Integer and a bare getLong("positionMs") silently returns null, rejecting every
        // real call. Same fallback fix.
        val positionMs = call.getLong("positionMs") ?: call.getInt("positionMs")?.toLong()
        if (positionMs == null) {
            call.reject("A positionMs is required")
            return
        }

        App.scope.launch {
            try {
                PlayerController.seek(positionMs)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not seek: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setQueue(call: PluginCall) {
        val uris = call.getArray("uris")
        if (uris == null || uris.length() == 0) {
            call.reject("A non-empty uris array is required")
            return
        }
        // startIndex is a small ordinal (queue position), never near Int.MAX_VALUE — getInt
        // with a default is correct here, unlike positionMs/trackId above which are boxed
        // ambiguously by org.json but still need Long on the Kotlin side.
        val startIndex = call.getInt("startIndex", 0) ?: 0

        App.scope.launch {
            try {
                // One MediaStore lookup for the whole queue, so every item carries its
                // title/artist/album/artwork before it is ever played. Bare uri items made
                // the notification lag a track behind at every transition (see
                // TrackSource.mediaItemsFor). mediaId stays == uri, as in playTrack.
                val items = TrackSource.mediaItemsFor(
                    context,
                    (0 until uris.length()).map { uris.getString(it) },
                )
                PlayerController.setQueue(items, startIndex)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not set queue: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun next(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.next()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not skip to next: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun previous(call: PluginCall) {
        App.scope.launch {
            try {
                PlayerController.previous()
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not skip to previous: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setShuffle(call: PluginCall) {
        val enabled = call.getBoolean("enabled")
        if (enabled == null) {
            call.reject("An enabled boolean is required")
            return
        }

        App.scope.launch {
            try {
                PlayerController.setShuffle(enabled)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not set shuffle: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setRepeatMode(call: PluginCall) {
        val mode = call.getString("mode")
        if (mode == null || repeatModeFromString(mode) == null) {
            call.reject("A mode of off|one|all is required")
            return
        }

        App.scope.launch {
            try {
                PlayerController.setRepeatMode(repeatModeFromString(mode)!!)
                call.resolve(PlayerController.snapshot().toJSObject())
            } catch (t: Throwable) {
                call.rejectWith("Could not set repeat mode: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun listTracks(call: PluginCall) {
        if (!hasAudioPermission()) {
            call.reject("Audio permission is required to list tracks")
            return
        }

        App.scope.launch {
            try {
                val tracks = TrackSource.listTracks(context)
                val array = JSArray()
                tracks.forEach { array.put(it.toJSObject()) }
                call.resolve(JSObject().put("tracks", array))
            } catch (t: Throwable) {
                call.rejectWith("Could not list tracks: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun getArtwork(call: PluginCall) {
        // PluginCall.getLong only matches a value already boxed as Long; org.json's parser
        // boxes small numbers (e.g. MediaStore _id values here) as Integer instead, so
        // getLong("trackId") alone silently returns null for every realistic trackId.
        val trackId = call.getLong("trackId") ?: call.getInt("trackId")?.toLong()
        if (trackId == null) {
            call.reject("A trackId is required")
            return
        }

        App.scope.launch {
            try {
                val dataUrl = TrackSource.getArtwork(context, trackId)
                call.resolve(JSObject().put("dataUrl", dataUrl ?: JSONObject.NULL))
            } catch (t: Throwable) {
                call.rejectWith("Could not load artwork: ${t.message}", t)
            }
        }
    }

    // ---- Playlists and favourites ------------------------------------------------------
    //
    // Unlike listTracks/getArtwork above, none of the following ten methods touch MediaStore,
    // so none of them check hasAudioPermission(): they only read and write this app's OWN Room
    // database (LibraryDatabase, T3), which the app owns outright and needs no runtime
    // permission for — the same reasoning setShareTargetEnabled/setAsRingtone apply to their
    // own private state below.

    @PluginMethod
    fun listPlaylists(call: PluginCall) {
        App.scope.launch {
            try {
                val playlists = libraryDao().listPlaylistsWithCounts()
                val array = JSArray()
                playlists.forEach { playlist ->
                    array.put(
                        JSObject()
                            .put("id", playlist.id)
                            .put("name", playlist.name)
                            .put("count", playlist.count),
                    )
                }
                call.resolve(JSObject().put("playlists", array))
            } catch (t: Throwable) {
                call.rejectWith("Could not list playlists: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun createPlaylist(call: PluginCall) {
        val name = call.getString("name")
        if (name.isNullOrBlank()) {
            call.reject("A non-blank name is required")
            return
        }

        App.scope.launch {
            try {
                // createdAt is a real timestamp, not a placeholder zero: listPlaylistsWithCounts
                // orders by it, and a zero here would make that ordering arbitrary.
                val id = libraryDao().insertPlaylist(
                    Playlist(name = name, createdAt = System.currentTimeMillis()),
                )
                call.resolve(JSObject().put("id", id))
            } catch (t: Throwable) {
                call.rejectWith("Could not create playlist: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun renamePlaylist(call: PluginCall) {
        val id = call.requiredId() ?: return
        val name = call.getString("name")
        if (name.isNullOrBlank()) {
            call.reject("A non-blank name is required")
            return
        }

        App.scope.launch {
            try {
                libraryDao().renamePlaylist(id, name)
                call.resolve()
            } catch (t: Throwable) {
                call.rejectWith("Could not rename playlist: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun deletePlaylist(call: PluginCall) {
        val id = call.requiredId() ?: return

        App.scope.launch {
            try {
                libraryDao().deletePlaylist(id)
                call.resolve()
            } catch (t: Throwable) {
                call.rejectWith("Could not delete playlist: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun addToPlaylist(call: PluginCall) {
        val id = call.requiredId() ?: return
        val paths = call.requiredPaths() ?: return

        App.scope.launch {
            try {
                // appendToPlaylist (LibraryDao) already owns "no duplicate path per playlist" —
                // idempotent per path, enforced by a UNIQUE index. No second dedupe layer here.
                libraryDao().appendToPlaylist(id, paths)
                call.resolve()
            } catch (t: Throwable) {
                call.rejectWith("Could not add to playlist: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun removeFromPlaylist(call: PluginCall) {
        val id = call.requiredId() ?: return
        val path = call.getString("path")
        if (path.isNullOrBlank()) {
            call.reject("A non-blank path is required")
            return
        }

        App.scope.launch {
            try {
                libraryDao().deleteItem(id, path)
                call.resolve()
            } catch (t: Throwable) {
                call.rejectWith("Could not remove from playlist: ${t.message}", t)
            }
        }
    }

    /**
     * Takes the FULL ordered list of paths, not a from/to index pair. Mirrors
     * [com.kefi.player.library.LibraryDao.reorderPlaylist]'s own choice: a complete rewrite in
     * one transaction leaves no index arithmetic to get wrong on either side of the bridge, and
     * there is no intermediate state a dropped call could leave half-applied.
     */
    @PluginMethod
    fun reorderPlaylist(call: PluginCall) {
        val id = call.requiredId() ?: return
        val paths = call.requiredPaths() ?: return

        App.scope.launch {
            try {
                libraryDao().reorderPlaylist(id, paths)
                call.resolve()
            } catch (t: Throwable) {
                call.rejectWith("Could not reorder playlist: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun listPlaylistPaths(call: PluginCall) {
        val id = call.requiredId() ?: return

        App.scope.launch {
            try {
                val paths = libraryDao().listPlaylistPaths(id)
                val array = JSArray()
                paths.forEach { array.put(it) }
                call.resolve(JSObject().put("paths", array))
            } catch (t: Throwable) {
                call.rejectWith("Could not list playlist paths: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun listFavorites(call: PluginCall) {
        App.scope.launch {
            try {
                val paths = libraryDao().listFavoritePaths()
                val array = JSArray()
                paths.forEach { array.put(it) }
                call.resolve(JSObject().put("paths", array))
            } catch (t: Throwable) {
                call.rejectWith("Could not list favorites: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun setFavorite(call: PluginCall) {
        val path = call.getString("path")
        if (path.isNullOrBlank()) {
            call.reject("A non-blank path is required")
            return
        }
        val favorite = call.getBoolean("favorite")
        if (favorite == null) {
            call.reject("A favorite boolean is required")
            return
        }

        App.scope.launch {
            try {
                if (favorite) {
                    libraryDao().insertFavorite(
                        FavoriteTrack(path = path, addedAt = System.currentTimeMillis()),
                    )
                } else {
                    libraryDao().deleteFavorite(path)
                }
                call.resolve(JSObject().put("favorite", favorite))
            } catch (t: Throwable) {
                call.rejectWith("Could not set favorite: ${t.message}", t)
            }
        }
    }

    @PluginMethod
    fun checkAudioPermission(call: PluginCall) {
        call.resolve(audioPermissionResult())
    }

    @PluginMethod
    fun requestAudioPermission(call: PluginCall) {
        if (hasAudioPermission()) {
            call.resolve(audioPermissionResult())
            return
        }
        requestPermissionForAlias(audioPermissionAlias(), call, "audioPermissionCallback")
    }

    @PermissionCallback
    private fun audioPermissionCallback(call: PluginCall) {
        call.resolve(audioPermissionResult())
    }

    /**
     * Opens this app's own page in system Settings. Needed by T19's denied state: once the
     * user has picked "Don't allow" twice, Android refuses to show the dialog again, so the
     * only honest thing the UI can offer is a way to the toggle that is actually blocking it.
     * `FLAG_ACTIVITY_NEW_TASK` because the Settings activity is started from an application
     * context, not from an Activity task.
     */
    /**
     * Asks the launcher to add the home-screen widget, which is the only supported way for
     * an app to place one (`AppWidgetManager.requestPinAppWidget`, API 26+). The launcher
     * shows its own confirmation; there is no callback worth waiting for, so this resolves
     * with whether the request could be made at all — some launchers do not support pinning,
     * and the UI hides the button when `supported` is false.
     */
    @PluginMethod
    fun requestWidgetPin(call: PluginCall) {
        val manager = AppWidgetManager.getInstance(context)
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.isRequestPinAppWidgetSupported
        if (supported) {
            manager.requestPinAppWidget(ComponentName(context, PlayerWidget::class.java), null, null)
        }
        call.resolve(JSObject().put("supported", supported))
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun setShareTargetEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled")
        if (enabled == null) {
            call.reject("An enabled boolean is required")
            return
        }
        Companion.setShareTargetEnabled(context, enabled)
        call.resolve()
    }

    /**
     * `WRITE_SETTINGS` is a special permission: unlike `audio` above, Android never shows a
     * grant dialog for it — the only way to get it is a manual toggle the user flips inside
     * system Settings — so this checks [Settings.System.canWrite] up front instead of calling
     * `requestPermissionForAlias`. When it is not yet granted this resolves `{ granted: false }`
     * rather than rejecting: "not granted yet" is a state the UI must be able to explain to the
     * user, not a plugin failure, and the call still opens `ACTION_MANAGE_WRITE_SETTINGS` so the
     * user lands on the exact toggle that is blocking it.
     */
    @PluginMethod
    fun setAsRingtone(call: PluginCall) {
        val uriString = call.getString("uri")
        if (uriString.isNullOrBlank()) {
            call.reject("A uri is required")
            return
        }
        val ringtoneType = when (call.getString("type")) {
            "ringtone" -> RingtoneManager.TYPE_RINGTONE
            "notification" -> RingtoneManager.TYPE_NOTIFICATION
            else -> null
        }
        if (ringtoneType == null) {
            call.reject("A type of ringtone|notification is required")
            return
        }

        if (!Settings.System.canWrite(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            call.resolve(JSObject().put("granted", false))
            return
        }

        try {
            RingtoneManager.setActualDefaultRingtoneUri(context, ringtoneType, Uri.parse(uriString))
            call.resolve(JSObject().put("granted", true))
        } catch (t: Throwable) {
            call.rejectWith("Could not set ringtone: ${t.message}", t)
        }
    }

    companion object {
        fun setShareTargetEnabled(context: Context, enabled: Boolean) {
            val comp = ComponentName(context, "${context.packageName}.ShareTarget")
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(comp, state, PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * `granted` is the answer the UI acts on; `state` is the extra detail T19's three states
     * need. Capacitor already tracks the distinction Android itself exposes — `prompt`
     * (never asked) and `prompt-with-rationale` (declined once, asking is still allowed) both
     * mean "the button should ask", while `denied` means the dialog will not appear again and
     * only system Settings can undo it. Guessing that from `granted: false` alone is
     * impossible, which is why the field is here rather than a locally-remembered "have we
     * asked yet" flag.
     */
    private fun audioPermissionResult(): JSObject = JSObject()
        .put("granted", hasAudioPermission())
        .put("state", getPermissionState(audioPermissionAlias()).toString())

    /**
     * `READ_MEDIA_AUDIO` (API 33+) replaced `READ_EXTERNAL_STORAGE` for reading audio;
     * minSdk is 24, so the legacy branch is reachable and mandatory, not defensive.
     */
    private fun audioPermissionAlias(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "audio" else "audioLegacy"

    private fun hasAudioPermission(): Boolean =
        getPermissionState(audioPermissionAlias()) == PermissionState.GRANTED

    /**
     * `PlayerController.Snapshot.repeatMode` is Media3's raw `Player.REPEAT_MODE_*` Int
     * (T6). The JS-facing contract is the STRING `off|one|all` instead (T18's repeat toggle
     * depends on this), so the mapping is applied only here, at the plugin boundary.
     */
    private fun repeatModeToString(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    /** Inverse of [repeatModeToString]; returns null for any string other than the 3 allowed. */
    private fun repeatModeFromString(mode: String): Int? = when (mode) {
        "off" -> Player.REPEAT_MODE_OFF
        "one" -> Player.REPEAT_MODE_ONE
        "all" -> Player.REPEAT_MODE_ALL
        else -> null
    }

    /**
     * PluginCall.reject only accepts Exception, but coroutine failures (and
     * PlayerController's suspend functions) surface as Throwable.
     */
    private fun PluginCall.rejectWith(message: String, cause: Throwable) {
        reject(message, cause as? Exception ?: Exception(cause))
    }

    /** The one shared handle onto T3's database, opened lazily on first use (see its KDoc). */
    private fun libraryDao() = LibraryDatabase.getInstance(context).libraryDao()

    /**
     * An id crosses the bridge from JS as a plain `number`. Same org.json boxing quirk as
     * `seek`'s positionMs and `getArtwork`'s trackId above: small values are boxed as Integer,
     * so `getLong` alone silently returns null for every realistic id. Rejects and returns null
     * when absent, so every caller can `?: return`.
     */
    private fun PluginCall.requiredId(): Long? {
        val id = getLong("id") ?: getInt("id")?.toLong()
        if (id == null) {
            reject("An id is required")
        }
        return id
    }

    /**
     * Reads `paths` as a JSArray and rejects if the key itself is missing. Individual elements
     * are converted defensively — see [JSArray.toStringList] — so one malformed entry from JS
     * cannot crash the whole call; it is just dropped.
     */
    private fun PluginCall.requiredPaths(): List<String>? {
        val array = getArray("paths")
        if (array == null) {
            reject("A paths array is required")
            return null
        }
        return array.toStringList()
    }

    /**
     * Drops any element that is not actually a String (e.g. a stray number or object), rather
     * than letting a malformed JS payload throw partway through a native database write.
     * Follows `setQueue`'s precedent for reading a JSArray of strings (~:185-213), made
     * defensive here because these writes are persisted, unlike that read-only uri list.
     */
    private fun JSArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { get(it) as? String }

    /**
     * Serialises straight to the field names T6 documented as this task's public contract
     * (`.claude/notepads/kefi-player-full-android-player/learnings.md`, `## T6`). Used for
     * both the `getPlayerState` resolve payload and the `playerState` push event, so pull
     * and push are interchangeable on the JS side. Nullable fields are written as explicit
     * JSON `null` (via `JSONObject.NULL`) rather than omitted, so every key is always
     * present regardless of whether a track is loaded. `repeatMode` crosses the bridge as
     * the string `off|one|all` (see [repeatModeToString]), not Media3's raw Int.
     */
    private fun PlayerController.Snapshot.toJSObject(): JSObject = JSObject().apply {
        put("mediaId", mediaId ?: JSONObject.NULL)
        put("uri", uri ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("artist", artist ?: JSONObject.NULL)
        put("currentPosition", currentPosition)
        put("duration", duration)
        put("isPlaying", isPlaying)
        put("shuffleModeEnabled", shuffleModeEnabled)
        put("repeatMode", repeatModeToString(repeatMode))
        put("queueLength", queueLength)
        put("currentIndex", currentIndex)
    }

    /**
     * Exact key set T15/T16 build the library UI on (`.claude/notepads/
     * kefi-player-full-android-player/learnings.md`, `## T9`): `id`, `uri`, `title`,
     * `artist`, `album`, `albumArtist`, `trackNumber`, `year`, `durationMs`, `size`.
     */
    private fun TrackSource.Track.toJSObject(): JSObject = JSObject().apply {
        put("id", id)
        put("uri", uri)
        put("title", title)
        put("artist", artist ?: JSONObject.NULL)
        put("album", album ?: JSONObject.NULL)
        put("albumArtist", albumArtist ?: JSONObject.NULL)
        put("trackNumber", trackNumber ?: JSONObject.NULL)
        put("year", year ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("size", size)
        put("folder", folder)
        put("displayName", displayName)
        put("dateAdded", dateAdded)
    }
}
