<script setup>
import { computed } from 'vue'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { formatMs } from '../utils/format'

const props = defineProps({
  track: { type: Object, required: true },
  /** True for the track the player is currently sitting on (playing or paused). */
  active: { type: Boolean, default: false },
  playing: { type: Boolean, default: false },
})

defineEmits(['select'])

const { t } = useI18n()
const { artwork } = useLibrary()

/*
 * The row shows the track's own cover, the same one the mini-player and Now Playing
 * show. It does NOT fetch it: the `data-track-id` on the root is picked up by the
 * list's `useLazyArtwork` observer, which loads covers as rows scroll into view.
 * Until then — and forever, for a track with no embedded art — this is null and the
 * token placeholder renders instead. `undefined` (not asked yet) and `null` (asked,
 * has none) deliberately look the same here.
 */
const cover = computed(() => artwork.value[props.track.id] ?? null)
</script>

<template>
  <button
    type="button"
    class="row track-row"
    :class="{ active: props.active }"
    :data-track-id="props.track.id"
    @click="$emit('select', props.track)"
  >
    <span class="art">
      <img v-if="cover" class="art-image" :src="cover" alt="" />
      <span v-else class="art-placeholder" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="8.2" />
          <circle cx="12" cy="12" r="2.1" />
        </svg>
      </span>
      <!-- Three bars over the cover, animated only while this row is the one actually
           playing: motion is the cheapest way to say "this is the sound you hear", and
           laying it over the artwork keeps both facts visible at once. -->
      <span
        v-if="props.active"
        class="bars"
        :class="{ animate: props.playing }"
        aria-hidden="true"
      >
        <i /><i /><i />
      </span>
    </span>
    <span class="body">
      <span class="title">{{ props.track.title }}</span>
      <span class="sub">{{ props.track.artist || t('nowPlaying.unknownArtist') }}</span>
    </span>
    <span class="duration">{{ formatMs(props.track.durationMs) }}</span>
  </button>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.6rem 0.25rem;
  background: transparent;
  border: 0;
  border-radius: 0.6rem;
  text-align: left;
}

.row:active {
  background: var(--surface-1);
}

.art {
  position: relative;
  flex-shrink: 0;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 0.45rem;
  overflow: hidden;
  background: var(--surface-2);
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

.bars {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
  padding-bottom: 0.55rem;
  /* Scrim, so the bars stay legible over a bright cover as well as over the
     placeholder. */
  background: rgb(8 10 10 / 55%);
}

.bars i {
  width: 3px;
  height: 30%;
  border-radius: 1px;
  background: var(--accent);
}

/* Paused keeps the bars still rather than swapping the icon: the row stays
   recognisable as "the current one" without implying sound. */
.bars.animate i {
  animation: bounce 0.9s ease-in-out infinite;
}

.bars.animate i:nth-child(2) {
  animation-delay: 0.15s;
}

.bars.animate i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes bounce {
  0%,
  100% {
    height: 30%;
  }
  50% {
    height: 100%;
  }
}

.body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}

.title {
  font-size: 0.92rem;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row.active .title {
  color: var(--accent);
}

.sub {
  font-size: 0.76rem;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.duration {
  flex-shrink: 0;
  font-size: 0.76rem;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
</style>
