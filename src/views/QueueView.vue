<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'
import { useLazyArtwork } from '../composables/useLazyArtwork'
import { useDragReorder } from '../composables/useDragReorder'
import { useTrackMenu } from '../composables/useTrackMenu'
import TrackRow from '../components/TrackRow.vue'
import TrackMenu from '../components/TrackMenu.vue'

const router = useRouter()
const { t } = useI18n()
const player = usePlayer()
const { trackByUri } = useLibrary()
const { openTrackMenu } = useTrackMenu()

const queue = ref([])
const currentIndex = ref(0)
const trackList = ref(null)

const displayTracks = computed(() =>
  queue.value.map((entry, index) => {
    const track = trackByUri.value.get(entry.uri)
    // Native queue entries deliberately stay compact. Reuse the richer library record when it
    // exists, otherwise retain enough shape for TrackRow to render a real queue item.
    return {
      ...(track ?? { id: `queue-${index}-${entry.uri}`, durationMs: 0 }),
      uri: entry.uri,
      title: entry.title || track?.title || t('nowPlaying.unknownTitle'),
      artist: entry.artist ?? track?.artist ?? null,
    }
  }),
)

async function refreshQueue() {
  const result = await player.getQueue()
  queue.value = result.items
  currentIndex.value = result.currentIndex
}

// Native pushes update these refs when a track advances or any timeline edit lands. Refetch the
// ordered rows rather than attempting to duplicate Media3's index semantics locally.
watch([player.currentIndex, player.queueLength], refreshQueue, { immediate: true })
useLazyArtwork(trackList, () => displayTracks.value)

async function onDrop(from, to) {
  await player.moveQueueItem(from, to)
  await refreshQueue()
}

const { dragIndex, dragOffset, shiftFor } = useDragReorder(trackList, {
  rowCount: () => displayTracks.value.length,
  onDrop,
  ignore: '.row-menu',
})

function jumpTo(index) {
  if (index !== currentIndex.value) player.jumpToQueueIndex(index)
}

function openOptions(track, index) {
  // Only upcoming tracks can be edited. The current row and playback history retain their
  // ordinary menu, but never receive a queue-removal context.
  if (index > currentIndex.value) openTrackMenu(track, { queueIndex: index })
  else openTrackMenu(track)
}
</script>

<template>
  <section class="page">
    <header class="bar">
      <button type="button" class="icon-button" :aria-label="t('trackMenu.back')" @click="router.back()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
          <path d="m16 5-7 7 7 7" />
        </svg>
      </button>
      <h1 class="bar-title">{{ t('queue.title') }}</h1>
      <span class="bar-spacer" />
    </header>

    <div v-if="displayTracks.length === 0" class="state">
      <p class="state-title">{{ t('queue.empty.title') }}</p>
      <p class="hint">{{ t('queue.empty.body') }}</p>
    </div>

    <ul v-else ref="trackList" class="list">
      <li
        v-for="(track, index) in displayTracks"
        :key="`${index}-${track.uri}`"
        class="row-wrap"
        :class="{ dragging: dragIndex === index }"
        :data-row-index="index > currentIndex ? index : null"
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
          :active="index === currentIndex"
          :playing="index === currentIndex && player.isPlaying.value"
          @select="jumpTo(index)"
          @options="openOptions(track, index)"
        />
      </li>
    </ul>

    <TrackMenu />
  </section>
</template>

<style scoped>
.page {
  min-height: 100%;
  padding: calc(var(--safe-top) + 0.6rem) 1rem 1rem;
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.8rem;
}

.bar-title {
  margin: 0;
  font-size: 1rem;
}

.icon-button,
.bar-spacer {
  width: 2.4rem;
  height: 2.4rem;
}

.icon-button {
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text);
}

.icon-button svg { width: 1.4rem; height: 1.4rem; }
.list { margin: 0; padding: 0; list-style: none; }
.row-wrap { transition: transform 0.18s ease; }
.row-wrap.dragging { position: relative; z-index: 1; opacity: 0.92; }
.state { padding: 3rem 1rem; text-align: center; }
.state-title { margin: 0 0 0.45rem; font-weight: 700; }
.hint { margin: 0; color: var(--text-muted); }
</style>
