import { onBeforeUnmount, onMounted } from 'vue'

/** Minimum horizontal travel before a drag counts as a swipe (CSS px). */
const MIN_DISTANCE = 56
/** How much more horizontal than vertical the travel must be. */
const DIRECTION_RATIO = 1.6
/** Anything slower than this is a considered drag, not a flick. */
const MAX_DURATION_MS = 700

/**
 * Calls `onSwipe('left' | 'right')` for a horizontal flick over `target`.
 *
 * Deliberately never calls `preventDefault`, and listens passively: the gesture has to
 * share the same surface as a vertically scrolling list, and stealing touch events would
 * make that list feel broken to save an animation nobody asked for. The direction test
 * runs on `touchend`, by which point the browser has already done whatever scrolling it
 * wanted — the ratio guard is what keeps a diagonal scroll from also flipping the tab.
 *
 * @param {import('vue').Ref<HTMLElement | null>} target
 * @param {{ onSwipe: (direction: 'left' | 'right') => void, ignore?: string }} options
 *   `ignore` is a CSS selector for regions that handle their own horizontal drag (a
 *   scrollable tab strip, a text field) — a gesture starting inside one is left alone.
 */
export function useHorizontalSwipe(target, { onSwipe, ignore }) {
  let startX = 0
  let startY = 0
  let startedAt = 0
  let tracking = false

  function onStart(event) {
    // Two fingers means a pinch or a scroll gesture, never a tab swipe.
    if (event.touches.length !== 1) {
      tracking = false
      return
    }
    if (ignore && event.target.closest?.(ignore)) {
      tracking = false
      return
    }
    const touch = event.touches[0]
    startX = touch.clientX
    startY = touch.clientY
    startedAt = event.timeStamp
    tracking = true
  }

  function onEnd(event) {
    if (!tracking) return
    tracking = false
    const touch = event.changedTouches[0]
    if (!touch) return
    const dx = touch.clientX - startX
    const dy = touch.clientY - startY
    if (event.timeStamp - startedAt > MAX_DURATION_MS) return
    if (Math.abs(dx) < MIN_DISTANCE) return
    if (Math.abs(dx) < Math.abs(dy) * DIRECTION_RATIO) return
    onSwipe(dx < 0 ? 'left' : 'right')
  }

  function onCancel() {
    tracking = false
  }

  onMounted(() => {
    const element = target.value
    if (!element) return
    element.addEventListener('touchstart', onStart, { passive: true })
    element.addEventListener('touchend', onEnd, { passive: true })
    element.addEventListener('touchcancel', onCancel, { passive: true })
  })

  onBeforeUnmount(() => {
    const element = target.value
    if (!element) return
    element.removeEventListener('touchstart', onStart)
    element.removeEventListener('touchend', onEnd)
    element.removeEventListener('touchcancel', onCancel)
  })
}
