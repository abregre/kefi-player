<script setup>
import { useI18n } from '../i18n'

const { t } = useI18n()

// TODO(T18): artwork, seek bar, transport, shuffle and repeat controls.
// Reached from the mini-player (T17), which is why it has no tab of its own.
</script>

<template>
  <section class="page">
    <h1 class="page-title">{{ t('nowPlaying.title') }}</h1>
    <p class="hint">{{ t('nowPlaying.empty.body') }}</p>
  </section>
</template>

<style scoped>
.page {
  padding: calc(var(--safe-top) + 1.1rem) 1rem 1rem;
}

.page-title {
  font-family: var(--font-display);
  font-size: 1.6rem;
  margin: 0 0 0.3rem;
}

.hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.85rem;
}
</style>
