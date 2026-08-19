<script setup>
// TODO(T13): replaced by the router shell — this whole file is a temporary
// state read-out, not real UI. It exists only so T8-T12's QA can observe
// WebView-side player state (via the <pre> below and the console.log, which
// Capacitor forwards to `adb logcat`).
import { Capacitor } from '@capacitor/core'
import { computed, onMounted, watch } from 'vue'
import { usePlayer } from './composables/usePlayer'

const platform = Capacitor.getPlatform()
const isNative = Capacitor.isNativePlatform()

const player = usePlayer()

// T2's f1.mp3 fixture ("Άσπρη Μέρα", MediaStore _id=24) — same fixture T7 used.
const FIXTURE_URI = 'content://media/external/audio/media/24'

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

onMounted(() => {
  player.initialize()
})

function playFixture() {
  player.playTrack({
    uri: FIXTURE_URI,
    title: 'Άσπρη Μέρα',
    artist: 'Δήμητρα Παπαδοπούλου',
  })
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
