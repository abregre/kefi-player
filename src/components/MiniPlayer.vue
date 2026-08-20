<script setup>
import { computed, watch } from 'vue'
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

const progress = computed(() => {
  const total = player.duration.value
  if (!total) return 0
  return Math.min(100, (player.displayPosition.value / total) * 100)
})

function toggle() {
  if (player.isPlaying.value) player.pause()
  else player.resume()
}
</script>

<template>
  <!-- Hidden entirely (not merely blank) until something is loaded, so the library
       gets the full height until there is a player to show. -->
  <div v-if="player.mediaId.value" class="mini" :class="{ hidden }" :aria-hidden="hidden">
    <div class="progress" :style="{ '--progress-fill': `${progress}%` }" aria-hidden="true" />
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
</style>
