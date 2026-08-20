<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { usePlayer } from '../composables/usePlayer'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const player = usePlayer()
const { trackByUri, artwork, loadArtwork } = useLibrary()

/** The library's own record for whatever is playing, when it knows it. */
const track = computed(() => (player.uri.value ? trackByUri.value.get(player.uri.value) : undefined))

// Artwork needs the MediaStore id, which the snapshot does not carry — the library
// does. Fetch is idempotent and cached, so a track repeating costs nothing.
watch(
  track,
  (value) => {
    if (value) loadArtwork(value.id)
  },
  { immediate: true },
)

/*
 * On Now Playing the strip would be a second copy of the same controls, so it is
 * hidden with CSS rather than `v-if`: the component must stay mounted (it lives
 * outside <RouterView> precisely so navigation never remounts it), it just has
 * nothing to say while the full player is on screen.
 */
const hidden = computed(() => route.path === '/now-playing')

const cover = computed(() => (track.value ? artwork.value[track.value.id] : null))
/** Snapshot first (it is the playing truth), library second, placeholder last. */
const title = computed(() => player.title.value || track.value?.title || t('nowPlaying.unknownTitle'))
const artist = computed(() => player.artist.value || track.value?.artist || t('nowPlaying.unknownArtist'))

/*
 * Drag state for the touch seek strip, the same shape NowPlayingView uses for its own
 * seek bar: `player.displayPosition` only ticks locally every 250ms (usePlayer.js), so
 * a fill bound straight to it would fight a dragging finger. `dragValue` holds the
 * finger's position until the drag commits; nothing else on this strip needs local state.
 */
const dragging = ref(false)
const dragValue = ref(0)

// If Now Playing opens mid-drag, the strip is hidden by CSS but stays mounted (see
// `hidden` above), so the `change` event that would normally clear `dragging` never
// fires. Without this watch the strip would come back from Now Playing wedged on the
// finger's last position instead of resuming the live ticker.
watch(hidden, (value) => {
  if (value) dragging.value = false
})

const sliderValue = computed(() => (dragging.value ? dragValue.value : player.displayPosition.value))

const progress = computed(() => {
  const total = player.duration.value
  if (!total) return 0
  return Math.min(100, (sliderValue.value / total) * 100)
})

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
</script>

<template>
  <!-- Hidden entirely (not merely blank) until something is loaded, so the library
       gets the full height until there is a player to show. -->
  <div v-if="player.mediaId.value" class="mini" :class="{ hidden }" :aria-hidden="hidden">
    <div class="progress-wrap">
      <div class="progress" :style="{ '--progress-fill': `${progress}%` }" />
      <input
        class="seek-input"
        type="range"
        min="0"
        :max="player.duration.value || 0"
        step="1000"
        :value="sliderValue"
        :aria-label="t('player.seek')"
        @input="onSeekInput"
        @change="onSeekCommit"
      />
    </div>
    <div class="body">
      <button
        type="button"
        class="open"
        :aria-label="t('player.openNowPlaying')"
        @click="router.push('/now-playing')"
      >
        <span class="art" :class="{ spinning: player.isPlaying.value }">
          <img v-if="cover" class="art-image" :src="cover" alt="" />
          <span v-else class="art-placeholder" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="8.2" />
              <circle cx="12" cy="12" r="2.1" />
            </svg>
          </span>
        </span>
        <span class="meta">
          <span class="title">{{ title }}</span>
          <span class="artist">{{ artist }}</span>
        </span>
      </button>
      <button
        type="button"
        class="side"
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
        class="toggle"
        :aria-label="player.isPlaying.value ? t('player.pause') : t('player.play')"
        @click="toggle"
      >
        <svg v-if="player.isPlaying.value" viewBox="0 0 24 24" fill="currentColor">
          <rect x="6.5" y="5" width="3.6" height="14" rx="1.1" />
          <rect x="13.9" y="5" width="3.6" height="14" rx="1.1" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="currentColor">
          <path d="M8 5.2c0-.9 1-1.5 1.8-1L19 11c.7.4.7 1.5 0 1.9L9.8 19.8c-.8.5-1.8-.1-1.8-1V5.2Z" />
        </svg>
      </button>
      <button
        type="button"
        class="side"
        :aria-label="t('player.next')"
        @click="player.next()"
      >
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M5 6.6v10.8c0 .9 1 1.4 1.7 1L15 13c.7-.4.7-1.4 0-1.8L6.7 5.7C6 5.2 5 5.7 5 6.6Z" />
          <rect x="16.4" y="5.5" width="2.6" height="13" rx="1" />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mini {
  background: var(--surface-1);
  border-top: 1px solid var(--border);
}

