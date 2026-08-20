<script>
import { ref } from 'vue'

/**
 * Module scope, NOT inside the `<script setup>` block below — mirrors the fix `FolderList
 * .vue`'s `expanded` just got, for the same reason. "View in folder" opened from
 * `/playlist/:id` (a different view entirely, mounted instead of this one) needs to land
 * THIS view on the Folders tab the next time it mounts, and a `ref` declared inside
 * `<script setup>` resets to 'songs' every time this component is destroyed and recreated by
 * the router (there is no `<keep-alive>` on `<RouterView />`, confirmed in T8's notes).
 * Exported so `PlaylistView.vue` can drive it the same way it drives `FolderList.vue`'s
 * `expandFolder` — no prop drill, no router query param, no event bus, just the module-level
 * pattern this app already uses for exactly this kind of cross-navigation state.
 *
 * Behaviour change worth flagging: the Library's selected tab now also survives a trip to
 * Now Playing / Settings / a playlist and back (it used to reset to Songs on every remount);
 * that is a side effect of making it drivable from outside, not a separate goal.
 * @type {import('vue').Ref<'songs' | 'albums' | 'artists' | 'folders' | 'playlists'>}
 */
export const tab = ref('songs')
</script>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'
import { usePlaylists } from '../composables/usePlaylists'
import { useTrackMenu } from '../composables/useTrackMenu'
import { useLazyArtwork } from '../composables/useLazyArtwork'
import TrackRow from '../components/TrackRow.vue'
import AlbumGrid from '../components/AlbumGrid.vue'
import ArtistList from '../components/ArtistList.vue'
import FolderList, { expandFolder } from '../components/FolderList.vue'
import PlaylistList from '../components/PlaylistList.vue'
import TrackMenu from '../components/TrackMenu.vue'
import { useHorizontalSwipe } from '../composables/useHorizontalSwipe'

const { t } = useI18n()
const {
  status,
  permission,
  query,
  tracks,
  visibleTracks,
  albums,
  artists,
  folders,
  initialize,
  reload,
  requestPermission,
  openAppSettings,
} = useLibrary()
const player = usePlayer()
// Only `playlists` (the list-of-lists count for the header) is needed here — the tab body
// itself pulls Favorites/Recently added/CRUD straight from `usePlaylists()` in
// PlaylistList.vue, per that composable's own "every component shares one instance" design.
const { playlists } = usePlaylists()
const { openTrackMenu } = useTrackMenu()
const router = useRouter()

// Appended, not inserted: `stepTab` below is index-based with no wrap-around, so putting
// 'playlists' anywhere but the end would silently change which tab every existing swipe
// lands on (e.g. a Folders->Songs swipe habit would instead land on the new tab). Appending
// costs nothing and keeps every existing index stable.
const TABS = ['songs', 'albums', 'artists', 'folders', 'playlists']

const page = ref(null)
const segmented = ref(null)

/**
 * Swiping the page moves one tab, the way the segmented control reads left to right. No
 * wrap-around: a segmented control has ends, and silently jumping from Folders back to
 * Songs would lose the user's place in a list they can see.
 * @param {'left' | 'right'} direction
 */
function stepTab(direction) {
  const index = TABS.indexOf(tab.value)
  const next = direction === 'left' ? index + 1 : index - 1
  if (next < 0 || next >= TABS.length) return
  tab.value = TABS[next]
}

// The search field and the tab strip do their own horizontal dragging (caret, scroll).
useHorizontalSwipe(page, { onSwipe: stepTab, ignore: '.segmented, .search' })

// Four tabs no longer fit on a narrow screen, so the strip scrolls — which means the tab
// a swipe just selected can be off screen. Bring it back into view.
watch(tab, () => {
  nextTick(() => {
    segmented.value
      ?.querySelector('.segment.selected')
      ?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
  })
})

/*
 * Song rows show their own cover, so the list needs the same lazy loader the album grid
 * uses. The source includes `tab` because switching away and back remounts the <ul> with
 * fresh (unobserved) rows even when the track list itself has not changed.
 */
const songList = ref(null)
useLazyArtwork(songList, () => [tab.value, visibleTracks.value])

onMounted(() => {
  // Resolves the permission first, then queries MediaStore only if it is granted —
  // both idempotent, so returning to this tab costs nothing.
  initialize()
})

/**
 * `prompt` and `prompt-with-rationale` both mean Android will still show the dialog, so
 * both get the button that asks. Only `denied` is a dead end that needs system settings.
 */
const canAsk = computed(
  () => permission.value === 'prompt' || permission.value === 'prompt-with-rationale',
)
const blocked = computed(() => permission.value === 'denied')
const empty = computed(() => status.value === 'ready' && tracks.value.length === 0)

