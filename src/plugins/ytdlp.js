import { registerPlugin } from '@capacitor/core'

/**
 * @typedef {Object} VideoInfo
 * @property {string} id
 * @property {string} title
 * @property {string | null} uploader
 * @property {number} duration
 * @property {string | null} thumbnail
 * @property {string} webpageUrl
 */

/**
 * @typedef {'128' | '256' | '320'} Bitrate
 */

/**
 * YouTube requires a PO token for media URLs. Extraction (title, duration) works without
 * one, but the actual transfer returns HTTP 403. yt-dlp cannot generate a token itself,
 * so it has to be supplied here.
 * @typedef {Object} YoutubeAuthOptions
 * @property {string} [poToken] e.g. "web.gvs+<token>". Paired with visitorData from the
 *   same session.
 * @property {string} [visitorData]
 * @property {string} [playerClient] Override the extractor client, e.g. "web_safari" or
 *   "tv_embedded".
 * @property {boolean} [usePoToken] Defaults to false: yt-dlp nightly extracts YouTube
 *   without a token, and forcing one forces the `web` player client, which as of 2026-08
 *   is YouTube's SABR-only delivery path that yt-dlp cannot download — so passing true is
 *   likely to make the download FAIL, not succeed. Kept for when that changes, and for a
 *   diagnostics screen. When true, the app mints a token in an offscreen WebView and
 *   caches it.
 * @property {boolean} [verbose] Passes -v to yt-dlp; the raw output arrives on the
 *   `progress` event.
 */

/**
 * @typedef {YoutubeAuthOptions & {
 *   url: string,
 *   jobId?: string,
 *   bitrate?: Bitrate,
 *   embedThumbnail?: boolean,
 * }} DownloadOptions
 * `jobId` — supply your own so you can cancel it later; otherwise the native side makes one.
 */

/**
 * @typedef {Object} DownloadResult
 * @property {string} jobId
 * @property {string} uri content:// or file:// URI of the saved file.
 * @property {string} displayName
 * @property {number} size
 * @property {string} title
 * @property {string} location Human-readable folder it was saved to, e.g. "Music/YTMP3".
 */

/**
 * @typedef {Object} StorageLocationInfo
 * @property {string} label Human-readable destination, e.g. "Music/YTMP3" or "SD card/Audio".
 * @property {boolean} isCustom false means the default music-library location.
 * @property {string | null} uri SAF tree URI when custom, otherwise null.
 * @property {boolean} cancelled True when the user dismissed the folder picker without
 *   choosing.
 */

/**
 * @typedef {Object} ProgressPayload
 * @property {string} jobId
 * @property {number} progress 0–100. yt-dlp reports -1 briefly before the first byte arrives.
 * @property {number} etaSeconds
 * @property {string} line Raw yt-dlp output line. Looks like debug noise but is
 *   load-bearing: the UI switches a job from "downloading" to "converting" when
 *   `line.includes('[ExtractAudio]')`.
 */

/**
 * @typedef {Object} FailurePayload
 * @property {string} jobId
 * @property {string} error
 */

/**
 * @typedef {Object} YtdlpPlugin
 * @property {() => Promise<{ ready: boolean, version: string }>} prepare
 *   Unpacks the bundled yt-dlp/ffmpeg. Slow only on first launch.
 * @property {(options: { url: string } & YoutubeAuthOptions) => Promise<VideoInfo>} getInfo
 * @property {(options: DownloadOptions) => Promise<DownloadResult>} download
 *   Both RESOLVES with the completion payload AND EMITS a `completed` event carrying the
 *   same payload — the composable listens to the event; the resolve is a fallback.
 *   Cancelling (`cancel()`) makes THIS promise reject with the code `"CANCELLED"` while a
 *   `cancelled` event also fires for the same jobId. That rejection is intentional, not an
 *   error path to "fix".
 * @property {(options: { jobId: string }) => Promise<{ cancelled: boolean }>} cancel
 * @property {(options?: { channel?: 'nightly' | 'stable' | 'master' }) => Promise<{ status: string, version: string }>} updateYtdlp
 *   Updates yt-dlp itself, without shipping a new APK. Defaults to the nightly channel,
 *   which as of 2026-08 is the only one that can extract YouTube.
 * @property {(options?: { channel?: 'nightly' | 'stable' | 'master' }) => Promise<{ installed: string | null, latest: string | null, updateAvailable: boolean }>} checkForUpdate
 *   Compares the installed yt-dlp against the newest release on its channel, without
 *   downloading anything.
 * @property {(options?: { forceRefresh?: boolean }) => Promise<{ poToken: string, visitorData: string }>} mintPoToken
 *   Mints (or returns the cached) PO token. Useful for a diagnostics screen.
 * @property {(options: { uri: string }) => Promise<void>} openFile
 *   Opens a finished file in the device's audio player, via the system chooser. Pass the
 *   `uri` from a DownloadResult.
 * @property {() => Promise<StorageLocationInfo>} getStorageLocation
 *   Where finished MP3s are written.
 *
 *   `pickStorageLocation` and `clearStorageLocation` (the SAF folder-picker pair from
 *   ytmp3) are DELIBERATELY NOT ported: files written into a user-picked SAF tree never
 *   land in MediaStore, which would break the requirement that downloads show up in this
 *   app's library. Everything downloaded here goes to the fixed default location.
 * @property {() => Promise<{ url?: string }>} consumeSharedUrl
 *   Returns and clears a URL shared into the app from another app. `url` is present only
 *   when a share was pending; otherwise the resolved object has no `url` key at all (the
 *   native side's `org.json.JSONObject.put(name, null)` drops a null-valued key rather
 *   than serialising a JSON `null`), so check `result.url` for truthiness — never
 *   `=== null`. Read-once: a second call right after immediately returns `{}`.
 * @property {() => Promise<{ text: string }>} readClipboard
 *   Reads the system clipboard natively. The Capacitor WebView never grants the async
 *   Clipboard API's read permission, so that browser API always fails there — this is
 *   the only working path for a Paste button. `text` is `""` when the
 *   clipboard holds no text; that resolves normally rather than rejecting.
 * @property {((eventName: 'progress', listener: (event: ProgressPayload) => void) => Promise<import('@capacitor/core').PluginListenerHandle>)
 *   & ((eventName: 'completed', listener: (event: DownloadResult) => void) => Promise<import('@capacitor/core').PluginListenerHandle>)
 *   & ((eventName: 'failed', listener: (event: FailurePayload) => void) => Promise<import('@capacitor/core').PluginListenerHandle>)
 *   & ((eventName: 'cancelled', listener: (event: { jobId: string }) => void) => Promise<import('@capacitor/core').PluginListenerHandle>)
 *   & ((eventName: 'sharedUrl', listener: () => void) => Promise<import('@capacitor/core').PluginListenerHandle>)} addListener
 *   `sharedUrl` fires when another app shares a link while this one is already open; call
 *   `consumeSharedUrl()` to collect it.
 */

/** @type {YtdlpPlugin} */
export const Ytdlp = registerPlugin('Ytdlp', {
  // Lets `npm run dev` work in a desktop browser, where there is no native side.
  web: () => import('./ytdlp.web').then((m) => new m.YtdlpWeb()),
})
