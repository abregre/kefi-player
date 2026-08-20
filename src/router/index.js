import { createRouter, createWebHashHistory } from 'vue-router'
import { App as CapacitorApp } from '@capacitor/app'
import DownloaderView from '../views/DownloaderView.vue'
import LibraryView from '../views/LibraryView.vue'
import NowPlayingView from '../views/NowPlayingView.vue'
import PlaylistView from '../views/PlaylistView.vue'
import SettingsView from '../views/SettingsView.vue'
import { unlocked } from '../composables/useEasterEgg'
import { usePlaylists } from '../composables/usePlaylists'
import { useTrackMenu } from '../composables/useTrackMenu'

// `usePlaylists()` is a plain function (no lifecycle hooks), so calling it here — outside
// any component — is safe and gives the guard below a live view of the same module-level
// state every screen shares, hydrated before mount by `main.js`'s `initializePlaylists()`.
const { playlists } = usePlaylists()
// Same reasoning, for the hardware-back guard `wireHardwareBack` needs below: `useTrackMenu()`
// has no lifecycle hooks either, so this is a live view of the SAME shared sheet state every
// `TrackRow`'s options button opens, not a private copy.
const { isOpen: trackMenuOpen, closeTrackMenu } = useTrackMenu()

/**
 * Hash history, not web history: Capacitor serves the bundle from a local origin
 * (`https://localhost` via the Android WebView asset loader) where a deep path
 * reload has no server to resolve it and 404s. A hash route is always resolved
 * by the bundle itself, so a WebView recreation (T8) can never land on a 404.
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/library' },
    { path: '/library', name: 'library', component: LibraryView },
    { path: '/now-playing', name: 'nowPlaying', component: NowPlayingView },
    { path: '/playlist/:id', name: 'playlist', component: PlaylistView },
    { path: '/settings', name: 'settings', component: SettingsView },
    // Guarded below: unlocked users only. Locked users are redirected to /library.
    { path: '/downloader', name: 'downloader', component: DownloaderView },
    // Anything unknown (a stale hash after an update) lands on the default tab
    // rather than a blank screen.
    { path: '/:pathMatch(.*)*', redirect: '/library' },
  ],
})

/**
 * Android hardware/gesture back. Attached exactly once for the app's lifetime —
 * same attach-once discipline as `usePlayer`'s listeners.
 *
 * `/library` is the root tab: back there backgrounds the app (`exitApp`) instead
 * of leaving a blank WebView on screen. Everywhere else it walks the router's own
 * history, falling back to `/library` when there is nothing to walk (a WebView
 * rebuilt straight onto `#/now-playing` has an empty history stack).
 *
 * The track-options sheet (T8) has to be dismissed from THIS listener rather than one of its
 * own: Capacitor's `backButton` event runs every registered listener in turn (verified in
 * `@capacitor/core`'s `notifyListeners`, `listeners.forEach(l => l(data))`) — there is no way
 * for a second listener to stop this one from also firing. So a listener added from inside the
 * sheet would close the sheet AND let the checks below act in the same press (on `/library`
 * that means backgrounding the whole app underneath the sheet it just closed). Checking
 * `trackMenuOpen` first and returning means a back-press while the sheet is open does exactly
 * one thing.
 */
export function wireHardwareBack() {
  CapacitorApp.addListener('backButton', () => {
    if (trackMenuOpen.value) {
      closeTrackMenu()
      return
    }
    if (router.currentRoute.value.path === '/library') {
      CapacitorApp.exitApp()
      return
    }
    if (window.history.state?.back) {
      router.back()
    } else {
      router.replace('/library')
    }
  })
}

router.beforeEach((to) => {
  if (to.path === '/downloader' && !unlocked.value) {
    return '/library'
  }
  // Same principle as the catch-all route below, one level up: a playlist id that is not
  // 'favorites', 'recently-added' or a KNOWN user playlist (a stale link, or one deleted
  // from another screen) must never mount PlaylistView on nothing. Checked here, before the
  // component exists, rather than inside PlaylistView itself.
  if (to.name === 'playlist') {
    const id = to.params.id
    if (id !== 'favorites' && id !== 'recently-added') {
      const numericId = Number(id)
      const exists =
        Number.isInteger(numericId) &&
        playlists.value.some((playlist) => playlist.id === numericId)
      if (!exists) return '/library'
    }
  }
})

export default router
