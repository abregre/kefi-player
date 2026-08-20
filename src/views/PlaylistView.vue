<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlaylists } from '../composables/usePlaylists'
import { usePlayer } from '../composables/usePlayer'
import { useLazyArtwork } from '../composables/useLazyArtwork'
import { useDragReorder } from '../composables/useDragReorder'
import { useTrackMenu } from '../composables/useTrackMenu'
import { Player } from '../plugins/player'
import TrackRow from '../components/TrackRow.vue'
import TrackMenu from '../components/TrackMenu.vue'
// `expandFolder`/`tab` are T13's module-scope shared state (FolderList.vue/LibraryView.vue),
// not a component import — importing a named export from a `.vue` file's non-`setup`
// `<script>` block works exactly like importing from a plain `.js` module. This file only
// reads/writes that shared state; it does not touch either file (Must NOT Do 3).
import { expandFolder } from '../components/FolderList.vue'
import { tab as libraryTab } from '../views/LibraryView.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { tracks } = useLibrary()
const {
  playlists,
  favoriteTracks,
  recentlyAdded,
  pathOf,
  renamePlaylist,
  deletePlaylist,
  reorderPlaylistPaths,
  errorMessage,
} = usePlaylists()
const player = usePlayer()
const { openTrackMenu } = useTrackMenu()

/** `'favorites' | 'recentlyAdded' | 'user'` — which of the three list kinds this route is. */
const kind = computed(() => {
  if (route.params.id === 'favorites') return 'favorites'
  if (route.params.id === 'recently-added') return 'recentlyAdded'
  return 'user'
})

const numericId = computed(() => Number(route.params.id))

/** The matching row from `usePlaylists().playlists` — undefined only for the instant before
 * the router guard (`src/router/index.js`) redirects an unknown/deleted id away. */
const summary = computed(() =>
  kind.value === 'user' ? playlists.value.find((playlist) => playlist.id === numericId.value) : undefined,
)

const title = computed(() => {
  if (kind.value === 'favorites') return t('playlists.favorites')
  if (kind.value === 'recentlyAdded') return t('playlists.recentlyAdded')
  return summary.value?.name ?? ''
})

/**
 * Lookup from a track's identity path to its full record — the shape of
 * `useLibrary.js`'s own `trackByUri` (`:195-199`), but keyed by `pathOf(track)` instead of
 * `uri`. A user playlist's stored membership is a list of paths, not tracks, so this is what
 * turns one back into the other; it is NOT a second derivation of the path itself, which
 * stays `pathOf`'s sole responsibility (usePlaylists.js).
 */
const trackByPath = computed(() => {
  const map = new Map()
  for (const track of tracks.value) map.set(pathOf(track), track)
  return map
})

/** Raw path list for a user playlist, in the database's own order. Empty for smart lists,
 * which have no stored membership at all. */
const playlistPaths = ref([])

/**
 * A user playlist's order is the `paths` array as Room returns it, NOT the library's title
 * sort — mapping over `playlistPaths` (rather than filtering `tracks`) is what preserves
 * that order. A path with no entry in `trackByPath` means the file was moved, renamed or
 * deleted since it was added (decision 3's accepted failure mode) and is skipped rather
 * than rendered as a blank row or thrown as an error.
 */
const userTracks = computed(() => {
  const resolved = []
  for (const path of playlistPaths.value) {
    const track = trackByPath.value.get(path)
    if (track) resolved.push(track)
  }
  return resolved
})

/** How many stored paths did NOT resolve to a track, for the "some entries are missing"
 * hint below. Always 0 for smart lists. Reactive over `trackByPath` too, so a library
 * rescan that finds a moved file again clears the count without a re-fetch. */
const missingCount = computed(() => {
  if (kind.value !== 'user') return 0
  return playlistPaths.value.filter((path) => !trackByPath.value.has(path)).length
})

const displayTracks = computed(() => {
  if (kind.value === 'favorites') return favoriteTracks.value
  if (kind.value === 'recentlyAdded') return recentlyAdded.value
  return userTracks.value
})

const countLabel = computed(() => {
  const count = displayTracks.value.length
  return count === 1 ? t('library.count.song') : t('library.count.songs', { count })
})

