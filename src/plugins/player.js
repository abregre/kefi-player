import { registerPlugin } from '@capacitor/core'

/**
 * @typedef {Object} PlayerSnapshot
 * @property {string | null} mediaId
 * @property {string | null} uri
 * @property {string | null} title
 * @property {string | null} artist
 * @property {number} currentPosition
 * @property {number} duration
 * @property {boolean} isPlaying
 * @property {boolean} shuffleModeEnabled
 * @property {'off' | 'one' | 'all'} repeatMode
 * @property {number} queueLength
 * @property {number} currentIndex
 */

/**
 * @typedef {Object} PlayerTrack
 * @property {number} id
 * @property {string} uri
 * @property {string} title
 * @property {string | null} artist
 * @property {string | null} album
 * @property {string | null} albumArtist
 * @property {number | null} trackNumber
 * @property {number | null} year
 * @property {number} durationMs
 * @property {number} size
 * @property {string} folder Containing directory as the user would recognise it
 *   (`Music/kefi-qa`), with no leading or trailing slash. Drives the library's Folders
 *   grouping; empty string when MediaStore reports no path.
 * @property {string} displayName The file name on disk, with extension (`Zeibekiko.mp3`).
 *   `folder + '/' + displayName` is the identity playlists and favourites key off.
 * @property {number} dateAdded Epoch SECONDS (not milliseconds) that MediaStore indexed this
 *   file at — when the scanner saw it, not when the user acquired it. Bulk-scanned libraries
 *   tie often at second resolution, since every file from one scan lands in the same second.
 * @property {string | null} mimeType The file's MIME type (`audio/mpeg`, `audio/mp4`), or
 *   `null` when MediaStore never recorded one. Identifies the real file format for the
 *   details view, instead of guessing from `displayName`'s extension.
 */

/**
 * @typedef {Object} QueueEntry
 * @property {string} uri
 * @property {string} title
 * @property {string | null} artist
 */

/**
 * @typedef {Object} PlayerPermissionResult
 * @property {boolean} granted
 * @property {'granted' | 'denied' | 'prompt' | 'prompt-with-rationale'} state
 *   Capacitor's own permission state. `prompt`/`prompt-with-rationale` mean asking is still
 *   allowed; `denied` means the system dialog will not appear again and only app settings
 *   can undo it. `granted: false` alone cannot tell those apart.
 */

/**
 * @typedef {Object} PlayerTrackUnavailable
 * @property {string | null} uri The failing queue item's uri (mirrors `PlayerSnapshot.uri`).
 * @property {string} reason Short code describing what failed, e.g. `'file_not_found'` |
 *   `'permission_denied'` (Media3's own `PlaybackException.errorCodeName` for anything else).
 */

/**
 * @typedef {Object} PlaybackSettings
 * @property {boolean} resumeTracks
 * @property {boolean} continuePlaylists
 * @property {boolean} controlsNotification
 */