const countLabel = computed(() => {
  if (tab.value === 'albums') return t('library.count.albums', { count: albums.value.length })
  if (tab.value === 'artists') return t('library.count.artists', { count: artists.value.length })
  if (tab.value === 'folders') {
    const count = folders.value.length
    return count === 1 ? t('library.count.folder') : t('library.count.folders', { count })
  }
  if (tab.value === 'playlists') {
    // The count of LISTS shown, not tracks: the two fixed smart lists (Favorites, Recently
    // added) plus however many user playlists exist. Always >= 2 today, so the singular
    // key is currently unreachable — kept anyway for the same reason every other branch
    // here has one: a future where a smart list becomes optional should not need a new key.
    const count = 2 + playlists.value.length
    return count === 1 ? t('library.count.playlist') : t('library.count.playlists', { count })
  }
  const count = visibleTracks.value.length
  return count === 1 ? t('library.count.song') : t('library.count.songs', { count })
})

/**
 * A forced MediaStore query on a library this size comes back in well under a second,
 * so the raw `status === 'loading'` window is too short for the spin to read as motion —
 * the class would flip on and off inside a frame, which looked like the button was dead
 * on a real device. One full turn at the existing `scan-spin` keyframe timing (0.9s) is
 * the shortest floor that still reads as a deliberate response rather than a glitch.
 */
const MIN_SPIN_MS = 900

/** True from the moment the button is tapped until BOTH the query and the minimum spin
 * window are done — independent of `status`, so a query that resolves early cannot cut
 * the spin short. */
const scanning = ref(false)
/** Single source of truth for both `:disabled` and `.spinning`: the query is genuinely
 * still running, or the minimum spin window has not elapsed. Combining them here means
 * the two can never disagree, and a second tap mid-spin is a no-op either way. */
const scanBusy = computed(() => status.value === 'loading' || scanning.value)

let spinTimer = null

/** @param {number} ms */
function wait(ms) {
  return new Promise((resolve) => {
    spinTimer = setTimeout(resolve, ms)
  })
}

/**
 * `reload()` itself never rejects — `useLibrary`'s `load()` catches its own errors and
 * routes them into `status` ('error' | 'denied') instead of throwing — but the `finally`
 * is what actually matters here: whichever way the query ends, the minimum spin window
 * must not be stranded open or cut short by it.
 */
async function scan() {
  scanning.value = true
  try {
    await Promise.all([reload(), wait(MIN_SPIN_MS)])
  } finally {
    scanning.value = false
    spinTimer = null
  }
}

onUnmounted(() => {
  if (spinTimer) clearTimeout(spinTimer)
})

/**
 * The queue is the list the user is LOOKING at, not the whole library: playing the
 * third search hit should then continue with the fourth hit, not with whatever
 * happens to be third in the unfiltered library.
 * @param {import('../plugins/player').PlayerTrack} track
 */
function playFromList(list, track) {
  const startIndex = list.findIndex((candidate) => candidate.id === track.id)
  player.setQueue({ uris: list.map((item) => item.uri), startIndex: Math.max(0, startIndex) })
}

/** @param {import('../plugins/player').PlayerTrack} track */
function playFrom(track) {
  playFromList(visibleTracks.value, track)
}

/**
 * Album and artist cells play their own group, in the group's own order — the album
 * from its first track, an artist from the top of their list.
 * @param {{ tracks: import('../plugins/player').PlayerTrack[] }} group
 */
function playGroup(group) {
  if (group.tracks.length === 0) return
  player.setQueue({ uris: group.tracks.map((item) => item.uri), startIndex: 0 })
}

/**
 * Playlists tab rows select a LIST, not a track, so they navigate to a detail screen
 * instead of queuing anything here. `id` is `'favorites'`, `'recently-added'`, or a
 * playlist's numeric id — the exact shape `/playlist/:id` (T10's route) expects.
 * @param {'favorites' | 'recently-added' | number} id
 */
function goToPlaylist(id) {
  router.push(`/playlist/${id}`)
}

/**
 * The options sheet's one outward event (T8): carries the track whose folder the user wants
 * to see. `track.folder` IS the folder group's key already — `useLibrary.js`'s `groupBy
 * ('folder', '')` keys the "MediaStore reported no path" group by the empty string, the exact
 * value `track.folder` holds in that case (see the `PlayerTrack` typedef) — so there is no
 * "does this key exist" branch to write here; every track's `folder` names a real, always-
 * rendered group, including that one.
 * @param {import('../plugins/player').PlayerTrack} track
 */
