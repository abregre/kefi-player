<script setup>
import { useI18n } from '../i18n'

const { t } = useI18n()

// TODO(T15/T16/T19): Songs/Albums/Artists, search, and the empty/permission states.
// T13 owns only the route + the page chrome the shell needs to be navigable.
</script>

<template>
  <section class="page">
    <h1 class="page-title">{{ t('library.title') }}</h1>
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