const emptyTitle = computed(() => {
  if (kind.value === 'favorites') return t('playlists.empty.favorites.title')
  if (kind.value === 'recentlyAdded') return t('playlists.empty.recentlyAdded.title')
  return t('playlists.empty.userPlaylist.title')
})
const emptyBody = computed(() => {
  if (kind.value === 'favorites') return t('playlists.empty.favorites.body')
  if (kind.value === 'recentlyAdded') return t('playlists.empty.recentlyAdded.body')
  return t('playlists.empty.userPlaylist.body')
})

/**
 * Refetches a user playlist's paths whenever the route param changes — navigating straight
 * from one playlist to another reuses this component instance, so an `onMounted`-only fetch
 * would leave the previous playlist's tracks on screen. `immediate: true` covers the first
 * mount too. Smart lists need no fetch at all: they are computeds over the library that
 * `usePlaylists.js` already keeps live.
 */
watch(
  () => route.params.id,
  async () => {
    if (kind.value !== 'user') {
      playlistPaths.value = []
      return
    }
    try {
      const { paths } = await Player.listPlaylistPaths({ id: numericId.value })
      playlistPaths.value = paths
    } catch {
      // A failed fetch degrades to an empty list rather than a crash — the same posture
      // usePlaylists.js takes on every native call.
      playlistPaths.value = []
    }
  },
  { immediate: true },
)

/**
 * Own root for this screen's `<ul>`, exactly like `LibraryView.vue`'s `songList`
 * (`:67-68`). The source array there includes `tab.value` because switching tabs remounts
 * the list with fresh, unobserved rows even when the track array is unchanged; the
 * equivalent here is the route param, since navigating from one playlist straight to
 * another is the same "fresh rows, same-looking data" case.
 */
const trackList = ref(null)
useLazyArtwork(trackList, () => [route.params.id, displayTracks.value])

/**
 * `data-row-index` (below, in the template) is only rendered when `kind === 'user'` —
 * reusing the exact same three-way check the rest of this file already uses to
 * distinguish a user playlist from the two smart lists, rather than inventing a second
 * "is this reorderable" flag. `useDragReorder` is still wired up unconditionally: with no
 * `[data-row-index]` on a Favorites/Recently-added row, every `touchstart` on those
 * screens fails the `closest` lookup inside the composable and returns immediately, so the
 * gesture is a complete no-op there — nothing needs a runtime `if (kind === 'user')` guard
 * around the wiring itself.
 */
const { dragIndex, dragOffset, shiftFor } = useDragReorder(trackList, {
  rowCount: () => displayTracks.value.length,
  onDrop: onDragCommit,
  ignore: '.row-menu',
})

/**
 * `fromIndex`/`toIndex` are positions within `userTracks` (== `displayTracks` for a user
 * playlist, the only case this ever fires for). Builds the resolved list's new order,
 * writes it to `playlistPaths` optimistically so the row visibly lands in place the instant
 * the finger lifts, then persists it.
 *
 * `reorderPlaylistPaths` (usePlaylists.js) rewrites a playlist's ENTIRE stored order in one
 * transaction, so any path left out of what's written is a path permanently deleted, not
 * merely left where it was. `userTracks`/`resolved` only ever contains paths that DID
 * resolve to a track (decision 3's missing-file case, `missingCount` above, is exactly the
 * complement of that set) — dragging can only ever touch a path the user can see, so a path
 * that never resolved to a track never appears in `resolved` at all. Those unresolved paths
 * are read straight back out of `playlistPaths.value` (already known — this reuses
 * `missingCount`'s own source rather than recomputing it) and appended after the
 * reordered/visible ones, so the write always carries every path this playlist held before,
 * just possibly in a new relative order for the ones the drag could actually reach.
 * Appending (rather than trying to reproduce their exact prior interleaving) is the honest
 * choice here: they render as nothing on screen, so there is no position for them that this
 * screen ever showed the user, and reconstructing one would mean tracking positions this UI
 * never displayed.
 * @param {number} fromIndex
 * @param {number} toIndex
 */
