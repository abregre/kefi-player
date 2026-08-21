import { computed, readonly, ref } from 'vue'
import { Ytdlp } from '../plugins/ytdlp'

/**
 * @typedef {import('../plugins/ytdlp').Bitrate} Bitrate
 * @typedef {import('../plugins/ytdlp').StorageLocationInfo} StorageLocationInfo
 * @typedef {import('../plugins/ytdlp').VideoInfo} VideoInfo
 * @typedef {import('../plugins/ytdlp').DownloadResult} DownloadResult
 */

/**
 * @typedef {'queued' | 'downloading' | 'converting' | 'done' | 'failed' | 'cancelled'} JobStatus
 */

/**
 * @typedef {Object} Job
 * @property {string} id
 * @property {string} url
 * @property {string} title
 * @property {string | null} thumbnail
 * @property {JobStatus} status
 * @property {number} progress
 * @property {number} etaSeconds
 * @property {number} [durationSeconds] Source media duration, used by the native conversion meter.
 * @property {string} [error]
 * @property {string} [displayName]
 * @property {number} [size]
 * @property {string} [uri] content:// (or FileProvider) URI of the saved file, for playback.
 * @property {string} [location] Where it was saved, e.g. "Music/YTMP3".
 * @property {string} [line] Latest raw yt-dlp line, shown in the detail row.
 */

// Module-level state: declared OUTSIDE the exported function so every component that
// mounts the (hidden) downloader tab shares one instance instead of each
// `useDownloads()` call getting its own private copy. Mirrors
// `src/composables/usePlayer.js:12-35`.
/** @type {import('vue').Ref<Job[]>} */
const jobs = ref([])
const engineVersion = ref('')
const isPreparing = ref(false)
const engineError = ref('')
const isUpdating = ref(false)
const isCheckingUpdate = ref(false)
const updateAvailable = ref(false)
/** @type {import('vue').Ref<string | null>} */
const latestVersion = ref(null)
/** True when the check itself could not run (offline, GitHub rate limit). */
const updateCheckFailed = ref(false)
/** @type {import('vue').Ref<StorageLocationInfo>} */
const storageLocation = ref({
  label: 'Music/YTMP3',
  isCustom: false,
  uri: null,
  cancelled: false,
})

// This app namespaces every localStorage/preferences key `kefi.*` (see
// `src/i18n/index.js:7`) — ytmp3 used the bare key `ytmp3.ytdlpUpdatedAt`, which would
// collide in spirit (though not literally, different origin) with this app's own
// naming convention, so it is renamed rather than copied verbatim.
const LAST_UPDATE_KEY = 'kefi.downloader.ytdlpUpdatedAt'
const UPDATE_INTERVAL_MILLIS = 24 * 60 * 60 * 1000

/** @returns {boolean} */
function shouldAutoUpdate() {
  const last = Number(localStorage.getItem(LAST_UPDATE_KEY) ?? 0)
  return !Number.isFinite(last) || Date.now() - last > UPDATE_INTERVAL_MILLIS
}

let listenersReady = false

/**
 * @param {string} id
 * @returns {Job | undefined}
 */
function findJob(id) {
  return jobs.value.find((job) => job.id === id)
}

/**
 * @param {JobStatus} status
 * @returns {boolean}
 */
function isActive(status) {
  return status === 'queued' || status === 'downloading' || status === 'converting'
}

/**
 * @param {unknown} err
 * @returns {string}
 */
function errorMessage(err) {
  if (err instanceof Error) return err.message
  if (typeof err === 'string') return err
  return 'Something went wrong'
}

/**
 * Registered once for the app's lifetime. Progress and terminal states arrive as events
 * rather than only via the download() promise, so a download still reports correctly if
 * the promise is lost. Mirrors the attach-once guard in `usePlayer.js:125-135`.
 */
async function attachListeners() {
  if (listenersReady) return
  listenersReady = true

  await Ytdlp.addListener('progress', (event) => {
    const job = findJob(event.jobId)
    if (!job || !isActive(job.status)) return
    // yt-dlp reports -1 until the first bytes land. During conversion the native layer
    // samples the MP3 that ffmpeg is really writing, so this is a second, independent
    // 0–100 curve rather than the download percentage carried over into the next phase.
    job.line = event.line
    if (event.phase === 'converting') {
      // The last downloader callback is normally 100%. It describes received bytes,
      // not encoded audio, so never carry it into the conversion bar.
      if (job.status !== 'converting') {
        job.status = 'converting'
        job.progress = 0
        job.etaSeconds = 0
      }
      if (event.measured) {
        job.progress = event.progress < 0 ? job.progress : event.progress
        job.etaSeconds = event.etaSeconds
      }
    } else {
      job.progress = event.progress < 0 ? job.progress : event.progress
      job.etaSeconds = event.etaSeconds
      job.status = 'downloading'
    }
  })

  await Ytdlp.addListener('completed', (event) => {
    const job = findJob(event.jobId)
    if (!job) return
    job.status = 'done'
    job.progress = 100
    job.displayName = event.displayName
    job.size = event.size
    job.uri = event.uri
    job.location = event.location
    job.title = event.title || job.title
  })

  await Ytdlp.addListener('failed', (event) => {
    const job = findJob(event.jobId)
    // Only act if the job is still active: a stale event for a job the user already
    // cleared (or that already reached a terminal state some other way) must not
    // resurrect it.
    if (!job || !isActive(job.status)) return
    job.status = 'failed'
    job.error = event.error
  })

  await Ytdlp.addListener('cancelled', (event) => {
    const job = findJob(event.jobId)
    if (!job || !isActive(job.status)) return
    job.status = 'cancelled'
  })
}

