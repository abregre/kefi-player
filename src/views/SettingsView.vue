<script setup>
import { LOCALES, useI18n } from '../i18n'

const { locale, t, setLocale } = useI18n()
</script>

<template>
  <section class="page">
    <h1 class="page-title">{{ t('settings.title') }}</h1>

    <h2 class="section-title">{{ t('settings.language') }}</h2>
    <div class="card" role="radiogroup" :aria-label="t('settings.language')">
      <button
        v-for="code in LOCALES"
        :key="code"
        type="button"
        class="option"
        role="radio"
        :aria-checked="locale === code"
        :class="{ selected: locale === code }"
        @click="setLocale(code)"
      >
        <span>{{ t(`settings.language.${code}`) }}</span>
        <span v-if="locale === code" class="tick" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
            <path d="m5 13 4.5 4.5L19 7" />
          </svg>
        </span>
      </button>
    </div>

    <h2 class="section-title">{{ t('settings.about') }}</h2>
    <div class="card about">
      <p class="app-name">{{ t('app.name') }}</p>
      <p class="about-body">{{ t('settings.about.body') }}</p>
    </div>
  </section>
</template>

<style scoped>
.page {
  padding: calc(var(--safe-top) + 1.1rem) 1rem 1.5rem;
}

.page-title {
  font-family: var(--font-display);
  font-size: 1.6rem;
  margin: 0 0 1.2rem;
}

.section-title {
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted);
  margin: 1.4rem 0 0.5rem;
}

.section-title:first-of-type {
  margin-top: 0;
}

.card {
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 0.85rem;
  overflow: hidden;
}

.option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0.85rem 0.9rem;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--border);
  text-align: left;
  font-size: 0.95rem;
}

.option:last-child {
  border-bottom: 0;
}

.option:active {
  background: var(--surface-2);
}

.option.selected {
  color: var(--accent);
  background: var(--accent-soft);
}

.tick svg {
  display: block;
  width: 1.1rem;
  height: 1.1rem;
}

.about {
  padding: 0.9rem;
}

.app-name {
  margin: 0 0 0.25rem;
  font-family: var(--font-display);
  font-weight: 500;
}

.about-body {
  margin: 0;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1.45;
}
</style>
