# RESUME — playing queue

Plan: `.claude/plans/kefi-player-playing-queue.md`.

## Progress

- **T1 complete (2026-08-21):** Added `Player.Listener.onTimelineChanged` in
  `PlayerController.kt`. Queue timeline mutations now call the existing
  `persistState()` and `broadcastState()` pair, keeping saved playback state,
  the home-screen widget, and the WebView in sync.
- **Verification:** `./gradlew :app:compileDebugKotlin` completed successfully
  with JDK 21 on 2026-08-21.
- **Native foundation in progress (2026-08-21):** Added the `QueueEntry` timeline
  reader and the six incremental Media3 primitives to `PlayerController.kt`:
  read, move, remove, jump, append, and insert-next. The current item is guarded
  against move/remove inside the controller thread. These methods are not yet
  exposed through `PlayerPlugin`/JS, so T2–T5 remain unchecked.
- **Verification:** `./gradlew :app:compileDebugKotlin` completed successfully
  after those controller changes.

## Next

Implement T2–T5: the native queue contract and Media3 incremental mutations.
`PlayerPlugin.kt` already has uncommitted file-details/delete work; preserve it
while adding the queue methods rather than reverting, staging, or committing it.

## Constraints

- No emulator, `adb`, release build, or device testing.
- Keep the queue changes separate from the pre-existing file-details/delete work.
- Update this note after each completed task and record verification evidence.
