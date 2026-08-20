<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'
import TrackRow from '../components/TrackRow.vue'
import AlbumGrid from '../components/AlbumGrid.vue'
import ArtistList from '../components/ArtistList.vue'

// TODO(T19): the permission / denied / empty states (status is already tracked).
const { t } = useI18n()
const { status, query, visibleTracks, albums, artists, load } = useLibrary()
const player = usePlayer()

/**
 * Module level on purpose: leaving for Now Playing and coming back should land on the
 * grouping the user was last looking at, not reset to Songs.
 */
const TABS = ['songs', 'albums', 'artists']
const tab = ref('songs')

onMounted(() => {
  // One MediaStore query per process, shared at module level: coming back to this
  // tab re-renders from memory instead of re-querying.
  load()
})

const countLabel = computed(() => {
  if (tab.value === 'albums') return t('library.count.albums', { count: albums.value.length })
  if (tab.value === 'artists') return t('library.count.artists', { count: artists.value.length })
  const count = visibleTracks.value.length
  return count === 1 ? t('library.count.song') : t('library.count.songs', { count })
})

/**
 * The queue is the list the user is LOOKING at, not the whole library: playing the
 * third search hit should then continue with the fourth hit, not with whatever
 * happens to be third in the unfiltered library.
 * @param {import('../plugins/player').PlayerTrack} track
 */
function playFrom(track) {
  const list = visibleTracks.value
  const startIndex = list.findIndex((candidate) => candidate.id === track.id)
  player.setQueue({ uris: list.map((item) => item.uri), startIndex: Math.max(0, startIndex) })
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
  <section class="page">
    <header class="head">
      <h1 class="page-title">{{ t('library.title') }}</h1>
      <p class="count">{{ countLabel }}</p>
    </header>

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

    <div class="segmented" role="tablist" :aria-label="t('library.title')">
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

    <p v-if="status === 'loading'" class="hint">{{ t('library.loading') }}</p>

    <template v-else>
      <ul v-if="tab === 'songs'" class="list">
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
      <ArtistList v-else :artists="artists" @select="playGroup" />
    </template>

    <div v-if="status === 'ready' && visibleTracks.length === 0 && query" class="notice">
      <p class="notice-title">{{ t('library.noResults.title', { query }) }}</p>
      <p class="hint">{{ t('library.noResults.body') }}</p>
    </div>
  </section>
</template>

<style scoped>
.page {
  padding: calc(var(--safe-top) + 1.1rem) 1rem 1rem;
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

.segmented {
  display: flex;
  gap: 0.3rem;
  margin-top: 0.7rem;
  padding: 0.2rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 999px;
}

.segment {
  flex: 1;
  padding: 0.4rem 0.2rem;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.8rem;
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

.notice {
  margin-top: 1.5rem;
  text-align: center;
}

.notice-title {
  margin: 0 0 0.3rem;
  font-family: var(--font-display);
  font-size: 1rem;
}
</style>
