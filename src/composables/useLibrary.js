import { computed, readonly, ref } from 'vue'
import { Preferences } from '@capacitor/preferences'
import { Player } from '../plugins/player'

/**
 * @typedef {import('../plugins/player').PlayerTrack} PlayerTrack
 */

/**
 * The library lives at module level so switching tabs (or opening Now Playing and
 * coming back) never re-queries MediaStore — same reasoning as `usePlayer`'s refs.
 * There is no database and no cache table by design (Must NOT Have); this ref IS
 * the cache, for the lifetime of the process.
 * @type {import('vue').Ref<PlayerTrack[]>}
 */
const tracks = ref([])
/** `'idle' | 'loading' | 'ready' | 'denied' | 'error'`. */
const status = ref('idle')
/**
 * The audio permission as Android sees it: `'unknown'` before the first check, then
 * Capacitor's own `granted | denied | prompt | prompt-with-rationale`. The two `prompt`
 * values mean the system dialog can still be shown, so the UI offers a button that asks;
 * `denied` means it never will again, so the only honest offer is system settings (T19).
 */
const permission = ref('unknown')
const errorMessage = ref('')
/** Free-text search, applied in memory over `tracks`. */
const query = ref('')
/**
 * Artwork by track id: a base64 data URL, or `null` for "asked, and this track has
 * none" — the distinction matters, because a plain object miss means "not asked yet"
 * and drives the lazy fetch, while a stored `null` must NOT trigger a retry.
 * A plain object rather than a Map so Vue's reactivity sees writes without a
 * `ref(new Map())` reassignment dance.
 * @type {import('vue').Ref<Record<number, string | null>>}
 */
const artwork = ref({})
/** Track ids with an in-flight `getArtwork` call, so a re-render cannot double-fetch. */
const artworkPending = new Set()

const PREF_HIDE_SHORT = 'kefi.library.hideShortTracks'
const PREF_MIN_DURATION = 'kefi.library.minDurationSec'
const PREF_SORT_BY = 'kefi.library.sortBy'
const PREF_SORT_ORDER = 'kefi.library.sortOrder'

const hideShortTracks = ref(true)
const minDurationSec = ref(60)
const sortBy = ref('title')
const sortOrder = ref('asc')

async function loadPreferences() {
  try {
    const [hideRes, minDurRes, sortRes, orderRes] = await Promise.all([
      Preferences.get({ key: PREF_HIDE_SHORT }),
      Preferences.get({ key: PREF_MIN_DURATION }),
      Preferences.get({ key: PREF_SORT_BY }),
      Preferences.get({ key: PREF_SORT_ORDER }),
    ])
    if (hideRes.value !== null) {
      hideShortTracks.value = hideRes.value === '1'
    } else {
      hideShortTracks.value = true
    }
    if (minDurRes.value !== null) {
      const parsed = parseInt(minDurRes.value, 10)
      if (!isNaN(parsed) && parsed > 0) minDurationSec.value = parsed
    }
    if (sortRes.value) sortBy.value = sortRes.value
    if (orderRes.value) sortOrder.value = orderRes.value
  } catch {}
}
void loadPreferences()

async function setHideShortTracks(enabled) {
  hideShortTracks.value = Boolean(enabled)
  try {
    await Preferences.set({ key: PREF_HIDE_SHORT, value: enabled ? '1' : '0' })
  } catch {}
}

async function setMinDurationSec(sec) {
  minDurationSec.value = Number(sec)
  try {
    await Preferences.set({ key: PREF_MIN_DURATION, value: String(sec) })
  } catch {}
}

async function setSorting(by, order) {
  sortBy.value = by
  if (order) sortOrder.value = order
  try {
    await Preferences.set({ key: PREF_SORT_BY, value: by })
    if (order) await Preferences.set({ key: PREF_SORT_ORDER, value: order })
  } catch {}
}

let loadPromise = null

/**
 * Greek-aware comparison for EVERY user-facing sort and search.
 *
 * `sensitivity: 'base'` folds both case and accents, which is the whole point: a
 * Greek library is full of tonos (Ωδή) and dialytika (Αϋπνία), and the default
 * `String.prototype.localeCompare`-free sort orders by UTF-16 code unit, which puts
 * `Ωδή` in a different place than any Greek speaker expects and makes `ωδη` fail to
 * match `Ωδή` outright. `numeric: true` additionally keeps `Track 2` before
 * `Track 10`, which album track lists hit immediately.
 */
const collator = new Intl.Collator('el', { sensitivity: 'base', numeric: true })

