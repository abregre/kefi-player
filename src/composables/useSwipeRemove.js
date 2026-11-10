import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Horizontal travel (CSS px) before a touch reads as "this finger is swiping a row away"
 * rather than scrolling the list. Deliberately just above `useDragReorder`'s
 * `MOVE_CANCEL_PX` (10): that composable shares this exact surface and abandons its pending
 * row pickup at 10px, so by the time a swipe commits here the reorder gesture has already
 * stood down on its own and the two can never both own the same touch.
 */
const COMMIT_PX = 12

/** How much more horizontal than vertical the travel must be to count as a swipe at all. */
const DIRECTION_RATIO = 1.4

/**
 * Fraction of the row's own width the finger must cross before release actually removes it.
 * A fraction rather than a fixed px so the gesture asks for the same *proportion* of the
 * screen on a 360px phone and a tablet. 0.38 sits far enough out that a careless nudge
 * springs back, while still being reachable with one thumb.
 */
const REMOVE_FRACTION = 0.38

/**
 * Swipe-a-row-away, for lists whose rows can be discarded individually.
 *
 * Shares its container with `useDragReorder` (long-press → carry) and resolves the overlap
 * two ways: it only commits past `COMMIT_PX`, by which point the reorder gesture has already
 * cancelled itself, and it refuses to start at all while `isBlocked()` reports a row is
 * currently carried. Rows opt in through the same `[data-row-index]` attribute the reorder
 * gesture uses, so a list that marks only its editable rows gets both gestures scoped to
 * exactly those rows with no second flag.
 *
 * Like `useDragReorder` and unlike `useHorizontalSwipe`, the `touchmove` listener is
 * non-passive — once the swipe has committed the browser must stop scrolling the list
 * underneath it. Nothing calls `preventDefault()` before that commit, so an ordinary scroll
 * or tap passes through untouched.
 *
 * @param {import('vue').Ref<HTMLElement | null>} container The list root.
 * @param {{
 *   onRemove: (index: number) => void,
 *   isBlocked?: () => boolean,
 *   ignore?: string,
 * }} options
 *   `onRemove` fires once, on release, only when the row travelled past the threshold; the
 *   caller owns the actual removal and any refresh. `isBlocked` is consulted at touchstart
 *   (the reorder gesture's "a row is currently picked up"). `ignore` is a CSS selector for
 *   controls that must never start a swipe.
 * @returns {{
 *   swipeIndex: import('vue').Ref<number | null>,
 *   swipeOffset: import('vue').Ref<number>,
 *   swipeProgress: (index: number) => number,
 * }}
 *   `swipeIndex` is the row being swiped (or null), `swipeOffset` its live translateX in px,
 *   and `swipeProgress(index)` a 0→1 ratio of how close that row is to being removed — meant
 *   for fading the row or revealing a delete affordance behind it.
 */
export function useSwipeRemove(container, { onRemove, isBlocked, ignore }) {
  const swipeIndex = ref(null)
  const swipeOffset = ref(0)
  const rowWidth = ref(0)

  let startX = 0
  let startY = 0
  let startIndex = null
  /** 'idle' | 'pending' (touch down, direction not yet decided) | 'swiping' (committed). */
  let phase = 'idle'

  function reset() {
    phase = 'idle'
    startIndex = null
    swipeIndex.value = null
    swipeOffset.value = 0
    rowWidth.value = 0
  }

  function onStart(event) {
    if (event.touches.length !== 1) {
      reset()
      return
    }
    if (isBlocked?.()) return
    if (ignore && event.target.closest?.(ignore)) return
    const rowEl = event.target.closest?.('[data-row-index]')
    if (!rowEl) return
    const touch = event.touches[0]
    startX = touch.clientX
    startY = touch.clientY
    startIndex = Number(rowEl.dataset.rowIndex)
    rowWidth.value = rowEl.getBoundingClientRect().width
    phase = 'pending'
  }

  function onMove(event) {
    if (phase === 'idle') return
    const touch = event.touches[0]
    if (!touch) return
    const dx = touch.clientX - startX
    const dy = touch.clientY - startY

    if (phase === 'pending') {
      // A mostly-vertical move is the list scrolling; bail out before ever calling
      // preventDefault so the scroll is as untouched as if this weren't attached.
      if (Math.abs(dy) > COMMIT_PX && Math.abs(dy) >= Math.abs(dx)) {
        reset()
        return
      }
      if (Math.abs(dx) < COMMIT_PX) return
      if (Math.abs(dx) < Math.abs(dy) * DIRECTION_RATIO) return
      // A carried row may have been picked up between touchstart and now; that gesture wins.
      if (isBlocked?.()) {
        reset()
        return
      }
      phase = 'swiping'
      swipeIndex.value = startIndex
    }

    event.preventDefault()
    swipeOffset.value = dx
  }

  function onEnd() {
    if (phase === 'swiping' && swipeIndex.value !== null) {
      const index = swipeIndex.value
      const travelled = Math.abs(swipeOffset.value)
      const threshold = rowWidth.value * REMOVE_FRACTION
      // The row is left translated so the removal reads as the row leaving rather than
      // blinking out; the caller's list re-render is what actually takes it away.
      if (rowWidth.value > 0 && travelled >= threshold) {
        onRemove(index)
        reset()
        return
      }
    }
    reset()
  }

  function swipeProgress(index) {
    if (swipeIndex.value !== index || rowWidth.value === 0) return 0
    return Math.min(1, Math.abs(swipeOffset.value) / (rowWidth.value * REMOVE_FRACTION))
  }

  onMounted(() => {
    const element = container.value
    if (!element) return
    element.addEventListener('touchstart', onStart, { passive: true })
    element.addEventListener('touchmove', onMove, { passive: false })
    element.addEventListener('touchend', onEnd, { passive: true })
    element.addEventListener('touchcancel', reset, { passive: true })
  })

  onBeforeUnmount(() => {
    const element = container.value
    if (!element) return
    element.removeEventListener('touchstart', onStart)
    element.removeEventListener('touchmove', onMove)
    element.removeEventListener('touchend', onEnd)
    element.removeEventListener('touchcancel', reset)
  })

  return { swipeIndex, swipeOffset, swipeProgress }
}
