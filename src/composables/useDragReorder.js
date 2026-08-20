import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * How long (ms) a touch must sit still on a row before it "arms" — i.e. before the next
 * finger movement is allowed to pick the row up. Chosen just under Android's own
 * long-press timeout (`ViewConfiguration.getLongPressTimeout()` defaults to 500ms), so this
 * gesture always wins the race against any OS-level long-press handling (e.g. a text
 * selection callout) rather than fighting it for the same touch. Comfortably longer than a
 * normal tap (a few hundred ms at most), so a plain tap-to-play release never reaches it.
 */
const LONG_PRESS_MS = 400

/**
 * Movement (CSS px) past which a touch reads as "this finger is scrolling/swiping", not
 * "this finger is holding still" — exceeding it before the hold timer fires cancels the
 * pending pickup outright. Matches the small slop `useVerticalDismiss.js` allows before its
 * own direction test (`Math.abs(dy) < 8`); this is the equivalent guard for a still-hold
 * instead of a direction, so ordinary finger jitter during a deliberate hold doesn't abort it.
 */
const MOVE_CANCEL_PX = 10

/**
 * Wires a long-press-then-drag row-reorder gesture onto `container`. Unlike
 * `useHorizontalSwipe` (passive, shares a scrolling surface, never steals the touch) and
 * `useVerticalDismiss` (non-passive, but its screen never scrolls so stealing costs
 * nothing), this gesture lives INSIDE a scrolling list and still needs to steal the touch
 * once a drag is really happening — the hardest of the three surfaces, because "let it
 * scroll" and "pick this row up" are both valid readings of the same finger-down-then-move
 * until the hold has lasted long enough to disambiguate them.
 *
 * The latch that makes this safe: a touch must sit within `MOVE_CANCEL_PX` for
 * `LONG_PRESS_MS` before it can become a drag at all ("armed"), and even then the actual
 * pickup — and the first `preventDefault()` — only happens on the FIRST move after arming,
 * never before. So a normal scroll (moves immediately) or a normal tap (releases well
 * before the timer) never reaches the gated `preventDefault()` below; only a touch that
 * both waited AND then moved does, which is the one case that unambiguously means "the user
 * is trying to drag this row", exactly the moment `useVerticalDismiss`'s own gated
 * `preventDefault()` argues a non-passive listener stops being a scrolling hazard and starts
 * being required (the browser must not also try to scroll the page under the carried row).
 *
 * @param {import('vue').Ref<HTMLElement | null>} container
 *   The list root. Rows are found by delegation, via the nearest `[data-row-index]`
 *   ancestor of the touched element — the caller decides which lists' rows carry that
 *   attribute (e.g. leaving it off for a non-reorderable list disables this gesture there
 *   for free, with no extra flag to thread through).
 * @param {{ rowCount: () => number, onDrop: (fromIndex: number, toIndex: number) => void, ignore?: string }} options
 *   `rowCount` reads the current row count live (not captured once) so it stays correct if
 *   the list's length changes while unrelated to any drag in progress. `onDrop` fires once,
 *   only when a completed drag actually changed the target slot; the caller owns turning
 *   that index pair into a real reorder + persistence. `ignore` is a CSS selector for
 *   controls that must never start a drag no matter how long they're held (the row's own
 *   options button).
 * @returns {{
 *   dragIndex: import('vue').Ref<number | null>,
 *   dragOffset: import('vue').Ref<number>,
 *   shiftFor: (index: number) => number,
 * }}
 *   `dragIndex` is the row currently picked up (or null). `dragOffset` is its live
 *   translateY in px, meant to be applied 1:1 (no damping) — this gesture is "carry this
 *   exact row to a slot", not a springy dismiss, so the row should track the finger exactly
 *   rather than lag behind it. `shiftFor(index)` is the px translateY every OTHER row
 *   should apply to visually open/close the gap the carried row would leave — 0 when no
 *   drag is in progress or the row isn't between the drag's start and current target.
 */
