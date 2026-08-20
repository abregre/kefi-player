<script setup>
import { useI18n } from '../i18n'
import { formatMs } from '../utils/format'

const props = defineProps({
  track: { type: Object, required: true },
  /** True for the track the player is currently sitting on (playing or paused). */
  active: { type: Boolean, default: false },
  playing: { type: Boolean, default: false },
})

defineEmits(['select'])

const { t } = useI18n()
</script>

<template>
  <button
    type="button"
    class="row track-row"
    :class="{ active: props.active }"
    @click="$emit('select', props.track)"
  >
    <span class="marker" aria-hidden="true">
      <!-- Three bars, animated only while this row is the one actually playing:
           motion is the cheapest way to say "this is the sound you hear". -->
      <span v-if="props.active" class="bars" :class="{ animate: props.playing }">
        <i /><i /><i />
      </span>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
        <circle cx="9" cy="17.5" r="2.6" />
        <circle cx="18" cy="15.5" r="2.6" />
        <path d="M11.6 17.5V6.6l9-1.9v10.8" />
      </svg>
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

.marker {
  flex-shrink: 0;
  width: 1.5rem;
  height: 1.5rem;
  display: grid;
  place-items: center;
  color: var(--text-muted);
}

.row.active .marker {
  color: var(--accent);
}

.marker svg {
  width: 1.35rem;
  height: 1.35rem;
}

.bars {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 1rem;
}

.bars i {
  width: 3px;
  height: 40%;
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