/**
 * Accent- and case-insensitive substring test built on the same collator, so search
 * and sort can never disagree about what two strings mean.
 *
 * There is no `Intl` primitive for "contains", so this slides a window of the
 * needle's length across the haystack and asks the collator whether that window and
 * the needle collate equal. Both sides are normalised to NFC first: a decomposed
 * source string (title from a tag editor that wrote NFD) would otherwise have its
 * combining marks sliced apart mid-window and never match.
 *
 * Cost is O(haystack length) collator compares per field, which is why callers run
 * it over the already-loaded in-memory list and never per keystroke over anything
 * larger.
 * @param {string | null | undefined} haystack
 * @param {string} needle Already NFC-normalised by the caller.
 * @returns {boolean}
 */
function collatorIncludes(haystack, needle) {
  if (!haystack) return false
  const text = haystack.normalize('NFC')
  const span = needle.length
  if (span === 0) return true
  if (text.length < span) return false
  for (let i = 0; i <= text.length - span; i += 1) {
    if (collator.compare(text.slice(i, i + span), needle) === 0) return true
  }
  return false
}

/** @param {unknown} err */
function messageOf(err) {
  if (err instanceof Error) return err.message
  if (typeof err === 'string') return err
  return 'Something went wrong'
}

/**
 * Reads the permission state WITHOUT prompting. Called before every load so the UI can
 * tell "never asked" from "shut out", which `granted: false` alone cannot express.
 * @returns {Promise<boolean>} whether audio may be read right now.
 */
async function checkPermission() {
  try {
    const result = await Player.checkAudioPermission()
    permission.value = result.state ?? (result.granted ? 'granted' : 'denied')
    return result.granted
  } catch (err) {
    permission.value = 'denied'
    errorMessage.value = messageOf(err)
    return false
  }
}

/**
 * One MediaStore query, on demand. Concurrent callers share the same in-flight
 * promise (two components mounting in the same tick must not fire two queries), and
 * a completed load is not repeated unless `reload()` asks for it.
 * @param {{ force?: boolean }} [options]
 */
async function load({ force = false } = {}) {
  if (loadPromise) return loadPromise
  if (!force && status.value === 'ready') return undefined

  status.value = 'loading'
  errorMessage.value = ''
  loadPromise = (async () => {
    try {
      const { tracks: found } = await Player.listTracks()
      tracks.value = found
      status.value = 'ready'
    } catch (err) {
      const message = messageOf(err)
      errorMessage.value = message
      // The native side rejects with a message containing "permission" when the
      // audio permission is missing — the one failure with its own actionable UI
      // (T19) rather than a generic error.
      status.value = /permission/i.test(message) ? 'denied' : 'error'
      tracks.value = []
    } finally {
      loadPromise = null
    }
  })()
  return loadPromise
}

const filteredBaseTracks = computed(() => {
  if (!hideShortTracks.value) return tracks.value
  const limitMs = (minDurationSec.value || 30) * 1000
  return tracks.value.filter((track) => (track.durationMs || 0) >= limitMs)
})

const sortedTracks = computed(() => {
  const list = [...filteredBaseTracks.value]
  const by = sortBy.value
  const order = sortOrder.value
  const multiplier = order === 'desc' ? -1 : 1

  return list.sort((a, b) => {
    if (by === 'dateAdded') {
      const dateA = a.dateAdded === 0 ? -Infinity : (a.dateAdded || 0)
      const dateB = b.dateAdded === 0 ? -Infinity : (b.dateAdded || 0)
      const diff = order === 'desc' ? dateB - dateA : dateA - dateB
      return diff || collator.compare(a.title, b.title)
    }
    if (by === 'duration') {
      const durA = a.durationMs || 0
      const durB = b.durationMs || 0
      const diff = order === 'desc' ? durB - durA : durA - durB
      return diff || collator.compare(a.title, b.title)
    }
    if (by === 'artist') {
      const diff = collator.compare(a.artist || '', b.artist || '')
      return (diff * multiplier) || collator.compare(a.title, b.title)
    }
    return collator.compare(a.title, b.title) * multiplier
  })
})

const visibleTracks = computed(() => {
  const needle = query.value.trim().normalize('NFC')
  if (!needle) return sortedTracks.value
  return sortedTracks.value.filter(
    (track) =>
      collatorIncludes(track.title, needle) ||
      collatorIncludes(track.artist, needle) ||
      collatorIncludes(track.album, needle),
  )
})

