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
    repeatMode: 0,
    queueLength: 0,
    currentIndex: 0,
  }

  /** @type {ReturnType<typeof setInterval> | null} */
  ticker = null

  async getPlayerState() {
    return { ...this.state }
  }

  async playTrack({ uri, title, artist }) {
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
