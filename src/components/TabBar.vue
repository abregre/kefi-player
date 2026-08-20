<script setup>
// Bottom tab bar. Only the two top-level destinations live here (Must Have 9);
// Now Playing is reached from the mini-player (T17), not from a tab, so the bar
// keeps the two-target shape a music library actually needs.
import { useI18n } from '../i18n'

const { t } = useI18n()

const tabs = [
  { to: '/library', key: 'tab.library' },
  { to: '/settings', key: 'tab.settings' },
]
</script>

<template>
  <nav class="tabbar">
    <RouterLink v-for="tab in tabs" :key="tab.to" class="tab" :to="tab.to">
      <span v-if="tab.to === '/library'" class="glyph" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H8v16H5.5A1.5 1.5 0 0 1 4 18.5v-13Z" />
          <path d="M10 4h2.5A1.5 1.5 0 0 1 14 5.5v13A1.5 1.5 0 0 1 12.5 20H10V4Z" />
          <path d="m16.6 5.4 1.9 13a1.5 1.5 0 0 0 1.7 1.2l.3-.1-2.2-14.6-1.7.5Z" />
        </svg>
      </span>
      <span v-else class="glyph" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <circle cx="12" cy="12" r="3.2" />
          <path d="M12 3v2.4M12 18.6V21M3 12h2.4M18.6 12H21M5.6 5.6l1.7 1.7M16.7 16.7l1.7 1.7M18.4 5.6l-1.7 1.7M7.3 16.7l-1.7 1.7" />
        </svg>
      </span>
      <span class="label">{{ t(tab.key) }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.tabbar {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  background: var(--surface-1);
  border-top: 1px solid var(--border);
  padding-bottom: var(--safe-bottom);
}

.tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.15rem;
  padding: 0.5rem 0 0.45rem;
  text-decoration: none;
  color: var(--text-muted);
}

.tab:active {
  background: var(--surface-2);
}

/* vue-router adds both classes; -active alone would also light Settings up while
   on a future nested settings route, which is the behaviour we want. */
.tab.router-link-active {
  color: var(--accent);
}

.glyph svg {
  display: block;
  width: 1.5rem;
  height: 1.5rem;
}

.label {
  font-size: 0.7rem;
  line-height: 1.2;
}
</style>
