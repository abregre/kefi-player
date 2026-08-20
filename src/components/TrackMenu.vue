<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from '../i18n'
import { usePlaylists } from '../composables/usePlaylists'
import { useTrackMenu } from '../composables/useTrackMenu'
import { Player } from '../plugins/player'

/*
 * The app's first bottom sheet. It intentionally stays ONE component with no external
 * dependency (no sheet library, no focus-trap library) and no global overlay system —
 * every list that needs it (Songs, a Folders drill-down, a playlist's detail screen)
 * mounts this same component and shares the "which track" state in useTrackMenu.js
 * instead of building its own.
 *
 * EVENT SURFACE DECISION: of the five actions, four are fully self-contained here —
 * favourite/unfavourite and add-to-playlist (including creating the playlist) go through
 * usePlaylists(), set-as-ringtone/notification go straight through Player.setAsRingtone —
 * and none of them need to tell a parent anything. "View in folder" is the one exception:
 * the navigation it implies is a different view's job, so it is the only action that
 * crosses the component boundary. Given exactly ONE action escapes, this emits a single
 * named event (`view-in-folder`) rather than a generic discriminated `action` event — a
 * discriminator earns its keep once there are several outward actions to tell apart, and
 * adding one here for a single case would be premature generality. If a future action
 * needs to escape too, add a second named event the same way; that is additive, not a
 * breaking change for whatever already listens for `view-in-folder`.
 *
 * This one DOES close the sheet itself, unlike the four self-contained actions above,
 * which close (or don't) on their own terms — see `onViewInFolder` below for why: every
 * current listener navigates away, and closing lived in three different call sites before
 * a review caught that one of them (Now Playing) had never been given the closing call at
 * all. Centralising it here means a fourth future listener can't repeat that mistake.
 *
 * HARDWARE BACK: deliberately NOT wired here. Capacitor's `App.addListener('backButton',
 * ...)` calls every registered listener in turn (`listeners.forEach(l => l(data))` in
 * @capacitor/core's `WebPlugin` — there is no way for one listener to stop another from
 * also running). `wireHardwareBack` in src/router/index.js is already one such listener,
 * unconditionally navigating/exiting on every press. A second listener added here that
 * just closes the sheet would run ALONGSIDE it, not instead of it — so a back-press while
 * this sheet is open would close the sheet AND let the router act underneath it (on
 * `/library` that means backgrounding the whole app while the user only meant to dismiss a
 * menu). router/index.js is T10's file this batch and out of scope here, so this is left
 * for that task to close: see the MOUNTING RECIPE in the T8 notepad entry for the exact
 * change `wireHardwareBack` needs. Backdrop tap and the explicit close button below are
 * fully wired and are the only dismissal paths this component can safely own on its own.
 */
const emit = defineEmits(['view-in-folder'])

const { t } = useI18n()
const { playlists, isFavorite, toggleFavorite, createPlaylist, addToPlaylist } = usePlaylists()
const { activeTrack, closeTrackMenu } = useTrackMenu()

/** 'menu' = the five top-level actions; 'playlist' = the add-to-playlist step. */
const step = ref('menu')
/** Shows the inline "new playlist" text input in place of the "New playlist…" button. */
const creatingPlaylist = ref(false)
const newPlaylistName = ref('')
/** @type {import('vue').Ref<HTMLInputElement | null>} */
const newPlaylistInput = ref(null)
/** Disables the ringtone/notification/add-to-playlist controls during a native round
 *  trip, so a slow call can't be tapped twice. */
const busy = ref(false)
/** @type {import('vue').Ref<{ kind: 'info' | 'error' | 'success', text: string } | null>} */
const notice = ref(null)

const isFavourite = computed(() => (activeTrack.value ? isFavorite(activeTrack.value) : false))