/**
 * @typedef {Object} PlayerPlugin
 * @property {() => Promise<PlayerSnapshot>} getPlayerState
 *   Always a live read off the native controller — never a cached value.
 * @property {(options: { uri: string, title?: string, artist?: string }) => Promise<PlayerSnapshot>} playTrack
 * @property {() => Promise<PlayerSnapshot>} pause
 * @property {() => Promise<PlayerSnapshot>} resume
 * @property {(options: { positionMs: number }) => Promise<PlayerSnapshot>} seek
 * @property {(options: { uris: string[], startIndex?: number, playlistId?: number }) => Promise<PlayerSnapshot>} setQueue
 *   Replaces the whole queue and starts playback at `startIndex` (default 0).
 * @property {() => Promise<{ items: QueueEntry[], currentIndex: number }>} getQueue
 * @property {(options: { from: number, to: number }) => Promise<PlayerSnapshot>} moveQueueItem
 * @property {(options: { index: number }) => Promise<PlayerSnapshot>} removeQueueItem
 * @property {(options: { index: number }) => Promise<PlayerSnapshot>} jumpToQueueIndex
 * @property {() => Promise<PlayerSnapshot>} clearQueue
 *   Empties the queue and stops playback. Unlike `removeQueueItem` this also discards the
 *   current item — that is the point of it.
 * @property {() => Promise<PlayerSnapshot & { removed: number }>} clearPlayedQueueItems
 *   Drops every entry before the current one; playback is untouched and the playing track
 *   becomes index 0. `removed` is how many went, so a no-op (`0`) is distinguishable from a
 *   real edit — the snapshot alone cannot say.
 * @property {(options: { uri: string }) => Promise<PlayerSnapshot>} addToQueue
 * @property {(options: { uri: string }) => Promise<PlayerSnapshot>} playNext
 * @property {() => Promise<PlayerSnapshot>} next
 * @property {() => Promise<PlayerSnapshot>} previous
 *   Media3's `seekToPrevious()` semantics: restarts the current track if more than ~3s in,
 *   otherwise moves to the previous queue item.
 * @property {(options: { enabled: boolean }) => Promise<PlayerSnapshot>} setShuffle
 * @property {(options: { mode: 'off' | 'one' | 'all' }) => Promise<PlayerSnapshot>} setRepeatMode
 * @property {() => Promise<object>} getSoundSettings
 * @property {(options: { enabled: boolean }) => Promise<object>} setEqualizerEnabled
 * @property {(options: { preset: number }) => Promise<object>} setEqualizerPreset
 * @property {(options: { levels: number[] }) => Promise<object>} setEqualizerBandLevels
 * @property {(options: { enabled: boolean }) => Promise<object>} setVolumeBoosterEnabled
 * @property {(options: { gainMb: number }) => Promise<object>} setVolumeBoosterGain
 * @property {() => Promise<PlaybackSettings>} getPlaybackSettings
 *   Reads the per-track resume switch. Native-owned, because PlaybackService consumes it with
 *   no WebView running.
 * @property {(options: { enabled: boolean }) => Promise<PlaybackSettings>} setResumeTracksEnabled
 *   Turns per-track resume on or off. Turning it OFF also discards every stored resume point.
 * @property {(options: { enabled: boolean }) => Promise<PlaybackSettings>} setContinuePlaylistsEnabled
 * @property {(options: { enabled: boolean }) => Promise<PlaybackSettings>} setControlsNotificationEnabled
 * @property {() => Promise<{ tracks: PlayerTrack[] }>} listTracks
 *   Device-wide MediaStore query, on demand (no cache/database). Rejects with a message
 *   containing "permission" when the audio permission has not been granted.
 * @property {(options: { trackId: number }) => Promise<{ dataUrl: string | null }>} getArtwork
 *   `dataUrl` is a `data:image/jpeg;base64,...` string, or `null` when the track has no
 *   artwork.
 * @property {() => Promise<PlayerPermissionResult>} checkAudioPermission
 *   Reads the current permission state without prompting
 *   (`READ_MEDIA_AUDIO` on API 33+, `READ_EXTERNAL_STORAGE` below).
 * @property {() => Promise<PlayerPermissionResult>} requestAudioPermission
 *   Prompts the user if not already granted, then resolves with the resulting state.
 * @property {() => Promise<{ supported: boolean }>} requestWidgetPin
 *   Asks the launcher to add the home-screen widget. Resolves `{ supported: false }` on a
 *   launcher that cannot pin widgets, which is the UI's cue to hide the offer.
 * @property {() => Promise<void>} openAppSettings
 *   Opens this app's page in system Settings — the only route back once the audio
 *   permission is `denied`, since Android then refuses to show the dialog again.
 * @property {() => Promise<{ playlists: Array<{ id: number, name: string, count: number }> }>} listPlaylists
 *   Every user-created playlist with its live track count. Does NOT include the Favorites or
 *   Recently added smart lists — those are computed client-side from `listFavorites`/`listTracks`.
 * @property {(options: { name: string }) => Promise<{ id: number }>} createPlaylist
 *   Creates an empty playlist and resolves its new id.
 * @property {(options: { id: number, name: string }) => Promise<void>} renamePlaylist
 * @property {(options: { id: number }) => Promise<void>} deletePlaylist
 *   Deletes the playlist and all its items. Not reversible; the UI confirms before calling this.
 * @property {(options: { id: number, paths: string[] }) => Promise<void>} addToPlaylist
 *   Appends `paths` to the end of the playlist, in the order given. A `path` is
 *   `folder + '/' + displayName` (see {@link PlayerTrack}`.displayName`) — the identity chosen
 *   with the user over the MediaStore numeric id. It does NOT survive the file being moved or
 *   renamed; that loss is an accepted consequence, not a bug, and every method below that takes
 *   or returns a `path`/`paths` uses this same identity. Idempotent per path: a path already in
 *   the playlist is not appended a second time.
 * @property {(options: { id: number, path: string }) => Promise<void>} removeFromPlaylist
 * @property {(options: { id: number, paths: string[] }) => Promise<void>} reorderPlaylist
 *   `paths` is the FULL new order, not a from/to index pair — the caller always sends every
 *   path the playlist holds, rewritten in one transaction, never a partial patch.
 * @property {(options: { id: number }) => Promise<{ paths: string[] }>} listPlaylistPaths
 *   Current membership, in playlist order.
 * @property {() => Promise<{ paths: string[] }>} listFavorites
 * @property {(options: { path: string, favorite: boolean }) => Promise<{ favorite: boolean }>} setFavorite
 *   Sets (not toggles) favourite status for one path and echoes back the resulting state.
 * @property {(options: { uri: string, type: 'ringtone' | 'notification' }) => Promise<{ granted: boolean }>} setAsRingtone
 *   Sets the device ringtone or notification tone to `uri`. Gated behind the `WRITE_SETTINGS`
 *   special permission: resolves `{ granted: false }` rather than rejecting when it is absent,
 *   which is the UI's cue to route the user to system settings instead of showing an error.
 * @property {(options: { uri: string }) => Promise<{ absolutePath: string | null, bitrate: number | null, sampleRate: number | null }>} getFileDetails
 *   Fetched ON DEMAND, only when the track menu's details step actually opens — never called
 *   as part of `listTracks`, since reading a file's header for every row in the library would
 *   be far too costly to pay up front. `bitrate`/`sampleRate` come from a fresh
 *   `MediaMetadataRetriever` read; either is `null` when the file simply doesn't carry that
 *   value, which is a normal outcome, not an error. `absolutePath` is a best-effort,
 *   display-only value (never an identity key, never used for a write) that can also be
 *   `null` on some devices/API levels where scoped storage does not expose it.
 * @property {(options: { uri: string }) => Promise<{ deleted: boolean, reason?: string }>} deleteTrack
 *   Deletes the file at `uri` from device storage. Three real outcomes, not just the two the
 *   type signature above shows: resolves `{ deleted: true }` when the file was actually
 *   deleted; resolves `{ deleted: false, reason: 'cancelled' }` when the user declines the
 *   OS-owned delete confirmation — a normal, expected outcome, never surfaced as an error; or
 *   the promise REJECTS for a genuine unexpected failure (a malformed uri, an I/O error, a row
 *   that was already gone). On Android this always routes through an OS-owned confirmation
 *   dialog before anything is removed, and which exact dialog appears varies by Android
 *   version — a detail the caller never needs to branch on differently, but should know
 *   exists, the same way `setAsRingtone`'s own outcome can redirect the user to system
 *   Settings.
 * @property {(eventName: 'playerState', listener: (event: PlayerSnapshot) => void) => Promise<import('@capacitor/core').PluginListenerHandle>} addListener
 *   Also fires for `eventName: 'trackUnavailable'` with a {@link PlayerTrackUnavailable}
 *   payload: emitted whenever the native controller fails to play a queue item (deleted/
 *   permission-revoked file) and automatically skips ahead to the next item — the queue
 *   never stalls silently. Separate from `playerState`; a `playerState` update for the
 *   now-current item still follows immediately after.
 */

/** @type {PlayerPlugin} */
export const Player = registerPlugin('Player', {
  // Lets `npm run dev` work in a desktop browser, where there is no native side.
  web: () => import('./player.web').then((m) => new m.PlayerWeb()),
})
