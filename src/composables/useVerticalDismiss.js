import { onBeforeUnmount, onMounted, ref } from 'vue'

/** Downward travel (CSS px) past which a release dismisses instead of springing back. */
const MIN_DISTANCE = 120
/** How much more vertical than horizontal the travel must be, so a diagonal drag reads
 *  as a scroll/swipe attempt rather than a dismiss. */
const DIRECTION_RATIO = 1.6
/** A release faster than this, past a lower distance floor, dismisses like a flick even
 *  though it never reached MIN_DISTANCE — mirrors useHorizontalSwipe's flick shortcut,
 *  because a quick downward flick reads as "get me out of here" just as much as a slow
 *  drag past the threshold does. */
const MAX_FLICK_DURATION_MS = 300
/** The flick shortcut's own, shorter distance floor. */
const MIN_FLICK_DISTANCE = 48
/** Follow-the-finger is damped rather than 1:1: a raw follow makes the sheet feel like it
 *  is being torn off, while a little resistance reads as "the sheet has weight" and also
 *  means the finger can travel well past MIN_DISTANCE without the sheet leaving the
 *  viewport before the user lets go. */
const DRAG_RESISTANCE = 0.55

/**
 * Wires a downward drag-to-dismiss gesture onto `target`. While a single finger drags
 * down, `offset` (in CSS px) tracks the drag so the caller can transform the element by
 * `translateY(offset)`; on release, a drag past the threshold (or a fast-enough flick)
 * calls `onDismiss()`, otherwise `offset` resets to 0 so the caller's transition springs
 * the element back into place.
 *
 * Mutable drag bookkeeping lives in plain closure variables, exactly like
 * `useHorizontalSwipe` — `offset` is the one exception, kept as a ref because the view
 * needs to render it on every touchmove to follow the finger.
 *
 * @param {import('vue').Ref<HTMLElement | null>} target
 * @param {{ onDismiss: () => void, ignore?: string }} options
 *   `ignore` is a CSS selector for regions that handle their own touch drag (the seek
 *   input, a button) — a gesture starting inside one is left alone.
 * @returns {{ offset: import('vue').Ref<number>, dragging: import('vue').Ref<boolean> }}
 *   `offset` is the live downward translation in px; `dragging` is true only while a
 *   finger is down, so the caller can suppress its settle transition during the drag.
 */
export function useVerticalDismiss(target, { onDismiss, ignore }) {
  const offset = ref(0)
  const dragging = ref(false)

  let startX = 0
  let startY = 0
  let startedAt = 0
  let tracking = false
  // Set once a touch has moved enough to commit to being a vertical drag (as opposed to
  // a tap or a horizontal/scroll gesture) — this is what gates preventDefault below, so
  // an undecided touch never has its default scrolling stolen.
  let verticalDrag = false

  function onStart(event) {
    // Two fingers means a pinch or a scroll gesture, never a dismiss.
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
    verticalDrag = false
  }

  function onMove(event) {
    if (!tracking) return
    const touch = event.touches[0]
    if (!touch) return
    const dx = touch.clientX - startX
    const dy = touch.clientY - startY

    if (!verticalDrag) {
      // Still undecided: only commit once the drag clearly reads as vertical, so a
      // horizontal or ambiguous touch is left for the page (or another gesture) to
      // handle untouched.
      if (Math.abs(dy) < 8 || Math.abs(dy) < Math.abs(dx) * DIRECTION_RATIO) return
      if (dy < 0) return // upward — not this gesture's concern
      verticalDrag = true
      dragging.value = true
    }

    /*
     * This is a non-passive listener with a conditional preventDefault, which is the
     * exact thing useHorizontalSwipe.js documents deliberately never doing. That
     * composable shares its surface with a vertically scrolling list, so stealing the
     * touch would break scrolling to save an animation nobody asked for. This screen has
     * no scrolling content behind it — it is a full-height sheet, and the drag IS the
     * interaction (the gesture that dismisses the screen), not a shortcut layered on top
     * of one. Once `verticalDrag` is true we are committed, so it is safe — and necessary
     * — to stop the browser also trying to scroll the page under the finger.
     */
    event.preventDefault()

    if (dy <= 0) {
      // The finger came back up above the start point: don't accumulate a negative
      // offset, just hold at rest.
      offset.value = 0
      return
    }
    offset.value = dy * DRAG_RESISTANCE
  }

  function reset() {
    tracking = false
    verticalDrag = false
    dragging.value = false
    offset.value = 0
  }

  function onEnd(event) {
    if (!tracking) return
    const wasVerticalDrag = verticalDrag
    tracking = false
    verticalDrag = false
    dragging.value = false

    if (!wasVerticalDrag) return
    const touch = event.changedTouches[0]
    const dy = touch ? touch.clientY - startY : 0
    const elapsed = event.timeStamp - startedAt

    const pastThreshold = dy >= MIN_DISTANCE
    const isFlick = dy >= MIN_FLICK_DISTANCE && elapsed <= MAX_FLICK_DURATION_MS
    if (pastThreshold || isFlick) {
      onDismiss()
      return
    }
    offset.value = 0
  }

  onMounted(() => {
    const element = target.value
    if (!element) return
    element.addEventListener('touchstart', onStart, { passive: true })
    element.addEventListener('touchmove', onMove, { passive: false })
    element.addEventListener('touchend', onEnd, { passive: true })
    element.addEventListener('touchcancel', reset, { passive: true })
  })

  onBeforeUnmount(() => {
    const element = target.value
    if (!element) return
    element.removeEventListener('touchstart', onStart)
    element.removeEventListener('touchmove', onMove)
    element.removeEventListener('touchend', onEnd)
    element.removeEventListener('touchcancel', reset)
  })

  return { offset, dragging }
}
