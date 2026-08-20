<script setup>
import { useI18n } from '../i18n'

const props = defineProps({
  /** `{ key, name, tracks }[]` as produced by `useLibrary`'s `artists` computed. */
  artists: { type: Array, required: true },
})
defineEmits(['select'])

const { t } = useI18n()
</script>

<template>
  <ul class="list">
    <li v-for="artist in props.artists" :key="artist.key">
      <button type="button" class="row artist-row" @click="$emit('select', artist)">
        <span class="avatar" aria-hidden="true">
          <!-- Initial rather than a generic person glyph: with a Greek library the
               first letter is the fastest thing to scan down a list. -->
          {{ (artist.name ?? '?').trim().slice(0, 1).toLocaleUpperCase('el') }}
        </span>
        <span class="body">
          <span class="name">{{ artist.name ?? t('nowPlaying.unknownArtist') }}</span>
          <span class="meta">
            {{
              artist.tracks.length === 1
                ? t('library.count.song')
                : t('library.count.songs', { count: artist.tracks.length })
            }}
          </span>
        </span>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.list {
  list-style: none;
  margin: 0.6rem 0 0;
  padding: 0;
}

.row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.55rem 0.25rem;
  background: transparent;
  border: 0;
  border-radius: 0.6rem;
  text-align: left;
}

.row:active {
  background: var(--surface-1);
}

.avatar {
  flex-shrink: 0;
  width: 2.4rem;
  height: 2.4rem;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 1rem;
}

.body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}

.name {
  font-size: 0.92rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta {
  font-size: 0.74rem;
  color: var(--text-muted);
}
</style>
