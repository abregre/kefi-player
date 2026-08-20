import { ref } from 'vue'
import { Preferences } from '@capacitor/preferences'
import { Player } from '../plugins/player'

/** Persisted preference key — namespaced like `kefi.locale`. */
const STORAGE_KEY = 'kefi.downloader.unlocked'

/**
 * Module-level so every component shares one instance — same reasoning as
 * `usePlayer`'s module-level refs. Starts false; hydrated from Preferences
 * once `initializeEasterEgg()` is awaited in `main.js`.
 * @type {import('vue').Ref<boolean>}
 */
const unlocked = ref(false)

/**
 * Sliding timestamp window used by `registerTap()`.
 * @type {number[]}
 */
const tapTimes = []

/** How long the sliding window looks back (milliseconds). */
const WINDOW_MS = 15000

/** Number of taps within the window that triggers the unlock. */
const TAP_THRESHOLD = 10

/**
 * Reads the stored preference, if any. Must be awaited before the app mounts
 * so the router guard (`/downloader`) and `TabBar`'s computed tabs already
 * reflect the persisted state on the very first render.
 *
 * Called once from `main.js`, exactly like `initLocale()`.
 */
export async function initializeEasterEgg() {
  try {
    const { value } = await Preferences.get({ key: STORAGE_KEY })
    if (value === '1') unlocked.value = true
  } catch {
    // No storage (or a browser with it blocked): stay locked.
  }
}

/**
 * Records a tap on the hidden target. Maintains a sliding 15-second window;
 * once 10 taps accumulate within it the downloader is unlocked permanently.
 *
 * Safe to call before `initializeEasterEgg()` resolves — the unlock persists
 * the flag itself, so the next launch will hydrate correctly regardless.
 */
export async function registerTap() {
  if (unlocked.value) return

  const now = Date.now()

  // Drop entries older than the window.
  const cutoff = now - WINDOW_MS
  let i = 0
  while (i < tapTimes.length && tapTimes[i] <= cutoff) i++
  tapTimes.splice(0, i)

  tapTimes.push(now)

  if (tapTimes.length >= TAP_THRESHOLD) {
    unlocked.value = true
    tapTimes.length = 0 // clear — the listener is now inert

    // Persist so the next launch re-hydrates already-unlocked.
    try {
      await Preferences.set({ key: STORAGE_KEY, value: '1' })
    } catch {
      // Persistence failure: the tab is visible for this session but will
      // disappear after a restart. Acceptable — the user can tap again.
    }

    // Enable the share-target activity alias so the system Share sheet
    // offers this app as a destination for URLs.
    try {
      await Player.setShareTargetEnabled({ enabled: true })
    } catch {
      // Native method not available in web/dev context — ignore.
    }
  }
}

export { unlocked }
