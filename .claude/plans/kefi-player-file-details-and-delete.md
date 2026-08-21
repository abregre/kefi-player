# Plan: Kefi Player — file details and delete from device

## Context

The track options menu (`src/components/TrackMenu.vue` + `src/composables/useTrackMenu.js`) shipped
in the playlists-tab plan with five actions: Favourite/Unfavourite, Add to playlist, Set as
ringtone, Set as notification, View in folder. The user asked for three more: **edit tags/metadata**,
**show file details**, **delete from device**.

Metadata editing turned out not to be a feature request at all, in the form asked for — it is an
Android platform limitation. This plan ships the other two.

### The metadata-editing dead end (decided, not deferred as "later work" — read this before re-proposing it)

`ContentResolver.update()` on `MediaStore.Audio.Media` cannot change what any app (including this
one) sees for `TITLE`/`ARTIST`/`ALBUM`/`ALBUM_ARTIST`/`YEAR`/`TRACK`, for ANY file, regardless of
who created it. Confirmed by reading AOSP's `MediaProvider.java` directly (`aosp-mirror/platform_
packages_providers_mediaprovider`, commit `a183e2b9`): a hard allowlist (`sMutableColumns`,
`MediaProvider.java:11823-11849`) governs which columns any app may write on a non-pending row —
`DISPLAY_NAME`, `IS_PENDING`, `RELATIVE_PATH`, a handful of others — and none of the six tag-like
columns are on it. The rejection is silent and triggers a rescan that overwrites the row back to
the real file bytes (`MediaProvider.java:8584-8607`, `:8879-8899`), with the reasoning stated in
Android's own source comment: *"Column values controlled by media scanner aren't writable by
apps, since any edits here don't reflect the metadata on disk, and they'd be overwritten during a
rescan."* An edit would appear to save, then silently revert.

The only way to actually change what other apps see is to rewrite the file's real embedded tag
bytes (ID3v2 / MP4 atoms / Vorbis comments) — this project has no library for that, and hand-
rolling three binary tag formats is a real, separately-sized feature with real corruption risk.
**The user chose to drop it from this plan.** If it returns as its own plan later, start from this
finding, not from `ContentResolver.update()`.

### Decisions taken with the user (interview, 2026-08-21)

1. **Metadata editing is OUT of this plan entirely**, for the platform reason above.
2. **Delete-from-device supports every device this app already supports** (`minSdk 24` up).
   Consequence accepted: three distinct native code paths by `Build.VERSION.SDK_INT`, not one.
3. **File details shows everything discussed**: the fields already free on `PlayerTrack`, format
   info fetched on demand (MIME type, bitrate, sample rate), and the file's real absolute path.

### Findings that shaped this plan (verified by reading the code and Android's own source)

- **The menu is already the one shared sheet everywhere.** `TrackMenu.vue`'s own header comment:
  "every list that needs it (Songs, a Folders drill-down, a playlist's detail screen) mounts this
  same component." There is no separate "Songs tab menu" anywhere in the codebase, and
  `useTrackMenu.js`'s `activeTrack` is module-level singleton state (`useTrackMenu.js:7-14,24`).
  "The songs menu" in the user's request is this same sheet — confirmed by reading the code, no
  further confirmation needed.
- **`TrackSource.kt`'s current projection** (`TrackSource.kt:118-131`): `_ID, DISPLAY_NAME, TITLE,
  ARTIST, ALBUM, ALBUM_ARTIST, TRACK, YEAR, DURATION, SIZE, DATE_ADDED` + a folder column.
  `MIME_TYPE` is already used in the `selection` WHERE-clause (`TrackSource.kt:155-156`) to filter
  out playlist-mimetype files, but is never added to the projection or surfaced — a genuinely free
  additive field once queried. No bitrate/sample-rate column is queried anywhere.
- **The additive-field pattern is established and documented three times over** (`folder`,
  `displayName`, `dateAdded` — each with a doc comment on the `Track` data class field explaining
  why it's safe to add, `TrackSource.kt:43-62`; the same field then threaded to `PlayerPlugin.kt`'s
  `toJSObject()` at `:755-769` and the JSDoc typedef in `src/plugins/player.js:19-38`). `mimeType`
  follows exactly this pattern.
- **`MediaMetadataRetriever` is already imported and used in this file** (`TrackSource.kt:9`, for
  embedded artwork at `:341-361`), so getting real bitrate/sample-rate needs no new dependency —
  confirmed by reading AOSP's own scanner (`ModernMediaScanner.java:1310-1341,1483-1485`): the
  `BITRATE`/`SAMPLERATE` MediaStore columns, where they exist at all, ARE `MediaMetadataRetriever`'s
  own output cached at scan time. Calling it directly when the details sheet opens gets a
  guaranteed-fresh number instead of a possibly-stale cached one, for the same cost the OS already
  pays once per file at index time — cheap enough for one on-demand, user-triggered call, done off
  the main thread.
- **No plugin method in this codebase has ever awaited an activity result.** Grep confirms zero
  hits for `ActivityCallback`/`startActivityForResult` anywhere in `com.kefi.player`. The four
  existing intent-launching methods (`setAsRingtone`, `openAppSettings`, `requestWidgetPin`, plus
  `setShareTargetEnabled` which doesn't launch an intent at all) are all fire-and-forget —
  `context.startActivity(intent); call.resolve(...)` immediately, never waiting to learn what the
  user did. Delete is the first feature in this app that genuinely needs a result back.
- **Capacitor's `Plugin.java` (`node_modules/@capacitor/android/.../Plugin.java:173-183`) provides
  `startActivityForResult(PluginCall, Intent, callbackName)` + `@ActivityCallback`**, built on
  `ActivityResultLauncher<Intent>` registered against the Bridge — this is real, present, and
  needs no `MainActivity` change, but it is `Intent`-shaped. The APIs delete needs
  (`RecoverableSecurityException.userAction.actionIntent`, `MediaStore.createDeleteRequest`'s
  returned `PendingIntent`) are both launched via their `.intentSender`, an `IntentSender`, not a
  plain `Intent` — a different shape Capacitor's documented helper does not cover. **This is the
  single biggest engineering risk in this plan** — see T4, which names a primary approach and an
  explicit fallback, mirroring how the previous plan handled its own biggest build risk (Room via
  KSP vs. kapt).
- **`MainActivity.java`** (confirmed `.java`, not Kotlin) extends `BridgeActivity`, overrides only
  `onCreate`, `onNewIntent`, and a WebView-crash-recovery listener — no `onActivityResult` override
  today.
- **No `MANAGE_EXTERNAL_STORAGE` permission is declared, and this plan does not need it.** The
  three SDK-gated delete branches (plain delete / per-file recoverable-exception / batched
  `createDeleteRequest`) are the correct, narrowly-scoped mechanism; `MANAGE_EXTERNAL_STORAGE` is
  a broad, Play-Store-scrutinised special permission for file-manager-class apps and is
  deliberately out of scope.
- **Room stores no track metadata, by explicit design** (`FavoriteTrack.kt`'s own KDoc: "no cached
  title/artist/duration... caching any of it here would create a second copy that can drift out of
  sync with the real file"). A deleted file's path stays referenced in `favorites`/`playlist_items`
  exactly as a moved/renamed file's already does — an accepted, precedented "lost visibility" case
  (the previous plan's decision 3), not a new gap to close.
- **The existing UI patterns to reuse, all already in `TrackMenu.vue`**: the `notice` ref
  (`{kind: 'info'|'error'|'success', text}`) for outcome feedback; `busy`/three-outcome handling
  exactly like `onSetAsRingtone` (`TrackMenu.vue:143-169` — granted / sent-to-Settings / rejected)
  as the template for delete's three real outcomes (deleted / user-declined-the-OS-dialog /
  real error); the sheet's existing step machine (`menu` → `addToPlaylist` → `newPlaylist`) as the
  place a `details` step belongs, rather than a second modal component; `PlaylistView.vue:275-281`'s
  `window.confirm()` as the established destructive-action gate (no new dialog primitive).
- **`useLibrary()` already exposes `reload()`** (`useLibrary.js:271-273`, a forced re-query) — the
  correct way to reflect a deletion, never a hand-spliced mutation of the `readonly()` `tracks` ref.
- **The currently-playing track being deleted needs no new code.** The player already "degrades
  instead of stalling" — a deleted or unreadable file in the queue is skipped and reported, per the
  app's own existing behaviour (README, "What the app does"). Delete does not need to special-case
  Now Playing.
- **Absolute path is a display-only, best-effort field.** `MediaStore.MediaColumns.DATA` remains
  readable by an app with audio-read access on modern Android, but its exact availability has
  shifted across API levels — the implementing task must verify this by reading, not assume, and
  the field must degrade to `null` rather than throw if unavailable. It is shown to the user, never
  used for identity, writes, or matching (that stays `folder + '/' + displayName`, per the
  playlists plan's decision 3 — unchanged by this plan).

### Fixed constants

- `PKG` = `com.kefi.player`. Build env: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
  ANDROID_HOME=/home/nonos/Android/Sdk`.
