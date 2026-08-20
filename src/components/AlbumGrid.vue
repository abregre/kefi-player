<script setup>
import { ref } from 'vue'
import { useI18n } from '../i18n'
import { useLibrary } from '../composables/useLibrary'
import { useLazyArtwork } from '../composables/useLazyArtwork'

const props = defineProps({
  /** `{ key, name, tracks }[]` as produced by `useLibrary`'s `albums` computed. */
  albums: { type: Array, required: true },
})
defineEmits(['select'])

const { t } = useI18n()
const { artwork } = useLibrary()

const root = ref(null)
// Cells carry data-track-id; the composable fetches covers as they come into view.
// Searching rebuilds the grid, so it re-observes whenever `albums` changes.
useLazyArtwork(root, () => props.albums)
</script>

<template>
  <div ref="root" class="grid">
    <button
      v-for="album in props.albums"
      :key="album.key"
      type="button"
      class="cell"
      :data-track-id="album.tracks[0]?.id"
      @click="$emit('select', album)"
    >
      <span class="art">
        <img
          v-if="artwork[album.tracks[0]?.id]"
          class="art-image"
          :src="artwork[album.tracks[0]?.id]"
          :alt="album.name ?? t('nowPlaying.unknownAlbum')"
        />
        <!-- Token-coloured placeholder, never a broken image: a null artwork result
             and a still-pending one both land here. -->
        <span v-else class="art-placeholder" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4">
            <circle cx="12" cy="12" r="8.2" />
            <circle cx="12" cy="12" r="2.1" />
          </svg>
        </span>
      </span>
      <span class="name">{{ album.name ?? t('nowPlaying.unknownAlbum') }}</span>
      <span class="meta">
        {{
          album.tracks.length === 1
            ? t('library.count.song')
            : t('library.count.songs', { count: album.tracks.length })
        }}
      </span>
    </button>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
  gap: 0.9rem 0.75rem;
  margin-top: 0.9rem;
}

.cell {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  padding: 0;
  background: transparent;
  border: 0;
  text-align: left;
  min-width: 0;
}

.cell:active .art {
  transform: scale(0.98);
}

.art {
  display: block;
  aspect-ratio: 1;
  border-radius: 0.6rem;
  overflow: hidden;
  background: var(--surface-2);
  border: 1px solid var(--border);
  transition: transform 0.12s ease;
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
  /* Warm wash off the same tokens as the rest of the app, so a missing cover reads
     as intentional rather than as a failure. */
  background: linear-gradient(150deg, var(--surface-2), var(--accent-soft));
  color: var(--accent);
}

.art-placeholder svg {
  width: 45%;
  height: 45%;
  opacity: 0.85;
}

.name {
  font-size: 0.85rem;
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta {
  font-size: 0.72rem;
  color: var(--text-muted);
}
</style>
