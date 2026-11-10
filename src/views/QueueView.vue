<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'
import { usePlaylists } from '../composables/usePlaylists'
import { useLazyArtwork } from '../composables/useLazyArtwork'
import { useDragReorder } from '../composables/useDragReorder'
import { useSwipeRemove } from '../composables/useSwipeRemove'
import { useTrackMenu } from '../composables/useTrackMenu'
import { formatMs } from '../utils/format'
import TrackRow from '../components/TrackRow.vue'
import TrackMenu from '../components/TrackMenu.vue'

const router = useRouter()
const { t } = useI18n()
const player = usePlayer()
const { trackByUri } = useLibrary()
const { openTrackMenu } = useTrackMenu()
const { createPlaylist, addToPlaylist } = usePlaylists()

const queue = ref([])
const currentIndex = ref(0)
const trackList = ref(null)

/**
 * Which overlay the header's actions button has open: `null` (none), `'menu'` (the three
 * queue-wide actions), `'confirmClear'` (the gate in front of the destructive one) or
 * `'savePlaylist'` (the name field). One ref rather than three booleans, because they are
 * mutually exclusive steps of a single sheet — the same shape `TrackMenu` uses for its own
 * confirm/details steps.
 */
const sheet = ref(null)
const playlistName = ref('')
const busy = ref(false)

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

/** Upcoming entries only — what "clear played" would leave behind, and what can be edited. */
const upcomingCount = computed(() => Math.max(0, displayTracks.value.length - currentIndex.value - 1))

const playedCount = computed(() => Math.min(currentIndex.value, displayTracks.value.length))

/**
 * Total duration of the current track and everything after it. Entries the library has no
 * record of contribute 0 rather than being guessed at, so this is a floor, not an estimate —
 * which is why it is only shown at all when the whole remainder is known.
 */
const remainingMs = computed(() =>
  displayTracks.value
    .slice(currentIndex.value)
    .reduce((total, track) => total + (track.durationMs || 0), 0),
)

