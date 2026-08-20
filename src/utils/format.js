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
