import { readonly, ref } from 'vue'
import { Player } from '../plugins/player'

const sound = ref(null)
const loading = ref(false)
const error = ref('')

function apply(snapshot) {
  sound.value = snapshot
  error.value = ''
}

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    apply(await Player.getSoundSettings())
  } catch {
    error.value = 'Could not load sound settings'
  } finally {
    loading.value = false
  }
}

async function update(method, options) {
  try {
    apply(await Player[method](options))
  } catch {
    error.value = 'Could not save sound settings'
  }
}

export function useSoundSettings() {
  return {
    sound: readonly(sound), loading: readonly(loading), error: readonly(error), load,
    setEqualizerEnabled: (enabled) => update('setEqualizerEnabled', { enabled }),
    setEqualizerPreset: (preset) => update('setEqualizerPreset', { preset }),
    setEqualizerBandLevels: (levels) => update('setEqualizerBandLevels', { levels }),
    setVolumeBoosterEnabled: (enabled) => update('setVolumeBoosterEnabled', { enabled }),
    setVolumeBoosterGain: (gainMb) => update('setVolumeBoosterGain', { gainMb }),
  }
}
