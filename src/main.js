import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router, { wireHardwareBack } from './router'

wireHardwareBack()

createApp(App).use(router).mount('#app')
