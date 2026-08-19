<script setup>
// TODO(T13): replaced by the router shell — this whole file is a temporary
// state read-out, not real UI. It exists only so T8-T12's QA can observe
// WebView-side player state (via the <pre> below and the console.log, which
// Capacitor forwards to `adb logcat`).
import { Capacitor } from '@capacitor/core'
import { computed, onMounted, ref, watch } from 'vue'
import { usePlayer } from './composables/usePlayer'
// TODO(T13): T9-only import — usePlayer doesn't (and shouldn't) wrap listTracks/getArtwork/
// permission checks, so this dev panel talks to the plugin directly for those.
import { Player } from './plugins/player'

const platform = Capacitor.getPlatform()
const isNative = Capacitor.isNativePlatform()

const player = usePlayer()

// T12 housekeeping: MediaStore ids renumber whenever fixtures are deleted/rescanned (T11's
// QA already did this once — a hardcoded id here would go stale silently). Resolve by title
// via listTracks() at call time instead, so this panel can never go stale again.
// T10 QA order: Άσπρη Μέρα, Zeibekiko, Ωδή, so `startIndex: 0` lands on Άσπρη Μέρα per the
// plan's QA scenario.
const FIXTURE_TITLES = ['Άσπρη Μέρα', 'Zeibekiko', 'Ωδή']

async function resolveTrackByTitle(title) {
  const { tracks } = await Player.listTracks()
  return tracks.find((t) => t.title === title)
}

// T9 QA state: track listing, permission checks, artwork lookups.
const permissionResult = ref(null)
const tracksResult = ref(null)
const artworkResult = ref(null)

const snapshot = computed(() => ({
  mediaId: player.mediaId.value,
  uri: player.uri.value,
  title: player.title.value,
  artist: player.artist.value,
  currentPosition: player.currentPosition.value,
  duration: player.duration.value,
  isPlaying: player.isPlaying.value,
  shuffleModeEnabled: player.shuffleModeEnabled.value,
  repeatMode: player.repeatMode.value,
  queueLength: player.queueLength.value,
  currentIndex: player.currentIndex.value,
}))

// Makes every snapshot visible in `adb logcat` (Capacitor forwards WebView
// console output under tag `Capacitor/Console`) — the T8 QA scenarios read
// WebView-side state through logcat, not through the screen.
watch(snapshot, (value) => {
  console.log('[T8 QA] playerState', JSON.stringify(value))
})

// T11 QA: makes the trackUnavailable event visible in `adb logcat` (Capacitor/Console),
// same precedent as the [T8 QA]/[T9 QA]/[T10 QA] logs above.
watch(player.lastError, (value) => {
  if (value) console.log('[T11 QA] trackUnavailable', JSON.stringify(value))
})

onMounted(() => {
  player.initialize()
})

async function playFixture() {
  const track = await resolveTrackByTitle('Άσπρη Μέρα')
  if (!track) {
    console.log('[T12 QA] playFixture: fixture not found via listTracks()')
    return
  }
  player.playTrack({ uri: track.uri, title: track.title, artist: track.artist })
}

// T9 QA helpers — logged so `adb logcat` can capture results even without reading the screen.
async function checkAudioPermission() {
  permissionResult.value = await Player.checkAudioPermission()
  console.log('[T9 QA] checkAudioPermission', JSON.stringify(permissionResult.value))
}

async function requestAudioPermission() {
  permissionResult.value = await Player.requestAudioPermission()
  console.log('[T9 QA] requestAudioPermission', JSON.stringify(permissionResult.value))
}

async function listTracks() {
  try {
    tracksResult.value = await Player.listTracks()
    console.log('[T9 QA] listTracks', JSON.stringify(tracksResult.value))
  } catch (err) {
    tracksResult.value = { error: err?.message ?? String(err) }
    console.log('[T9 QA] listTracks error', tracksResult.value.error)
  }
}

async function getArtworkFor(trackId) {
  artworkResult.value = await Player.getArtwork({ trackId })
  const preview = artworkResult.value.dataUrl
    ? `${artworkResult.value.dataUrl.slice(0, 32)}... (${artworkResult.value.dataUrl.length} chars)`
    : null
  console.log('[T9 QA] getArtwork', trackId, JSON.stringify({ dataUrl: preview }))
}

// T12 housekeeping: resolve the fixture's current MediaStore id by title instead of
// hardcoding it, same reasoning as playFixture/queueFixtures above.
async function getArtworkForTitle(title) {
  const track = await resolveTrackByTitle(title)
  if (!track) {
    console.log('[T12 QA] getArtwork: fixture not found via listTracks()', title)
    return
  }
  await getArtworkFor(track.id)
}

// T10 QA helpers — usePlayer doesn't (and shouldn't) wrap queue/shuffle/repeat, so this dev
// panel talks to the plugin directly for those, same precedent as the T9 helpers above.
// Every result is also console.logged so `adb logcat` (Capacitor/Console) can observe it.
async function queueFixtures() {
  const tracks = await Promise.all(FIXTURE_TITLES.map(resolveTrackByTitle))
  const uris = tracks.filter(Boolean).map((t) => t.uri)
  if (uris.length === 0) {
    console.log('[T12 QA] queueFixtures: no fixtures found via listTracks()')
    return
  }
  const result = await Player.setQueue({ uris, startIndex: 0 })
  console.log('[T10 QA] setQueue', JSON.stringify(result))
}

