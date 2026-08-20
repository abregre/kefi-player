import { readonly, ref } from 'vue'
import { Player } from '../plugins/player'

/**
 * @typedef {import('../plugins/player').PlayerSnapshot} PlayerSnapshot
 */

// Module-level state: declared OUTSIDE the exported function so every
// component (and the future T17 mini-player) shares one instance instead of
// each `usePlayer()` call getting its own private copy. Mirrors
// useDownloads.ts:30-45 in /mnt/X/myProjects/ytmp3.
const mediaId = ref(null)
const uri = ref(null)
const title = ref(null)
const artist = ref(null)
const currentPosition = ref(0)
const duration = ref(0)
const isPlaying = ref(false)
const shuffleModeEnabled = ref(false)
const repeatMode = ref('off')
const queueLength = ref(0)
const currentIndex = ref(0)
/** Message from the most recent failed getPlayerState/action call, if any. */
const stateError = ref('')
/**
 * Most recent `trackUnavailable` event, if any (T11) — `{ uri, reason }` or `null`. The
 * native controller has already skipped past the failing item and moved on by the time
 * this fires; this is surfaced state for the UI T19 will build (e.g. a toast/snackbar),
 * not something this composable acts on beyond recording it.
 * @type {import('vue').Ref<{ uri: string | null, reason: string } | null>}
 */
const lastError = ref(null)

let listenersReady = false
let visibilityHandlerReady = false

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
 * @param {PlayerSnapshot} snapshot
 */
function applySnapshot(snapshot) {
  mediaId.value = snapshot.mediaId
  uri.value = snapshot.uri
  title.value = snapshot.title
  artist.value = snapshot.artist
  currentPosition.value = snapshot.currentPosition
  duration.value = snapshot.duration
  isPlaying.value = snapshot.isPlaying
  shuffleModeEnabled.value = snapshot.shuffleModeEnabled
  repeatMode.value = snapshot.repeatMode
  queueLength.value = snapshot.queueLength
  currentIndex.value = snapshot.currentIndex
  stateError.value = ''
}

/**
 * PUSH half. Subscribes to native `playerState` broadcasts and is attached
 * exactly once for the app's lifetime — later calls are no-ops. Mirrors the
 * attach-once guard in useDownloads.ts:55,76-115. This alone is NOT enough to
 * survive a WebView recreation: the listener has no memory of anything that
 * happened before it was (re)registered, which is exactly why `refreshState`
 * below also exists.
 */
async function attachListeners() {
  if (listenersReady) return
  listenersReady = true

  await Player.addListener('playerState', (snapshot) => {
    applySnapshot(snapshot)
  })
  await Player.addListener('trackUnavailable', ({ uri, reason }) => {
    lastError.value = { uri, reason }
  })
}

/**
 * PULL half — the load-bearing one. `PlayerController`/`PlaybackService` are
 * application-scoped and can keep playing after the WebView that pushed to
 * them has been destroyed and recreated; a brand new WebView has zero push
 * history, so this is the only path that can repopulate the UI with the
 * truth. Always re-reads live off the native controller — never render from
 * whatever value happens to still be sitting in the refs above.
 */
async function refreshState() {
  try {
    const snapshot = await Player.getPlayerState()
    applySnapshot(snapshot)
  } catch (err) {
    stateError.value = errorMessage(err)
  }
}

/**
 * Attached exactly once for the app's lifetime. Every time the document
 * becomes visible again — including after the WebView itself was reclaimed
 * and rebuilt while backgrounded — pulls a fresh snapshot rather than trusting
 * the (possibly stale, possibly entirely absent) push history.
 */
function attachVisibilityHandler() {
  if (visibilityHandlerReady) return
  visibilityHandlerReady = true

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      refreshState()
    }
  })
}

export function usePlayer() {
  /**
   * Call once per app start (safe to call from every component that mounts
   * usePlayer — both halves are idempotent): wires the push listener, wires
   * the pull-on-resume handler, and does the first pull immediately so the
   * very first render is never based on a stale/default value.
   */
  async function initialize() {
    await attachListeners()
    attachVisibilityHandler()
    await refreshState()
  }

  /** @param {{ uri: string, title?: string, artist?: string }} options */
  async function playTrack(options) {
    try {
      applySnapshot(await Player.playTrack(options))
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  async function pause() {
    try {
      applySnapshot(await Player.pause())
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  async function resume() {
    try {
      applySnapshot(await Player.resume())
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  /**
   * Replaces the whole queue and starts at `startIndex` (T15: the tapped row, with
   * the full VISIBLE list behind it, so a search result plays in the order the user
   * is looking at).
   * @param {{ uris: string[], startIndex?: number }} options
   */
  async function setQueue(options) {
    try {
      applySnapshot(await Player.setQueue(options))
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  async function next() {
    try {
      applySnapshot(await Player.next())
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  async function previous() {
    try {
      applySnapshot(await Player.previous())
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  /** @param {boolean} enabled */
  async function setShuffle(enabled) {
    try {
      applySnapshot(await Player.setShuffle({ enabled }))
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  /** @param {'off' | 'one' | 'all'} mode */
  async function setRepeatMode(mode) {
    try {
      applySnapshot(await Player.setRepeatMode({ mode }))
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  /** @param {number} positionMs */
  async function seek(positionMs) {
    try {
      applySnapshot(await Player.seek({ positionMs }))
    } catch (err) {
      stateError.value = errorMessage(err)
    }
  }

  return {
    mediaId: readonly(mediaId),
    uri: readonly(uri),
    title: readonly(title),
    artist: readonly(artist),
    currentPosition: readonly(currentPosition),
    duration: readonly(duration),
    isPlaying: readonly(isPlaying),
    shuffleModeEnabled: readonly(shuffleModeEnabled),
    repeatMode: readonly(repeatMode),
    queueLength: readonly(queueLength),
    currentIndex: readonly(currentIndex),
    stateError: readonly(stateError),
    lastError: readonly(lastError),
    initialize,
    refreshState,
    playTrack,
    pause,
    resume,
    seek,
    setQueue,
    next,
    previous,
    setShuffle,
    setRepeatMode,
  }
}
