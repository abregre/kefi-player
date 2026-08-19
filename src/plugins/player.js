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
 */

/**
 * @typedef {Object} PlayerPermissionResult
 * @property {boolean} granted
 */

/**
 * @typedef {Object} PlayerPlugin
 * @property {() => Promise<PlayerSnapshot>} getPlayerState
 *   Always a live read off the native controller — never a cached value.
 * @property {(options: { uri: string, title?: string, artist?: string }) => Promise<PlayerSnapshot>} playTrack
 * @property {() => Promise<PlayerSnapshot>} pause
 * @property {() => Promise<PlayerSnapshot>} resume
 * @property {(options: { positionMs: number }) => Promise<PlayerSnapshot>} seek
 * @property {(options: { uris: string[], startIndex?: number }) => Promise<PlayerSnapshot>} setQueue
 *   Replaces the whole queue and starts playback at `startIndex` (default 0).
 * @property {() => Promise<PlayerSnapshot>} next
 * @property {() => Promise<PlayerSnapshot>} previous
 *   Media3's `seekToPrevious()` semantics: restarts the current track if more than ~3s in,
 *   otherwise moves to the previous queue item.
 * @property {(options: { enabled: boolean }) => Promise<PlayerSnapshot>} setShuffle
 * @property {(options: { mode: 'off' | 'one' | 'all' }) => Promise<PlayerSnapshot>} setRepeatMode
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
 * @property {(eventName: 'playerState', listener: (event: PlayerSnapshot) => void) => Promise<import('@capacitor/core').PluginListenerHandle>} addListener
 */

/** @type {PlayerPlugin} */
export const Player = registerPlugin('Player', {
  // Lets `npm run dev` work in a desktop browser, where there is no native side.
  web: () => import('./player.web').then((m) => new m.PlayerWeb()),
})
