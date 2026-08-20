/**
 * Ported from /mnt/X/myProjects/ytmp3/src/utils/format.ts:1-8 (TypeScript there,
 * plain JS + JSDoc here — this project has no TypeScript, deliberately).
 * @param {number} totalSeconds
 * @returns {string} `m:ss`, or `h:mm:ss` past an hour, or `--:--` for junk input.
 */
export function formatDuration(totalSeconds) {
  if (!Number.isFinite(totalSeconds) || totalSeconds <= 0) return '--:--'
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = Math.floor(totalSeconds % 60)
  const pad = (n) => String(n).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`
}

/**
 * The native layer speaks milliseconds everywhere (`durationMs`, `currentPosition`),
 * so every caller would otherwise divide by 1000 at the call site.
 * @param {number | null | undefined} ms
 * @returns {string}
 */
export function formatMs(ms) {
  return formatDuration((ms ?? 0) / 1000)
}

/**
 * Ported from /mnt/X/myProjects/ytmp3/src/utils/format.ts:10-14.
 * Takes SECONDS (not ms) — this is for download-job ETAs, not playback position.
 * @param {number} seconds
 * @returns {string} `Ns left` / `Nm left`, or '' (empty string, not '0s') for junk/finished input —
 *   the empty string is deliberate so a job row with no ETA yet renders nothing in that slot,
 *   rather than a placeholder like '--' that would look like a stuck/broken value.
 */
export function formatEta(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return ''
  if (seconds < 60) return `${Math.round(seconds)}s left`
  return `${Math.round(seconds / 60)}m left`
}

/**
 * Ported from /mnt/X/myProjects/ytmp3/src/utils/format.ts:16-26.
 * Takes BYTES. Used for a completed download's file size.
 * @param {number | undefined} bytes
 * @returns {string} e.g. `'4.2 MB'`, or '' (empty string) for undefined/non-finite/<= 0 —
 *   same rationale as `formatEta`: nothing to show yet, so show nothing rather than a dash.
 */
export function formatBytes(bytes) {
  if (bytes === undefined || !Number.isFinite(bytes) || bytes <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  // Whole bytes get no decimal; everything past B gets one decimal unless >= 10 of that unit
  // (matches ytmp3 exactly — do not re-derive the threshold).
  return `${value.toFixed(value < 10 && unit > 0 ? 1 : 0)} ${units[unit]}`
}

// Ported from /mnt/X/myProjects/ytmp3/src/utils/format.ts:28. Matches youtube.com/youtu.be,
// with optional www./m./music. subdomain, http or https.
const YOUTUBE_URL = /^https?:\/\/(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)\//i

/**
 * Ported from /mnt/X/myProjects/ytmp3/src/utils/format.ts:30-32.
 * @param {string} value
 * @returns {boolean} true for any http(s) URL with no whitespace, not just YouTube — used to
 *   decide whether the paste field even looks like a link before trying anything smarter.
 */
export function looksLikeUrl(value) {
  return /^https?:\/\/\S+$/i.test(value.trim())
}

/**
 * Ported from /mnt/X/myProjects/ytmp3/src/utils/format.ts:34-36.
 * @param {string} value
 * @returns {boolean} true if `value` is specifically a YouTube URL (see `YOUTUBE_URL`).
 */
export function looksLikeYoutube(value) {
  return YOUTUBE_URL.test(value.trim())
}
