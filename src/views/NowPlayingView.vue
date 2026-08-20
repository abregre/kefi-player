<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'
import { useVerticalDismiss } from '../composables/useVerticalDismiss'
import { formatMs } from '../utils/format'

const router = useRouter()
const { t } = useI18n()
const player = usePlayer()
const { trackByUri, artwork, loadArtwork } = useLibrary()

/** Repeat cycles the way every music player does: none → whole queue → this track. */
const REPEAT_CYCLE = { off: 'all', all: 'one', one: 'off' }

const track = computed(() => (player.uri.value ? trackByUri.value.get(player.uri.value) : undefined))

watch(
  track,
  (value) => {
    if (value) loadArtwork(value.id)
  },
  { immediate: true },
)

const cover = computed(() => (track.value ? artwork.value[track.value.id] : null))
const title = computed(() => player.title.value || track.value?.title || t('nowPlaying.unknownTitle'))
const artist = computed(() => player.artist.value || track.value?.artist || t('nowPlaying.unknownArtist'))
const album = computed(() => track.value?.album || t('nowPlaying.unknownAlbum'))

/*
 * The ONLY local state on this screen, and only for the duration of a drag: the thumb
 * has to follow the finger, and the interpolated position would otherwise yank it back
 * four times a second. Everything else — including which icon the repeat button shows —
 * renders from the snapshot, so a resume after the WebView was destroyed rebuilds this
 * screen from the native truth rather than from anything remembered here.
 */
const dragging = ref(false)
const dragValue = ref(0)

const sliderValue = computed(() => (dragging.value ? dragValue.value : player.displayPosition.value))
/**
 * How much of the seek track is behind the thumb, as a percentage. Fed to the track as a
 * custom property because a range input's fill is not a thing CSS gives you: the track
 * pseudo-element uncovers this much of the full-width sweep itself.
 */
const progressFill = computed(() => {
  const total = player.duration.value
  if (!total) return 0
  return Math.min(100, (sliderValue.value / total) * 100)
})
const elapsedLabel = computed(() => formatMs(sliderValue.value))
const remainingLabel = computed(() =>
  player.duration.value ? `-${formatMs(player.duration.value - sliderValue.value)}` : '--:--',
)

/** @param {Event} event */
function onSeekInput(event) {
  dragging.value = true
  dragValue.value = Number(event.target.value)
}

/** @param {Event} event */
function onSeekCommit(event) {
  const positionMs = Number(event.target.value)
  dragging.value = false
  player.seek(positionMs)
}

function toggle() {
  if (player.isPlaying.value) player.pause()
  else player.resume()
}

function cycleRepeat() {
  player.setRepeatMode(REPEAT_CYCLE[player.repeatMode.value] ?? 'all')
}

const page = ref(null)

/*
 * The back button at line 84 stays the primary way to leave this screen; this is an
 * additional path to the same `router.back()`, not a replacement. `offset` and
 * `sheetDragging` are presentation-only (how far the sheet currently sits below its rest
 * position, and whether it is being dragged right now) and live in the composable, not
 * here — nothing about this gesture becomes a second source of playback truth. Renamed
 * at the destructure (the composable's own `dragging` return stays as-is) because the
 * seek bar above already owns the identifier `dragging` for its own, unrelated drag
 * state — the two never read or write each other. Since the view is unmounted on every
 * navigation away from it (the MiniPlayer, not this component, stays alive), a fresh
 * mount always gets a fresh ref at 0 — there is no stale offset to reset explicitly.
 */
const { offset, dragging: sheetDragging } = useVerticalDismiss(page, {
  onDismiss: () => router.back(),
  ignore: '.seek-input, .icon-button, .play',
})
</script>

