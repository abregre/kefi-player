<script setup>
// The shell: a scrolling route area, then the mini-player (T17), then the tab bar.
// Both of the latter live OUTSIDE <RouterView> so navigating never remounts them —
// that is what keeps playback controls (and their local position interpolation)
// alive across a tab change.
import { onMounted, ref, watch } from 'vue'
import { useI18n } from './i18n'
import MiniPlayer from './components/MiniPlayer.vue'
import TabBar from './components/TabBar.vue'
import { usePlayer } from './composables/usePlayer'

const player = usePlayer()
const { t } = useI18n()

/*
 * T11 skips an unplayable file and moves on; Must Have 13 wants that REPORTED rather
 * than silent. It lives in the shell, not in a view, because the skip can happen while
 * any screen is open (or none of them — the queue advances in the background too), and
 * it self-dismisses because there is nothing for the user to do about it.
 */
const skipNotice = ref(false)
/** @type {ReturnType<typeof setTimeout> | null} */
let skipTimer = null

onMounted(() => {
  // Idempotent: wires the playerState push listener, the pull-on-resume handler,
  // and takes the first snapshot so the first render is never a default value.
  player.initialize()
})

/*
 * QA observability, not UI. The scripted scenarios (T8 rehydration, T12 process
 * death, and the T20 suite that re-runs both) read WebView-side player state out
 * of `adb logcat`, which Capacitor feeds from the WebView console under tag
 * `Capacitor/Console`. The T13 rewrite deleted the old dev panel that used to
 * carry these lines; the logging has to survive it or those assertions lose their
 * only window into the WebView. Two console lines are the whole cost.
 */
watch(
  () => [
    player.mediaId.value,
    player.title.value,
    player.currentPosition.value,
    player.isPlaying.value,
    player.shuffleModeEnabled.value,
    player.repeatMode.value,
    player.currentIndex.value,
  ],
  () => {
    console.log(
      '[QA] playerState',
      JSON.stringify({
        mediaId: player.mediaId.value,
        uri: player.uri.value,
        title: player.title.value,
        artist: player.artist.value,
        currentPosition: player.currentPosition.value,
        duration: player.duration.value,
        isPlaying: player.isPlaying.value,
        shuffleModeEnabled: player.shuffleModeEnabled.value,
        repeatMode: player.repeatMode.value,
        queueLength: player.queueLength.value,
        currentIndex: player.currentIndex.value,
      }),
    )
  },
)

watch(player.lastError, (value) => {
  if (!value) return
  console.log('[QA] trackUnavailable', JSON.stringify(value))
  skipNotice.value = true
  if (skipTimer != null) clearTimeout(skipTimer)
  skipTimer = setTimeout(() => {
    skipNotice.value = false
  }, 6000)
})
</script>

<template>
  <div class="shell">
    <main class="content">
      <RouterView />
    </main>
    <p v-if="skipNotice" class="skip-notice" role="status">{{ t('error.trackUnavailable') }}</p>
    <MiniPlayer />
    <TabBar />
  </div>
</template>

<style scoped>
.shell {
  /* Route area scrolls; mini-player and tab bar are pinned by the grid, so no
     fixed positioning and no scroll-jump when the keyboard opens. */
  display: grid;
  grid-template-rows: 1fr auto auto auto;
  /*
   * `minmax(0, 1fr)`, never the implicit `auto` column. A grid item's automatic minimum
   * size is its MIN-CONTENT size, so one long unbroken run of text — a 70-character song
   * title in the list or in the mini-player — sizes the single column to its own
   * min-content and the whole app is laid out wider than the screen. With `overflow:
   * hidden` below there is no scrollbar to reveal it either: the right-hand side of every
   * row, and every control living there, is simply clipped away and untappable. Measured on
   * a 360px-wide viewport: the route area came out 835px wide. Reported from a real device
   * on 2026-08-20 ("κάνει overflow x και σπάει το UI"); `minmax(0, …)` is what lets the
   * column go below min-content.
   */
  grid-template-columns: minmax(0, 1fr);
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  /* Landscape cutouts and curved edges inset the sides, never the content's own padding.
     Top and bottom stay with the surfaces that own them: pages pad their own --safe-top so
     they can still scroll under the status bar, and the tab bar pads its own --safe-bottom
     so its background reaches the bottom of the screen. */
  padding-left: var(--safe-left);
  padding-right: var(--safe-right);
}

.content {
  overflow-y: auto;
  /* Belt to the grid's braces: whatever a future screen does, it can never turn the app
     into a horizontally scrolling surface. Vertical scrolling is unaffected. */
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
}

/* A row in the grid rather than a floating toast: it pushes the mini-player down for
   the few seconds it is up, which is impossible to miss and impossible to overlap. */
.skip-notice {
  margin: 0;
  padding: 0.55rem 0.9rem;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 0.8rem;
  line-height: 1.35;
  border-top: 1px solid var(--border);
}
</style>