/**
 * Groups the visible tracks by one of their own fields. Pure in-memory work over the
 * single `listTracks` result — no second native call, no database (Must NOT Have).
 * Grouping follows the SEARCH result, not the whole library, so a query narrows all
 * three tabs consistently instead of showing albums that contain no matching song.
 * @param {'album' | 'artist'} field
 * @param {string} fallbackKey Bucket for tracks with that field empty.
 */
function groupBy(field, fallbackKey) {
  /** @type {Map<string, { key: string, name: string | null, tracks: PlayerTrack[] }>} */
  const groups = new Map()
  for (const track of visibleTracks.value) {
    const name = track[field] || null
    const key = name ?? fallbackKey
    if (!groups.has(key)) groups.set(key, { key, name, tracks: [] })
    groups.get(key).tracks.push(track)
  }
  for (const group of groups.values()) {
    // Inside an album, track order is the album's own order; title only breaks ties
    // (and covers files with no track number at all, like the QA fixtures).
    group.tracks.sort(
      (a, b) =>
        (a.trackNumber ?? Number.MAX_SAFE_INTEGER) - (b.trackNumber ?? Number.MAX_SAFE_INTEGER) ||
        collator.compare(a.title, b.title),
    )
  }
  return [...groups.values()].sort((a, b) => collator.compare(a.key, b.key))
}

const albums = computed(() => groupBy('album', ''))
/**
 * By containing directory. The one grouping that is not about tags at all: it is how the
 * files were actually put on the device, which is the only view that still works for a
 * library with no metadata worth speaking of.
 */
const folders = computed(() => groupBy('folder', ''))
/**
 * Lookup from a content uri to the full track record. The player's snapshot carries a
 * uri (and only a title/artist), but artwork needs the MediaStore `id` and the mini-
 * player wants the album name — both of which the library already knows.
 */
const trackByUri = computed(() => {
  const map = new Map()
  for (const track of tracks.value) map.set(track.uri, track)
  return map
})
const artists = computed(() => groupBy('artist', ''))

/**
 * Fetches artwork for one track at most once per process. Called by album cells when
 * they scroll into view (T16) rather than on mount, because artwork crosses the bridge
 * as a base64 JPEG and a large library would otherwise pull hundreds of them for cells
 * nobody looked at.
 * @param {number} trackId
 */
async function loadArtwork(trackId) {
  if (trackId in artwork.value || artworkPending.has(trackId)) return
  artworkPending.add(trackId)
  try {
    const { dataUrl } = await Player.getArtwork({ trackId })
    artwork.value = { ...artwork.value, [trackId]: dataUrl ?? null }
  } catch {
    // A track whose artwork cannot be read is indistinguishable, for the UI, from one
    // that has none: both render the placeholder. Cached as null so it is asked once.
    artwork.value = { ...artwork.value, [trackId]: null }
  } finally {
    artworkPending.delete(trackId)
  }
}

/**
 * Shows the system dialog, then loads immediately on success — the plan's requirement is
 * that granting populates the list with no app restart. A refusal leaves `permission` at
 * whatever Android now reports, which is how the UI decides between "ask again" and
 * "open settings".
 */
async function requestPermission() {
  try {
    const result = await Player.requestAudioPermission()
    permission.value = result.state ?? (result.granted ? 'granted' : 'denied')
    if (result.granted) await load({ force: true })
    return result.granted
  } catch (err) {
    errorMessage.value = messageOf(err)
    return false
  }
}

/**
 * The one call a view needs on mount: resolve the permission, then load only if it is
 * actually granted. Asking `listTracks` first and reading the rejection would work too,
 * but it cannot distinguish "never asked" from "denied for good".
 */
async function initialize() {
  if (await checkPermission()) await load()
}

export function useLibrary() {
  return {
    permission: readonly(permission),
    checkPermission,
    requestPermission,
    initialize,
    openAppSettings: () => Player.openAppSettings(),
    albums,
    artists,
    folders,
    trackByUri,
    artwork: readonly(artwork),
    loadArtwork,
    tracks: readonly(tracks),
    status: readonly(status),
    errorMessage: readonly(errorMessage),
    query,
    sortedTracks,
    visibleTracks,
    collator,
    hideShortTracks: readonly(hideShortTracks),
    setHideShortTracks,
    minDurationSec: readonly(minDurationSec),
    setMinDurationSec,
    sortBy: readonly(sortBy),
    sortOrder: readonly(sortOrder),
    setSorting,
    load,
    /** Re-queries MediaStore even if a previous load succeeded (pull-to-refresh, T19). */
    reload: () => load({ force: true }),
  }
}
