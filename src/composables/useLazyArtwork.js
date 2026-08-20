import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { useLibrary } from './useLibrary'

/**
 * Fetches artwork for whatever is (nearly) on screen, and nothing else.
 *
 * Artwork crosses the Capacitor bridge as a base64 JPEG, so a library of a few hundred
 * albums would be megabytes of images for rows nobody scrolled to. Every element that
 * wants a cover carries `data-track-id`; this observes them inside `root`, calls
 * `loadArtwork` on first intersection and unobserves immediately after —
 * `useLibrary.loadArtwork` is itself idempotent and de-duped, so a re-render mid-scroll
 * cannot double-fetch either way.
 *
 * `rootMargin` starts the fetch a screenful early, which is usually enough for the image
 * to be there by the time the row actually appears.
 *
 * @param {import('vue').Ref<HTMLElement | null>} root Container to search within.
 * @param {() => unknown} source Reactive source whose change means "the list was rebuilt,
 *   observe the new elements" (a search narrowing the list, a different grouping, …).
 */
export function useLazyArtwork(root, source) {
  const { loadArtwork } = useLibrary()
  /** @type {IntersectionObserver | null} */
  let observer = null

  function observeAll() {
    if (!observer || !root.value) return
    observer.disconnect()
    for (const element of root.value.querySelectorAll('[data-track-id]')) {
      observer.observe(element)
    }
  }

  onMounted(() => {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          const trackId = Number(entry.target.dataset.trackId)
          if (Number.isFinite(trackId)) loadArtwork(trackId)
          observer?.unobserve(entry.target)
        }
      },
      { rootMargin: '200px' },
    )
    observeAll()
  })

  watch(source, () => nextTick(observeAll))

  onBeforeUnmount(() => observer?.disconnect())
}
