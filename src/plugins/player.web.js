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

  /**
   * In-memory playlists, instance-level on this class (there is no Room here) — reset on every
   * page reload, same as `state`/`queue` above. Seeded with one playlist holding
   * two of the three `listTracks` mock paths, so `npm run dev` and the offline QA suite have a
   * non-empty Playlists tab to render immediately.
   * @type {Array<{ id: number, name: string, paths: string[] }>}
   */
  playlists = [
    {
      id: 1,
      name: 'Kefi Mix',
      paths: ['Music/kefi-qa/Aspri Mera.mp3', 'Music/kefi-qa/02 Zeibekiko.mp3'],
    },
  ]

  /** @type {number} Next id `createPlaylist` hands out; the seeded playlist already took `1`. */
  nextPlaylistId = 2

  /**
   * Favourite paths. Seeded with the third mock track's path, so all three `listTracks` mocks
   * are reachable through either the seed playlist or this favourite.
   * @type {Set<string>}
   */
  favoritePaths = new Set(['Music/kefi-qa/vol2/Odi.mp3'])

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
          displayName: 'Aspri Mera.mp3',
          dateAdded: 1_755_680_400,
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
          displayName: '02 Zeibekiko.mp3',
          dateAdded: 1_750_000_000,
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
          displayName: 'Odi.mp3',
          dateAdded: 1_700_000_000,
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

  async requestWidgetPin() {
    console.info('[PlayerWeb] requestWidgetPin is a no-op in the browser')
    return { supported: false }
  }

  async openAppSettings() {
    console.info('[PlayerWeb] openAppSettings is a no-op in the browser')
  }

  async listPlaylists() {
    return {
      playlists: this.playlists.map(({ id, name, paths }) => ({ id, name, count: paths.length })),
    }
  }

  async createPlaylist({ name }) {
    const id = this.nextPlaylistId++
    this.playlists.push({ id, name, paths: [] })
    return { id }
  }

  async renamePlaylist({ id, name }) {
    // Unknown id resolves as a no-op rather than rejecting: the offline QA suite drives the UI
    // through this mock, and a rejection here would surface as a UI error for a state the
    // browser mock simply does not have (e.g. a stale id left over from a previous session).
    const playlist = this.playlists.find((p) => p.id === id)
    if (playlist) playlist.name = name
  }

  async deletePlaylist({ id }) {
    this.playlists = this.playlists.filter((p) => p.id !== id)
  }

  async addToPlaylist({ id, paths }) {
    const playlist = this.playlists.find((p) => p.id === id)
    if (!playlist) return
    for (const path of paths) {
      if (!playlist.paths.includes(path)) playlist.paths.push(path)
    }
  }

  async removeFromPlaylist({ id, path }) {
    const playlist = this.playlists.find((p) => p.id === id)
    if (!playlist) return
    playlist.paths = playlist.paths.filter((p) => p !== path)
  }

  async reorderPlaylist({ id, paths }) {
    const playlist = this.playlists.find((p) => p.id === id)
    if (!playlist) return
    playlist.paths = [...paths]
  }

  async listPlaylistPaths({ id }) {
    const playlist = this.playlists.find((p) => p.id === id)
    return { paths: playlist ? [...playlist.paths] : [] }
  }

  async listFavorites() {
    return { paths: [...this.favoritePaths] }
  }

  async setFavorite({ path, favorite }) {
    if (favorite) this.favoritePaths.add(path)
    else this.favoritePaths.delete(path)
    return { favorite }
  }

  async setAsRingtone() {
    // A browser has no ringtones or notification tones to set; always resolve the same
    // "permission absent" shape the native side uses, rather than throwing.
    return { granted: false }
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
