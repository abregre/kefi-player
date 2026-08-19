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
 * @property {number} repeatMode
 * @property {number} queueLength
 * @property {number} currentIndex
 */

/**
 * @typedef {Object} PlayerPlugin
 * @property {() => Promise<PlayerSnapshot>} getPlayerState
 *   Always a live read off the native controller — never a cached value.
 * @property {(options: { uri: string, title?: string, artist?: string }) => Promise<PlayerSnapshot>} playTrack
 * @property {() => Promise<PlayerSnapshot>} pause
 * @property {() => Promise<PlayerSnapshot>} resume
 * @property {(options: { positionMs: number }) => Promise<PlayerSnapshot>} seek
 * @property {(eventName: 'playerState', listener: (event: PlayerSnapshot) => void) => Promise<import('@capacitor/core').PluginListenerHandle>} addListener
 */

/** @type {PlayerPlugin} */
export const Player = registerPlugin('Player', {
  // Lets `npm run dev` work in a desktop browser, where there is no native side.
  web: () => import('./player.web').then((m) => new m.PlayerWeb()),
})
