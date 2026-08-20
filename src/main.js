import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router, { wireHardwareBack } from './router'
import { initLocale } from './i18n'
import { initializeEasterEgg } from './composables/useEasterEgg'

wireHardwareBack()

// Mount only after both the stored locale and the easter-egg flag are read:
// both are sub-millisecond local Preferences reads, and waiting ensures the
// router guard and TabBar already reflect persisted state on the first render.
Promise.all([initLocale(), initializeEasterEgg()]).finally(() => {
  createApp(App).use(router).mount('#app')
})
