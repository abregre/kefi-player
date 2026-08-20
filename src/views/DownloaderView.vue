<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from '../i18n'
import { useDownloads } from '../composables/useDownloads'
import { Ytdlp } from '../plugins/ytdlp'
import { formatDuration, looksLikeUrl, looksLikeYoutube } from '../utils/format'
import JobCard from '../components/JobCard.vue'

const { t } = useI18n()
const {
  jobs,
  engineVersion,
  isPreparing,
  isUpdating,
  isCheckingUpdate,
  updateAvailable,
  latestVersion,
  updateCheckFailed,
  engineError,
  storageLocation,
  activeCount,
  initialize,
  lookup,
  start,
  cancel,
  remove,
  updateEngine,
  play,
} = useDownloads()

const url = ref('')
const info = ref(null)
const bitrate = ref('256')
const isLookingUp = ref(false)
const lookupError = ref('')
const notice = ref('')

const BITRATES = ['128', '256', '320']

/*
 * Hidden only when the engine is provably current. If the check itself could not run
 * (offline, GitHub rate limit) the button stays, because hiding it would remove a
 * working action on nothing more than a failed network call.
 */
const showUpdateButton = computed(() => updateAvailable.value || updateCheckFailed.value)

async function handleLookup() {
  const value = url.value.trim()
  lookupError.value = ''
  info.value = null

  if (!looksLikeUrl(value)) {
    lookupError.value = t('downloader.lookup.invalidUrl')
    return
  }

  isLookingUp.value = true
  try {
    info.value = await lookup(value)
  } catch (err) {
    lookupError.value = err instanceof Error ? err.message : t('downloader.lookup.error')
  } finally {
    isLookingUp.value = false
  }
}

async function handleDownload() {
  const value = url.value.trim()
  if (!looksLikeUrl(value)) {
    lookupError.value = t('downloader.lookup.invalidUrl')
    return
  }
  // Queue it and clear the box so the next link can be pasted right away.
  const captured = info.value
  url.value = ''
  info.value = null
  lookupError.value = ''
  await start(value, captured, bitrate.value)
}

async function handlePaste() {
  try {
    const text = await navigator.clipboard.readText()
    if (text.trim()) {
      url.value = text.trim()
      await handleLookup()
    }
  } catch {
    lookupError.value = t('downloader.url.clipboardUnavailable')
  }
}

async function handlePlay(job) {
  notice.value = ''
  try {
    await play(job)
  } catch (err) {
    // Most likely no audio player installed, or the file was deleted or moved out
    // from under us since the download finished.
    notice.value = err instanceof Error ? err.message : t('downloader.play.error')
  }
}

async function handleUpdateEngine() {
  notice.value = t('downloader.update.checking')
  try {
    const status = await updateEngine()
    notice.value =
      status === 'ALREADY_UP_TO_DATE'
        ? t('downloader.update.upToDate')
        : t('downloader.update.updated', { status })
  } catch (err) {
    notice.value = err instanceof Error ? err.message : t('downloader.update.failed')
  }
}

/**
 * A link shared from the YouTube app is parked natively before the WebView is ready,
 * so this is checked on mount and again whenever the app returns to the foreground.
 * Only fills the field — it deliberately stops short of starting a download, the same
 * as `$SRC/src/App.vue`'s `checkSharedUrl`.
 */
