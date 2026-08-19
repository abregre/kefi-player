<script setup>
import { Capacitor } from '@capacitor/core'
import { onMounted, onUnmounted, ref, watch } from 'vue'
// TODO(T8): replace this whole dev panel with the usePlayer composable.
import { Player } from './plugins/player'

const platform = Capacitor.getPlatform()
const isNative = Capacitor.isNativePlatform()

// TODO(T8): replace with usePlayer composable — this is a temporary, dev-only
// panel to drive the Player plugin bridge from the WebView for T7 QA (there is
// no real UI yet). T2's f1.mp3 fixture ("Άσπρη Μέρα", MediaStore _id=24).
const FIXTURE_URI = 'content://media/external/audio/media/24'
const snapshot = ref(null)
let listenerHandle

// TODO(T8): remove — console.log makes every snapshot visible in `adb logcat`
// (Capacitor forwards WebView console output to Logcat), which is how T7's QA
// grep confirms the bridge is live from the web layer.
watch(snapshot, (value) => {
  if (value) console.log('[T7 QA] playerState', JSON.stringify(value))
})

async function refreshState() {
  snapshot.value = await Player.getPlayerState()
}

async function playFixture() {
  snapshot.value = await Player.playTrack({
    uri: FIXTURE_URI,
    title: 'Άσπρη Μέρα',
    artist: 'Δήμητρα Παπαδοπούλου',
  })
}

async function pauseTrack() {
  snapshot.value = await Player.pause()
}

async function resumeTrack() {
  snapshot.value = await Player.resume()
}

onMounted(async () => {
  listenerHandle = await Player.addListener('playerState', (state) => {
    snapshot.value = state
  })
  await refreshState()
})

onUnmounted(() => {
  listenerHandle?.remove()
})
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

    <!-- TODO(T8): replace with usePlayer composable — dev-only Player bridge panel -->
    <section class="dev-player">
      <h2>Player bridge (dev, T7 QA)</h2>
      <div class="dev-player-actions">
        <button type="button" @click="playFixture">Play fixture (f1)</button>
        <button type="button" @click="pauseTrack">Pause</button>
        <button type="button" @click="resumeTrack">Resume</button>
        <button type="button" @click="refreshState">Refresh state</button>
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

/* TODO(T8): remove alongside the dev-only Player bridge panel above. */
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