<template>
  <section
    ref="page"
    class="page"
    :class="{ dragging: sheetDragging }"
    :style="{ transform: offset ? `translateY(${offset}px)` : undefined }"
  >
    <header class="bar">
      <button type="button" class="icon-button" @click="router.back()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
          <path d="m6 9.5 6 6 6-6" />
        </svg>
      </button>
      <span class="bar-title">{{ t('nowPlaying.title') }}</span>
      <span class="bar-spacer" />
    </header>

    <template v-if="player.mediaId.value">
      <div class="art">
        <img v-if="cover" class="art-image" :src="cover" :alt="album" />
        <span v-else class="art-placeholder" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.1">
            <circle cx="12" cy="12" r="8.4" />
            <circle cx="12" cy="12" r="2" />
          </svg>
        </span>
      </div>

      <div class="meta">
        <h1 class="title">{{ title }}</h1>
        <p class="artist">{{ artist }}</p>
        <p class="album">{{ album }}</p>
      </div>

      <div class="seek">
        <input
          class="seek-input"
          type="range"
          min="0"
          :max="player.duration.value || 0"
          step="1000"
          :value="sliderValue"
          :style="{ '--progress-fill': `${progressFill}%` }"
          :aria-label="t('player.seek')"
          @input="onSeekInput"
          @change="onSeekCommit"
        />
        <div class="times">
          <span>{{ elapsedLabel }}</span>
          <span>{{ remainingLabel }}</span>
        </div>
      </div>

      <div class="transport">
        <button
          type="button"
          class="icon-button shuffle"
          :class="{ on: player.shuffleModeEnabled.value }"
          :aria-label="t('player.shuffle')"
          :aria-pressed="player.shuffleModeEnabled.value"
          @click="player.setShuffle(!player.shuffleModeEnabled.value)"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M16 4.5 19.5 8 16 11.5M4 8h4.2c1.4 0 2.7.7 3.4 2l3.2 5c.7 1.2 2 2 3.4 2h2.3" />
            <path d="M16 12.5 19.5 16 16 19.5M4 16h4.2c1.4 0 2.7-.7 3.4-2" />
          </svg>
        </button>

        <button
          type="button"
          class="icon-button"
          :aria-label="t('player.previous')"
          @click="player.previous()"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <rect x="5" y="5.5" width="2.6" height="13" rx="1" />
            <path d="M19 6.6v10.8c0 .9-1 1.4-1.7 1L9 13c-.7-.4-.7-1.4 0-1.8l8.3-5.5c.7-.5 1.7 0 1.7.9Z" />
          </svg>
        </button>

        <button
          type="button"
          class="play"
          :aria-label="player.isPlaying.value ? t('player.pause') : t('player.play')"
          @click="toggle"
        >
          <svg v-if="player.isPlaying.value" viewBox="0 0 24 24" fill="currentColor">
            <rect x="6.5" y="4.5" width="3.8" height="15" rx="1.2" />
            <rect x="13.7" y="4.5" width="3.8" height="15" rx="1.2" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 4.9c0-.9 1-1.5 1.8-1.1l9.4 7.1c.7.4.7 1.5 0 1.9L9.8 20.2c-.8.5-1.8-.1-1.8-1V4.9Z" />
          </svg>
        </button>

        <button
          type="button"
          class="icon-button"
          :aria-label="t('player.next')"
          @click="player.next()"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M5 6.6v10.8c0 .9 1 1.4 1.7 1L15 13c.7-.4.7-1.4 0-1.8L6.7 5.7C6 5.2 5 5.7 5 6.6Z" />
            <rect x="16.4" y="5.5" width="2.6" height="13" rx="1" />
          </svg>
        </button>

        <button
          type="button"
          class="icon-button repeat"
          :class="{ on: player.repeatMode.value !== 'off' }"
          :aria-label="t(`player.repeat.${player.repeatMode.value}`)"
          @click="cycleRepeat"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M7 6h10a3 3 0 0 1 3 3v1M17 18H7a3 3 0 0 1-3-3v-1" />
            <path d="m9.5 3.5-2.6 2.6 2.6 2.6M14.5 20.5l2.6-2.6-2.6-2.6" />
          </svg>
          <!-- The "one" state needs to be distinguishable from "all" at a glance;
               the badge is cheaper to read than a second arrow glyph. -->
          <span v-if="player.repeatMode.value === 'one'" class="badge">1</span>
        </button>
      </div>
    </template>

    <div v-else class="empty">
      <p class="empty-title">{{ t('nowPlaying.empty.title') }}</p>
      <p class="hint">{{ t('nowPlaying.empty.body') }}</p>
    </div>
  </section>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: calc(var(--safe-top) + 0.6rem) 1.25rem 1.25rem;
  min-height: 100%;
  /* Settles back to translateY(0) after a drag that didn't cross the dismiss threshold.
     No transition while the finger is still down (below) — otherwise the sheet lags
     behind the touch instead of following it. */
  transition: transform 0.2s ease-out;
}