async function checkSharedUrl() {
  try {
    const { url: shared } = await Ytdlp.consumeSharedUrl()
    if (shared) {
      url.value = shared
      await handleLookup()
    }
  } catch {
    // No share pending, or running in the web mock (which always returns null).
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') void checkSharedUrl()
}

// Attach-once guard for the `sharedUrl` plugin listener, same idea as `listenersReady`
// in `src/composables/usePlayer.js`: this screen mounts fresh each time the hidden tab
// is opened and `onUnmounted` below detaches it, but the flag also protects against
// `onMounted` somehow firing twice for the same instance and stacking a second listener.
let sharedUrlListenerAttached = false
/** @type {{ remove: () => Promise<void> } | null} */
let sharedUrlListenerHandle = null

onMounted(async () => {
  if (!sharedUrlListenerAttached) {
    sharedUrlListenerAttached = true
    sharedUrlListenerHandle = await Ytdlp.addListener('sharedUrl', () => {
      void checkSharedUrl()
    })
  }
  // Unpacks yt-dlp/ffmpeg and, once a day, updates yt-dlp over the network. This tab
  // is hidden behind an easter egg, so this call must live HERE — a user who never
  // opens it must generate zero network traffic.
  await initialize()
  await checkSharedUrl()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  void sharedUrlListenerHandle?.remove()
  sharedUrlListenerAttached = false
})
</script>

<template>
  <section class="page downloader-page">
    <header class="head">
      <div class="titles">
        <h1 class="page-title">{{ t('downloader.title') }}</h1>
        <p class="engine-line">
          <template v-if="isUpdating">{{ t('downloader.engine.updating') }}</template>
          <template v-else-if="isPreparing">{{ t('downloader.engine.preparing') }}</template>
          <template v-else-if="isCheckingUpdate">{{ t('downloader.engine.checking') }}</template>
          <template v-else-if="engineVersion">
            {{ t('downloader.engine.version', { version: engineVersion }) }}
            <span v-if="updateAvailable && latestVersion" class="new-version">
              &middot; {{ t('downloader.engine.available', { version: latestVersion }) }}
            </span>
          </template>
          <template v-else>{{ t('downloader.engine.notReady') }}</template>
        </p>
      </div>
      <button
        v-if="showUpdateButton"
        type="button"
        class="ghost-btn"
        :disabled="isPreparing || isUpdating"
        @click="handleUpdateEngine"
      >
        {{ isUpdating ? t('downloader.update.updating') : t('downloader.update.action') }}
      </button>
    </header>

    <p v-if="engineError" class="banner error">{{ engineError }}</p>
    <p v-else-if="notice" class="banner" @click="notice = ''">{{ notice }}</p>

    <section class="composer">
      <label class="field">
        <span class="field-label">{{ t('downloader.url.label') }}</span>
        <div class="url-row">
          <input
            v-model="url"
            type="url"
            inputmode="url"
            class="url-input"
            :placeholder="t('downloader.url.placeholder')"
            spellcheck="false"
            autocapitalize="none"
            :aria-label="t('downloader.url.label')"
            @keyup.enter="handleLookup"
          />
          <button
            type="button"
            class="ghost-btn small"
            :aria-label="t('downloader.url.paste')"
            @click="handlePaste"
          >
            {{ t('downloader.url.paste') }}
          </button>
        </div>
      </label>

      <p v-if="lookupError" class="hint error">{{ lookupError }}</p>
      <p v-else-if="url && !looksLikeYoutube(url)" class="hint">
        {{ t('downloader.url.notYoutube') }}
      </p>

      <button
        type="button"
        class="secondary-btn"
        :disabled="isLookingUp || !url.trim()"
        @click="handleLookup"
      >
        {{ isLookingUp ? t('downloader.lookup.loading') : t('downloader.lookup.action') }}
      </button>

      <div v-if="info" class="preview">
        <img v-if="info.thumbnail" :src="info.thumbnail" :alt="info.title" class="preview-thumb" />
        <div class="preview-body">
          <p class="preview-title">{{ info.title }}</p>
          <p class="preview-meta">
            <span v-if="info.uploader">{{ info.uploader }}</span>
            <span v-if="info.duration">{{ formatDuration(info.duration) }}</span>
          </p>
        </div>
      </div>

      <div class="field">
        <span class="field-label">{{ t('downloader.bitrate.label') }}</span>
        <div class="chips">
          <button
            v-for="rate in BITRATES"
            :key="rate"
            type="button"
            class="chip"
            :class="{ active: bitrate === rate }"
            @click="bitrate = rate"
          >
            {{ t('downloader.bitrate.kbps', { rate }) }}
          </button>
        </div>
      </div>

      <button
        type="button"
        class="primary-btn"
        :disabled="isPreparing || !url.trim()"
        @click="handleDownload"
      >
        {{ t('downloader.convert.action') }}
      </button>
    </section>

    <section class="jobs">
      <h2 v-if="jobs.length" class="jobs-title">
        {{ t('downloader.jobs.title') }}
        <span v-if="activeCount" class="running-count">
          {{ t('downloader.jobs.running', { count: activeCount }) }}
        </span>
      </h2>
      <p v-else class="empty">
        {{ t('downloader.jobs.empty', { location: storageLocation.label }) }}
      </p>

      <JobCard
        v-for="job in jobs"
        :key="job.id"
        :job="job"
        @cancel="cancel(job.id)"
        @remove="remove(job.id)"
        @open="handlePlay(job)"
      />
    </section>
  </section>
</template>

<style scoped>
.page {
  padding: calc(var(--safe-top) + 1.1rem) 1rem 1rem;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.titles {
  min-width: 0;
}

.page-title {
  font-family: var(--font-display);
  font-size: 1.6rem;
  margin: 0;
}

.engine-line {
  margin: 0.15rem 0 0;
  font-size: 0.78rem;
  color: var(--text-muted);
}

.new-version {
  color: var(--accent);
  white-space: nowrap;
}

.banner {
  margin: 0;
  padding: 0.65rem 0.8rem;
  border-radius: 0.6rem;
  background: var(--surface-2);
  font-size: 0.85rem;
  color: var(--text-muted);
  overflow-wrap: anywhere;
}

.banner.error {
  background: var(--danger-soft);
  color: var(--danger);
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
  padding: 1rem;
  border-radius: 0.9rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.field-label {
  font-family: var(--font-display);
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-muted);
}

.url-row {
  display: flex;
  gap: 0.5rem;
}

.url-input {
  flex: 1;
  min-width: 0;
  padding: 0.7rem 0.8rem;
  border-radius: 0.6rem;
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text);
  font: inherit;
  font-size: 0.92rem;
}

.url-input:focus {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}

.hint {
  margin: 0;
  font-size: 0.78rem;
  color: var(--text-muted);
  padding: 0.4rem 0.55rem;
  border-radius: 0.45rem;
  overflow-wrap: anywhere;
}

.hint.error {
  background: var(--danger-soft);
  color: var(--danger);
}

button {
  font: inherit;
  cursor: pointer;
  border-radius: 0.6rem;
  border: 1px solid transparent;
  padding: 0.7rem 0.9rem;
}

button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.primary-btn {
  background: linear-gradient(140deg, var(--accent-strong), var(--accent-deep));
  color: var(--on-accent);
  font-weight: 500;
}

.primary-btn:not(:disabled):active {
  filter: brightness(0.92);
}

.secondary-btn {
  background: var(--surface-2);
  color: var(--text);
  border-color: var(--border);
}

.ghost-btn {
  background: transparent;
  color: var(--text-muted);
  border-color: var(--border);
  flex-shrink: 0;
}

.ghost-btn.small {
  padding: 0.7rem 0.75rem;
  font-size: 0.85rem;
}

.chips {
  display: flex;
  gap: 0.4rem;
}

.chip {
  flex: 1;
  padding: 0.5rem 0.4rem;
  background: var(--surface-2);
  border-color: var(--border);
  color: var(--text-muted);
  font-size: 0.82rem;
}

.chip.active {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--text);
  font-weight: 500;
}

.preview {
  display: flex;
  gap: 0.75rem;
  padding: 0.6rem;
  border-radius: 0.75rem;
  background: var(--surface-2);
}

.preview-thumb {
  width: 6rem;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  border-radius: 0.5rem;
  flex-shrink: 0;
  background: var(--surface-1);
}

.preview-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 0.25rem;
}

.preview-title {
  margin: 0;
  font-size: 0.9rem;
  font-weight: 500;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}

.preview-meta {
  margin: 0;
  display: flex;
  gap: 0.6rem;
  font-size: 0.75rem;
  color: var(--text-muted);
}

.jobs {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

.jobs-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.running-count {
  font-size: 0.7rem;
  padding: 0.15rem 0.45rem;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--text);
  letter-spacing: 0;
  text-transform: none;
}

.empty {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.5;
  color: var(--text-muted);
  text-align: center;
  padding: 1.5rem 1rem;
  border: 1px solid var(--border);
  border-radius: 0.85rem;
  overflow-wrap: anywhere;
}
</style>
