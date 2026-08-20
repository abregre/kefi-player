<script setup>
// The shell: a scrolling route area, then the mini-player (T17), then the tab bar.
// Both of the latter live OUTSIDE <RouterView> so navigating never remounts them —
// that is what keeps playback controls (and their local position interpolation)
// alive across a tab change.
import { onMounted, watch } from 'vue'
import MiniPlayer from './components/MiniPlayer.vue'
import TabBar from './components/TabBar.vue'
import { usePlayer } from './composables/usePlayer'

const player = usePlayer()

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
  if (value) console.log('[QA] trackUnavailable', JSON.stringify(value))
})
</script>

<template>
  <div class="shell">
    <main class="content">
      <RouterView />
    </main>
    <MiniPlayer />
    <TabBar />
  </div>
</template>

<style scoped>
.shell {
  /* Route area scrolls; mini-player and tab bar are pinned by the grid, so no
     fixed positioning and no scroll-jump when the keyboard opens. */
  display: grid;
  grid-template-rows: 1fr auto auto;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.content {
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
</style>
