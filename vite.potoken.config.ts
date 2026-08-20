import { defineConfig } from 'vite'

/**
 * Builds the PO token minter as one self-contained IIFE, separate from the main
 * app bundle.
 *
 * It has to be a single file with no imports because PoTokenGenerator.kt inlines
 * it into an HTML page served under a https://www.youtube.com base URL. Loading
 * it as a separate <script src> would be a cross-origin request from that origin
 * and get blocked.
 *
 *   npm run build:potoken
 */
export default defineConfig({
  // Without this, Vite copies public/ (favicon, icons) into the assets folder too.
  publicDir: false,
  build: {
    outDir: 'android/app/src/main/assets/potoken',
    emptyOutDir: true,
    // Android's WebView on older devices lags desktop Chrome.
    target: 'es2017',
    lib: {
      entry: 'src/potoken/index.ts',
      name: 'YtMp3PoToken',
      formats: ['iife'],
      fileName: () => 'potoken.js',
    },
  },
})