async function onDragCommit(fromIndex, toIndex) {
  const resolved = [...userTracks.value]
  const [moved] = resolved.splice(fromIndex, 1)
  resolved.splice(toIndex, 0, moved)
  const resolvedPaths = resolved.map(pathOf)

  const resolvedPathSet = new Set(resolvedPaths)
  const unresolvedPaths = playlistPaths.value.filter((path) => !resolvedPathSet.has(path))
  const newPaths = [...resolvedPaths, ...unresolvedPaths]

  const previousPaths = playlistPaths.value
  playlistPaths.value = newPaths

  await reorderPlaylistPaths(numericId.value, newPaths)
  // reorderPlaylistPaths() never throws (usePlaylists.js's error policy): success always
  // clears errorMessage to '', so a non-empty value right after the await is the only
  // signal this particular write failed. Reverting in that case matters because the
  // alternative is worse than a no-op — leaving the optimistic order applied would show an
  // order that isn't actually persisted, and the next time this playlist is opened (a
  // fresh `listPlaylistPaths` fetch) it would silently snap back with nothing to explain why.
  if (errorMessage.value) {
    playlistPaths.value = previousPaths
  }
}

/**
 * "View in folder" from a playlist screen is a cross-view navigation: the Folders UI it
 * needs to land on lives in `LibraryView.vue`/`FolderList.vue`, which are T13's files, not
 * this one. T13 (landed earlier this batch, see the notepad's `## T13` entry) already
 * exported exactly the shared state this needs — `expandFolder` (`FolderList.vue`) and
 * `tab` (`LibraryView.vue`), both module-scope refs/functions, the same mechanism
 * `usePlayer.js` uses for its own module-level state — and documented this exact snippet as
 * what "T11" must call. No scroll-into-view step is needed here (unlike the same-view case
 * T13 handles inside `LibraryView.vue` itself): by the time `LibraryView` mounts fresh at
 * `/library`, `tab.value` is already `'folders'` and the target folder is already in
 * `expanded`, so `FolderList` renders open to it immediately with nothing left to trigger
 * after the navigation.
 * @param {import('../plugins/player').PlayerTrack} track
 */
function onViewInFolder(track) {
  expandFolder(track.folder)
  libraryTab.value = 'folders'
  router.push('/library')
}

/**
 * The queue is the list the user is LOOKING at — the same rule `playFromList`
 * (`LibraryView.vue:150-153`) applies to the Songs tab and folders.
 * @param {import('../plugins/player').PlayerTrack} track
 */
function playFrom(track) {
  const list = displayTracks.value
  const startIndex = list.findIndex((candidate) => candidate.id === track.id)
  player.setQueue({ uris: list.map((item) => item.uri), startIndex: Math.max(0, startIndex) })
}

const renaming = ref(false)
const renameValue = ref('')
const renameInput = ref(null)

function startRename() {
  renameValue.value = summary.value?.name ?? ''
  renaming.value = true
  nextTick(() => renameInput.value?.focus())
}

function cancelRename() {
  renaming.value = false
}

async function commitRename() {
  const name = renameValue.value.trim()
  renaming.value = false
  if (!name || name === summary.value?.name) return
  await renamePlaylist(numericId.value, name)
}

/**
 * The one confirmation gate Must NOT Have 12 requires. This app has no modal component of
 * its own yet (T8/T9 are separately solving the same "no text-input precedent" problem for
 * their own screens), so a native `confirm()` is the smallest thing that satisfies "behind a
 * confirm" without inventing a second dialog idiom the day before a third one might land.
 */
async function deleteThis() {
  if (!window.confirm(t('playlists.confirmDelete', { name: summary.value?.name ?? '' }))) return
  await deletePlaylist(numericId.value)
  router.replace('/library')
}
</script>

