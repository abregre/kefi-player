import { createRouter, createWebHashHistory } from 'vue-router'
import { App as CapacitorApp } from '@capacitor/app'
import DownloaderView from '../views/DownloaderView.vue'
import LibraryView from '../views/LibraryView.vue'
import NowPlayingView from '../views/NowPlayingView.vue'
import SettingsView from '../views/SettingsView.vue'

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
    { path: '/settings', name: 'settings', component: SettingsView },
    // Reachable by typing the hash only — intentionally no navigation guard here yet.
    // The tab and the unlock check that gates it both land together in a later task;
    // until then this route exists purely so the screen can be QA'd on its own.
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
 */
export function wireHardwareBack() {
  CapacitorApp.addListener('backButton', () => {
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

export default router
