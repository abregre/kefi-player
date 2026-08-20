import { computed, readonly, ref } from 'vue'
import { Player } from '../plugins/player'
import { useLibrary } from './useLibrary'

/**
 * @typedef {import('../plugins/player').PlayerTrack} PlayerTrack
 */

/**
 * One user-created playlist as `listPlaylists` reports it — no track list, just enough
 * to render a row in the Playlists tab. The full path list is fetched separately, per
 * playlist, by the detail screen (T10) via `Player.listPlaylistPaths`.
 * @typedef {Object} PlaylistSummary
 * @property {number} id
 * @property {string} name
 * @property {number} count
 */

// Module-level state: declared OUTSIDE the exported function so every component (the
// Playlists tab, a playlist detail screen, the track options menu) shares one instance
// instead of each `usePlaylists()` call getting its own private copy. Mirrors
// usePlayer.js:8-32.
/** @type {import('vue').Ref<PlaylistSummary[]>} */
const playlists = ref([])
/**
 * Favourited paths, held as a `Set` inside a `ref` for O(1) `isFavorite` lookups instead
 * of an O(n) array scan per track row. `ref()` wraps an object value in Vue's own
 * `reactive()` (`toReactive` in @vue/reactivity), and `reactive()` instruments Map/Set
 * mutating methods (`add`/`delete`/`clear`) directly — so calling `.add()`/`.delete()` on
 * `favoritePaths.value` triggers the same dependency tracking a full reassignment would,
 * without copying the whole Set on every toggle. This is a deliberate reliance on Vue's
 * reactive-collection semantics, not an accidental miss: every write below mutates the
 * Set in place (see `toggleFavorite`) rather than rebuilding it, EXCEPT
 * `initializePlaylists`, which replaces the whole Set once on hydration (there is nothing
 * to preserve identity with yet).
 * @type {import('vue').Ref<Set<string>>}
 */
const favoritePaths = ref(new Set())
/** `'idle' | 'loading' | 'ready' | 'error'`, mirrors useLibrary.js:17. */
const status = ref('idle')
/** Message from the most recent failed hydrate or write, if any. */
const errorMessage = ref('')

const library = useLibrary()

/** @param {unknown} err */
function messageOf(err) {
  if (err instanceof Error) return err.message
  if (typeof err === 'string') return err
  return 'Something went wrong'
}

/**
 * THE single place `folder + '/' + displayName` is computed. Every other file (the
 * options menu, the playlist detail screen, drag-to-reorder) must import this rather than
 * re-deriving the join: a second derivation that disagreed by one slash would silently
 * orphan every favourite and playlist entry it touched, since the native side keys
 * membership off the exact string.
 *
 * `folder` is `''` when MediaStore reports no path (see the `PlayerTrack` typedef in
 * `player.js`) — in that case the result is just `displayName`, with no leading slash.
 * @param {PlayerTrack} track
 * @returns {string}
 */
function pathOf(track) {
  return track.folder === '' ? track.displayName : track.folder + '/' + track.displayName
}

/**
 * `dateAdded` is epoch SECONDS, MediaStore's index time (not acquisition time), and `0`
 * means the column came back NULL (T1's finding). Sorting "Recently added" by raw
 * `dateAdded` descending would put every undated track at the very TOP, ahead of
 * everything with a real timestamp — exactly backwards. Mapping `0` to `-Infinity` here
 * keeps the comparator a single descending numeric sort while guaranteeing undated
 * tracks always lose to any track with a real date, and only tie against each other
 * (broken by title below).
 * @param {PlayerTrack} track
 * @returns {number}
 */
function effectiveDateAdded(track) {
  return track.dateAdded === 0 ? -Infinity : track.dateAdded
}

/**
 * Favorites, sorted with the library's shared collator (never a second `Intl.Collator` —
 * consistent sort order app-wide is the whole point of that export). Built over a COPY:
 * `library.tracks` is `readonly()`, and `filter()` already returns a fresh array, so the
 * `.sort()` below never touches the original.
 */
const favoriteTracks = computed(() =>
  library.tracks.value
    .filter((track) => favoritePaths.value.has(pathOf(track)))
    .sort((a, b) => library.collator.compare(a.title, b.title)),
)

/**
 * Newest 15 tracks by `dateAdded` descending, ties (common on a bulk-scanned library, per
 * T1) broken by title. `[...tracks.value]` copies before sorting — same house pattern as
 * `useLibrary.js`'s `sortedTracks` (:139-141) — since `library.tracks` is `readonly()` and
 * must never be mutated in place.
 */
const recentlyAdded = computed(() =>
  [...library.tracks.value]
    .sort(
      (a, b) =>
        effectiveDateAdded(b) - effectiveDateAdded(a) || library.collator.compare(a.title, b.title),
    )
    .slice(0, 15),
)

/**
 * Hydrates playlists and favourites from the native side. Awaited in `main.js` before the
 * app mounts, so the Playlists tab and the options menu's Favourite/Unfavourite label are
 * correct on first paint instead of flipping a tick later — which is exactly why this must
 * NEVER throw: a rejection here would delay or break the app's very first render. Swallows
 * and degrades exactly like `initLocale()`/`initializeEasterEgg()` — a failure leaves
 * `playlists` empty and `favoritePaths` empty, which is a plain retry (or a later
 * `initializePlaylists()` call) away from correct, not a crash.
 *
 * Declared at module scope, not inside the `usePlaylists()` factory, so `main.js` can
 * import and call it bare in its `Promise.all([...])` — the same shape as `initLocale()`
 * and `initializeEasterEgg()`, both of which it sits alongside. It is also re-exposed on
 * `usePlaylists()`'s return value below, so a component could re-hydrate later the same
 * way it calls any other action.
 */