<template>
  <section class="page">
    <header class="bar">
      <button type="button" class="icon-button" @click="router.back()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
          <path d="M15 5.5 9 12l6 6.5" />
        </svg>
      </button>

      <input
        v-if="renaming"
        ref="renameInput"
        v-model="renameValue"
        class="rename-input"
        type="text"
        :aria-label="t('playlists.renameLabel')"
        @keyup.enter="commitRename"
        @keyup.esc="cancelRename"
      />
      <h1 v-else class="bar-title">{{ title }}</h1>

      <!-- Smart lists (decision 9) offer neither control. -->
      <div v-if="renaming" class="bar-actions">
        <button type="button" class="icon-button" @click="commitRename">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
            <path d="m5 13 4.5 4.5L19 7" />
          </svg>
        </button>
        <button type="button" class="icon-button" @click="cancelRename">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
            <path d="M6 6l12 12M18 6 6 18" />
          </svg>
        </button>
      </div>
      <div v-else-if="kind === 'user'" class="bar-actions">
        <button type="button" class="icon-button" :aria-label="t('playlists.rename')" @click="startRename">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M14.7 4.3 18 7.6 8 17.6l-4 1 1-4 9.7-10.3Z" />
          </svg>
        </button>
        <button type="button" class="icon-button" :aria-label="t('playlists.delete')" @click="deleteThis">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M5 6.5h14M9.5 6.5V4.8a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1.7M7 6.5l1 13.2a1 1 0 0 0 1 .9h6a1 1 0 0 0 1-.9l1-13.2" />
          </svg>
        </button>
      </div>
      <span v-else class="bar-spacer" />
    </header>

    <p v-if="displayTracks.length > 0" class="count">{{ countLabel }}</p>

    <!-- Path-keying's accepted cost (decision 3): tell the user rather than silently
         dropping entries that no longer resolve to a file. -->
    <p v-if="missingCount > 0" class="hint missing">
      {{ missingCount === 1 ? t('playlists.missingEntry') : t('playlists.missingEntries', { count: missingCount }) }}
    </p>

    <div v-if="displayTracks.length === 0" class="state">
      <p class="state-title">{{ emptyTitle }}</p>
      <p class="hint">{{ emptyBody }}</p>
    </div>

    <!-- `data-row-index` only for a user playlist (see the useDragReorder wiring comment
         above) — that single condition is both "can this be reordered" and "does
         useDragReorder's row lookup find anything here" at once. -->
    <ul v-else ref="trackList" class="list">
      <li
        v-for="(track, index) in displayTracks"
        :key="track.id"
        class="row-wrap"
        :class="{ dragging: dragIndex === index }"
        :data-row-index="kind === 'user' ? index : null"
        :style="
          dragIndex === index
            ? { transform: `translateY(${dragOffset}px)`, transition: 'none' }
            : shiftFor(index)
              ? { transform: `translateY(${shiftFor(index)}px)` }
              : null
        "
      >
        <TrackRow
          :track="track"
          :active="player.mediaId.value === track.uri"
          :playing="player.isPlaying.value && player.mediaId.value === track.uri"
          @select="playFrom"
          @options="openTrackMenu"
        />
      </li>
    </ul>
  </section>

  <TrackMenu @view-in-folder="onViewInFolder" />
</template>

<style scoped>
.page {
  padding: calc(var(--safe-top) + 0.6rem) 1rem 1rem;
  min-height: 100%;
}

.bar {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.bar-title {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.3rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bar-spacer {
  width: 2.4rem;
  flex-shrink: 0;
}

.bar-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.icon-button {
  width: 2.4rem;
  height: 2.4rem;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
}

.icon-button svg {
  width: 1.4rem;
  height: 1.4rem;
}

.rename-input {
  flex: 1;
  min-width: 0;
  padding: 0.4rem 0.6rem;
  border: 1px solid var(--accent);
  border-radius: 0.5rem;
  background: var(--surface-1);
  color: var(--text);
  font: inherit;
  font-size: 1rem;
}

.rename-input:focus {
  outline: none;
}

.count {
  margin: 0.6rem 0 0;
  font-size: 0.76rem;
  color: var(--text-muted);
}

.list {
  list-style: none;
  margin: 0.6rem 0 0;
  padding: 0;
}

/* The displacement animation for rows a drag is passing over — NOT for the dragged row
   itself, which sets `transition: none` inline (see the template) so it tracks the finger
   with zero lag instead of chasing it. */
.row-wrap {
  transition: transform 0.15s ease;
}

.row-wrap.dragging {
  position: relative;
  z-index: 1;
  background: var(--surface-1);
  border-radius: 0.6rem;
}

/* Same vestibular-motion carve-out as MiniPlayer.vue/TrackMenu.vue: the shift is
   informational (it shows where a row will land), not decorative, but the ANIMATION of
   that shift is skippable — turning it off just makes rows jump straight to their
   in-progress slot instead of sliding into it. */
@media (prefers-reduced-motion: reduce) {
  .row-wrap {
    transition: none;
  }
}

.hint {
  margin: 0.6rem 0 0;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.45;
}

.state {
  margin-top: 2rem;
  padding: 1.1rem;
  text-align: center;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 0.9rem;
}

.state-title {
  margin: 0 0 0.35rem;
  font-family: var(--font-display);
  font-size: 1.05rem;
  line-height: 1.3;
}
</style>