.page.dragging {
  transition: none;
}

/* Vestibular disorders are a real accessibility need, and this settle animation is
   decoration: the drag itself already communicated the state change. */
@media (prefers-reduced-motion: reduce) {
  .page {
    transition: none;
  }
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.bar-title {
  font-size: 0.72rem;
  letter-spacing: 0.09em;
  text-transform: uppercase;
  color: var(--text-muted);
}

.bar-spacer {
  width: 2.4rem;
}

.icon-button {
  width: 2.4rem;
  height: 2.4rem;
  display: grid;
  place-items: center;
  position: relative;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
}

.icon-button svg {
  width: 1.5rem;
  height: 1.5rem;
}

.icon-button.on {
  color: var(--accent);
}

.badge {
  position: absolute;
  right: 0.15rem;
  bottom: 0.15rem;
  font-size: 0.6rem;
  line-height: 1;
  padding: 0.1rem 0.2rem;
  border-radius: 0.3rem;
  background: var(--accent);
  color: var(--on-accent);
}

.art {
  width: 100%;
  aspect-ratio: 1;
  /* Keeps the whole screen usable on a short device without scrolling. */
  max-height: 46vh;
  margin: 0 auto;
  border-radius: 1rem;
  overflow: hidden;
  background: var(--surface-2);
  border: 1px solid var(--border);
}

.art-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.art-placeholder {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  background: linear-gradient(150deg, var(--surface-2), var(--accent-soft));
  color: var(--accent);
}

.art-placeholder svg {
  width: 40%;
  height: 40%;
  opacity: 0.8;
}

.meta {
  text-align: center;
}

/*
 * Long names are the norm in a local library — MediaStore falls back to the file name, which
 * is often one unbroken string with no spaces to break at. Two things follow, and both are
 * needed: `overflow-wrap: anywhere` so such a string cannot push the layout sideways, and a
 * hard line budget so it cannot push the transport controls off the bottom of the screen
 * either (an unclamped title took three lines on a 360px viewport and the play button went
 * below the fold).
 */
.title {
  font-family: var(--font-display);
  font-size: 1.3rem;
  margin: 0;
  line-height: 1.25;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
}

.artist,
.album {
  /* One line each: on this screen they are context for the title, not the subject. */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.artist {
  margin: 0.25rem 0 0;
  font-size: 0.9rem;
}

.album {
  margin: 0.1rem 0 0;
  font-size: 0.78rem;
  color: var(--text-muted);
}

.seek {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.seek-input {
  appearance: none;
  width: 100%;
  height: 1.2rem;
  background: transparent;
}

.seek-input::-webkit-slider-runnable-track {
  height: 0.28rem;
  border-radius: 999px;
  /*
   * A full-width green -> yellow -> red sweep with only the elapsed part uncovered.
   * `--progress-fill` is set on the input itself and inherits in here — a range input
   * gives no ::fill pseudo-element in Chromium, so the track paints its own.
   */
  /*
   * Two layers, no masks: the FIRST layer paints on TOP and is transparent up to
   * `--progress-fill`, then the empty-track colour — so the full-width sweep underneath
   * shows through exactly as far as playback has got. `--progress-fill` has to be read in
   * a normal property like this one, not from inside a custom property (see src/style.css).
   */
  background:
    linear-gradient(
        90deg,
        transparent 0 var(--progress-fill, 0%),
        var(--surface-2) var(--progress-fill, 0%) 100%
      ),
    var(--progress-gradient);
}

.seek-input::-webkit-slider-thumb {
  appearance: none;
  width: 0.85rem;
  height: 0.85rem;
  margin-top: -0.28rem;
  border-radius: 50%;
  background: var(--text);
}

.times {
  display: flex;
  justify-content: space-between;
  font-size: 0.72rem;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.transport {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.4rem;
  margin-top: auto;
  padding-top: 0.5rem;
}

.play {
  width: 3.9rem;
  height: 3.9rem;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: var(--accent);
  color: var(--on-accent);
}

.play svg {
  width: 1.9rem;
  height: 1.9rem;
}

.empty {
  margin: auto 0;
  text-align: center;
}

.empty-title {
  margin: 0 0 0.3rem;
  font-family: var(--font-display);
  font-size: 1.1rem;
}

.hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.45;
}
</style>
