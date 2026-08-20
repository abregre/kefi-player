import { WebPlugin } from '@capacitor/core'

/**
 * Browser stand-in for the native `Ytdlp` plugin. There is no yt-dlp or ffmpeg here, so
 * it fakes plausible metadata and a progress curve. That is enough to build and style the
 * whole downloader UI with `npm run dev` — and the offline QA suite drives the entire flow
 * through this mock too, since it is forbidden from touching the network.
 * @implements {import('./ytdlp').YtdlpPlugin}
 */
export class YtdlpWeb extends WebPlugin {
  /** @type {Set<string>} jobIds requested to cancel mid-download. */
  cancelled = new Set()

  /** @type {import('./ytdlp').StorageLocationInfo} */
  storage = {
    label: 'Music/YTMP3',
    isCustom: false,
    uri: null,
    cancelled: false,
  }

  async prepare() {
    await delay(400)
    return { ready: true, version: 'mock (browser)' }
  }

  async getInfo({ url }) {
    await delay(400)
    const id = extractVideoId(url) ?? 'dQw4w9WgXcQ'
    return {
      id,
      title: `Mock video ${id}`,
      uploader: 'Mock Channel',
      duration: 213,
      thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
      webpageUrl: url,
    }
  }

  async download(options) {
    const jobId = options.jobId ?? crypto.randomUUID()
    const title = `Mock video ${extractVideoId(options.url) ?? 'audio'}`

    // Deterministic, quick progress loop: reaches 100% in ~1s so the offline QA suite
    // (network-forbidden) can drive the whole downloader UI through this mock.
    for (let progress = 0; progress <= 100; progress += 10) {
      if (this.cancelled.has(jobId)) {
        this.cancelled.delete(jobId)
        this.notifyListeners('cancelled', { jobId })
        const error = new Error('Download cancelled')
        error.code = 'CANCELLED'
        throw error
      }
      await delay(90)
      this.notifyListeners('progress', {
        jobId,
        progress,
        etaSeconds: Math.round(((100 - progress) / 100) * 10),
        line: `[download] ${progress}% of 3.40MiB`,
      })
    }

    const result = {
      jobId,
      uri: `mock://Music/YTMP3/${title}.mp3`,
      displayName: `${title}.mp3`,
      size: 3_400_000,
      title,
      location: this.storage.label,
    }
    // Real plugin both resolves AND emits `completed` with the same payload; mirror that.
    this.notifyListeners('completed', result)
    return result
  }

  async cancel({ jobId }) {
    this.cancelled.add(jobId)
    return { cancelled: true }
  }

  async updateYtdlp() {
    await delay(600)
    return { status: 'DONE', version: 'mock-nightly (browser)' }
  }

  async checkForUpdate() {
    await delay(400)
    return { installed: 'mock (browser)', latest: 'mock (browser)', updateAvailable: false }
  }

  async mintPoToken() {
    await delay(500)
    return { poToken: 'mock-po-token', visitorData: 'mock-visitor-data' }
  }

  async openFile({ uri }) {
    // No system chooser in a browser; surface it so the flow stays testable.
    console.info('[ytdlp mock] would open in an audio player:', uri)
  }

  async getStorageLocation() {
    // Fixed default: pickStorageLocation/clearStorageLocation are not ported (see ytdlp.js),
    // so this mock never has anything else to return.
    return this.storage
  }

  async consumeSharedUrl() {
    return { url: null }
  }

  async readClipboard() {
    // A real browser DOES grant readText() on a user gesture (unlike the Capacitor
    // WebView the native side works around), but this must never throw: the offline QA
    // suite drives the Paste button through this mock.
    try {
      const text = await navigator.clipboard.readText()
      return { text: text ?? '' }
    } catch {
      return { text: '' }
    }
  }
}

/** @param {number} ms */
function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** @param {string} url */
function extractVideoId(url) {
  const match = url.match(/(?:v=|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})/)
  return match?.[1] ?? null
}
