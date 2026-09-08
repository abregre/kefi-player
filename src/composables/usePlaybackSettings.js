import { readonly, ref } from 'vue'
import { Player } from '../plugins/player'

/**
 * The per-track resume switch.
 *
 * Module-level state, like `useSoundSettings`, so every screen reads one value. Deliberately
 * thin: this composable can READ and WRITE the setting, but nothing in the WebView ever ACTS on
 * it. Resuming is decided natively, in `PlaybackService`, because the feature has to work when
 * the user is driving playback from a car's buttons with the app closed — there is no screen to
 * ask on at that moment, which is exactly why this is a setting and not a prompt.
 */
const resumeTracks = ref(true)
const error = ref('')
let loaded = false

async function load() {
  if (loaded) return
  loaded = true
  try {
    const { resumeTracks: enabled } = await Player.getPlaybackSettings()
    resumeTracks.value = enabled
  } catch (err) {
    // Leave the default in place rather than rendering a switch in an unknown position, and
    // allow a later call to try again.
    loaded = false
    error.value = 'Could not load playback settings'
  }
}

/** @param {boolean} enabled */
async function setResumeTracks(enabled) {
  const previous = resumeTracks.value
  // Optimistic: a settings toggle that lags a bridge round trip reads as broken.
  resumeTracks.value = enabled
  try {
    const { resumeTracks: applied } = await Player.setResumeTracksEnabled({ enabled })
    resumeTracks.value = applied
    error.value = ''
  } catch (err) {
    resumeTracks.value = previous
    error.value = 'Could not save playback settings'
  }
}

export function usePlaybackSettings() {
  return { resumeTracks: readonly(resumeTracks), error: readonly(error), load, setResumeTracks }
}