async function onViewInFolder(track) {
  expandFolder(track.folder)
  tab.value = 'folders'
  // `expandFolder` and the `tab` switch above are both synchronous, so Vue queues a single
  // render flush for both; `nextTick` resolves once that flush's DOM patch has landed —
  // including `<FolderList>` itself freshly mounting — which is the earliest point the
  // folder's (already expanded) row exists to scroll to. Built by scanning every
  // `[data-folder-key]` row rather than a `querySelector` template string so a folder path
  // containing a quote character can never break the selector.
  await nextTick()
  const row = [...(page.value?.querySelectorAll('[data-folder-key]') ?? [])].find(
    (el) => el.dataset.folderKey === track.folder,
  )
  row?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
</script>

<template>
  <section ref="page" class="page">
    <header class="head">
      <h1 class="page-title">{{ t('library.title') }}</h1>
      <!-- Same visibility as the count: while a permission state owns the screen there is
           nothing to rescan yet. Shown through the empty state on purpose — a finished
           download that MediaStore already has is exactly the case with nothing to count. -->
      <div v-if="!canAsk && !blocked" class="head-actions">
        <!-- Downloads land in MediaStore from outside this screen, so there is no local
             event to react to; re-running the same listTracks() query on demand is the
             only way a finished download can appear without restarting the app. -->
        <button
          type="button"
          class="scan-button"
          :class="{ spinning: scanBusy }"
          :disabled="scanBusy"
          :aria-label="t('library.scan')"
          @click="scan()"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M23 4v6h-6" />
            <path d="M1 20v-6h6" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10" />
            <path d="M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
        </button>
        <p v-if="!empty" class="count">{{ countLabel }}</p>
      </div>
    </header>

    <!-- Permission has never been refused for good: offer to ask. -->
    <div v-if="canAsk" class="state">
      <p class="state-title">{{ t('permission.ask.title') }}</p>
      <p class="hint">{{ t('permission.ask.body') }}</p>
      <button type="button" class="cta" @click="requestPermission()">
        {{ t('permission.ask.action') }}
      </button>
    </div>

    <!-- Android will not show the dialog again; only Settings can undo this. -->
    <div v-else-if="blocked" class="state">
      <p class="state-title">{{ t('permission.denied.title') }}</p>
      <p class="hint">{{ t('permission.denied.body') }}</p>
      <button type="button" class="cta" @click="openAppSettings()">
        {{ t('permission.denied.action') }}
      </button>
    </div>

    <!-- Permission granted, MediaStore simply has no audio. -->
    <div v-else-if="empty" class="state">
      <p class="state-title">{{ t('library.empty.title') }}</p>
      <p class="hint">{{ t('library.empty.body') }}</p>
      <button type="button" class="cta" @click="reload()">{{ t('library.empty.action') }}</button>
    </div>

    <template v-else>
      <div class="search">
        <span class="search-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="10.5" cy="10.5" r="6" />
            <path d="m15 15 4.5 4.5" />
          </svg>
        </span>
        <input
          v-model="query"
          class="search-input"
          type="search"
          inputmode="search"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
          :placeholder="t('library.search')"
          :aria-label="t('library.search')"
        />
      </div>

      <div ref="segmented" class="segmented" role="tablist" :aria-label="t('library.title')">
        <button
          v-for="name in TABS"
          :key="name"
          type="button"
          role="tab"
          class="segment"
          :class="{ selected: tab === name }"
          :aria-selected="tab === name"
          @click="tab = name"
        >
          {{ t(`library.tab.${name}`) }}
        </button>
      </div>

      <p v-if="status === 'loading'" class="hint loading">{{ t('library.loading') }}</p>

      <template v-else>
        <ul v-if="tab === 'songs'" ref="songList" class="list">
          <li v-for="track in visibleTracks" :key="track.id">
            <TrackRow
              :track="track"
              :active="player.mediaId.value === track.uri"
              :playing="player.isPlaying.value && player.mediaId.value === track.uri"
              @select="playFrom"
              @options="openTrackMenu"
            />
          </li>
        </ul>
        <AlbumGrid v-else-if="tab === 'albums'" :albums="albums" @select="playGroup" />
        <ArtistList v-else-if="tab === 'artists'" :artists="artists" @select="playGroup" />
        <FolderList
          v-else-if="tab === 'folders'"
          :folders="folders"
          :current-uri="player.uri.value"
          :playing="player.isPlaying.value"
          @select="({ track, tracks }) => playFromList(tracks, track)"
          @options="openTrackMenu"
        />
        <!-- Playlists is a list of LISTS (Decision 8), so it does not filter by the search
             query the way songs/albums/artists/folders do: Favorites, Recently added and
             a user playlist are fixed by name/membership, not by matching track text, so
             there is no well-defined "search hit" for one of them. The search box stays
             visible for muscle memory but has no effect on this tab, deliberately. -->
        <PlaylistList v-else @select="goToPlaylist" />
      </template>

      <div v-if="status === 'ready' && visibleTracks.length === 0 && query" class="notice">
        <p class="notice-title">{{ t('library.noResults.title', { query }) }}</p>
        <p class="hint">{{ t('library.noResults.body') }}</p>
      </div>
    </template>
  </section>

  <!-- Fixed-position and renders nothing until a menu is open (T8), so its place in the
       template has no effect on this screen's layout. -->
  <TrackMenu @view-in-folder="onViewInFolder" />
</template>

<style scoped>
.page {
  padding: calc(var(--safe-top) + 1.1rem) 1rem 1rem;
  /* Fills the scroll area even when the list is short: the swipe listener lives on this
     element, and without the min-height a flick anywhere below the last row would land on
     the empty parent and do nothing — which is exactly how it behaved when first tried. */
  min-height: 100%;
}

.head {
  display: flex;
  /* Was `baseline`: fine for two lines of text, but the scan button is a circle with no
     text baseline of its own, so the row is centered now that it can hold one. */
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.8rem;
}

.page-title {
  font-family: var(--font-display);
  font-size: 1.6rem;
  margin: 0;
}

.head-actions {
  display: flex;
  align-items: center;
}

.count {
  margin: 0;
  /* Not allowed to shrink: when the header gets tight, the title (which can wrap) is
     the one that gives, never this line or the button next to it. */
  flex-shrink: 0;
  font-size: 0.76rem;
  color: var(--text-muted);
  white-space: nowrap;
}

/* Sized to MiniPlayer's `.toggle` (2.6rem / 1.4rem svg) — the app's one existing round
   icon-button idiom — rather than the smaller `.icon-button` seen elsewhere, so this is
   not a second, smaller tap target competing with the established one. */
.scan-button {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 2.3rem;
  height: 2.3rem;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
}

.scan-button svg {
  width: 1rem;
  height: 1rem;
}

.scan-button:disabled {
  opacity: 0.6;
}

.scan-button.spinning svg {
  animation: scan-spin 0.9s linear infinite;
}

@keyframes scan-spin {
  to {
    transform: rotate(1turn);
  }
}

/* Same accessibility carve-out MiniPlayer's disc spin makes: the disabled state already
   says "working", the rotation is decoration on top of it. */
@media (prefers-reduced-motion: reduce) {
  .scan-button.spinning svg {
    animation: none;
  }
}

.search {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0 0.65rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 999px;
}

.search:focus-within {
  border-color: var(--accent);
}

.search-icon {
  display: grid;
  place-items: center;
  color: var(--text-muted);
}

.search-icon svg {
  width: 1.1rem;
  height: 1.1rem;
}

.search-input {
  flex: 1;
  min-width: 0;
  padding: 0.6rem 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 0.9rem;
}

.search-input:focus {
  outline: none;
}

/* The WebView's own clear affordance is a grey square on a dark ground; the
   placeholder-driven empty state reads better than styling around it. */
.search-input::-webkit-search-cancel-button {
  appearance: none;
}

/* Four tabs of Greek do not fit on a 360px screen at any font scale worth supporting, so
   the strip scrolls instead of squeezing. The scrollbar is hidden because the overflowing
   segment is its own affordance, and swiping the page switches tabs anyway. */
.segmented {
  display: flex;
  margin-top: 0.7rem;
  padding: 0.2rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 999px;
  overflow-x: auto;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}

.segmented::-webkit-scrollbar {
  display: none;
}

.segment {
  flex: 1 0 auto;
  padding: 0.4rem 0.75rem;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.8rem;
  white-space: nowrap;
}

.segment.selected {
  background: var(--accent);
  color: var(--on-accent);
}

.list {
  list-style: none;
  margin: 0.6rem 0 0;
  padding: 0;
}

.hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.45;
}

.loading {
  margin-top: 1rem;
}

/* One shape for all three of permission-ask / permission-denied / nothing-found: they
   are the same kind of moment — the library cannot show a list, and the user needs one
   thing to do about it. */
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

.cta {
  margin-top: 0.9rem;
  padding: 0.6rem 1.1rem;
  border: 0;
  border-radius: 999px;
  background: var(--accent);
  color: var(--on-accent);
  font-size: 0.9rem;
}

.notice {
  margin-top: 1.5rem;
  text-align: center;
}

.notice-title {
  margin: 0 0 0.3rem;
  font-family: var(--font-display);
  font-size: 1rem;
  /* Echoes the user's own query back, which can be anything at all. */
  overflow-wrap: anywhere;
}
</style>