- SDK levels (`android/variables.gradle`): `minSdk 24`, `compileSdk`/`targetSdk 36`.
- New i18n namespace additions live under the existing `trackMenu.*` prefix.
- No device access, no emulator, no release build this session (standing project rules). The only
  automatable gates are `npm run build`, `./gradlew :app:compileDebugKotlin`, `npm run apk`, greps,
  and reading the diff.

## Must Have

1. `mimeType` added to the track contract additively (`TrackSource.kt` → `PlayerPlugin.kt` →
   `player.js` JSDoc → `player.web.js` mock), following the exact pattern `folder`/`displayName`/
   `dateAdded` already established.
2. A new `getFileDetails({ uri })` plugin method returning `{ absolutePath: string | null,
   bitrate: number | null, sampleRate: number | null }`, computed on demand via
   `MediaMetadataRetriever` (bitrate/sample rate) and the `DATA` column (absolute path), off the
   main thread, called only when the details step of the sheet opens — never during `listTracks`.
3. A `details` step in `TrackMenu.vue`'s existing sheet, showing: title, artist, album, album
   artist, track number, year, duration, size, date added, folder, MIME type, bitrate, sample
   rate, absolute path — the free fields rendered immediately, the on-demand fields showing a
   loading state until `getFileDetails` resolves.