export function useDragReorder(container, { rowCount, onDrop, ignore }) {
  const dragIndex = ref(null)
  const dragOffset = ref(0)
  // Measured once per gesture, from the actual row that was pressed — this assumes every
  // row in the list is the same height (true here: they're all one TrackRow), so a single
  // measurement is enough to convert `dragOffset` into "how many rows has it crossed".
  const rowHeight = ref(0)

  let startX = 0
  let startY = 0
  let startIndex = null
  /** 'idle' | 'pending' (holding, timer running) | 'armed' (held long enough, waiting for
   *  the first move) | 'dragging' (committed, following the finger). */
  let phase = 'idle'
  let timer = null

  const targetIndex = computed(() => {
    if (dragIndex.value === null || rowHeight.value === 0) return dragIndex.value
    const shift = Math.round(dragOffset.value / rowHeight.value)
    const max = rowCount() - 1
    return Math.min(max, Math.max(0, dragIndex.value + shift))
  })

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function reset() {
    clearTimer()
    phase = 'idle'
    startIndex = null
    dragIndex.value = null
    dragOffset.value = 0
    rowHeight.value = 0
  }

  function onStart(event) {
    // Two fingers means a pinch/scroll, never a row pickup.
    if (event.touches.length !== 1) {
      reset()
      return
    }
    if (ignore && event.target.closest?.(ignore)) return
    const rowEl = event.target.closest?.('[data-row-index]')
    if (!rowEl) return
    const touch = event.touches[0]
    startX = touch.clientX
    startY = touch.clientY
    startIndex = Number(rowEl.dataset.rowIndex)
    phase = 'pending'
    rowHeight.value = rowEl.getBoundingClientRect().height
    clearTimer()
    timer = setTimeout(() => {
      // Only advances the state machine; does not touch dragIndex/dragOffset yet, so
      // nothing renders differently and no listener has intercepted anything at this
      // point — arming is invisible until the finger actually moves.
      if (phase === 'pending') phase = 'armed'
    }, LONG_PRESS_MS)
  }

  function onMove(event) {
    if (phase === 'idle') return
    const touch = event.touches[0]
    if (!touch) return
    const dx = touch.clientX - startX
    const dy = touch.clientY - startY

    if (phase === 'pending') {
      // Still waiting out the hold. Any real movement before the timer fires means this
      // touch is a scroll or a swipe, not someone holding a row still to pick it up — bail
      // out completely. Nothing above this line has ever called preventDefault, so the
      // touch is left exactly as untouched as if this composable weren't attached at all:
      // the list scrolls, or (on a different screen) useHorizontalSwipe still sees it.
      if (Math.abs(dx) > MOVE_CANCEL_PX || Math.abs(dy) > MOVE_CANCEL_PX) reset()
      return
    }

    if (phase === 'armed') {
      // The hold lasted long enough, and this is the first move since. Commit: from here
      // on this touch IS a drag, so it becomes safe — and necessary, see the file-level
      // comment — to stop the browser from also trying to scroll the page under it.
      phase = 'dragging'
      dragIndex.value = startIndex
    }

    // phase is 'dragging' below this line (just entered above, or already was).
    event.preventDefault()
    dragOffset.value = dy
  }

  function onEnd() {
    if (phase === 'dragging' && dragIndex.value !== null) {
      const from = dragIndex.value
      const to = targetIndex.value
      if (to !== null && to !== from) onDrop(from, to)
    }
    // A release that never made it past 'pending'/'armed' (a tap, or a long hold that was
    // let go without ever moving) never called preventDefault above, so the browser's own
    // click still fires normally on whatever was actually touched — this gesture has
    // nothing further to undo for that case, `reset()` just clears its own bookkeeping.
    reset()
  }

  function shiftFor(index) {
    if (dragIndex.value === null || index === dragIndex.value) return 0
    const from = dragIndex.value
    const to = targetIndex.value
    // The carried row is conceptually "lifted out"; every row strictly between its start
    // and current target slides one row-height to close the gap it left (or open the gap
    // it's about to occupy), which is exactly the visual a reorder needs to be legible
    // while it's still in progress rather than only after release.
    if (to > from && index > from && index <= to) return -rowHeight.value
    if (to < from && index >= to && index < from) return rowHeight.value
    return 0
  }

  onMounted(() => {
    const element = container.value
    if (!element) return
    element.addEventListener('touchstart', onStart, { passive: true })
    // Must be non-passive so the gated preventDefault() above is even allowed to do
    // anything — a passive listener silently ignores a later preventDefault() call, so
    // this can't be registered passively "just in case" and made conditional afterwards.
    element.addEventListener('touchmove', onMove, { passive: false })
    element.addEventListener('touchend', onEnd, { passive: true })
    // touchcancel (e.g. an incoming call, the OS taking the gesture for something else)
    // resets exactly like a plain reset — nothing was ever committed to `onDrop`, so
    // there is no write to undo and the rendered order was never touched, only the
    // in-progress transform offsets, which `reset()` zeroes.
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

  return { dragIndex, dragOffset, shiftFor }
}