// Covers both a fresh open AND jumping straight from one track's menu to another's
// (openTrackMenu can be called again before this one is closed, skipping the null in
// between) — either way, stale step/draft/notice state from a previous track must not
// leak into the next one's menu.
watch(activeTrack, () => {
  step.value = 'menu'
  creatingPlaylist.value = false
  newPlaylistName.value = ''
  notice.value = null
})

watch(creatingPlaylist, async (showing) => {
  if (!showing) return
  await nextTick()
  newPlaylistInput.value?.focus()
})

function close() {
  closeTrackMenu()
}

function onToggleFavourite() {
  if (!activeTrack.value) return
  toggleFavorite(activeTrack.value)
}

function openPlaylistStep() {
  step.value = 'playlist'
  notice.value = null
}

function backToMenu() {
  step.value = 'menu'
  creatingPlaylist.value = false
  newPlaylistName.value = ''
  notice.value = null
}

function cancelNewPlaylist() {
  creatingPlaylist.value = false
  newPlaylistName.value = ''
}

/** @param {{ id: number }} playlist */
async function onAddToExisting(playlist) {
  if (!activeTrack.value || busy.value) return
  busy.value = true
  try {
    await addToPlaylist(playlist.id, [activeTrack.value])
    close()
  } finally {
    busy.value = false
  }
}

async function onCreateAndAdd() {
  const name = newPlaylistName.value.trim()
  if (!name || !activeTrack.value || busy.value) return
  busy.value = true
  try {
    const id = await createPlaylist(name)
    if (id === null) {
      // createPlaylist() never throws (usePlaylists.js's error policy) — a null id is
      // its own signal that the write failed, recorded in usePlaylists' own
      // errorMessage. Surfacing a plain generic notice here keeps this component from
      // having to know the shape of that message.
      notice.value = { kind: 'error', text: t('trackMenu.actionFailed') }
      return
    }
    await addToPlaylist(id, [activeTrack.value])
    close()
  } finally {
    busy.value = false
  }
}

/** @param {'ringtone' | 'notification'} type */
async function onSetAsRingtone(type) {
  if (!activeTrack.value || busy.value) return
  busy.value = true
  notice.value = null
  try {
    const { granted } = await Player.setAsRingtone({ uri: activeTrack.value.uri, type })
    if (granted) {
      notice.value = {
        kind: 'success',
        text: type === 'ringtone' ? t('trackMenu.ringtoneSet') : t('trackMenu.notificationSet'),
      }
    } else {
      // NOT an error: `{ granted: false }` means the native side has already sent the
      // user to system Settings to grant the WRITE_SETTINGS special permission (T12's
      // documented contract). Showing this as a failure would report that something
      // broke when nothing did, and retrying automatically would just bounce the user
      // back to Settings before they have had a chance to grant it there.
      notice.value = { kind: 'info', text: t('trackMenu.permissionNeeded') }
    }
  } catch {
    // A real rejection — this IS worth surfacing as a failure, unlike the branch above.
    notice.value = { kind: 'error', text: t('trackMenu.actionFailed') }
  } finally {
    busy.value = false
  }
}

function onViewInFolder() {
  if (!activeTrack.value) return
  // Every listener (LibraryView, PlaylistView, NowPlayingView) navigates to/within the
  // Library on this action, so leaving the sheet open would show the destination screen
  // with this `.backdrop` (position: fixed; inset: 0) still sitting on top of it until
  // the user dismissed it by hand. Snapshot the track BEFORE closing: closeTrackMenu()
  // nulls the shared activeTrack ref, and the listener needs the actual object, not
  // whatever the ref holds afterwards.
  const track = activeTrack.value
  closeTrackMenu()
  emit('view-in-folder', track)
}
</script>

