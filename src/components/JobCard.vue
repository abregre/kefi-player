<script setup>
import { computed } from 'vue'
import { useI18n } from '../i18n'
import { formatBytes, formatEta } from '../utils/format'

const props = defineProps({
  job: { type: Object, required: true },
})
defineEmits(['cancel', 'remove', 'open'])

const { t } = useI18n()

const isActive = computed(
  () =>
    props.job.status === 'queued' ||
    props.job.status === 'downloading' ||
    props.job.status === 'converting',
)

/** Only a finished file with a saved URI can be handed to a player. */
const isPlayable = computed(() => props.job.status === 'done' && Boolean(props.job.uri))

const statusLabel = computed(() => {
  switch (props.job.status) {
    case 'queued':
      return t('downloader.job.status.queued')
    case 'downloading':
      return t('downloader.job.status.downloading', { percent: Math.round(props.job.progress) })
    case 'converting':
      // ffmpeg reports no useful percentage during this pass, so don't imply one.
      return t('downloader.job.status.converting')
    case 'done':
      return props.job.location
        ? t('downloader.job.status.done', { location: props.job.location })
        : t('downloader.job.status.doneGeneric')
    case 'failed':
      return t('downloader.job.status.failed')
    case 'cancelled':
      return t('downloader.job.status.cancelled')
    default:
      return ''
  }
})
</script>

<template>
  <article
    class="job-card"
    :class="[job.status, { playable: isPlayable }]"
    :role="isPlayable ? 'button' : undefined"
    :tabindex="isPlayable ? 0 : undefined"
    :aria-label="isPlayable ? t('downloader.job.playAria', { title: job.title }) : undefined"
    @click="isPlayable && $emit('open')"
    @keydown.enter="isPlayable && $emit('open')"
    @keydown.space.prevent="isPlayable && $emit('open')"
  >
    <div class="top">
      <img v-if="job.thumbnail" :src="job.thumbnail" alt="" class="thumb" />
      <div class="info">
        <p class="title">{{ job.title }}</p>
        <p class="status-line">
          <span v-if="isPlayable" class="play-mark" aria-hidden="true">&#9654;</span>
          <span class="status-text">{{ statusLabel }}</span>
          <span v-if="job.status === 'downloading' && formatEta(job.etaSeconds)" class="detail">
            {{ formatEta(job.etaSeconds) }}
          </span>
          <span v-if="job.status === 'done' && formatBytes(job.size)" class="detail">
            {{ formatBytes(job.size) }}
          </span>
        </p>
      </div>
      <button
        v-if="isActive"
        type="button"
        class="action-btn"
        @click.stop="$emit('cancel')"
      >
        {{ t('downloader.job.stop') }}
      </button>
      <button
        v-else
        type="button"
        class="action-btn"
        :aria-label="t('downloader.job.removeAria')"
        @click.stop="$emit('remove')"
      >
        {{ t('downloader.job.clear') }}
      </button>
    </div>

    <div v-if="isActive" class="progress-track">
      <div
        v-if="job.status === 'downloading'"
        class="progress-fill"
        :style="{ '--progress-fill': `${job.progress}%` }"
      />
      <div v-else class="progress-fill indeterminate" />
    </div>

    <p v-if="job.error" class="error-line">{{ job.error }}</p>
  </article>
</template>

<style scoped>
.job-card {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  border-radius: 0.85rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
}

.job-card.done {
  border-color: var(--success);
}

.job-card.failed {
  border-color: var(--danger);
}

.job-card.playable {
  cursor: pointer;
}

.job-card.playable:active {
  background: var(--surface-2);
}

.job-card.playable:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.top {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}

.thumb {
  width: 3.4rem;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  border-radius: 0.45rem;
  flex-shrink: 0;
  background: var(--surface-2);
}

/* min-width: 0 so a long, unbroken video title cannot widen the row (or the shell
   underneath it) — the title itself stays a single ellipsised line, per
   src/components/TrackRow.vue's pattern. */
.info {
  flex: 1;
  min-width: 0;
}

.title {
  margin: 0;
  font-size: 0.88rem;
  font-weight: 500;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-line {
  margin: 0.2rem 0 0;
  display: flex;
  align-items: center;
  gap: 0.45rem;
  font-size: 0.76rem;
  color: var(--text-muted);
}

.job-card.done .status-line {
  color: var(--success);
}

.job-card.failed .status-line {
  color: var(--danger);
}

.play-mark {
  color: var(--success);
  font-size: 0.65rem;
}

.detail {
  opacity: 0.75;
  flex-shrink: 0;
}

.action-btn {
  flex-shrink: 0;
  font-size: 0.78rem;
  padding: 0.4rem 0.65rem;
  border-radius: 0.5rem;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
}

.progress-track {
  height: 0.3rem;
  border-radius: 999px;
  background: var(--surface-2);
  overflow: hidden;
}

/*
 * Full-width sweep, uncovered up to --progress-fill — the same technique
 * src/components/MiniPlayer.vue's playback strip uses: the gradient is anchored across
 * the WHOLE track, so the colour at a given x position always means the same amount of
 * progress, whether the bar is at 5% or 95%. A growing-width fill squeezes the same
 * green->yellow->red sweep into whatever fraction is done, which looks identical at very
 * different progress levels — the thing this technique exists to avoid. `--progress-fill`
 * is read here, in an ordinary property, never inside `--progress-gradient` itself (see
 * the comment above that token in src/style.css for why the layered background cannot
 * live in the token).
 */
.progress-fill {
  width: 100%;
  height: 100%;
  border-radius: 999px;
  background:
    linear-gradient(
        90deg,
        transparent 0 var(--progress-fill, 0%),
        var(--surface-2) var(--progress-fill, 0%) 100%
      ),
    var(--progress-gradient);
}

/* ffmpeg's pass (and the queued state before the first byte) report no percentage, so
   this shows motion instead of a fake number: a short slice of the gradient sliding
   across the track, not the full-sweep uncover technique above. */
.progress-fill.indeterminate {
  width: 35%;
  background: var(--progress-gradient);
  animation: job-card-slide 1.1s ease-in-out infinite;
}

@keyframes job-card-slide {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(340%);
  }
}

.error-line {
  margin: 0;
  font-size: 0.76rem;
  line-height: 1.4;
  color: var(--danger);
  background: var(--danger-soft);
  padding: 0.5rem 0.6rem;
  border-radius: 0.5rem;
  overflow-wrap: anywhere;
}
</style>
