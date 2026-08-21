# Plan: Kefi Player — a playing queue view

## Context

"we also miss a playing queue" — there is currently no way to see, reorder, or edit the live
playback queue from JS. This plan adds a queue screen plus the native plumbing it needs, and two
new track-menu actions to feed the queue.

### What exists today and what genuinely doesn't (verified by reading the code)

- **No native method returns the queue's ordered contents.** `getPlayerState`/`setQueue`/`next`/
  `previous`/`setShuffle`/`setRepeatMode` all resolve only `PlayerController.Snapshot`
  (`PlayerController.kt:54-66`) — `queueLength`(count) and `currentIndex`(ordinal), never the
  uris/titles. `usePlayer.js` never keeps its own copy of the list either (`queueLength`/
  `currentIndex` are set only from the snapshot, `usePlayer.js:78-79`). A queue view cannot be
  built on the current contract; a read method is mandatory, not optional.
- **`setQueue` is destructive and must never be reused for edit operations.**
  `PlayerController.setQueue` (`:391-396`) always calls `setMediaItems(items, startIndex,
  C.TIME_UNSET) → prepare() → play()`. Re-sending the whole uri list on every reorder/remove would
  restart the currently-playing track from position 0 and force `play()` even if it was paused.
- **Media3's incremental primitives are one thin layer away, not a restructure.**
  `PlayerController`'s `controller` field is a `MediaController` (`:69`), which implements Media3's
  `Player` interface — `moveMediaItem(from, to)`, `removeMediaItem(index)`, `addMediaItem(item)` /
  `addMediaItem(index, item)`, and `seekTo(index, positionMs)` are already callable on it, simply
  unused today. Wrapping them follows the exact `withController { it.xxx() }` one-liner shape
  every existing method uses (`next()`/`previous()`/`setShuffle()`, `:383-400`).
- **A real, currently-invisible wiring gap: no `Player.Listener` override fires on a bare timeline
  mutation.** All six existing listener branches (`PlayerController.kt:165-236`) call
  `persistState()`+`broadcastState()` on state/track/error changes — none of them are
  `onTimelineChanged`. Media3's `Player.Listener.onTimelineChanged(timeline, reason)` is the
  documented, standard callback for exactly this — it fires for playlist changes including moves,
  removals, and additions (`Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED`). Without it: a
  reorder/remove/insert would resolve correctly for the caller (each new method takes its own
  fresh snapshot after mutating), but no `playerState` push would reach any other open listener
  (the widget, a second window) and `PlaybackStateStore`'s persisted queue would go stale until
  some unrelated event happened to fire. **Fixed once, centrally, by adding the missing
  `onTimelineChanged` override** — not by having every new method remember to call
  `persistState()`+`broadcastState()` itself, which is exactly the kind of thing a future sixth
  method would forget.
- **`useDragReorder.js` is fully reusable, verbatim, with zero playlist-specific coupling.** Its
  public API (`container` ref, `{ rowCount, onDrop, ignore }`) is generic; `PlaylistView.vue`
  already demonstrates the "make one specific row non-draggable" trick this plan needs for the
  current-item row — by simply omitting `data-row-index` on that one row
  (`PlaylistView.vue:349-358`: `:data-row-index="kind === 'user' ? index : null"`), the gesture's
  own `closest('[data-row-index]')` lookup fails and it's a complete no-op there, no flag needed.
- **`TrackSource.mediaItemsFor(context, uris)`** (used today by `setQueue`, `PlayerPlugin.kt:~200`)
  already resolves an arbitrary uri list into `MediaItem`s carrying `mediaMetadata` (title/artist/
  album/etc., since its own projection includes `TITLE`/`DISPLAY_NAME`/`ARTIST`/`ALBUM`/
  `ALBUM_ARTIST`/`TRACK`) — the exact primitive "Play next"/"Add to queue" need for a single uri,
  and the exact primitive `getQueue` needs in reverse (`MediaItem.mediaMetadata.title`/`.artist`
  read back out per queue item).
- **`NowPlayingView.vue`'s header already has a pattern to extend**: its existing three-dot
  `TrackMenu` trigger and its `router.push`/`router.back()` idiom (used by its own `onViewInFolder`
  for the Folders-tab navigation) are the natural fit for a queue entry point and a queue route —
  confirmed against the file, not assumed.
