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
const continuePlaylists = ref(true)
const controlsNotification = ref(false)
const error = ref('')
let loaded = false

/** @param {import('../plugins/player').PlaybackSettings} settings */
function apply(settings) {
  resumeTracks.value = settings.resumeTracks
  continuePlaylists.value = settings.continuePlaylists
  controlsNotification.value = settings.controlsNotification
}

async function load() {
  if (loaded) return
  loaded = true
  try {
    apply(await Player.getPlaybackSettings())
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
    apply(await Player.setResumeTracksEnabled({ enabled }))
    error.value = ''
  } catch (err) {
    resumeTracks.value = previous
    error.value = 'Could not save playback settings'
  }
}

/** @param {boolean} enabled */
async function setContinuePlaylists(enabled) {
  const previous = continuePlaylists.value
  continuePlaylists.value = enabled
  try {
    apply(await Player.setContinuePlaylistsEnabled({ enabled }))
    error.value = ''
  } catch (err) {
    continuePlaylists.value = previous
    error.value = 'Could not save playback settings'
  }
}

/** @param {boolean} enabled */
async function setControlsNotification(enabled) {
  const previous = controlsNotification.value
  controlsNotification.value = enabled
  try {
    apply(await Player.setControlsNotificationEnabled({ enabled }))
    error.value = ''
  } catch (err) {
    controlsNotification.value = previous
    error.value = 'Could not save playback settings'
  }
}

export function usePlaybackSettings() {
  return {
    resumeTracks: readonly(resumeTracks),
    continuePlaylists: readonly(continuePlaylists),
    controlsNotification: readonly(controlsNotification),
    error: readonly(error),
    load,
    setResumeTracks,
    setContinuePlaylists,
    setControlsNotification,
  }
}
