import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router, { wireHardwareBack } from './router'
import { initLocale } from './i18n'
import { initializeEasterEgg } from './composables/useEasterEgg'
import { initializePlaylists } from './composables/usePlaylists'

wireHardwareBack()

// Mount only after the stored locale, the easter-egg flag, and playlists/favourites are
// all read: the first two are sub-millisecond local Preferences reads, and
// `initializePlaylists` is a native-plugin round trip (Room, behind the bridge) — waiting
// on all three ensures the router guard, TabBar, the Playlists tab, and the track options
// menu's Favourite/Unfavourite label already reflect persisted state on the first render.
Promise.all([initLocale(), initializeEasterEgg(), initializePlaylists()]).finally(() => {
  createApp(App).use(router).mount('#app')
})