- **The mock keeps a queue array already**: `player.web.js`'s `PlayerWeb` class has `queue = []`
  (`:29-30`, "mirrors setQueue's uris"), already read/written by its `setQueue`/`jumpToIndex`
  mock methods — the new methods extend this same field rather than adding a second one.

### Decisions taken with the user (interview, 2026-08-21)

1. **The currently-playing row cannot be dragged or removed.** Reordering and removal apply only
   to tracks after the current one. Leaving the current track happens only via tapping another
   row, `next()`/`previous()`, or the transport controls — never by removing it out from under
   itself. Enforced in two places (UI: no drag handle, no remove action shown; native: rejected if
   attempted anyway) — the same defense-in-depth posture this project already uses elsewhere.
2. **A new full route (`/queue`), not a bottom sheet.** `TrackMenu.vue`'s sheet is for a short,
   static action list; a live, scrollable, reorderable list is a different shape and gets its own
   screen, opened from a new button in `NowPlayingView.vue`'s header and closed the same way that
   screen already navigates back.
3. **"Play next" and "Add to queue" ARE added to `TrackMenu.vue` in this pass** — the user chose
   this over the smaller, view-only default. Both insert into the LIVE queue only; neither touches
   the Room-backed playlists feature, which is a separate, unrelated concept.
4. **Tapping a queue row jumps to it and starts playback**, matching every other "tap a track"
   gesture already in this app (Library, Playlists) — never a silent reposition-without-playing.

### One flat list — no history pane

"Up next" is everything at index `> currentIndex` in the one ordered list `PlaybackStateStore`
already persists. There is no separate history concept, no shuffle-preview pane, and none should
be added — this app's queue has always been one flat list with a position pointer, and this plan
does not change that shape, only makes it visible and editable.

### An observation, not a blocker

Once this plan and the separately-approved file-details-and-delete plan both land,
`TrackMenu.vue` will carry nine actions (five existing + File details + Delete from device + Play
next + Add to queue). Nothing in this plan reorganises the menu into groups or sub-menus — that
would be solving a problem nobody has asked about yet. Noted here so it isn't a surprise later.

### Fixed constants