<template>
  <Transition name="track-menu">
    <div v-if="activeTrack" class="backdrop" @click="close">
      <div
        class="sheet"
        role="dialog"
        aria-modal="true"
        :aria-label="t('trackMenu.open')"
        @click.stop
      >
        <div class="sheet-header">
          <button
            v-if="step === 'playlist'"
            type="button"
            class="icon-button"
            :aria-label="t('trackMenu.back')"
            @click="backToMenu"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
              <path d="m16 5-7 7 7 7" />
            </svg>
          </button>
          <p class="sheet-title">
            {{ step === 'menu' ? activeTrack.title : t('trackMenu.addToPlaylist') }}
          </p>
          <button
            type="button"
            class="icon-button"
            :aria-label="t('trackMenu.close')"
            @click="close"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </div>

        <template v-if="step === 'menu'">
          <button type="button" class="option" :disabled="busy" @click="onToggleFavourite">
            <span class="option-icon" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                :fill="isFavourite ? 'currentColor' : 'none'"
                stroke="currentColor"
                stroke-width="1.7"
              >
                <path
                  d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
                />
              </svg>
            </span>
            <span class="option-label">
              {{ isFavourite ? t('trackMenu.unfavourite') : t('trackMenu.favourite') }}
            </span>
          </button>

          <button type="button" class="option" :disabled="busy" @click="openPlaylistStep">
            <span class="option-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M4 6h11M4 12h11M4 18h6" />
                <path d="M17 15v6M14 18h6" />
              </svg>
            </span>
            <span class="option-label">{{ t('trackMenu.addToPlaylist') }}</span>
          </button>

          <button
            type="button"
            class="option"
            :disabled="busy"
            @click="onSetAsRingtone('ringtone')"
          >
            <span class="option-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
                <path d="M13.73 21a2 2 0 0 1-3.46 0" />
              </svg>
            </span>
            <span class="option-label">{{ t('trackMenu.setRingtone') }}</span>
          </button>

          <button
            type="button"
            class="option"
            :disabled="busy"
            @click="onSetAsRingtone('notification')"
          >
            <span class="option-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
                <path d="M13.73 21a2 2 0 0 1-3.46 0" />
                <circle cx="18" cy="6" r="3" fill="currentColor" stroke="none" />
              </svg>
            </span>
            <span class="option-label">{{ t('trackMenu.setNotification') }}</span>
          </button>

          <button type="button" class="option" @click="onViewInFolder">
            <span class="option-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path
                  d="M3.5 7.5a2 2 0 0 1 2-2h3.2l2 2.2h7.8a2 2 0 0 1 2 2v8.3a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-10.5Z"
                />
              </svg>
            </span>
            <span class="option-label">{{ t('trackMenu.viewInFolder') }}</span>
          </button>
        </template>

        <template v-else>
          <button
            v-for="playlist in playlists"
            :key="playlist.id"
            type="button"
            class="option"
            :disabled="busy"
            @click="onAddToExisting(playlist)"
          >
            <span class="option-label">{{ playlist.name }}</span>
            <span class="option-meta">
              {{ t('trackMenu.playlistTrackCount', { count: playlist.count }) }}
            </span>
          </button>

          <button
            v-if="!creatingPlaylist"
            type="button"
            class="option"
            @click="creatingPlaylist = true"
          >
            <span class="option-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M12 5v14M5 12h14" />
              </svg>
            </span>
            <span class="option-label">{{ t('trackMenu.newPlaylist') }}</span>
          </button>

          <form v-else class="new-playlist-form" @submit.prevent="onCreateAndAdd">
            <input
              ref="newPlaylistInput"
              v-model="newPlaylistName"
              type="text"
              class="new-playlist-input"
              :placeholder="t('trackMenu.newPlaylistPlaceholder')"
              :aria-label="t('trackMenu.newPlaylistPlaceholder')"
              :disabled="busy"
            />
            <div class="new-playlist-actions">
              <button
                type="button"
                class="text-button"
                :disabled="busy"
                @click="cancelNewPlaylist"
              >
                {{ t('trackMenu.cancel') }}
              </button>
              <button
                type="submit"
                class="text-button primary"
                :disabled="busy || !newPlaylistName.trim()"
              >
                {{ t('trackMenu.createAndAdd') }}
              </button>
            </div>
          </form>
        </template>

        <p v-if="notice" class="notice" :class="notice.kind" role="status">{{ notice.text }}</p>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  align-items: flex-end;
  background: rgb(8 10 10 / 55%);
}

