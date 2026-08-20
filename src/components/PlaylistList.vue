<script setup>
import { nextTick, ref } from 'vue'
import { useI18n } from '../i18n'
import { usePlaylists } from '../composables/usePlaylists'

// No props: `usePlaylists()` is module-level shared state (see its own doc comment, which
// names "the Playlists tab" as one of the components meant to call it directly), so this
// list pulls its own data instead of LibraryView deriving and forwarding it — unlike
// AlbumGrid/ArtistList/FolderList, whose data (albums/artists/folders) is derived from the
// SEARCHED track set and has nowhere else to live.
// Assigned to a variable (not just called) because `submitCreate` below needs to fire
// `select` itself, after a successful create — the only emit in this file that isn't a
// simple template `$emit`.
const emit = defineEmits(['select'])

const { t } = useI18n()
const { playlists, favoriteTracks, recentlyAdded, createPlaylist } = usePlaylists()

/** True while the inline "new playlist" input is showing in place of the create button. */
const creating = ref(false)
const newName = ref('')
const createInput = ref(null)

/** @param {number} count */
function songCountLabel(count) {
  return count === 1 ? t('library.count.song') : t('library.count.songs', { count })
}

function startCreate() {
  creating.value = true
  // The input does not exist yet on the tick `creating` flips true (v-if swaps it in),
  // so the focus call has to wait for that render.
  nextTick(() => createInput.value?.focus())
}

function cancelCreate() {
  creating.value = false
  newName.value = ''
}

/**
 * Enter (or the checkmark button, via `@mousedown.prevent` so it never steals focus and
 * fires the input's `@blur` cancel first) is the only way to create — blurring the input
 * by tapping elsewhere discards the typed name instead of creating a blank-adjacent
 * playlist by accident.
 */
async function submitCreate() {
  const name = newName.value.trim()
  if (name) {
    const id = await createPlaylist(name)
    // `createPlaylist` returns `null` on failure (see usePlaylists.js) and already records
    // `errorMessage` for a toast elsewhere; nothing further to do here but not navigate.
    if (id != null) emit('select', id)
  }
  cancelCreate()
}
</script>

<template>
  <ul class="list">
    <!-- Fixed smart lists first, in the order the plan names them: Favorites, then
         Recently added. Their counts are TRACK counts (how many songs are in each list),
         matching the meta line every other row idiom in this app already uses. -->
    <li>
      <button type="button" class="row playlist-row" @click="$emit('select', 'favorites')">
        <span class="avatar" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path
              d="M12 20s-7-4.35-9.5-9A5.5 5.5 0 0 1 12 6.5 5.5 5.5 0 0 1 21.5 11c-2.5 4.65-9.5 9-9.5 9Z"
            />
          </svg>
        </span>
        <span class="body">
          <span class="name">{{ t('playlists.favorites') }}</span>
          <span class="meta">{{ songCountLabel(favoriteTracks.length) }}</span>
        </span>
      </button>
    </li>
    <li>
      <button type="button" class="row playlist-row" @click="$emit('select', 'recently-added')">
        <span class="avatar" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <circle cx="12" cy="12" r="8.5" />
            <path d="M12 7.5V12l3.2 2" />
          </svg>
        </span>
        <span class="body">
          <span class="name">{{ t('playlists.recentlyAdded') }}</span>
          <span class="meta">{{ songCountLabel(recentlyAdded.length) }}</span>
        </span>
      </button>
    </li>

    <!-- User playlists: same row idiom as ArtistList.vue, initial letter standing in for
         an avatar exactly the way an artist's name does. -->
    <li v-for="playlist in playlists" :key="playlist.id">
      <button type="button" class="row playlist-row" @click="$emit('select', playlist.id)">
        <span class="avatar" aria-hidden="true">
          {{ (playlist.name ?? '?').trim().slice(0, 1).toLocaleUpperCase('el') }}
        </span>
        <span class="body">
          <span class="name">{{ playlist.name }}</span>
          <span class="meta">{{ songCountLabel(playlist.count) }}</span>
        </span>
      </button>
    </li>

    <!-- Create affordance: a button that becomes an inline input, styled after
         LibraryView's `.search-input` (the only text-input precedent in this app) rather
         than inventing a modal or a second input idiom alongside T8's options-sheet one. -->
    <li v-if="!creating">
      <button type="button" class="row playlist-row create-row" @click="startCreate">
        <span class="avatar" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
        <span class="body">
          <span class="name">{{ t('playlists.create') }}</span>
        </span>
      </button>
    </li>
    <li v-else class="create-row-active">
      <span class="avatar" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </span>
      <input
        ref="createInput"
        v-model.trim="newName"
        class="create-input"
        type="text"
        autocapitalize="off"
        autocorrect="off"
        spellcheck="false"
        :placeholder="t('playlists.create.placeholder')"
        :aria-label="t('playlists.create.placeholder')"
        @keyup.enter="submitCreate"
        @keyup.esc="cancelCreate"
        @blur="cancelCreate"
      />
      <button
        type="button"
        class="create-confirm"
        :aria-label="t('playlists.create.action')"
        @mousedown.prevent
        @click="submitCreate"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
          <path d="M5 12.5l4.5 4.5L19 7" />
        </svg>
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

.avatar svg {
  width: 1.2rem;
  height: 1.2rem;
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

/* The active create row is not a <button> (it holds its own input and confirm button),
   so it gets its own flex layout rather than the `.row` button reset. */
.create-row-active {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.55rem 0.25rem;
}

.create-input {
  flex: 1;
  min-width: 0;
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface-1);
  color: var(--text);
  font: inherit;
  font-size: 0.9rem;
}

.create-input:focus {
  outline: none;
  border-color: var(--accent);
}

.create-confirm {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 2.2rem;
  height: 2.2rem;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--accent);
}

.create-confirm svg {
  width: 1.2rem;
  height: 1.2rem;
}
</style>