- Build env: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`.
- Route: `/queue`, pushed from `NowPlayingView.vue`, closed via `router.back()`.
- No device or emulator this session. Build verification completed with JDK 21:
  `:app:compileDebugKotlin`, `npm run apk` (debug APK), and offline `assembleRelease` all pass.

## Must Have

1. `getQueue()`: a new native method returning the ordered queue as `{ items: [{ uri, title,
   artist }], currentIndex }`, via the same timeline-walking pattern `persistState()` already uses
   (`PlayerController.kt:151-153`) plus `mediaMetadata.title`/`.artist` per item.
2. `moveQueueItem(from, to)`, `removeQueueItem(index)`, `jumpToQueueIndex(index)`,
   `addToQueue(uri)`, `playNext(uri)` — five new native methods wrapping Media3's
   `moveMediaItem`/`removeMediaItem`/`seekTo`+`play`/`addMediaItem`/`addMediaItem(index, item)`
   respectively. None of them call `setMediaItems`.
3. `removeQueueItem` and `moveQueueItem`'s destination reject/no-op if the target is the current
   index, checked BOTH in `PlayerPlugin.kt` (fast reject before launching, using the snapshot it
   already has) AND inside `PlayerController`'s controller-thread-hopped block (the race-free
   source of truth, since playback can advance between the plugin's check and the native call).
4. An `onTimelineChanged` override added to `PlayerController`'s shared `Player.Listener`, calling
   the same `persistState()`+`broadcastState()` every other mutation already triggers — verified
   independently of the five new methods above.
5. All six new methods present in all four layers: `PlayerController.kt` → `PlayerPlugin.kt` →
   `src/plugins/player.js` (JSDoc) + `player.web.js` (mock, extending its existing `queue` field)
   → `usePlayer.js` (action wrappers, same try/catch-into-`stateError` idiom as every existing
   action).
6. `addToQueue`/`playNext`'s JS wrappers fall back to `setQueue({ uris: [uri] })` when the queue is
   currently empty (`queueLength.value === 0`) — there is nothing to insert into, and the smallest
   honest behaviour is to just start playing the track, not a silent no-op tap.
7. `src/views/QueueView.vue`: a new route rendering the queue as `<ul><TrackRow></ul>` (the
   established pattern), the current row visually marked and non-draggable, `useDragReorder.js`
   reused verbatim for reordering the rows after it, tap-to-jump on any row, a per-row "Remove from
   queue" action (via `TrackMenu`, opened in a queue context) hidden on the current row.
8. `NowPlayingView.vue` gets a new header icon-button opening `/queue`, beside its existing
   three-dot menu trigger.
9. `TrackMenu.vue` gets two new actions, "Play next" and "Add to queue", calling the new
   `usePlayer()` wrappers on `activeTrack.value`.
10. Every new string through `t()`, in both `src/i18n/en.js` and `src/i18n/el.js`.

## Must NOT Have

1. **No reorder/remove implemented by re-sending the full uri list through `setQueue`.** `setQueue`
   stays reserved for "load a fresh list and start playing" — never for editing the existing one.
2. **No dragging or removing the currently-playing row**, in the UI or accepted by native.
3. **No new touch-gesture composable.** `useDragReorder.js` is reused unmodified; removal is a
   `TrackMenu` action, not a swipe gesture (no fourth-and-a-half gesture invented for this).
4. **No history pane, no shuffle-preview, no multi-select/bulk queue edits.** One flat list.
5. **No new persistence.** `PlaybackStateStore` already persists queue/index/position; this plan's
   job is to keep that correct through the new mutations (Must Have 4), not build a second store.
6. **No changes to the Room-backed playlists feature.** "Add to queue"/"Play next" touch only the
   live playback queue; `usePlaylists.js`, `LibraryDao`, and the playlists UI are untouched.
7. **No reorganising `TrackMenu.vue`'s existing five (or, once the other plan lands, seven)
   actions** into groups/sub-menus. Two rows added, nothing rearranged.
8. **No `MediaSession.Callback` override** (`onAddQueueItem`/`onRemoveQueueItem`/
   `onSkipToQueueItem`) for OS-level (Android Auto, lock screen) queue exposure. Out of scope —
   this plan is the in-app queue view only.
9. **No TypeScript, no new state library, no new npm/Capacitor dependency.**
10. **No weakening of any existing `TrackMenu.vue`/`PlayerController`/`usePlayer.js` behaviour** or
    of `qa-playback.sh`'s existing checks.

## TODOs

### Phase A — native (the part with real risk: get this compiling and correctly wired first)

- [x] **T1: `onTimelineChanged` — close the persistence/broadcast gap**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerController.kt`.
  - Add `override fun onTimelineChanged(timeline: Timeline, reason: Int)` to the existing shared
    `Player.Listener` (alongside the six branches at `:165-236`), calling the same
    `persistState()`+`broadcastState()` pair the other branches call. Do this FIRST, independent
    of T2-T5, so every subsequent native method benefits from it without needing its own call.
  - QA Scenario: `grep -n "onTimelineChanged" android/app/src/main/java/com/kefi/player/PlayerController.kt`
    shows the override, and reading its body confirms both `persistState()` and `broadcastState()`
    are called (or whatever the file's actual shared-helper names turn out to be — verify by
    reading the six existing branches first, don't assume the names). `./gradlew
    :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.

- [x] **T2: `getQueue()`**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerController.kt`,
    `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`, `src/plugins/player.js`,
    `src/plugins/player.web.js`.
  - `PlayerController`: a small `data class QueueEntry(val uri: String, val title: String, val
    artist: String?)` (or whatever naming this file's existing `Snapshot`/`Track`-style data
    classes suggest — match that convention), and `suspend fun getQueue(): List<QueueEntry> =
    withController { ctrl -> (0 until ctrl.mediaItemCount).map { i -> val item =
    ctrl.getMediaItemAt(i); QueueEntry(item.localConfiguration?.uri?.toString() ?: "",
    item.mediaMetadata.title?.toString() ?: "", item.mediaMetadata.artist?.toString()) } }` —
    mirroring `persistState()`'s existing timeline-walk (`:151-153`) exactly, plus the two extra
    metadata reads.
  - `PlayerPlugin.kt`: `@PluginMethod fun getQueue(call: PluginCall)` resolving `{ items: [{ uri,
    title, artist }], currentIndex }` (fetch the snapshot for `currentIndex` in the same launch).
  - `player.js`: JSDoc contract entry. `player.web.js`: implement over the existing `queue` field,
    building `{ uri, title, artist }` per entry from whatever the mock's fake tracks already carry
    (title/artist are already on the seeded mock tracks from the playlists plan).
  - QA Scenario: `grep -n "getQueue" android/app/src/main/java/com/kefi/player/PlayerController.kt
    android/app/src/main/java/com/kefi/player/PlayerPlugin.kt src/plugins/player.js
    src/plugins/player.web.js` shows all four. `compileDebugKotlin` 0 errors.

- [x] **T3: `moveQueueItem(from, to)` and `removeQueueItem(index)`**
  - Files: same four as T2.
  - `PlayerController`: `suspend fun moveItem(from: Int, to: Int): Unit = withController {
    it.moveMediaItem(from, to) }`; `suspend fun removeItem(index: Int): Unit = withController { ctrl
    -> check(index != ctrl.currentMediaItemIndex) { "Cannot remove the currently playing item" };
    ctrl.removeMediaItem(index) }` — the `check()` is the race-free, source-of-truth guard (Must
    Have 3); it runs AFTER the thread hop, immediately before the mutation.
  - `PlayerPlugin.kt`: two `@PluginMethod`s. Each reads its snapshot FIRST and fast-rejects if the
    target index equals `currentIndex` (Must Have 3's other half — good UX, avoids the round trip
    for the common case), THEN calls the controller method inside `App.scope.launch`, catching the
    `IllegalStateException` from the `check()` above (belt-and-suspenders) via the existing
    `rejectWith` pattern with a message naming the reason.
  - `player.js`/`player.web.js`: JSDoc + mock, mutating the mock's `queue` array directly
    (`Array.prototype.splice`-style move, filter-based remove), rejecting on a current-index target
    the same way the native side does, so the mock and native agree.
  - QA Scenario: `grep -n "moveQueueItem\|removeQueueItem\|currentMediaItemIndex\|check(" android/app/src/main/java/com/kefi/player/PlayerController.kt`
    shows the guard present. `compileDebugKotlin` 0 errors.

- [x] **T4: `jumpToQueueIndex(index)`**
  - Files: same four as T2.
  - `PlayerController`: `suspend fun jumpToIndex(index: Int): Unit = withController { it.seekTo(index,
    0L); it.play() }` — unconditional `play()`, per decision 4 (tapping always starts playback).
  - QA Scenario: `grep -n "jumpToQueueIndex\|seekTo" android/app/src/main/java/com/kefi/player/PlayerController.kt`
    shows it. `compileDebugKotlin` 0 errors.

- [x] **T5: `addToQueue(uri)` and `playNext(uri)`**
  - Files: same four as T2.
  - `PlayerController`: `suspend fun addToQueue(item: MediaItem): Unit = withController {
    it.addMediaItem(item) }`; `suspend fun playNext(item: MediaItem): Unit = withController { ctrl
    -> ctrl.addMediaItem(ctrl.currentMediaItemIndex + 1, item) }`.
  - `PlayerPlugin.kt`: both methods take `{ uri: string }`, resolve it to a single `MediaItem` via
    the EXISTING `TrackSource.mediaItemsFor(context, listOf(uri))` helper `setQueue` already calls
    — do not write a second uri-to-`MediaItem` resolver.
  - QA Scenario: `grep -n "addToQueue\|playNext\|mediaItemsFor" android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`
    shows both methods reusing the existing resolver. `compileDebugKotlin` 0 errors.

### Phase B — JS composable

- [x] **T6: `usePlayer.js` wrappers**
  - Files: `src/composables/usePlayer.js`.
  - Six new exported actions: `getQueue()`, `moveQueueItem(from, to)`, `removeQueueItem(index)`,
    `jumpToQueueIndex(index)`, `addToQueue(track)`, `playNext(track)` — the last two accept a track
    object (matching how every existing action here takes rich inputs where natural), derive
    `track.uri`, and implement Must Have 6's empty-queue fallback: `if (queueLength.value === 0)
    { await setQueue({ uris: [track.uri] }) } else { await Player.addToQueue({ uri: track.uri }) }`
    (and the `playNext` equivalent).
  - Every action follows the existing try/catch-into-`stateError` shape (e.g. `next()`/`setQueue()`
    in this same file) — no new error-handling idiom.
  - QA Scenario: `grep -n "getQueue\|moveQueueItem\|removeQueueItem\|jumpToQueueIndex\|addToQueue\|playNext"
    src/composables/usePlayer.js` shows all six. `npm run build` exits 0.

### Phase C — UI

- [x] **T7: `src/views/QueueView.vue` and its route**
  - Files: NEW `src/views/QueueView.vue`, `src/router/index.js`.
  - Route `{ path: '/queue', name: 'queue', component: QueueView }`, registered the same way
    `/playlist/:id` already is; a header with a back button (`router.back()`) and title.
  - Renders `getQueue()`'s items as `<ul><TrackRow></ul>` (own `useLazyArtwork` root ref, following
    the established pattern — check whether artwork resolution needs `trackByUri`/`useLibrary` for
    cover art, since `getQueue`'s native payload is `{uri,title,artist}` only, no artwork id; a
    queue row may show a plain placeholder unless it resolves the uri against `useLibrary().tracks`
    for the richer object — decide and document which).
  - The row at `currentIndex` is visually marked (e.g. the same "now playing" treatment `TrackRow`
    already supports via its `active`/`playing` props) and OMITS `data-row-index` (Must Have 2/3,
    reusing the exact `PlaylistView.vue:349-358` trick) so it can never be picked up by
    `useDragReorder`.
  - `useDragReorder(container, { rowCount, onDrop, ignore: '.row-menu' })`, wired exactly like
    `PlaylistView.vue:168-172`; `onDrop(from, to)` calls `moveQueueItem(from, to)`, then re-fetches
    `getQueue()` to reflect the native truth (do not hand-splice the local list — same
    optimistic-then-reconcile posture `PlaylistView.vue`'s own `onDragCommit` already uses, or a
    plain refetch if simpler; decide and document).
  - Tapping a row (not the current one) calls `jumpToQueueIndex(index)`.
  - `TrackRow`'s `options` event opens `TrackMenu` in a "queue context" — extend
    `useTrackMenu.js`'s shared state minimally to carry which row/context opened it (so `TrackMenu`
    can show a "Remove from queue" action only in that context, only when the row isn't current)
    calling `removeQueueItem(index)` on tap, then refetching `getQueue()`.
  - The list must re-render on every relevant `playerState` push (queue advancing via `next()`/
    auto-skip must move the highlighted row and reflect any order change), not only on mount —
    re-fetch `getQueue()` whenever `usePlayer()`'s `currentIndex`/`queueLength` change while this
    view is active.
  - QA Scenario: `@vue/compiler-sfc` (`parse`→`compileScript`→`compileTemplate`) compiles clean.
    `grep -n "useDragReorder\|data-row-index" src/views/QueueView.vue` shows the current row's
    index conditionally omitted, matching the `PlaylistView.vue` precedent. Hardcoded-text gate
    empty. `npm run build` exits 0.

- [x] **T8: `NowPlayingView.vue`'s queue button**
  - Files: `src/views/NowPlayingView.vue`.
  - A new header icon-button beside the existing three-dot `TrackMenu` trigger, `@click="router.push('/queue')"`,
    following that file's existing `onViewInFolder` navigation idiom. Icon in the same 24x24
    stroke/fill convention as its sibling buttons.
  - Do NOT disturb the seek bar's `dragging`/`sheetDragging` state or the drag-to-dismiss gesture —
    this file has broken its own build once already from a name collision; grep before naming
    anything new.
  - QA Scenario: `grep -n "router.push('/queue')" src/views/NowPlayingView.vue` present.
    `@vue/compiler-sfc` compiles clean. `npm run build` exits 0.

- [x] **T9: "Play next" / "Add to queue" in `TrackMenu.vue`**
  - Files: `src/components/TrackMenu.vue`, `src/i18n/{en,el}.js`.
  - Two new `.option` rows (matching the existing five's markup shape), calling
    `usePlayer().playNext(activeTrack.value)` / `.addToQueue(activeTrack.value)`, with a `notice`
    on success/failure following the file's existing `busy`/`notice` idiom (same as
    `onSetAsRingtone`).
  - QA Scenario: `grep -n "playNext\|addToQueue" src/components/TrackMenu.vue` shows both wired.
    Hardcoded-text gate empty. `@vue/compiler-sfc` compiles clean.

### Phase D — strings, QA, docs

- [x] **T10: Strings**
  - Files: `src/i18n/en.js`, `src/i18n/el.js`.
  - Every key T2-T9 need (`queue.*` for the screen, `trackMenu.playNext`/`.addToQueue`/
    `.removeFromQueue` for the menu additions), identical set and order in both files. Natural
    Greek, matching the app's established informal register.
  - QA Scenario: extract and diff both files' key lists — identical. Hardcoded-text gate empty
    across `src/views` and `src/components`.

- [x] **T11: QA additions (written; the real drag gesture and live audio continuity cannot be
  driven by CDP — say so honestly, do not fake a check that can't fail)**
  - Files: `scripts/qa-playback.sh`.
  - Checks exercisable through the web mock: the queue button opens `/queue`; the queue list
    renders the mock's seeded queue items; tapping a non-current row calls `jumpToQueueIndex` and
    updates `currentIndex`; calling `Capacitor.Plugins.Player.moveQueueItem`/`removeQueueItem`
    directly (the previous plan's precedent for a gesture CDP can't drive) and asserting the
    resulting order/count; attempting `removeQueueItem` on the current index and asserting a
    rejection; `addToQueue`/`playNext` from the track menu appending/inserting correctly in the
    mock's queue.
  - QA Scenario: `bash -n scripts/qa-playback.sh` exits 0; `git diff scripts/qa-playback.sh | grep '^-' | grep -v '^--- ' | wc -l` prints `0`.

- [x] **T12: Documentation**
  - Files: `README.md`.
  - A short addition: the queue view exists, reorder/remove apply to everything except the
    currently-playing track, "Play next"/"Add to queue" feed the live queue and are unrelated to
    the Room-backed Playlists feature.
  - QA Scenario: `grep -n "queue" README.md` shows the new content in the right section.

## Final Verification Wave

- [x] **F1: Build integrity** — `npm run build` exits 0; `./gradlew :app:compileDebugKotlin 2>&1 |
  grep -c '^e:'` prints `0`; `npm run apk` prints `BUILD SUCCESSFUL`. Compare APK size to the
  pre-this-plan baseline — no new dependency, so no expected change. Verified 2026-08-21 with
  JDK 21: `npm run apk` produced the 61 MiB debug APK; offline `assembleRelease` also succeeded
  and produced a 59 MiB release APK.
- [x] **F2: Contract integrity** — all six new methods (`getQueue`, `moveQueueItem`,
  `removeQueueItem`, `jumpToQueueIndex`, `addToQueue`, `playNext`) present in all four layers.
- [x] **F3: i18n integrity** — `queue.*`/new `trackMenu.*` keys identical in set and order across
  both locales; hardcoded-text gate empty.
- [x] **F4: Scope review of the complete diff**, by an independent reviewer, specifically
  checking: zero new calls to `setQueue` outside its existing "load and play" call sites (no
  reorder/remove implemented by re-sending the full list); `onTimelineChanged` present and
  verified to call the persist/broadcast pair; the current-index guard present in BOTH
  `PlayerPlugin.kt` (fast reject) and `PlayerController.kt` (source-of-truth `check()`); no new
  gesture composable; `useDragReorder.js` unmodified; the current row genuinely cannot be dragged
  (no `data-row-index`) or removed (menu option absent/guarded); `usePlaylists.js`/`LibraryDao`
  untouched.
- [x] **F5: Hands-on QA by the user (DEVICE-ONLY)** — **PASSED on the user's connected device,
  2026-08-21.** The user reported testing all device scenarios successfully, including the
  things no static check can cover: dragging actually reorders live playback without interrupting
  audio; removing an item mid-playback behaves correctly; the widget/lock-screen notification
  still reflects the queue correctly after a reorder (proving the `onTimelineChanged` fix actually
  closes the gap it was written for); tapping a queue row jumps and plays; "Play next"/"Add to
  queue" from the track menu land in the right position; the empty-queue fallback actually starts
  playback rather than silently doing nothing.

## Success Criteria

1. `npm run build` exits `0`.
2. `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.
3. `npm run apk` prints `BUILD SUCCESSFUL`.
4. `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` prints nothing.
5. `grep -n "onTimelineChanged" android/app/src/main/java/com/kefi/player/PlayerController.kt` matches.
6. `grep -rn "setQueue" src/composables/usePlayer.js src/views/QueueView.vue android/app/src/main/java/com/kefi/player/PlayerController.kt | grep -v "^.*://"` shows `setQueue` used only where the plan's own tasks name it (the empty-queue fallback in T6, and its pre-existing call sites) — not inside `moveQueueItem`/`removeQueueItem`.
7. F4 returns APPROVE.
8. F5 is reported honestly as not run unless the user reports back.