.mini.hidden {
  display: none;
}

/* The wrapper's height is exactly the visible line's height (2px): the touchable area
   added below is absolutely positioned over it rather than sized into normal flow, so a
   taller hit target never grows the strip or pushes `.body` down. */
.progress-wrap {
  position: relative;
  height: 2px;
}

/* Thin line rather than a full bar: the mini-player is a status strip, and the
   seekable bar belongs to Now Playing. One element, no inner fill: `--progress-track`
   uncovers the elapsed part of a full-width sweep (see src/style.css), which is also
   exactly what the Now Playing seek bar paints. */
.progress {
  height: 2px;
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

/*
 * The seek control: a real `<input type="range">` so dragging/committing reuses the
 * browser's own touch handling (same idiom as NowPlayingView's `.seek-input`), sized
 * past the 2px visible line because a finger cannot reliably land on a 2px target.
 * Anchored to the TOP of `.progress-wrap` and grown downward only — never centred on
 * the line — because `.mini` sits in a CSS grid row (App.vue) that does not clip its
 * children: a hit area straddling the strip's top edge would overlay the bottom of the
 * scrolling list above it and steal taps meant for the last visible row. Growing
 * downward instead lands entirely inside the strip's own top padding on `.body`
 * (0.45rem), which is safe to cover because nothing sits there to be tapped.
 */
.seek-input {
  appearance: none;
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 0.6rem;
  margin: 0;
  padding: 0;
  background: transparent;
}

/* The visible line is `.progress` underneath; the input's own track paints nothing. */
.seek-input::-webkit-slider-runnable-track {
  height: 100%;
  background: transparent;
}

/* No handle, by request: hidden rather than merely small, since even a tiny thumb is
   still a thumb. `-webkit` is the only engine this ships in — an Android WebView is
   Chromium. */
.seek-input::-webkit-slider-thumb {
  appearance: none;
  width: 0;
  height: 0;
  background: transparent;
}

.body {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.45rem 0.6rem;
}

.open {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0;
  background: transparent;
  border: 0;
  text-align: left;
}

/*
 * A round cover in the strip, and it turns while the music plays — the mini-player is the
 * one place in the app that is always on screen, so it is where a sign of life belongs.
 * Only here: the list rows keep square thumbnails (a grid of spinning discs would be
 * noise) and Now Playing has the seek bar to say the same thing.
 *
 * Paused via `animation-play-state`, not by removing the animation: the disc holds the
 * angle it stopped at and picks up from there, the way a record does. Removing it would
 * snap back to 0deg on every pause.
 */
.art {
  flex-shrink: 0;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 50%;
  overflow: hidden;
  background: var(--surface-2);
  animation: spin 6s linear infinite;
  animation-play-state: paused;
}

.art.spinning {
  animation-play-state: running;
}

@keyframes spin {
  to {
    transform: rotate(1turn);
  }
}

/* Vestibular disorders are a real accessibility need, and this rotation is decoration:
   the play/pause icon already carries the state. */
@media (prefers-reduced-motion: reduce) {
  .art {
    animation: none;
  }
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
  width: 55%;
  height: 55%;
}

.meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.title,
.artist {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.title {
  font-size: 0.85rem;
}

.artist {
  font-size: 0.72rem;
  color: var(--text-muted);
}

.toggle {
  flex-shrink: 0;
  width: 2.6rem;
  height: 2.6rem;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 0;
  background: var(--accent);
  color: var(--on-accent);
}

.toggle svg {
  width: 1.4rem;
  height: 1.4rem;
}

/* Secondary controls flanking the primary play button: transparent background and a
   muted colour rather than the accent fill, so `.toggle` stays the one thing the eye
   lands on first — the same secondary-button idiom as NowPlayingView's `.icon-button`. */
.side {
  flex-shrink: 0;
  width: 2.4rem;
  height: 2.4rem;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 0;
  background: transparent;
  color: var(--text-muted);
}

.side svg {
  width: 1.5rem;
  height: 1.5rem;
}
</style>
