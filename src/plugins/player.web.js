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
   * Three mock tracks shaped exactly like the native `listTracks` payload, so the library
   * UI can be built and styled with `npm run dev`. Mixed Greek/Latin on purpose — the
   * collation and search behaviour is the part a browser CAN exercise faithfully.
   */
  async listTracks() {
    return {
      tracks: [
        {
          id: 1,
          uri: 'mock://1',
          title: 'Άσπρη Μέρα',
          artist: 'Δήμητρα Παπαδοπούλου',
          album: 'Κέφι Vol 1',
          albumArtist: null,
          trackNumber: null,
          year: null,
          durationMs: 180000,
          size: 2_880_000,
          folder: 'Music/kefi-qa',
        },
        {
          id: 2,
          uri: 'mock://2',
          title: 'Zeibekiko',
          artist: 'Nikos Alexiou',
          album: 'Kefi Vol 1',
          albumArtist: null,
          trackNumber: null,
          year: null,
          durationMs: 210000,
          size: 3_360_000,
          folder: 'Music/kefi-qa',
        },
        {
          id: 3,
          uri: 'mock://3',
          title: 'Ωδή',
          artist: 'Δήμητρα Παπαδοπούλου',
          album: 'Κέφι Vol 2',
          albumArtist: null,
          trackNumber: null,
          year: null,
          durationMs: 240000,
          size: 3_840_000,
          folder: 'Music/kefi-qa/vol2',
        },
      ],
    }
  }

  async getArtwork() {
    // No embedded art in a browser: null exercises the placeholder path, which is the
    // branch worth looking at while styling.
    return { dataUrl: null }
  }

  async checkAudioPermission() {
    // A browser has no MediaStore permission to ask about; granted keeps `npm run dev`
    // on the happy path instead of parking it in T19's permission state.
    return { granted: true, state: 'granted' }
  }

  async requestAudioPermission() {
    return { granted: true, state: 'granted' }
  }

  async openAppSettings() {
    console.info('[PlayerWeb] openAppSettings is a no-op in the browser')
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