.sheet {
  width: 100%;
  max-height: 80vh;
  overflow-y: auto;
  background: var(--surface-1);
  border-top: 1px solid var(--border);
  border-radius: 0.9rem 0.9rem 0 0;
  padding: 0.5rem 0 calc(var(--safe-bottom) + 0.5rem);
}

.track-menu-enter-active,
.track-menu-leave-active {
  transition: opacity 0.2s ease;
}

.track-menu-enter-from,
.track-menu-leave-to {
  opacity: 0;
}

.sheet {
  transition: transform 0.22s ease;
}

.track-menu-enter-from .sheet,
.track-menu-leave-to .sheet {
  transform: translateY(100%);
}

/* Vestibular disorders are a real accessibility need, and the slide/fade here is pure
   decoration — the sheet's presence already says everything the motion does. Mirrors
   MiniPlayer.vue's `.art` rule: motion off entirely, not merely shortened. */
@media (prefers-reduced-motion: reduce) {
  .backdrop,
  .sheet {
    transition: none;
  }
}

.sheet-header {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 0.5rem 0.6rem;
}

.sheet-title {
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 0 0.2rem;
  font-size: 0.95rem;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Same secondary icon-button idiom as TrackRow.vue's `.row-menu` / Now Playing's
   `.icon-button`: 2.4rem box, transparent, muted, no `:active` fill. */
.icon-button {
  flex-shrink: 0;
  width: 2.4rem;
  height: 2.4rem;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
}

.icon-button svg {
  width: 1.3rem;
  height: 1.3rem;
}

/* Same row shape as SettingsView.vue's `.option`, reused verbatim so this sheet doesn't
   invent a second "list row" idiom next to the one Settings already established. */
.option {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  min-height: 2.6rem;
  padding: 0.75rem 1rem;
  background: transparent;
  border: 0;
  text-align: left;
  font-size: 0.92rem;
}

.option:active {
  background: var(--surface-2);
}

.option:disabled {
  opacity: 0.5;
}

.option-icon {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 1.5rem;
  color: var(--text-muted);
}

.option-icon svg {
  width: 100%;
  height: 100%;
}

.option-label {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.option-meta {
  flex-shrink: 0;
  font-size: 0.78rem;
  color: var(--text-muted);
}

.new-playlist-form {
  padding: 0.5rem 1rem 0.25rem;
}

/* The app's only other text input (LibraryView.vue's `.search`/`.search-input`) is a
   pill; this one borrows the same border/radius/focus tokens so it reads as the same
   control family instead of a one-off. */
.new-playlist-input {
  width: 100%;
  padding: 0.6rem 0.75rem;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 0.6rem;
  color: var(--text);
  font: inherit;
  font-size: 0.9rem;
}

.new-playlist-input:focus {
  outline: none;
  border-color: var(--accent);
}

.new-playlist-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.6rem;
}

.text-button {
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font-size: 0.85rem;
}

.text-button.primary {
  color: var(--on-accent);
  background: var(--accent);
}

.text-button:disabled {
  opacity: 0.5;
}

.notice {
  margin: 0.4rem 1rem 0.2rem;
  padding: 0.55rem 0.7rem;
  border-radius: 0.5rem;
  font-size: 0.8rem;
  line-height: 1.4;
}

.notice.success {
  color: var(--success);
  background: var(--accent-soft);
}

.notice.info {
  color: var(--text-muted);
  background: var(--surface-2);
}

.notice.error {
  color: var(--danger);
  background: var(--danger-soft);
}
</style>
