import { WebPlugin } from '@capacitor/core'

/**
 * Browser stand-in for the native `Player` plugin. There is no Media3/MediaController
 * here, so it keeps a small in-memory snapshot and fakes plausible playback progress.
 * That is enough to build and style the whole UI with `npm run dev`, then verify for
 * real on a device.
 * @implements {import('./player').PlayerPlugin}
 */
export class PlayerWeb extends WebPlugin {
  /** @type {import('./player').PlayerSnapshot} */
  state = {
    mediaId: null,
    uri: null,
    title: null,
    artist: null,
    currentPosition: 0,
    duration: 0,
    isPlaying: false,
    shuffleModeEnabled: false,
    repeatMode: 'off',
    queueLength: 0,
    currentIndex: 0,
  }

  /** @type {ReturnType<typeof setInterval> | null} */
  ticker = null

  /** @type {string[]} Mock queue backing next()/previous(), mirrors setQueue's uris. */
  queue = []

  async getPlayerState() {
    return { ...this.state }
  }

  async playTrack({ uri, title, artist }) {
    this.queue = [uri]
    this.state = {
      ...this.state,
      mediaId: uri,
      uri,
      title: title ?? 'Mock title',
      artist: artist ?? 'Mock artist',
      currentPosition: 0,
      duration: 180000,
      isPlaying: true,
      queueLength: 1,
      currentIndex: 0,
    }
    this.startTicker()
    this.emitState()
    return { ...this.state }
  }

  async pause() {
    this.state = { ...this.state, isPlaying: false }
    this.stopTicker()
    this.emitState()
    return { ...this.state }
  }

  async resume() {
    this.state = { ...this.state, isPlaying: true }
    this.startTicker()
    this.emitState()
    return { ...this.state }
  }

  async seek({ positionMs }) {
    this.state = { ...this.state, currentPosition: positionMs }
    this.emitState()
    return { ...this.state }
  }

  async setQueue({ uris, startIndex = 0 }) {
    this.queue = [...uris]
    this.jumpToIndex(startIndex, { isPlaying: true })
    this.emitState()
    return { ...this.state }
  }

  async next() {
    this.jumpToIndex(this.state.currentIndex + 1)
    this.emitState()
    return { ...this.state }
  }

  async previous() {
    // Mirrors Media3's seekToPrevious(): restart the current track if more than ~3s in,
    // otherwise move to the previous queue item (see PlayerController.kt notes, T10).
    if (this.state.currentPosition > 3000) {
      this.state = { ...this.state, currentPosition: 0 }
    } else {
      this.jumpToIndex(this.state.currentIndex - 1)
    }
    this.emitState()
    return { ...this.state }
  }

  async setShuffle({ enabled }) {
    this.state = { ...this.state, shuffleModeEnabled: enabled }
    this.emitState()
    return { ...this.state }
  }

  async setRepeatMode({ mode }) {
    this.state = { ...this.state, repeatMode: mode }
    this.emitState()
    return { ...this.state }
  }

  /**
   * Dev-only helper, not part of the native `PlayerPlugin` contract: lets `npm run dev`
   * (no real Media3/file-system errors available in a browser) exercise the `trackUnavailable`
   * path the same way a real deleted/permission-revoked file does on device (T11).
   * @param {{ uri: string | null, reason: string }} options
   */
  async simulateTrackUnavailable({ uri, reason }) {
    this.notifyListeners('trackUnavailable', { uri, reason })
  }

  jumpToIndex(index, extra = {}) {
    if (this.queue.length === 0) return
    const clamped = Math.max(0, Math.min(index, this.queue.length - 1))
    const uri = this.queue[clamped]
    this.state = {
      ...this.state,
      ...extra,
      mediaId: uri,
      uri,
      currentPosition: 0,
      currentIndex: clamped,
      queueLength: this.queue.length,
    }
  }

  startTicker() {
    this.stopTicker()
    this.ticker = setInterval(() => {
      if (!this.state.isPlaying) return
      this.state = { ...this.state, currentPosition: this.state.currentPosition + 1000 }
      this.emitState()
    }, 1000)
  }

  stopTicker() {
    if (this.ticker != null) {
      clearInterval(this.ticker)
      this.ticker = null
    }
  }

  emitState() {
    this.notifyListeners('playerState', { ...this.state })
  }
}
