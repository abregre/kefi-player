<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '../i18n'
import { useSoundSettings } from '../composables/useSoundSettings'
const router = useRouter(); const { t } = useI18n(); const { sound, load, setVolumeBoosterEnabled, setVolumeBoosterGain } = useSoundSettings()
const booster = computed(() => sound.value?.volumeBooster)
onMounted(load)
</script>
<template><section class="page"><header><button class="back" type="button" :aria-label="t('sound.back')" @click="router.back()">‹</button><h1>{{ t('sound.booster') }}</h1></header><p class="intro">{{ t('sound.booster.description') }}</p><div v-if="booster" class="card"><label class="row"><span>{{ t('sound.enabled') }}</span><input type="checkbox" :checked="booster.enabled" @change="setVolumeBoosterEnabled($event.target.checked)"></label><label class="gain" :class="{ disabled: !booster.enabled }"><span>{{ (booster.gainMb / 100).toFixed(1) }} dB</span><input type="range" min="0" :max="booster.maxGainMb" :value="booster.gainMb" :disabled="!booster.enabled" @change="setVolumeBoosterGain(Number($event.target.value))"></label></div><p v-if="!booster?.ready" class="note">{{ t('sound.booster.notReady') }}</p></section></template>
<style scoped>.page { padding:calc(var(--safe-top) + 1.1rem) 1rem 1.5rem; }header{display:flex;align-items:center;gap:.6rem}h1{font-family:var(--font-display);font-size:1.6rem;margin:0}.back{border:0;background:none;color:var(--text);font-size:2rem;line-height:1;padding:0 .35rem}.intro,.note{color:var(--text-muted);font-size:.85rem;line-height:1.45rem}.card{background:var(--surface-1);border:1px solid var(--border);border-radius:.85rem}.row,.gain{display:flex;justify-content:space-between;padding:.9rem}.gain{display:block;border-top:1px solid var(--border)}.gain input{display:block;width:100%;margin-top:.6rem}.disabled{opacity:.55}</style>
