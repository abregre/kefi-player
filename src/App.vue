<script setup>
import { Capacitor } from '@capacitor/core'

const platform = Capacitor.getPlatform()
const isNative = Capacitor.isNativePlatform()
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
</style>
