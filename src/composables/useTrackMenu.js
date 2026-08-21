import { computed, readonly, ref } from 'vue'

/**
 * @typedef {import('../plugins/player').PlayerTrack} PlayerTrack
 */

// Module-level, declared OUTSIDE the exported factory — same reasoning as
// usePlaylists.js's playlists/favoritePaths refs: TrackMenu.vue is meant to be mounted
// once per view (the Songs tab, the Folders drill-down, a playlist's detail screen), and
// every one of those mounts must open/close the SAME sheet rather than each maintaining
// its own "which track is my menu open for" ref. Only one options sheet can ever be on
// screen at a time app-wide (it is a bottom sheet, not a per-row popover), so a single
// nullable ref is enough — there is no case where two tracks need independent open state
// simultaneously.
//
// This also answers Expected Outcome's "must be robust to the track disappearing": the
// ref holds the actual PlayerTrack OBJECT the caller had in hand at open time (e.g. a row
// out of `library.tracks`, `favoriteTracks`, or a playlist's own rendered list), not an id
// or path that gets re-resolved later. So a favourite whose underlying file was deleted
// between opening the sheet and tapping an action inside it still has a perfectly good
// `track.uri`/`track.title` to act on — there is nothing to re-look-up, and therefore
// nothing that can fail to resolve.
/** @type {import('vue').Ref<PlayerTrack | null>} */
const activeTrack = ref(null)
/** Queue index when the sheet was opened from QueueView; null everywhere else. */
const queueIndex = ref(null)

const isOpen = computed(() => activeTrack.value !== null)

/**
 * Opens the sheet for `track`. Called from a `TrackRow` `@options` handler (or any future
 * equivalent) with the exact track object the row was already holding.
 * @param {PlayerTrack} track
 */
function openTrackMenu(track, options = {}) {
  activeTrack.value = track
  queueIndex.value = Number.isInteger(options.queueIndex) ? options.queueIndex : null
}

/**
 * Closes the sheet. Safe to call even when already closed. This is also the function the
 * hardware-back seam needs — see the MOUNTING RECIPE / hardware-back note in
 * `.claude/notepads/kefi-player-playlists-tab/learnings.md`'s `## T8` section for exactly
 * how and where.
 */
function closeTrackMenu() {
  activeTrack.value = null
  queueIndex.value = null
}

export function useTrackMenu() {
  return {
    // readonly: every consumer is expected to go through openTrackMenu/closeTrackMenu
    // rather than assigning `.value` directly, so a stray `activeTrack.value = x` in some
    // future view can't silently bypass whatever TrackMenu.vue itself resets (its `step`,
    // its in-progress "new playlist" input) when a menu opens or closes.
    activeTrack: readonly(activeTrack),
    queueIndex: readonly(queueIndex),
    isOpen,
    openTrackMenu,
    closeTrackMenu,
  }
}