4. A `deleteTrack({ uri })` plugin method covering three `Build.VERSION.SDK_INT` branches:
   - `< 29`: plain `ContentResolver.delete(uri, null, null)`.
   - `== 29`: attempt delete, catch `RecoverableSecurityException`, launch its
     `userAction.actionIntent.intentSender` for a result, retry the delete on approval.
   - `>= 30`: `MediaStore.createDeleteRequest(resolver, listOf(uri))`, launch its `PendingIntent`
     for a result; the system performs the deletion itself on approval — no retry needed.
   Resolves one of `{ deleted: true }` / `{ deleted: false, reason: 'cancelled' }`, or rejects on a
   genuine I/O error — mirroring `onSetAsRingtone`'s three-outcome rigor, not a naive try/catch.
5. The IntentSender-launching mechanism T4 designs, with a primary approach and a named fallback
   (see T4) — this plan's biggest risk, called out as such.
6. "Delete from device" as a sixth `TrackMenu.vue` action, gated behind `window.confirm()` naming
   the file (matching `playlists.confirmDelete`'s existing `{ name }` interpolation pattern), using
   the existing `busy`/`notice` idiom, calling `useLibrary().reload()` on success.
7. Every new string through `t()`, in both `src/i18n/en.js` and `src/i18n/el.js`.

## Must NOT Have

1. **No metadata-editing feature of any kind** — no edit form, no `ContentResolver.update()` call
   on `TITLE`/`ARTIST`/`ALBUM`/`ALBUM_ARTIST`/`YEAR`/`TRACK`. Confirmed dead end (see Context).
2. **No new tag-writing dependency** (that need disappears with metadata editing being dropped).
3. **No `MANAGE_EXTERNAL_STORAGE` permission.**
4. **No caching of track metadata in Room.** `favorites`/`playlist_items` stay path-only.
5. **No cleanup of orphaned Room rows on delete.** A deleted file's path staying referenced in
   `favorites`/`playlist_items` is the same accepted "lost visibility" outcome a moved/renamed file
   already produces — consistent with existing design, not a gap. Do not add cleanup logic.
6. **No bulk or multi-select delete.** `activeTrack` (`useTrackMenu.js:24`) is a single nullable
   ref by design; this plan operates on exactly one track at a time, same as every other menu
   action.
7. **No custom confirmation UI replacing the OS-owned dialogs.** `RecoverableSecurityException`'s
   intent and `createDeleteRequest`'s `PendingIntent` each show their own system confirmation; the
   app must not attempt to pre-confirm and then suppress or duplicate it.
8. **No TypeScript**, no new state library, no new npm dependency for anything in this plan.
9. **No change to how the currently-playing track's disappearance is handled** — the existing
   skip-and-report behaviour already covers it.
10. **No weakening of any existing `TrackMenu.vue` action** (Favourite, Add to playlist, ringtone,
    notification, view in folder) or of `qa-playback.sh`'s existing checks.

## TODOs

### Phase A — file details (native + contract)

- [x] **T1: `mimeType` on the track contract**
  - Files: `android/app/src/main/java/com/kefi/player/TrackSource.kt`,
    `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`, `src/plugins/player.js`,
    `src/plugins/player.web.js`.
  - Add `MediaStore.Audio.Media.MIME_TYPE` (or the equivalent `Files.FileColumns.MIME_TYPE` —
    verify which constant `TrackSource.kt`'s existing selection-clause use already references,
    `:155-156`, and reuse the same one) to the `listTracks` projection; add `mimeType: String?` to
    the `Track` data class with a doc comment following the `folder`/`displayName`/`dateAdded`
    precedent (`TrackSource.kt:43-62`); read it in the cursor loop; add it to
    `toJSObject()` (`PlayerPlugin.kt:755-769`); add the `@property` line to the `PlayerTrack`
    typedef (`player.js:19-38`); give the three mock tracks in `player.web.js` plausible MIME
    types (`audio/mpeg` etc., matching their existing fake extensions).
  - QA Scenario: `grep -n "MIME_TYPE\|mimeType" android/app/src/main/java/com/kefi/player/TrackSource.kt
    android/app/src/main/java/com/kefi/player/PlayerPlugin.kt src/plugins/player.js
    src/plugins/player.web.js` shows the field in the projection, the data class, `toJSObject`, the
    JSDoc typedef, and all three mocks. `npm run build` exits 0;
    `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.

- [x] **T2: `getFileDetails({ uri })`**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`, `src/plugins/player.js`,
    `src/plugins/player.web.js`.
  - New `@PluginMethod`, following the validate-then-`App.scope.launch`-then-resolve/reject shape
    used throughout this file (e.g. `playTrack`, `PlayerPlugin.kt:105-141`), but running the actual
    work on an IO-appropriate dispatcher since `MediaMetadataRetriever.setDataSource` can block.
  - Resolves `{ absolutePath: string | null, bitrate: number | null, sampleRate: number | null }`.
    `bitrate`/`sampleRate` via `MediaMetadataRetriever.extractMetadata(METADATA_KEY_BITRATE /
    METADATA_KEY_SAMPLERATE)`, parsed to a number or `null` if the retriever returns nothing —
    never throw for a missing value, only for a genuinely unreadable file. `absolutePath` via the
    `DATA` column, verified by reading (not assumed) whether it is populated for a query built from
    `READ_MEDIA_AUDIO` access on this project's `targetSdk 36`; `null` if unavailable rather than
    an empty string or a guess.
  - Add the JSDoc contract entry to `player.js` and a mock in `player.web.js` returning plausible
    fake values for the three mock tracks (a mock `absolutePath` under the fake `Music/kefi-qa/...`
    tree is fine — it never needs to resolve to a real file in the browser).
  - QA Scenario: `grep -n "getFileDetails" android/app/src/main/java/com/kefi/player/PlayerPlugin.kt
    src/plugins/player.js src/plugins/player.web.js` shows all three; `npm run build` exits 0;
    `compileDebugKotlin` 0 errors.

### Phase B — file details (UI)

- [x] **T3: The details step in `TrackMenu.vue`**
  - Files: `src/components/TrackMenu.vue`, `src/i18n/{en,el}.js`.
  - Add "File details" as a new `.option` row in the `step === 'menu'` template (same markup shape
    as the existing five, `~TrackMenu.vue:222-292`), pushing to a new `step === 'details'`.
  - The details step renders the free fields immediately from `activeTrack` (title, artist, album,
    album artist, track number, year, duration via the existing `formatMs` import, size via a
    human-readable byte formatter — check `src/utils/format.js` for one before writing a new one —
    date added, folder, MIME type), then calls `getFileDetails({ uri: activeTrack.value.uri })` and
    fills in absolute path / bitrate / sample rate when it resolves, showing a loading state (not a
    blank field) in the meantime.
  - A back control returns to `step === 'menu'`, matching how `addToPlaylist`/`newPlaylist`
    already navigate within the sheet.
  - Every label through `t()`. No new component, no new dialog primitive.
  - QA Scenario: `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/components/TrackMenu.vue | grep -v "t("`
    prints nothing. SFC compiles via `@vue/compiler-sfc` (`parse` → `compileScript` →
    `compileTemplate`). `npm run build` exits 0.

### Phase C — delete from device (the biggest risk in this plan)

- [x] **T4: The IntentSender-launching mechanism — primary approach, named fallback**
  - Files: TBD by this task's own investigation; most likely `android/app/src/main/java/com/kefi/player/MainActivity.java`
    and/or a new small Kotlin file in `android/app/src/main/java/com/kefi/player/`.
  - **The problem**: `RecoverableSecurityException.userAction.actionIntent.intentSender` (API 29)
    and `MediaStore.createDeleteRequest(...)`'s returned `PendingIntent.intentSender` (API 30+)
    both need to be launched as an `IntentSender`, and Capacitor's documented plugin mechanism
    (`Plugin.startActivityForResult(call, intent, callbackName)` + `@ActivityCallback`) is built on
    `ActivityResultContracts.StartActivityForResult`, which is `Intent`-shaped, not
    `IntentSender`-shaped. No plugin in this codebase has ever awaited any activity result, so
    there is no in-repo precedent to copy.
  - **Primary approach**: `MainActivity` (which extends `BridgeActivity`, itself a
    `ComponentActivity`) registers its OWN `ActivityResultLauncher<IntentSenderRequest>` via
    `registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), ...)`,
    and exposes a minimal, generic way for a plugin to (a) trigger a launch with an `IntentSender`
    and (b) receive the boolean result back — e.g. a single public method on `MainActivity` that
    accepts an `IntentSender` and a callback/continuation, or a small held broker object
    `MainActivity` owns and the plugin reaches via `bridge.getActivity()`. Keep this GENERIC (not
    delete-specific) so nothing else needing the same shape later has to duplicate it.
  - **Named fallback, only if the primary approach cannot be made to work cleanly**: the classic,
    deprecated-but-present `Activity.startIntentSenderForResult(IntentSender, requestCode, Intent,
    int, int, int)` called directly on `bridge.getActivity()`, with the plugin manually tracking
    its own `requestCode → PluginCall` mapping and Capacitor's `Plugin.handleOnActivityResult`
    (confirmed present in this Capacitor version, `Plugin.java`, deprecated but functional)
    receiving the callback. This is uglier and touches a deprecated API, but it is a real fallback,
    not a dead end — do not thrash between the two, pick one after one genuine attempt at the
    primary approach and say which in the notepad.
  - Whichever approach is used, it MUST be reusable (not hardcoded to one delete call) — this is
    infrastructure, and T5 is its first and only consumer in this plan.
  - QA Scenario: this cannot be end-to-end verified without a real device showing a real system
    dialog — that is out of reach this session. The verifiable bar is: `compileDebugKotlin` 0
    errors, and the mechanism is exercised by T5's code compiling against it. Record in the
    notepad which approach was used and exactly why, so F5's hands-on pass knows what it's testing.

- [x] **T5: `deleteTrack({ uri })`**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`, plus whatever T4 exposes.
  - Three `Build.VERSION.SDK_INT` branches, exactly as specified in Must Have 4. On API 29, the
    delete must be RE-ATTEMPTED after the recoverable-exception intent is approved (the original
    attempt did not succeed, only the permission was granted). On API 30+, do NOT re-attempt a
    delete after approval — `createDeleteRequest`'s contract is that the system performs the
    deletion itself; calling `delete()` again afterward would either be a redundant no-op or, on a
    row the system just removed, an error to swallow rather than surface.
  - Resolve `{ deleted: true }`, resolve `{ deleted: false, reason: 'cancelled' }` for a user
    decline, reject only for a genuine unexpected failure — matching `onSetAsRingtone`'s three-way
    rigor (`TrackMenu.vue:143-169`), which this task's JS-side caller (T7) depends on.
  - QA Scenario: `grep -n "deleteTrack\|createDeleteRequest\|RecoverableSecurityException"
    android/app/src/main/java/com/kefi/player/PlayerPlugin.kt` shows all three SDK branches
    present. `grep -c "SDK_INT" android/app/src/main/java/com/kefi/player/PlayerPlugin.kt` output
    includes at least 2 new occurrences (the `>= 30` and `== 29` branch guards) beyond whatever the
    file already had. `compileDebugKotlin` 0 errors.

- [x] **T6: JS contract + web mock for `deleteTrack`**
  - Files: `src/plugins/player.js`, `src/plugins/player.web.js`.
  - `@property {(options: { uri: string }) => Promise<{ deleted: boolean, reason?: string }>} deleteTrack`
    documenting the three outcomes exactly.
  - Web mock: resolves `{ deleted: true }` for any URI (a browser has no real file to delete, and
    no OS dialog to simulate) — document that plainly in a comment so nobody mistakes the mock for
    a faithful simulation of the permission dance.
  - QA Scenario: `grep -n "deleteTrack" src/plugins/player.js src/plugins/player.web.js` shows both.
    `node --check src/plugins/player.web.js` passes.

- [x] **T7: "Delete from device" wired into `TrackMenu.vue`**
  - Files: `src/components/TrackMenu.vue`, `src/i18n/{en,el}.js`.
  - Sixth `.option` row. On tap: `window.confirm(t('trackMenu.confirmDelete', { name:
    activeTrack.value.displayName }))` (matching `playlists.confirmDelete`'s existing
    interpolation shape) — if declined, do nothing, sheet stays open. If confirmed: set the
    existing `busy` ref, call `Player.deleteTrack({ uri: activeTrack.value.uri })`, then branch on
    the three outcomes exactly like `onSetAsRingtone` does — `deleted: true` → `notice = {kind:
    'success', ...}`, call `useLibrary().reload()`, close the sheet; `deleted: false, reason:
    'cancelled'` → a neutral notice, sheet stays open, no reload (nothing changed); a rejection →
    an error notice, sheet stays open.
  - Do NOT add any Room cleanup call here (Must NOT Have 5) and do NOT special-case Now Playing
    (Must NOT Have 9).
  - QA Scenario: `grep -n "deleteTrack\|window.confirm\|reload()" src/components/TrackMenu.vue`
    shows the confirm gate, the call, and the reload-on-success. Hardcoded-text gate empty.
    `@vue/compiler-sfc` compiles clean. `npm run build` exits 0.

### Phase D — strings, QA, docs

- [x] **T8: Strings**
  - Files: `src/i18n/en.js`, `src/i18n/el.js`.
  - Every key T2/T3/T7 need, under `trackMenu.*`, in identical order in both files. Greek natural,
    matching the app's existing informal register (see the just-shipped playlists strings for the
    voice to match).
  - QA Scenario: extract and diff both files' key lists — identical set and order. Hardcoded-text
    gate empty across `src/views` and `src/components`.

- [x] **T9: QA additions (written; the OS confirmation dialog cannot be driven by CDP — say so)**
  - Files: `scripts/qa-playback.sh`.
  - Checks that CAN be genuinely exercised offline, through the web mock: the "File details" and
    "Delete from device" rows appear in the menu; opening details renders the free fields with
    real values from the mock track; `window.confirm` being invoked on a delete tap (stub/observe
    it over CDP if the existing suite has a pattern for this, otherwise note honestly that it
    cannot be observed and skip rather than fake it); the mock's `deleteTrack` resolving `{deleted:
    true}` and the library reloading (row count drops by one in the mock's data).
  - The real native delete flow (any of the three SDK branches, or the actual OS dialog) CANNOT be
    exercised without a device — mark this in the script's own comment and do not write a check
    that appears to test it but cannot fail.
  - QA Scenario: `bash -n scripts/qa-playback.sh` exits 0; `git diff scripts/qa-playback.sh | grep '^-' | grep -v '^--- ' | wc -l` prints `0` (pure addition).

- [x] **T10: Documentation**
  - Files: `README.md`.
  - A short addition to the existing track-menu documentation: the two new actions, that delete
    works on every supported Android version via three different native code paths culminating in
    an OS-owned confirmation dialog the app cannot skip or replace, and that metadata editing was
    considered and explicitly rejected as an Android platform limitation (link the reasoning, so a
    future reader doesn't re-propose `ContentResolver.update()`).
  - QA Scenario: `grep -n "createDeleteRequest\|RecoverableSecurityException\|metadata editing"
    README.md` shows the new content.

## Final Verification Wave

- [x] **F1: Build integrity** — `npm run build` exits 0; `./gradlew :app:compileDebugKotlin 2>&1 |
  grep -c '^e:'` prints `0`; `npm run apk` prints `BUILD SUCCESSFUL`. Record the debug APK size and
  compare to the pre-this-plan baseline (no expected change — no new dependency).
- [x] **F2: Contract integrity** — `mimeType`, `getFileDetails`, and `deleteTrack` each present in
  all four layers (native, JSDoc contract, web mock, a real caller in `TrackMenu.vue`).
- [x] **F3: i18n integrity** — `trackMenu.*` key sets identical in set and order across both
  locales; hardcoded-text gate empty.
- [x] **F4: Scope review of the complete diff** against Must Have / Must NOT Have, by an
  independent reviewer, specifically checking: zero occurrences of `ContentResolver.update()`
  targeting `TITLE|ARTIST|ALBUM|ALBUM_ARTIST|YEAR|TRACK`; no `MANAGE_EXTERNAL_STORAGE` in the
  manifest; no new Room column/table for track metadata; no cleanup-on-delete code touching
  `favorites`/`playlist_items`; no multi-select scaffolding; the three SDK branches in `deleteTrack`
  are each reachable (not dead code behind a wrong version guard); T4's chosen mechanism is
  generic, not delete-specific, and does not silently duplicate or suppress the OS confirmation UI.
- [x] **F5: Hands-on QA by the user (DEVICE-ONLY)** — **PASSED on the user's connected device,
  2026-08-21.** The user reported testing all device scenarios successfully, including the
  things no static check can cover: the details sheet showing correct real bitrate/sample-rate/
  absolute path for a real file; deleting a track on whichever real Android version the user's
  device runs, confirming the OS dialog appears, confirming the file is actually gone (not just
  the MediaStore row), confirming the library list updates without a manual restart; deleting the
  currently-playing track and confirming playback skips to the next item without crashing.
- [x] **F6: This plan's own scope boundary** — confirm no metadata-editing code of any kind exists
  anywhere in the diff (a stray form field or a hopeful `update()` call left over from an
  implementing agent "helpfully" trying to satisfy the original three-item request literally).

## Success Criteria

1. `npm run build` exits `0`.
2. `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.
3. `npm run apk` prints `BUILD SUCCESSFUL`.
4. `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` prints nothing.
5. `grep -rn "ContentResolver().update\|resolver.update" android/app/src/main/java/com/kefi/player/ | grep -iE "title|artist|album|track|year"` prints nothing.
6. `grep -c "MANAGE_EXTERNAL_STORAGE" android/app/src/main/AndroidManifest.xml` prints `0`.
7. F4 returns APPROVE.
8. F5 is reported honestly as not run unless the user reports back.
