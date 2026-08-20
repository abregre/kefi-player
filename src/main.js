import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router, { wireHardwareBack } from './router'
import { initLocale } from './i18n'

wireHardwareBack()

// Mount only after the stored locale is read: a Preferences hit is a local,
// sub-millisecond read, and waiting for it means the user's chosen language never
// flashes past in the other one on cold start.
initLocale().finally(() => {
  createApp(App).use(router).mount('#app')
})
