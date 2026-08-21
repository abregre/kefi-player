<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useSoundSettings } from '../composables/useSoundSettings'

const router = useRouter()
const { t } = useI18n()
const { sound, load, setEqualizerEnabled, setEqualizerPreset, setEqualizerBandLevels } = useSoundSettings()
const equalizer = computed(() => sound.value?.equalizer)
onMounted(load)

function setBand(index, event) {
  const levels = [...equalizer.value.bands.map((band) => band.levelMb)]
  levels[index] = Number(event.target.value)
  setEqualizerBandLevels(levels)
}
</script>

<template>
  <section class="page">
    <header><button class="back" type="button" :aria-label="t('sound.back')" @click="router.back()">‹</button><h1>{{ t('sound.equalizer') }}</h1></header>
    <p class="intro">{{ t('sound.equalizer.description') }}</p>
    <div v-if="equalizer" class="card">
      <label class="row"><span>{{ t('sound.enabled') }}</span><input type="checkbox" :checked="equalizer.enabled" @change="setEqualizerEnabled($event.target.checked)"></label>
    </div>
    <p v-if="!equalizer?.ready" class="note">{{ t('sound.equalizer.notReady') }}</p>
    <template v-else>
      <h2>{{ t('sound.presets') }}</h2>
      <div class="card">
        <button v-for="preset in equalizer.presets" :key="preset.id" class="choice" :class="{ active: equalizer.preset === preset.id }" @click="setEqualizerPreset(preset.id)">{{ preset.name }}</button>
      </div>
      <h2>{{ t('sound.bands') }}</h2>
      <div class="card bands" :class="{ disabled: !equalizer.enabled }">
        <label v-for="band in equalizer.bands" :key="band.index"><span>{{ (band.lowerFrequencyMilliHz / 1000).toFixed(0) }}–{{ (band.upperFrequencyMilliHz / 1000).toFixed(0) }} Hz</span><input type="range" :min="equalizer.levelMinMb" :max="equalizer.levelMaxMb" :value="band.levelMb" :disabled="!equalizer.enabled" @change="setBand(band.index, $event)"></label>
      </div>
    </template>
  </section>
</template>

<style scoped>
.page { padding: calc(var(--safe-top) + 1.1rem) 1rem 1.5rem; } header { display:flex; align-items:center; gap:.6rem; } h1 { font-family:var(--font-display); font-size:1.6rem; margin:0; } h2 { font-size:.72rem; letter-spacing:.08em; text-transform:uppercase; color:var(--text-muted); margin:1.4rem 0 .5rem; }.back { border:0; background:none; color:var(--text); font-size:2rem; line-height:1; padding:0 .35rem; }.intro,.note { color:var(--text-muted); font-size:.85rem; line-height:1.45; }.card { background:var(--surface-1); border:1px solid var(--border); border-radius:.85rem; overflow:hidden; }.row,.choice { display:flex; justify-content:space-between; width:100%; padding:.9rem; border:0; border-bottom:1px solid var(--border); background:transparent; color:var(--text); text-align:left; font:inherit; }.choice.active { color:var(--accent); background:var(--accent-soft); }.choice:last-child,.row:last-child { border-bottom:0; }.bands { padding:.5rem .9rem; }.bands label { display:block; font-size:.8rem; margin:.65rem 0; }.bands input { display:block; width:100%; margin-top:.3rem; }.disabled { opacity:.55; }
</style>