export function useDownloads() {
  /**
   * Call once when the (hidden) downloader screen mounts: unpacks yt-dlp/ffmpeg and
   * wires up events. Deliberately NOT called at app start — a user who never unlocks
   * the hidden tab must generate zero network traffic, so the once-per-24h nightly
   * auto-update below only ever runs from here, triggered by the downloader screen's
   * own mount, not by app start.
   * @returns {Promise<void>}
   */
  async function initialize() {
    isPreparing.value = true
    engineError.value = ''
    try {
      await attachListeners()
      const { version } = await Ytdlp.prepare()
      engineVersion.value = version
      storageLocation.value = await Ytdlp.getStorageLocation()

      // The yt-dlp bundled in the APK is a stable release, and as of 2026-08 stable
      // cannot download YouTube at all -- so the very first time the downloader screen
      // is opened, this installs nightly outright rather than waiting for the user to
      // notice a broken download. Later opens only check and offer (see
      // refreshUpdateStatus below).
      if (shouldAutoUpdate()) {
        isUpdating.value = true
        try {
          await updateEngine('nightly')
        } catch {
          // Offline, or GitHub unreachable. The existing binary still works for other
          // sites, and the Update button can retry.
        } finally {
          isUpdating.value = false
        }
      }

      await refreshUpdateStatus()
    } catch (err) {
      engineError.value = errorMessage(err)
    } finally {
      isPreparing.value = false
    }
  }

  /**
   * Asks whether a newer yt-dlp exists, so the UI can hide the Update button when there
   * is nothing to install.
   * @returns {Promise<void>}
   */
  async function refreshUpdateStatus() {
    isCheckingUpdate.value = true
    updateCheckFailed.value = false
    try {
      const result = await Ytdlp.checkForUpdate({ channel: 'nightly' })
      updateAvailable.value = result.updateAvailable
      latestVersion.value = result.latest
      if (result.installed) engineVersion.value = result.installed
    } catch {
      // Cannot prove the engine is current, so leave the button reachable rather than
      // hiding a working action behind a failed network call.
      updateCheckFailed.value = true
      updateAvailable.value = false
    } finally {
      isCheckingUpdate.value = false
    }
  }

  /**
   * @param {string} url
   * @returns {Promise<VideoInfo>}
   */
  async function lookup(url) {
    return Ytdlp.getInfo({ url })
  }

  /**
   * @param {string} url
   * @param {VideoInfo | null} info
   * @param {Bitrate} bitrate
   * @returns {Promise<void>}
   */
  async function start(url, info, bitrate) {
    const id = crypto.randomUUID()
    jobs.value.unshift({
      id,
      url,
      title: info?.title ?? url,
      thumbnail: info?.thumbnail ?? null,
      durationSeconds: info?.duration ?? 0,
      status: 'queued',
      progress: 0,
      etaSeconds: 0,
    })

    try {
      await Ytdlp.download({
        url,
        jobId: id,
        bitrate,
        durationSeconds: info?.duration ?? 0,
        embedThumbnail: true,
      })
      // Terminal state is applied by the 'completed' listener.
    } catch (err) {
      // The 'failed'/'cancelled' listeners normally cover this. This catch is for
      // rejections that never emit an event, such as argument validation.
      const job = findJob(id)
      if (job && isActive(job.status)) {
        job.status = 'failed'
        job.error = errorMessage(err)
      }
    }
  }

  /**
   * @param {string} id
   * @returns {Promise<void>}
   */
  async function cancel(id) {
    await Ytdlp.cancel({ jobId: id })
    const job = findJob(id)
    if (job && isActive(job.status)) {
      job.status = 'cancelled'
    }
  }

  /** @param {string} id */
  function remove(id) {
    const index = jobs.value.findIndex((job) => job.id === id)
    if (index !== -1) jobs.value.splice(index, 1)
  }

  /**
   * Opens a finished download in the device's audio player.
   * @param {Job} job
   * @returns {Promise<void>}
   */
  async function play(job) {
    if (!job.uri) throw new Error('That download has no file to play')
    await Ytdlp.openFile({ uri: job.uri })
  }

  /**
   * @param {'nightly' | 'stable' | 'master'} [channel]
   * @returns {Promise<string>}
   */
  async function updateEngine(channel = 'nightly') {
    const { status, version } = await Ytdlp.updateYtdlp({ channel })
    engineVersion.value = version
    latestVersion.value = version
    updateAvailable.value = false
    updateCheckFailed.value = false
    localStorage.setItem(LAST_UPDATE_KEY, String(Date.now()))
    return status
  }

  return {
    jobs: readonly(jobs),
    engineVersion: readonly(engineVersion),
    isPreparing: readonly(isPreparing),
    isUpdating: readonly(isUpdating),
    isCheckingUpdate: readonly(isCheckingUpdate),
    updateAvailable: readonly(updateAvailable),
    latestVersion: readonly(latestVersion),
    updateCheckFailed: readonly(updateCheckFailed),
    engineError: readonly(engineError),
    storageLocation: readonly(storageLocation),
    activeCount: computed(() => jobs.value.filter((job) => isActive(job.status)).length),
    initialize,
    lookup,
    start,
    cancel,
    remove,
    updateEngine,
    play,
    refreshUpdateStatus,
  }
}
