<script setup>
// TODO(T14): language switch (el/en), persisted through @capacitor/preferences.
</script>

<template>
  <section class="page">
    <h1 class="page-title">Settings</h1>
    <p class="hint">Language and app info land here.</p>
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