async function nextTrack() {
  const result = await Player.next()
  console.log('[T10 QA] next', JSON.stringify(result))
}

async function previousTrack() {
  const result = await Player.previous()
  console.log('[T10 QA] previous', JSON.stringify(result))
}

async function toggleShuffle(enabled) {
  const result = await Player.setShuffle({ enabled })
  console.log('[T10 QA] setShuffle', JSON.stringify(result))
}

async function setRepeatMode(mode) {
  const result = await Player.setRepeatMode({ mode })
  console.log('[T10 QA] setRepeatMode', JSON.stringify(result))
}

// Real seek proof (T10 defect fix (a)): resolves instead of rejecting with
// "A positionMs is required" now that PlayerPlugin.kt coerces Integer-boxed JSON numbers.
async function seekTo(positionMs) {
  await player.seek(positionMs)
  console.log('[T10 QA] seek', positionMs, JSON.stringify({ currentPosition: player.currentPosition.value }))
}
</script>

<template>
  <main class="shell">
    <h1>Kefi Player</h1>
    <p class="tagline">Vue 3 + Vite + Capacitor shell. Playback layer comes next.</p>

    <dl class="env">
      <dt>Platform</dt>
      <dd>{{ platform }}</dd>
      <dt>Runtime</dt>
      <dd>{{ isNative ? 'native WebView' : 'browser' }}</dd>
    </dl>

    <!-- TODO(T13): replaced by the router shell — usePlayer-driven read-out -->
    <section class="dev-player">
      <h2>Player state (dev, usePlayer)</h2>
      <div class="dev-player-actions">
        <button type="button" @click="playFixture">Play fixture (f1)</button>
        <button type="button" @click="player.pause">Pause</button>
        <button type="button" @click="player.resume">Resume</button>
        <button type="button" @click="player.refreshState">Refresh state</button>
      </div>
      <pre class="dev-player-state">{{ JSON.stringify(snapshot) }}</pre>
      <!-- T11 QA: surfaces the most recent trackUnavailable event, if any. -->
      <pre class="dev-player-state">{{ JSON.stringify(player.lastError.value) }}</pre>
    </section>

    <!-- TODO(T13): T10-only QA panel for queue/next/previous/shuffle/repeat/seek -->
    <section class="dev-player">
      <h2>Queue &amp; transport (dev, T10 QA)</h2>
      <div class="dev-player-actions">
        <button type="button" @click="queueFixtures">Queue all 3 (start Άσπρη Μέρα)</button>
        <button type="button" @click="nextTrack">Next</button>
        <button type="button" @click="previousTrack">Previous</button>
        <button type="button" @click="toggleShuffle(true)">Shuffle on</button>
        <button type="button" @click="toggleShuffle(false)">Shuffle off</button>
        <button type="button" @click="setRepeatMode('off')">Repeat off</button>
        <button type="button" @click="setRepeatMode('one')">Repeat one</button>
        <button type="button" @click="setRepeatMode('all')">Repeat all</button>
        <button type="button" @click="seekTo(0)">Seek 0</button>
        <button type="button" @click="seekTo(60000)">Seek 60000</button>
      </div>
    </section>

    <!-- TODO(T13): T9-only QA panel for listTracks/getArtwork/audio permissions -->
    <section class="dev-player">
      <h2>Tracks (dev, T9 QA)</h2>
      <div class="dev-player-actions">
        <button type="button" @click="checkAudioPermission">Check permission</button>
        <button type="button" @click="requestAudioPermission">Request permission</button>
        <button type="button" @click="listTracks">List tracks</button>
        <button type="button" @click="getArtworkForTitle('Άσπρη Μέρα')">Artwork (Άσπρη Μέρα)</button>
        <button type="button" @click="getArtworkForTitle('Zeibekiko')">Artwork (Zeibekiko)</button>
      </div>
      <pre class="dev-player-state">{{ JSON.stringify(permissionResult) }}</pre>
      <pre class="dev-player-state">{{ JSON.stringify(tracksResult) }}</pre>
      <pre class="dev-player-state">{{ JSON.stringify(artworkResult) }}</pre>
    </section>
  </main>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: calc(var(--safe-top) + 2rem) 1.5rem calc(var(--safe-bottom) + 2rem);
  max-width: 30rem;
  margin: 0 auto;
}

h1 {
  font-family: var(--font-display);
  margin: 0;
  font-size: 2rem;
  /* Reads as one warm sweep rather than a flat block of amber. */
  background: linear-gradient(100deg, var(--accent), var(--accent-deep));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.tagline {
  margin: 0;
  color: var(--text-muted);
}

.env {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.4rem 1rem;
  margin: 0;
  padding: 1rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 0.75rem;
}

dt {
  color: var(--text-muted);
}

dd {
  margin: 0;
}

/* TODO(T13): remove alongside the rest of this temporary read-out. */
.dev-player {
  padding: 1rem;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.dev-player h2 {
  margin: 0;
  font-size: 1rem;
}

.dev-player-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.dev-player-actions button {
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
  border: 1px solid var(--border);
  background: var(--accent);
  color: var(--on-accent);
  font: inherit;
}

.dev-player-state {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-muted);
  font-size: 0.75rem;
}
</style>
