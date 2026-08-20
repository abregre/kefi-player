<script setup>
import { computed, ref } from 'vue'
import { useI18n } from '../i18n'
import TrackRow from './TrackRow.vue'

const props = defineProps({
  /** `{ key, name, tracks }[]` as produced by `useLibrary`'s `folders` computed. */
  folders: { type: Array, required: true },
  /** The uri the player is currently sitting on, so the right row can mark itself. */
  currentUri: { type: String, default: null },
  playing: { type: Boolean, default: false },
})

defineEmits(['select'])

const { t } = useI18n()

/**
 * Module level, like the Library's own tab: expanding a folder, going to Now Playing and
 * coming back should not silently collapse everything the user opened.
 * @type {import('vue').Ref<string[]>}
 */
const expanded = ref([])

/** @param {string} key */
function toggle(key) {
  expanded.value = expanded.value.includes(key)
    ? expanded.value.filter((open) => open !== key)
    : [...expanded.value, key]
}

/**
 * A path is shown as its last segment (the folder you would recognise) with the parents
 * as context underneath, because `Music/Rebetika/1960s` and `Backup/Rebetika/1960s` are
 * the same word until you see where they live.
 */
const rows = computed(() =>
  props.folders.map((folder) => {
    const path = folder.name ?? ''
    const segments = path.split('/').filter(Boolean)
    return {
      key: folder.key,
      tracks: folder.tracks,
      name: segments.at(-1) ?? t('library.folders.root'),
      parent: segments.slice(0, -1).join('/'),
    }
  }),
)
</script>

<template>
  <ul class="list">
    <li v-for="folder in rows" :key="folder.key">
      <button
        type="button"
        class="row folder-row"
        :class="{ open: expanded.includes(folder.key) }"
        :aria-expanded="expanded.includes(folder.key)"
        @click="toggle(folder.key)"
      >
        <span class="icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M3.5 7.5a2 2 0 0 1 2-2h3.2l2 2.2h7.8a2 2 0 0 1 2 2v8.3a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-10.5Z" />
          </svg>
        </span>
        <span class="body">
          <span class="name">{{ folder.name }}</span>
          <span class="meta">
            <span v-if="folder.parent" class="parent">{{ folder.parent }}</span>
            <span>
              {{
                folder.tracks.length === 1
                  ? t('library.count.song')
                  : t('library.count.songs', { count: folder.tracks.length })
              }}
            </span>
          </span>
        </span>
        <span class="chevron" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
            <path d="m8 5 7 7-7 7" />
          </svg>
        </span>
      </button>

      <!-- Expanded in place rather than pushed onto a screen of its own: the segmented
           control and the tab bar stay put, and the hardware back button keeps meaning
           "leave the library" instead of "close this folder". -->
      <ul v-if="expanded.includes(folder.key)" class="tracks">
        <li v-for="track in folder.tracks" :key="track.id">
          <TrackRow
            :track="track"
            :active="props.currentUri === track.uri"
            :playing="props.playing && props.currentUri === track.uri"
            @select="$emit('select', { track, tracks: folder.tracks })"
          />
        </li>
      </ul>
    </li>
  </ul>
</template>

<style scoped>
.list,
.tracks {
  list-style: none;
  margin: 0;
  padding: 0;
}

.list {
  margin-top: 0.6rem;
}

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

.icon {
  flex-shrink: 0;
  width: 2.5rem;
  height: 2.5rem;
  display: grid;
  place-items: center;
  border-radius: 0.45rem;
  background: var(--surface-2);
  color: var(--accent);
}

.icon svg {
  width: 1.4rem;
  height: 1.4rem;
}

.body {
  flex: 1;
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

.row.open .name {
  color: var(--accent);
}

.meta {
  display: flex;
  gap: 0.4rem;
  font-size: 0.74rem;
  color: var(--text-muted);
  min-width: 0;
}

.parent {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.parent::after {
  content: ' ·';
}

.chevron {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  color: var(--text-muted);
  transition: transform 0.15s ease;
}

.chevron svg {
  width: 1.1rem;
  height: 1.1rem;
}

.row.open .chevron {
  transform: rotate(90deg);
  color: var(--accent);
}

/* Indented and hairlined so an open folder reads as a container, not as more list. */
.tracks {
  margin: 0 0 0.4rem 1.4rem;
  padding-left: 0.6rem;
  border-left: 1px solid var(--border);
}
</style>