const remainingKnown = computed(() =>
  displayTracks.value.slice(currentIndex.value).every((track) => track.durationMs > 0),
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

/**
 * Opening the queue on a long list used to land on row 0 — i.e. on whatever was played
 * longest ago — so the track actually playing had to be hunted for. Scroll to it instead,
 * placing it near the top so the *upcoming* tracks (the reason to open this screen) fill the
 * screen below it.
 *
 * Deliberately once per visit, not on every track change: re-scrolling as tracks advance
 * would yank the list out from under someone who has scrolled away to reorder something.
 */
const didInitialScroll = ref(false)
watch(
  () => displayTracks.value.length,
  async (length) => {
    if (didInitialScroll.value || length === 0) return
    didInitialScroll.value = true
    await nextTick()
    const row = trackList.value?.children?.[currentIndex.value]
    row?.scrollIntoView({ block: 'start', behavior: 'auto' })
  },
  { immediate: true },
)

async function onDrop(from, to) {
  await player.moveQueueItem(from, to)
  await refreshQueue()
}

const { dragIndex, dragOffset, shiftFor } = useDragReorder(trackList, {
  rowCount: () => displayTracks.value.length,
  onDrop,
  ignore: '.row-menu',
})

async function onSwipeRemove(index) {
  if (await player.removeQueueItem(index)) await refreshQueue()
}

const { swipeIndex, swipeOffset, swipeProgress } = useSwipeRemove(trackList, {
  onRemove: onSwipeRemove,
  // A row already picked up by the reorder gesture must not also be swipeable.
  isBlocked: () => dragIndex.value !== null,
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

function closeSheet() {
  sheet.value = null
  playlistName.value = ''
}

async function onClearPlayed() {
  if (busy.value) return
  busy.value = true
  try {
    const removed = await player.clearPlayedQueueItems()
    if (removed !== false) await refreshQueue()
  } finally {
    busy.value = false
    closeSheet()
  }
}

async function onClearAll() {
  if (busy.value) return
  busy.value = true
  try {
    if (await player.clearQueue()) await refreshQueue()
  } finally {
    busy.value = false
    closeSheet()
  }
}

function openSavePlaylist() {
  // Pre-filled with today's date so the common case is one tap on Save, while still leaving
  // a real, editable name rather than an auto-generated one the user never sees.
  playlistName.value = t('queue.savedPlaylistName', {
    date: new Date().toLocaleDateString(undefined, { day: '2-digit', month: '2-digit' }),
  })
  sheet.value = 'savePlaylist'
}

/**
 * Only queue entries the library actually knows can be saved: a playlist is stored as
 * `folder/displayName` paths (`usePlaylists.pathOf`), which a bare queue entry — uri, title,
 * artist — simply does not carry. In practice every entry comes from the library, so this
 * filter is a guard rather than a routine loss; the count in the button tells the user what
 * is really being saved either way.
 */
const savableTracks = computed(() =>
  queue.value.map((entry) => trackByUri.value.get(entry.uri)).filter(Boolean),
)

async function onSavePlaylist() {
  const name = playlistName.value.trim()
  if (!name || busy.value || savableTracks.value.length === 0) return
  busy.value = true
  try {
    const id = await createPlaylist(name)
    if (id !== null) await addToPlaylist(id, savableTracks.value)
  } finally {
    busy.value = false
    closeSheet()
  }
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
      <div class="bar-heading">
        <h1 class="bar-title">{{ t('queue.title') }}</h1>
        <p v-if="displayTracks.length > 0" class="bar-meta">
          {{ t('queue.upcomingCount', { count: upcomingCount }) }}
          <template v-if="remainingKnown && remainingMs > 0">
            · {{ t('queue.remaining', { duration: formatMs(remainingMs) }) }}
          </template>
        </p>
      </div>
      <button
        type="button"
        class="icon-button"
        :disabled="displayTracks.length === 0"
        :aria-label="t('queue.actions')"
        @click="sheet = 'menu'"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="12" cy="5" r="1.7" />
          <circle cx="12" cy="12" r="1.7" />
          <circle cx="12" cy="19" r="1.7" />
        </svg>
      </button>
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
        :class="{ dragging: dragIndex === index, swiping: swipeIndex === index }"
        :data-row-index="index > currentIndex ? index : null"
        :style="
          dragIndex === index
            ? { transform: `translateY(${dragOffset}px)`, transition: 'none' }
            : swipeIndex === index
              ? {
                  transform: `translateX(${swipeOffset}px)`,
                  opacity: 1 - swipeProgress(index) * 0.55,
                  transition: 'none',
                }
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

    <Transition name="queue-sheet">
      <div v-if="sheet" class="backdrop" @click.self="closeSheet">
        <div class="sheet" role="dialog" aria-modal="true">
          <template v-if="sheet === 'menu'">
            <button
              type="button"
              class="option"
              :disabled="busy || playedCount === 0"
              @click="onClearPlayed"
            >
              <span class="option-label">{{ t('queue.clearPlayed') }}</span>
              <span class="option-note">{{ t('queue.clearPlayedNote', { count: playedCount }) }}</span>
            </button>

            <button
              type="button"
              class="option"
              :disabled="busy || savableTracks.length === 0"
              @click="openSavePlaylist"
            >
              <span class="option-label">{{ t('queue.saveAsPlaylist') }}</span>
              <span class="option-note">{{ t('queue.saveAsPlaylistNote', { count: savableTracks.length }) }}</span>
            </button>

            <button
              type="button"
              class="option danger"
              :disabled="busy"
              @click="sheet = 'confirmClear'"
            >
              <span class="option-label">{{ t('queue.clearAll') }}</span>
            </button>
          </template>

          <template v-else-if="sheet === 'confirmClear'">
            <p class="sheet-title">{{ t('queue.confirmClear.title') }}</p>
            <p class="sheet-body">{{ t('queue.confirmClear.body') }}</p>
            <div class="sheet-actions">
              <button type="button" class="ghost" :disabled="busy" @click="sheet = 'menu'">
                {{ t('queue.cancel') }}
              </button>
              <button type="button" class="solid danger-solid" :disabled="busy" @click="onClearAll">
                {{ t('queue.clearAll') }}
              </button>
            </div>
          </template>

          <template v-else>
            <p class="sheet-title">{{ t('queue.saveAsPlaylist') }}</p>
            <input
              v-model="playlistName"
              class="name-input"
              type="text"
              enterkeyhint="done"
              :aria-label="t('queue.playlistName')"
              :placeholder="t('queue.playlistName')"
              @keyup.enter="onSavePlaylist"
            />
            <div class="sheet-actions">
              <button type="button" class="ghost" :disabled="busy" @click="sheet = 'menu'">
                {{ t('queue.cancel') }}
              </button>
              <button
                type="button"
                class="solid"
                :disabled="busy || playlistName.trim() === ''"
                @click="onSavePlaylist"
              >
                {{ t('queue.save') }}
              </button>
            </div>
          </template>
        </div>
      </div>
    </Transition>

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
  gap: 0.4rem;
  margin-bottom: 0.8rem;
}

.bar-heading {
  flex: 1;
  min-width: 0;
  text-align: center;
}

.bar-title {
  margin: 0;
  font-size: 1rem;
}

.bar-meta {
  margin: 0.1rem 0 0;
  font-size: 0.72rem;
  color: var(--text-muted);
}

.icon-button {
  width: 2.4rem;
  height: 2.4rem;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text);
}

.icon-button:disabled { opacity: 0.35; }
.icon-button svg { width: 1.4rem; height: 1.4rem; }
.list { margin: 0; padding: 0; list-style: none; }
.row-wrap { transition: transform 0.18s ease; }
.row-wrap.dragging { position: relative; z-index: 1; opacity: 0.92; }
/* The swiped row rides above its neighbours so the travel reads as one row leaving the
   list, rather than as a row sliding behind the ones next to it. */
.row-wrap.swiping { position: relative; z-index: 1; }
.state { padding: 3rem 1rem; text-align: center; }
.state-title { margin: 0 0 0.45rem; font-weight: 700; }
.hint { margin: 0; color: var(--text-muted); }

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  align-items: flex-end;
  background: rgb(8 10 10 / 55%);
}

.sheet {
  width: 100%;
  background: var(--surface-1);
  border-top: 1px solid var(--border);
  border-radius: 0.9rem 0.9rem 0 0;
  padding: 0.6rem 0.9rem calc(var(--safe-bottom) + 0.9rem);
}

.option {
  display: block;
  width: 100%;
  padding: 0.75rem 0.4rem;
  border: 0;
  border-radius: 0.6rem;
  background: transparent;
  color: var(--text);
  text-align: start;
}

.option:disabled { opacity: 0.4; }
.option.danger { color: var(--danger); }
.option-label { display: block; font-weight: 600; }
.option-note { display: block; margin-top: 0.1rem; font-size: 0.74rem; color: var(--text-muted); }

.sheet-title { margin: 0.3rem 0 0.35rem; font-weight: 700; }
.sheet-body { margin: 0 0 0.9rem; color: var(--text-muted); font-size: 0.86rem; }

.name-input {
  width: 100%;
  margin: 0 0 0.9rem;
  padding: 0.6rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: 0.6rem;
  background: var(--surface-2);
  color: var(--text);
  font: inherit;
}

.sheet-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}

.ghost,
.solid {
  padding: 0.55rem 1rem;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text);
  font: inherit;
  font-weight: 600;
}

.solid {
  border-color: transparent;
  background: var(--accent);
  color: var(--on-accent);
}

.solid.danger-solid { background: var(--danger); color: #fff; }
.ghost:disabled,
.solid:disabled { opacity: 0.45; }

.queue-sheet-enter-active,
.queue-sheet-leave-active { transition: opacity 0.2s ease; }
.queue-sheet-enter-from,
.queue-sheet-leave-to { opacity: 0; }
.queue-sheet-enter-active .sheet,
.queue-sheet-leave-active .sheet { transition: transform 0.22s ease; }
.queue-sheet-enter-from .sheet,
.queue-sheet-leave-to .sheet { transform: translateY(100%); }

/* Mirrors TrackMenu.vue: the slide/fade is decoration, and the sheet's presence already
   says everything the motion does. */
@media (prefers-reduced-motion: reduce) {
  .backdrop,
  .sheet,
  .row-wrap { transition: none; }
}
</style>
