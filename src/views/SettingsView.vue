<script setup>
import { ref } from 'vue'
import { LOCALES, useI18n } from '../i18n'
import { Player } from '../plugins/player'
import { registerTap } from '../composables/useEasterEgg'
import { useRouter } from 'vue-router'
import { useSoundSettings } from '../composables/useSoundSettings'
import { usePlaybackSettings } from '../composables/usePlaybackSettings'

const { locale, t, setLocale } = useI18n()
const router = useRouter()
const { sound, load: loadSound } = useSoundSettings()
loadSound()

/*
 * Read here and written here, never consulted at playback time. The native side decides on its
 * own whether to resume a track, precisely so the decision can be made with no screen present —
 * the user may well be starting that track from a car stereo with the phone in a pocket.
 */
const { resumeTracks, load: loadPlayback, setResumeTracks } = usePlaybackSettings()
loadPlayback()

/*
 * An app cannot place its own widget; it can only ASK the launcher to
 * (`requestPinAppWidget`), and some launchers refuse. There is no way to find out except by
 * asking, so the offer is always shown and a refusal turns into a line explaining the long
 * way round — the launcher's own widget list, which always works.
 */
const pinRefused = ref(false)

async function addWidget() {
  try {
    const { supported } = await Player.requestWidgetPin()
    pinRefused.value = !supported
  } catch {
    pinRefused.value = true
  }
}
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

    <h2 class="section-title">{{ t('sound.title') }}</h2>
    <div class="card">
      <button type="button" class="option" @click="router.push('/settings/equalizer')"><span>{{ t('sound.equalizer') }}</span><span class="summary">{{ sound?.equalizer?.enabled ? t('sound.on') : t('sound.off') }} ›</span></button>
      <button type="button" class="option" @click="router.push('/settings/volume-booster')"><span>{{ t('sound.booster') }}</span><span class="summary">{{ sound?.volumeBooster?.enabled ? t('sound.on') : t('sound.off') }} ›</span></button>
    </div>

    <h2 class="section-title">{{ t('settings.playback') }}</h2>
    <div class="card">
      <label class="option toggle">
        <span>{{ t('settings.playback.resume') }}</span>
        <input
          type="checkbox"
          :checked="resumeTracks"
          @change="setResumeTracks($event.target.checked)"
        >
      </label>
    </div>
    <p class="note">{{ t('settings.playback.resume.hint') }}</p>

    <h2 class="section-title">{{ t('settings.widget') }}</h2>
    <div class="card">
      <button type="button" class="option" @click="addWidget">
        <span>{{ t('settings.widget.add') }}</span>
        <span class="tick" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
      </button>
    </div>
    <p v-if="pinRefused" class="note">{{ t('settings.widget.manual') }}</p>

    <h2 class="section-title">{{ t('settings.about') }}</h2>
    <div class="card about">
      <p class="app-name">{{ t('app.name') }}</p>
      <p class="about-body">{{ t('settings.about.body') }}</p>
    </div>

    <!--
      Text only, deliberately: there is no donation link yet (decided 2026-08-20). When one
      exists this becomes a button that opens it — which needs a plugin method to hand the
      url to the system browser, since a Capacitor WebView would otherwise open it inside
      the app. Until then a dead button would be worse than none.
    -->
    <h2 class="section-title">{{ t('settings.support') }}</h2>
    <div class="card support" @click="registerTap()">
      <span class="support-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 20.3s-7.5-4.6-7.5-9.4a4.2 4.2 0 0 1 7.5-2.6 4.2 4.2 0 0 1 7.5 2.6c0 4.8-7.5 9.4-7.5 9.4Z" />
        </svg>
      </span>
      <p class="support-body">{{ t('settings.support.body') }}</p>
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

/* The `.option` rows above are buttons; this one is a label wrapping a checkbox, so it needs
   the pointer affordance and shrink protection a button gets for free. */
.toggle {
  gap: 0.9rem;
}

.toggle input {
  flex-shrink: 0;
  width: 1.15rem;
  height: 1.15rem;
  accent-color: var(--accent);
}

.tick svg {
  display: block;
  width: 1.1rem;
  height: 1.1rem;
}

.note {
  margin: 0.5rem 0.2rem 0;
  font-size: 0.78rem;
  line-height: 1.45;
  color: var(--text-muted);
}
.summary { color: var(--text-muted); font-size: .82rem; }

.about {
  padding: 0.9rem;
}

.support {
  display: flex;
  align-items: flex-start;
  gap: 0.7rem;
  padding: 0.9rem;
  background: var(--accent-soft);
  border-color: var(--accent-soft);
}

.support-icon {
  flex-shrink: 0;
  color: var(--accent);
}

.support-icon svg {
  display: block;
  width: 1.3rem;
  height: 1.3rem;
}

.support-body {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.45;
  user-select: none;
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