async function initializePlaylists() {
  status.value = 'loading'
  try {
    const [{ playlists: list }, { paths }] = await Promise.all([
      Player.listPlaylists(),
      Player.listFavorites(),
    ])
    playlists.value = list
    favoritePaths.value = new Set(paths)
    status.value = 'ready'
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
    status.value = 'error'
  }
}

/**
 * Error policy for every write action below (create/rename/delete/add/remove/reorder/
 * toggleFavorite): failures are recorded in `errorMessage` for the UI to surface (a toast,
 * per T19's territory) but never change `status` away from whatever it already was. A
 * single failed rename, for instance, must not blank out an already-hydrated Playlists
 * tab — only `initializePlaylists` (the hydrate-before-mount call) owns `status`.
 *
 * Local state is patched optimistically after each successful write instead of
 * refetching `listPlaylists`/`listFavorites` wholesale — cheaper, and consistent with how
 * `usePlayer.js` applies each call's own returned snapshot rather than re-pulling state.
 */
async function createPlaylist(name) {
  try {
    const { id } = await Player.createPlaylist({ name })
    playlists.value = [...playlists.value, { id, name, count: 0 }]
    errorMessage.value = ''
    return id
  } catch (err) {
    errorMessage.value = messageOf(err)
    return null
  }
}

/**
 * @param {number} id
 * @param {string} name
 */
async function renamePlaylist(id, name) {
  try {
    await Player.renamePlaylist({ id, name })
    playlists.value = playlists.value.map((playlist) =>
      playlist.id === id ? { ...playlist, name } : playlist,
    )
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
  }
}

/** @param {number} id */
async function deletePlaylist(id) {
  try {
    await Player.deletePlaylist({ id })
    playlists.value = playlists.value.filter((playlist) => playlist.id !== id)
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
  }
}

/**
 * @param {number} id
 * @param {PlayerTrack[]} tracks
 */
async function addToPlaylist(id, tracks) {
  const paths = tracks.map(pathOf)
  try {
    await Player.addToPlaylist({ id, paths })
    // The native side is idempotent per path (a path already in the playlist is not
    // appended twice), but this optimistic bump assumes the common case of adding tracks
    // not already present. A wrong count self-heals the next time the detail screen
    // reloads that playlist's paths.
    playlists.value = playlists.value.map((playlist) =>
      playlist.id === id ? { ...playlist, count: playlist.count + paths.length } : playlist,
    )
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
  }
}

/**
 * @param {number} id
 * @param {PlayerTrack} track
 */
async function removeFromPlaylist(id, track) {
  const path = pathOf(track)
  try {
    await Player.removeFromPlaylist({ id, path })
    playlists.value = playlists.value.map((playlist) =>
      playlist.id === id ? { ...playlist, count: Math.max(0, playlist.count - 1) } : playlist,
    )
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
  }
}

/**
 * The ONE place that reaches `Player.reorderPlaylist` — `reorderPlaylist` (below) is a thin
 * wrapper over this, not a second path to the plugin. `paths` is the FULL new order, not a
 * from/to index pair or a partial list: `Player.reorderPlaylist` rewrites the whole stored
 * order in one transaction (T3/T4's contract), so a path left out of `paths` is a path
 * permanently deleted from the playlist, not merely left in place. This is deliberately
 * path-shaped rather than track-shaped so a caller can include a path that has no matching
 * track right now (e.g. a file renamed/moved on disk since it was added — decision 3's
 * accepted "lost visibility" case) WITHOUT that path being silently dropped from storage
 * just because it can't currently be resolved to a track object. Does not touch
 * `playlists`' `count`: a reorder never changes membership.
 * @param {number} id
 * @param {string[]} paths
 */
async function reorderPlaylistPaths(id, paths) {
  try {
    await Player.reorderPlaylist({ id, paths })
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
  }
}

/**
 * Track-object convenience wrapper over `reorderPlaylistPaths`, for a caller whose entire
 * new order is made of real tracks (e.g. a playlist with nothing unresolved). A caller that
 * also needs to carry forward paths with no matching track must call
 * `reorderPlaylistPaths` directly with the full path list instead — see that function's doc
 * for why this one can't accept those paths at all (there is no track object for them).
 * @param {number} id
 * @param {PlayerTrack[]} tracks
 */
async function reorderPlaylist(id, tracks) {
  await reorderPlaylistPaths(id, tracks.map(pathOf))
}

/** @param {PlayerTrack} track */
function isFavorite(track) {
  return favoritePaths.value.has(pathOf(track))
}

/**
 * Sets (not toggles server-side, per `setFavorite`'s own contract) the opposite of the
 * track's current favourite state. Mutates `favoritePaths.value` in place on success —
 * see the reactivity note on the ref's declaration above for why that is enough to update
 * every consumer.
 * @param {PlayerTrack} track
 */
async function toggleFavorite(track) {
  const path = pathOf(track)
  const next = !favoritePaths.value.has(path)
  try {
    const { favorite } = await Player.setFavorite({ path, favorite: next })
    if (favorite) favoritePaths.value.add(path)
    else favoritePaths.value.delete(path)
    errorMessage.value = ''
  } catch (err) {
    errorMessage.value = messageOf(err)
  }
}

export function usePlaylists() {
  return {
    playlists: readonly(playlists),
    favoritePaths: readonly(favoritePaths),
    status: readonly(status),
    errorMessage: readonly(errorMessage),
    favoriteTracks,
    recentlyAdded,
    initializePlaylists,
    createPlaylist,
    renamePlaylist,
    deletePlaylist,
    addToPlaylist,
    removeFromPlaylist,
    reorderPlaylist,
    reorderPlaylistPaths,
    toggleFavorite,
    isFavorite,
    pathOf,
  }
}

export { initializePlaylists }
