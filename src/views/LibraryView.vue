<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'
import { useLazyArtwork } from '../composables/useLazyArtwork'
import TrackRow from '../components/TrackRow.vue'
import AlbumGrid from '../components/AlbumGrid.vue'
import ArtistList from '../components/ArtistList.vue'
import FolderList from '../components/FolderList.vue'
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

const TABS = ['songs', 'albums', 'artists', 'folders']
const tab = ref('songs')

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
  const count = visibleTracks.value.length
  return count === 1 ? t('library.count.song') : t('library.count.songs', { count })
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
</script>

<template>
  <section ref="page" class="page">
    <header class="head">
      <h1 class="page-title">{{ t('library.title') }}</h1>
      <p v-if="!canAsk && !blocked && !empty" class="count">{{ countLabel }}</p>
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
            />
          </li>
        </ul>
        <AlbumGrid v-else-if="tab === 'albums'" :albums="albums" @select="playGroup" />
        <ArtistList v-else-if="tab === 'artists'" :artists="artists" @select="playGroup" />
        <FolderList
          v-else
          :folders="folders"
          :current-uri="player.uri.value"
          :playing="player.isPlaying.value"
          @select="({ track, tracks }) => playFromList(tracks, track)"
        />
      </template>

      <div v-if="status === 'ready' && visibleTracks.length === 0 && query" class="notice">
        <p class="notice-title">{{ t('library.noResults.title', { query }) }}</p>
        <p class="hint">{{ t('library.noResults.body') }}</p>
      </div>
    </template>
  </section>
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
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.8rem;
}

.page-title {
  font-family: var(--font-display);
  font-size: 1.6rem;
  margin: 0;
}

.count {
  margin: 0;
  font-size: 0.76rem;
  color: var(--text-muted);
  white-space: nowrap;
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
