# Plan: Kefi Player — the Playlists tab, favourites, and a track options menu

## Context

`kefi-player` is a working local music player (Vue 3 + Capacitor, plain JS + JSDoc, Greek/English
through a hand-rolled `t()`). Its library is four tabs — Songs, Albums, Artists, Folders — all
derived in `src/composables/useLibrary.js` from ONE MediaStore query, with no cache and no
database. A hidden fifth downloader tab sits behind a ten-tap easter egg.

This plan adds a **Playlists tab**: a list of lists holding two built-in smart lists (Favorites,
Recently added) plus user-created playlists, and a **track options menu** reachable from every
track row and from Now Playing.

### This is the plan the previous one deferred

`.claude/plans/kefi-player-full-android-player.md:25-27` recorded, on 2026-08-19:
*"Persistence: SharedPreferences-class storage now, Room later. Playlists/favourites/play-counts
and the Room schema they need are a separate later plan."* — and its Must NOT Have said
*"No database… No playlists, favourites… Named as the next plan."* This is that plan. The
"no database" posture in `useLibrary.js:11-12` was scoped to the previous plan and does **not**
bind this one.

### Decisions taken with the user (interview, 2026-08-20)

1. **User-created playlists are IN SCOPE** — not just smart lists.
2. **Room owns playlist and favourite data**, per the user's original 2026-08-19 decision. Kotlin
   only, reached through new `PlayerPlugin` methods. The JS layer gains no state library.
3. **Identity is the file's relative path**, not the MediaStore numeric id — the user chose
   path-keying explicitly. Consequence accepted: a favourite survives a MediaStore rescan that
   reassigns ids, and does NOT survive the user moving or renaming the file.
4. **Recently added = the newest 15 tracks** by `DATE_ADDED`, descending.
5. **Full CRUD including reordering**: create, rename, delete a playlist; add and remove tracks;
   drag to reorder within a playlist.
6. **Every track row and Now Playing get an options button**, and the menu carries: Favourite,
   Add to playlist, Set as ringtone, Set as notification, View in folder.
7. **No one-tap favourite heart anywhere.** The user settled this directly: "no one tap
   favorote, add it in the menu." Favourite/Unfavourite is the first item in the options menu, on
   rows and on Now Playing alike. The row therefore gains exactly ONE new control — the options
   button — so it stays art / title+artist / duration / options, and the 360px density problem
   that a heart-plus-menu row would have created does not arise.
8. **The Playlists tab is a list of lists** — Favorites, Recently added, then user playlists with
   track counts — and tapping one opens a detail screen. Matches how Albums and Artists behave.
9. Smart lists cannot be reordered or renamed; their order is defined by their nature (Favorites
   by title, Recently added by date). Only user playlists have an editable order.

### Findings that shaped this plan (verified by reading the code, not assumed)

- **"Recently added" cannot be built today.** `TrackSource.kt:104-117`'s projection selects
  `_ID, DISPLAY_NAME, TITLE, ARTIST, ALBUM, ALBUM_ARTIST, ALBUM_ID, TRACK, YEAR, DURATION, SIZE`
  plus the folder column. **No `DATE_ADDED`, no `DATE_MODIFIED`** — grep confirms zero references
  in the whole Kotlin package. This is a mandatory native change.
- **The query has NO sort order at all**: `TrackSource.kt:145` passes `null` as the 5th argument
  to `contentResolver.query`. Row order today is whatever the provider returns.
- **The path key is not exposed to JS either.** `DISPLAY_NAME` is selected but used only as a
  title fallback (`TrackSource.kt:159-180`); it never reaches the JSON. `PlayerTrack`
  (`src/plugins/player.js:19-33`) carries `folder` but no filename, so `folder + '/' + displayName`
  — the chosen identity — requires exposing `DISPLAY_NAME` as an additive field.
- **`ALBUM_ID` is dead weight** in the projection (`TrackSource.kt:111`): selected, never read.
- **`TrackRow.vue`'s root IS a `<button>`** (`TrackRow.vue:30-64`) with a single `select` emit and
  no trailing control, menu or long-press handler. A nested `<button>` is invalid HTML and
  inaccessible, so adding an options button REQUIRES restructuring the root away from
  `<button>`. This component is shared by the Songs tab (`LibraryView.vue:265-274`) and nested
  inside `FolderList.vue:91`, so the restructure touches both at once.
- **`MediaStore.IS_FAVORITE` is rejected**, not overlooked: API 30+ against `minSdk 24`
  (`android/variables.gradle`: minSdk 24, compileSdk/targetSdk 36), it needs a
  `createFavoriteRequest` consent-intent round-trip through `startIntentSenderForResult`, it is
  unreliable for files the app does not own, and it is a system-wide flag shared with other apps —
  the wrong owner for this app's private favourites.
- **Room needs an annotation processor**, which this Capacitor-generated Gradle build has never
  had. That is the single biggest build risk in this plan; T2 handles it with an explicit fallback.
- **Existing persistence precedent**: `PlaybackStateStore.kt` uses ONE SharedPreferences file
  (`"kefi.player"`, `MODE_PRIVATE`) with `player.*` keys and `org.json` serialisation, and its
  `clear()` (`:71-73`) wipes the whole file — so playlists must NOT live there.
- `@capacitor/preferences` is the only storage plugin installed; `@capacitor/filesystem` is
  absent. Dependencies are deliberately few: `@capacitor/{android,app,core,preferences}`,
  `bgutils-js`, `vue`, `vue-router`.
- **The tab strip is already tight at four tabs.** `LibraryView.vue:423-456`: `.segmented` scrolls
  horizontally (`overflow-x: auto`, hidden scrollbar) because "four tabs of Greek do not fit on a
  360px screen"; `.segment` is `flex: 1 0 auto` so segments never compress. A fifth tab relies on
  that same mechanism, plus the `watch(tab, …)` scroll-into-view at `:54-60`.
- **Swipe between tabs is index-based and order-agnostic** (`stepTab`, `LibraryView.vue:42-47`,
  no wrap-around), wired at `:50` with `ignore: '.segmented, .search'`. Extending `TABS` needs no
  gesture change — but any new horizontally-draggable strip inside the tab would fight it.
- **Lazy artwork is per-list-root**: `useLazyArtwork(songList, () => [tab.value, visibleTracks.value])`
  (`LibraryView.vue:67-68`). `AlbumGrid.vue:19` has its own. `FolderList`'s nested `TrackRow`s are
  observed by nothing — an existing gap. Every new track list needs its own wiring or its rows
  show placeholder art forever.
- **Ringtone/notification needs `WRITE_SETTINGS`** — a special permission granted only through
  `Settings.ACTION_MANAGE_WRITE_SETTINGS`, checked with `Settings.System.canWrite(context)`. The
  app already has a precedent for sending the user to system settings: `PlayerPlugin.openAppSettings`
  (`PlayerPlugin.kt:359-367`).
- **`FolderList.vue`'s `expanded` state is NOT module-level, despite its own comment.** The
  comment at `FolderList.vue:18-22` reads "Module level, like the Library's own tab: expanding a
  folder, going to Now Playing and coming back should not silently collapse everything the user
  opened" — but `const expanded = ref([])` sits INSIDE `<script setup>` at `:23`, so it is
  per-instance. Navigating to `/now-playing` unmounts `LibraryView` and with it `FolderList`, so
  the behaviour that comment promises is already broken. Found while fact-checking this plan; it
  is a PRE-EXISTING bug, not something this feature introduces, and T13 depends on fixing it.
- **No date formatter exists.** `src/utils/format.js` exports `formatDuration`, `formatMs`,
  `formatEta`, `formatBytes`, `looksLikeUrl`, `looksLikeYoutube` — reuse `formatMs`, do not
  reimplement it.
- **The hardcoded-UI-text gate is a grep, run by hand**:
  `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` must be empty.
- **QA cannot be executed in the executing session.** The user withdrew device access ("stop
  testing on my mobile device") and retired the emulator; release builds are forbidden ("no
  release apk, it is not finished"). Every scripted QA scenario here is written to be run LATER
  and must be reported as not-run, never as passing.

### Fixed constants (used verbatim by every task)

- `PKG` = `com.kefi.player`. Build env:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`.
- Track identity: `path` = `folder + '/' + displayName`, no leading slash; `folder` may be `''`,
  in which case `path` is just `displayName`.
- Recently added: **15** tracks, `DATE_ADDED` descending, ties broken by the existing
  `Intl.Collator` on title (`useLibrary.js:52`).
- Route for a list's detail screen: `/playlist/:id`, where `id` is `favorites`,
  `recently-added`, or a numeric user-playlist id.
- Room database file: `kefi-library.db`, version 1. It must NOT reuse the `"kefi.player"`
  SharedPreferences file, whose `clear()` wipes everything in it.
- New i18n namespace: `playlists.*` and `trackMenu.*`, in BOTH `src/i18n/en.js` and `el.js`.

## Must Have

1. `DATE_ADDED` and `DISPLAY_NAME` exposed as additive fields on the track contract, through
   `TrackSource.kt` → `PlayerPlugin.toJSObject` → `player.js` JSDoc → `player.web.js` mock.
2. A Room database (Kotlin only) owning: user playlists, their ordered items, and favourites —
   all keyed by relative path.
3. New `PlayerPlugin` methods covering: list playlists (with counts), create, rename, delete,
   add tracks, remove track, reorder, list favourites, toggle favourite. Each with a JSDoc
   contract entry in `src/plugins/player.js` and a working `player.web.js` mock.
4. `src/composables/usePlaylists.js` — module-level state in the shape of `usePlayer.js`
   (`readonly()` at the boundary, actions inside), hydrated before mount from `main.js`.
5. A fifth `TABS` entry rendering a list of lists: Favorites, Recently added, then user playlists
   with track counts and a create affordance.
6. A detail screen at `/playlist/:id` rendering tracks as `<ul><TrackRow></ul>` — the Songs-tab
   pattern (`LibraryView.vue:265-274`), NOT a new grid — with its own `useLazyArtwork` wiring.
7. Drag-to-reorder inside a user playlist's detail screen, persisted to Room.
8. `TrackRow.vue` restructured so its root is no longer a `<button>`, carrying an options button
   without losing its current tap-to-play behaviour, `active`/`playing` states, or
   `data-track-id` (the lazy-artwork hook). No heart on the row (decision 7).
9. A track options sheet with: Favourite / Unfavourite, Add to playlist, Set as ringtone, Set as
   notification, View in folder.
10. Native ringtone and notification-tone setting via `RingtoneManager`, gated behind
    `Settings.System.canWrite` with a route to `ACTION_MANAGE_WRITE_SETTINGS` when absent.
11. "View in folder" switching to the Folders tab with that track's folder expanded.
12. An options button on `NowPlayingView.vue` opening the same sheet. No heart there either —
    favouriting the playing track is one menu item away (decision 7).
13. Every new string through `t()` in both locales; the hardcoded-text grep stays empty.
14. Scripted QA additions written for later execution and clearly labelled as not run.

## Must NOT Have

1. **No JS state library** — no Pinia, no Vuex, no JS ORM, no `@capacitor-community/sqlite`.
   Room is Kotlin-only, behind the plugin bridge, exactly like `TrackSource`.
2. **No TypeScript in the app.** The only permitted `.ts` files remain `src/potoken/index.ts` and
   `vite.potoken.config.ts`.
3. **No `MediaStore.IS_FAVORITE`** and no `createFavoriteRequest` flow.
4. **No m3u/pls import or export**, no "smart playlist rules engine", no play counts, no
   listening history, no recommendations.
5. **No changes to `AlbumGrid.vue` or `ArtistList.vue`** — they render groups, not tracks, and
   giving them per-track controls means restructuring them, which nobody asked for.
6. **No new horizontally-draggable strip inside the Playlists tab** — it would fight
   `useHorizontalSwipe` (`LibraryView.vue:50`) the way `.segmented` and `.search` already have to
   be excluded from it.
7. **No content hashing, fingerprinting or fuzzy re-matching** to chase moved files. Path-keying's
   limitation is accepted and documented (decision 3).
8. **No writes into the `"kefi.player"` SharedPreferences file** — `PlaybackStateStore.clear()`
   wipes it wholesale.
9. **No weakening of the existing library behaviour**: the four existing tabs, the search box,
   swipe-between-tabs, scroll-into-view, and the artwork cache all keep working unchanged.
10. **No release build** (`npm run apk:release`) and **no device or emulator access** at any
    point. Debug build + Kotlin compile + greps are the only gates.
11. **No `@capacitor/filesystem`** or any other new npm dependency. Room's Gradle artifacts are
    the only new dependencies permitted, and only for the Kotlin side.
12. **No deleting a user's playlist without confirmation**, and no destructive migration: the
    database is created at version 1 and nothing else in the app is migrated into it.

## TODOs

### Phase A — the native contract and the database

- [x] **T1: Expose `dateAdded` and `displayName` on the track contract**
  - Files: `android/app/src/main/java/com/kefi/player/TrackSource.kt`,
    `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`, `src/plugins/player.js`,
    `src/plugins/player.web.js`.
  - Add `MediaStore.Audio.Media.DATE_ADDED` to the projection (`TrackSource.kt:104-117`) — no SDK
    guard needed, it predates minSdk 24 — and read `DISPLAY_NAME`, which is ALREADY in the
    projection at `:106` but currently only used as a title fallback.
  - Add `dateAdded: Long` (epoch **seconds**, as MediaStore returns) and `displayName: String` to
    the `Track` data class (`:32-49`) and the cursor loop (`:159-180`). Follow the additive
    precedent set by `folder`, whose comment at `:43-48` explains the contract rule.
  - Add both keys to `TrackSource.Track.toJSObject()` (`PlayerPlugin.kt:467-479`), nullable-safe in
    the same style as the existing fields.
  - Add both `@property` lines to the `PlayerTrack` typedef (`src/plugins/player.js:19-33`),
    documenting that `dateAdded` is epoch SECONDS and can tie for many files scanned together.
  - Give all three mock tracks in `player.web.js:117-161` staggered fake `dateAdded` values and
    real-looking `displayName`s, so `npm run dev` can exercise Recently added.
  - While here, delete the dead `ALBUM_ID` column (`TrackSource.kt:111`) — selected, never read.
  - QA Scenario: `grep -n "DATE_ADDED\|dateAdded\|displayName" android/app/src/main/java/com/kefi/player/TrackSource.kt android/app/src/main/java/com/kefi/player/PlayerPlugin.kt src/plugins/player.js src/plugins/player.web.js`
    shows the field in the projection, the data class, `toJSObject`, the JSDoc typedef and all
    three mocks; `grep -c "ALBUM_ID" android/app/src/main/java/com/kefi/player/TrackSource.kt`
    prints `0`; `npm run build` exits 0; `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'`
    prints `0`.

- [x] **T2: Room in the build — with a named fallback**
  - Files: `android/app/build.gradle`, `android/build.gradle` (or `android/variables.gradle` for
    versions), `android/.gitignore` if a schema export directory is added.
  - Add `androidx.room:room-runtime` + `room-ktx` and the KSP plugin with
    `ksp("androidx.room:room-compiler")`. Pick versions compatible with the project's Kotlin
    version — read it from the Gradle files, do not guess.
  - **This is the plan's biggest build risk**: this Capacitor-generated build has never had an
    annotation processor. Timebox it. **If KSP cannot be made to work in a reasonable attempt,
    fall back to a hand-written `SQLiteOpenHelper`** — same SQLite storage, same schema, same
    plugin surface, zero build-system change — and record the failure and the switch in the
    notepad. The user chose "Room" to mean a real database, not the annotation processor
    specifically. Do NOT thrash: one honest attempt, then the fallback.
  - QA Scenario: `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`
    and `npm run apk` prints `BUILD SUCCESSFUL`. Record in the notepad which path was taken
    (Room+KSP or SQLiteOpenHelper) and the exact versions used.

- [x] **T3: Schema and DAO**
  - Files: NEW `android/app/src/main/java/com/kefi/player/library/LibraryDatabase.kt`,
    `Playlist.kt`, `PlaylistItem.kt`, `FavoriteTrack.kt`, `LibraryDao.kt` (or the equivalent
    single-file `SQLiteOpenHelper` if T2 fell back).
  - Tables: `playlists(id INTEGER PK, name TEXT NOT NULL, createdAt INTEGER)`;
    `playlist_items(id INTEGER PK, playlistId INTEGER NOT NULL, path TEXT NOT NULL, position INTEGER NOT NULL)`
    with an index on `(playlistId, position)` and `ON DELETE CASCADE` from `playlists`;
    `favorites(path TEXT PRIMARY KEY, addedAt INTEGER)`.
  - `path` is the identity from decision 3 — `folder + '/' + displayName`. Document in a comment
    on the column that a moved or renamed file loses its membership, and that this was the user's
    explicit choice over MediaStore ids.
  - Database file `kefi-library.db`, version 1. Do NOT touch `PlaybackStateStore`'s
    `"kefi.player"` SharedPreferences file.
  - DAO methods: list playlists with item counts, insert/rename/delete playlist, insert items
    appending at `MAX(position)+1`, delete item, rewrite positions for a reorder (single
    transaction), list a playlist's paths in `position` order, list favourite paths, insert/delete
    a favourite.
  - QA Scenario: `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`;
    `grep -rn "kefi-library.db" android/app/src/main/java/` shows exactly one definition;
    `grep -rn "kefi.player" android/app/src/main/java/com/kefi/player/library/ | wc -l` prints `0`
    (the new code must not touch the playback prefs file).

- [x] **T4: Plugin methods**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`.
  - Add `@PluginMethod`s, each following the shape of `listTracks` (`:275-292`) — permission-free
    (these touch only the app's own database), running on `App.scope`, resolving a `JSObject`,
    rejecting via the existing `rejectWith` (`:435-437`):
    `listPlaylists` → `{ playlists: [{ id, name, count }] }`;
    `createPlaylist({ name })` → `{ id }`; `renamePlaylist({ id, name })`;
    `deletePlaylist({ id })`; `addToPlaylist({ id, paths })`;
    `removeFromPlaylist({ id, path })`; `reorderPlaylist({ id, paths })` (the full ordered list,
    rewritten in one transaction — simpler and less error-prone than from/to indices);
    `listPlaylistPaths({ id })` → `{ paths }`; `listFavorites()` → `{ paths }`;
    `setFavorite({ path, favorite })` → `{ favorite }`.
  - Validate inputs and reject with a clear message on a missing/blank name or a missing id, in
    the style of `setShareTargetEnabled` (`:369-378`).
  - QA Scenario: `grep -c "@PluginMethod" android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`
    increases by exactly 10 from its current value (record both numbers in the notepad);
    `compileDebugKotlin` reports 0 errors.

- [x] **T5: JS contract and web mock**
  - Files: `src/plugins/player.js`, `src/plugins/player.web.js`.
  - Add a JSDoc `@property` entry per new method to the plugin typedef, matching the existing
    entries' depth (`player.js:67-69` is the model), documenting payload shapes exactly.
  - Implement all ten in the web mock over an in-memory structure seeded with one fake playlist
    and one favourite, so `npm run dev` and any offline QA drive real UI paths. The mock must
    never throw.
  - QA Scenario: `grep -c "listPlaylists\|createPlaylist\|renamePlaylist\|deletePlaylist\|addToPlaylist\|removeFromPlaylist\|reorderPlaylist\|listPlaylistPaths\|listFavorites\|setFavorite" src/plugins/player.js src/plugins/player.web.js`
    shows every name in both files; `npm run build` exits 0.

### Phase B — the state layer

- [x] **T6: `usePlaylists.js`**
  - Files: NEW `src/composables/usePlaylists.js`; `src/main.js`.
  - Module-level refs OUTSIDE the exported function, `readonly()` at the return boundary, actions
    inside — the exact shape of `usePlayer.js:8-32` and `:266-293`. Not the bare-ref export style
    of `useEasterEgg.js`; this has many actions.
  - State: `playlists` (id/name/count), `favoritePaths` (a `Set` in a ref, for O(1) `isFavorite`),
    plus a `status`/`errorMessage` pair mirroring `useLibrary.js:17-25`.
  - Actions: `initializePlaylists()` (hydrate playlists + favourites; swallow errors and degrade,
    like `initLocale`), `createPlaylist`, `renamePlaylist`, `deletePlaylist`, `addToPlaylist`,
    `removeFromPlaylist`, `reorderPlaylist`, `toggleFavorite(track)`, `isFavorite(track)`,
    `pathOf(track)` (the one place `folder + '/' + displayName` is computed — every other file
    imports it rather than re-deriving it).
  - Smart lists as computeds over `useLibrary().tracks`: `favoriteTracks` (tracks whose path is in
    `favoritePaths`, sorted by the shared collator) and `recentlyAdded` (newest **15** by
    `dateAdded` desc, ties broken by `collator.compare(title)`).
  - Add `initializePlaylists()` to the `Promise.all([...])` in `src/main.js:13` so the Playlists
    tab and the menu's Favourite/Unfavourite label are correct on first paint rather than
    flipping a tick later.
  - QA Scenario: `grep -n "readonly" src/composables/usePlaylists.js` shows the state exposed
    read-only; `grep -n "initializePlaylists" src/main.js` shows it in the `Promise.all`;
    `grep -rn "folder + '/'" src/ | wc -l` prints `1` (only `pathOf` derives the path);
    `npm run build` exits 0.

### Phase C — the UI

- [x] **T7: `TrackRow.vue` restructure**
  - Files: `src/components/TrackRow.vue`.
  - The root is currently a `<button>` (`:30-64`); a nested button is invalid and inaccessible, so
    the root becomes a `<div class="row track-row">` holding: a `<button class="row-main">` (art +
    title/artist + duration, emitting `select` exactly as today) and a `<button class="row-menu">`
    options button emitting a new `options` event. TWO buttons, no heart (decision 7).
  - `data-track-id` MUST stay on an element the lazy-artwork observer sees (`useLazyArtwork` keys
    off it) — verify against `src/composables/useLazyArtwork.js` which element it queries before
    moving anything.
  - Keep `active`/`playing` styling and the `.bars` animation working unchanged.
  - Tap targets follow the app's icon-button convention (2.4-2.6rem; `MiniPlayer.vue`'s `.toggle`
    is 2.6rem/1.4rem, `NowPlayingView.vue`'s `.icon-button` is the 2.4rem secondary variant).
  - One new control only, so the row should still fit at 360px; verify the title/artist still
    ellipsize rather than the row overflowing, and leave `duration` where it is.
  - `FolderList.vue:91` nests this component and must keep working; check its emit plumbing still
    lines up and fix it there if the event surface changed.
  - QA Scenario: `grep -c "<button" src/components/TrackRow.vue` is `2`;
    `grep -n "data-track-id" src/components/TrackRow.vue` still present;
    `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/components/TrackRow.vue | grep -v "t("` is empty;
    `npm run build` exits 0. Verify the SFC compiles with
    `node -e` + `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) — a plain
    re-read has already missed a real error once on this project.

- [x] **T8: The options sheet**
  - Files: NEW `src/components/TrackMenu.vue`; `src/i18n/{en,el}.js`.
  - A bottom sheet listing: Favourite/Unfavourite, Add to playlist, Set as ringtone, Set as
    notification, View in folder. Props: the track. Emits one event per action, or a single
    `action` event with a discriminator — decide and document.
  - "Add to playlist" opens a second step listing existing playlists plus "New playlist…". The
    new-playlist path needs a text input, which this app has NONE of yet — build the smallest
    inline input that matches the app's tokens; do not introduce a modal framework.
  - Dismiss on backdrop tap and on hardware back (`wireHardwareBack` in `src/router/index.js` is
    the existing back-handling seam — read it before wiring anything).
  - Styling from this app's tokens only; `prefers-reduced-motion` respected for any transition,
    matching `MiniPlayer.vue:179-183`.
  - QA Scenario: the hardcoded-text grep is empty for the new file; every label resolves through
    `t()` and exists in both locales; `npm run build` exits 0.

- [x] **T9: The Playlists tab and its list of lists**
  - Files: `src/views/LibraryView.vue`; NEW `src/components/PlaylistList.vue`;
    `src/i18n/{en,el}.js`.
  - Add `'playlists'` to `TABS` (`LibraryView.vue:30`) — decide the position deliberately and say
    why in the notepad (appending keeps existing swipe muscle-memory intact; inserting shifts
    every index).
  - Render rows: Favorites (with its count), Recently added (count capped at 15), then user
    playlists with counts, then a create affordance. Reuse the `ArtistList.vue` row idiom rather
    than inventing a new one.
  - `countLabel` (`:86-95`) gains a `playlists` branch with its own i18n keys.
  - Confirm the 5-segment strip still scrolls correctly — `.segment` is `flex: 1 0 auto` and
    `.segmented` is `overflow-x: auto` (`:423-456`), so this should need no CSS change; verify
    rather than assume, and note the narrow-screen check is for the user's hands-on pass.
  - QA Scenario: `grep -n "playlists" src/views/LibraryView.vue` shows the TABS entry and the
    countLabel branch; the hardcoded-text grep is empty; `npm run build` exits 0.

- [x] **T10: The list detail screen**
  - Files: NEW `src/views/PlaylistView.vue`; `src/router/index.js`; `src/i18n/{en,el}.js`.
  - Route `/playlist/:id` where `id` is `favorites`, `recently-added`, or a numeric id. Unknown or
    deleted id redirects to `/library` rather than rendering an empty screen — follow the existing
    catch-all precedent in `src/router/index.js`.
  - Tracks render as `<ul><TrackRow></ul>` (the Songs-tab pattern, `LibraryView.vue:265-274`),
    with its OWN `useLazyArtwork` root ref — without it, every row shows placeholder art forever.
  - Tapping a track queues the list it is in, exactly like `playFromList`
    (`LibraryView.vue:150-153`): the queue is the list the user is looking at.
  - Empty states through `t()`: an empty Favorites list, and a Recently-added list on a library
    with no dated tracks, read differently and both need copy.
  - A user playlist's screen also offers rename and delete (delete behind a confirm — Must NOT
    Have 12); smart lists offer neither (decision 9).
  - QA Scenario: `grep -n "/playlist/" src/router/index.js` shows the route;
    `grep -n "useLazyArtwork" src/views/PlaylistView.vue` present; hardcoded-text grep empty;
    `npm run build` exits 0.

- [x] **T11: Drag to reorder**
  - Files: `src/views/PlaylistView.vue` (and a NEW composable if the logic warrants one).
  - Only inside a user playlist. Reorder by long-press-then-drag on a row, committing via
    `reorderPlaylist` with the full new order.
  - **This must not fight the two existing gestures**: `useHorizontalSwipe` steps library tabs, and
    `useVerticalDismiss` (`src/composables/useVerticalDismiss.js`) dismisses Now Playing with a
    downward drag. Read BOTH before writing a third gesture, and document why this one's
    listeners/`preventDefault` policy is safe next to them — `useHorizontalSwipe.js:14-18`
    deliberately never calls `preventDefault`, and `useVerticalDismiss` deliberately does, gated
    behind a commitment latch. This one needs its own justification, not a copy of either comment.
  - No new dependency (no sortable.js).
  - QA Scenario: `grep -rn "preventDefault" src/composables/*.js` — the new gesture's use is gated,
    not unconditional; `npm run build` exits 0. Actual drag feel is device-only: label it for the
    user's hands-on pass.

- [x] **T12: Ringtone and notification tone (native)**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`,
    `android/app/src/main/AndroidManifest.xml`.
  - New `@PluginMethod setAsRingtone({ uri, type })` where `type` is `ringtone` or `notification`,
    mapping to `RingtoneManager.TYPE_RINGTONE` / `TYPE_NOTIFICATION` and
    `RingtoneManager.setActualDefaultRingtoneUri`.
  - Declare `android.permission.WRITE_SETTINGS` in the manifest, and gate every call on
    `Settings.System.canWrite(context)`. When it is false, resolve
    `{ granted: false }` and open `Settings.ACTION_MANAGE_WRITE_SETTINGS` for this package —
    follow `openAppSettings` (`PlayerPlugin.kt:359-367`) for the intent-launch idiom. Never crash
    and never silently no-op: the UI must be able to tell the user why nothing happened.
  - Contract entry in `player.js` + a mock in `player.web.js` returning `{ granted: false }`
    (a browser has no ringtones).
  - QA Scenario: `grep -n "WRITE_SETTINGS" android/app/src/main/AndroidManifest.xml` present;
    `grep -n "canWrite" android/app/src/main/java/com/kefi/player/PlayerPlugin.kt` present;
    `compileDebugKotlin` 0 errors. Actually setting a ringtone is device-only — user's hands-on
    pass.

- [x] **T13: "View in folder"** (reopened by F4, then fixed: the Now Playing entry point was never wired)
  - Files: `src/views/LibraryView.vue`, `src/components/FolderList.vue`, and whatever the menu
    needs to trigger it.
  - Switch the library to the Folders tab with that track's folder expanded and scrolled into
    view.
  - **First, fix the lie.** `FolderList.vue:18-22` documents `expanded` as module-level so that
    expansion survives a trip to Now Playing, but `:23` declares it inside `<script setup>` —
    per-instance, reset on every unmount. Hoist `const expanded = ref([])` ABOVE the
    `<script setup>` body's component scope (module scope, the way `usePlayer.js:8-32` does it) so
    the comment becomes true, then have "view in folder" push the target folder's key into it.
    Do this as a deliberate, separately-noted fix — it changes existing behaviour (folder
    expansion will now persist across navigation, as it was always documented to) and the user
    should be told rather than discovering it.
  - Do NOT add a prop drill or a router query param for the target folder; module-level state is
    already the established pattern for exactly this in `LibraryView.vue`'s own `tab` handling.
  - QA Scenario: `grep -n "const expanded" src/components/FolderList.vue` shows the declaration
    OUTSIDE the component scope; `npm run build` exits 0; the actual navigation and the new
    persistence are hands-on checks.

- [x] **T14: Now Playing gets the options menu** (reopened by F4, then fixed: `onViewInFolder` was a stub)
  - Files: `src/views/NowPlayingView.vue`.
  - An options button opening the same `TrackMenu`, using the existing `.icon-button` idiom on
    that screen — no heart (decision 7). Do NOT disturb the seek bar's `dragging`/`dragValue`
    state, the transport controls, or the drag-to-dismiss gesture — and note that `dragging` is
    already taken as an identifier in that file (a redeclaration broke the build once already;
    the sheet's own state must be named distinctly).
  - The menu must be dismissible without triggering the drag-to-dismiss on the screen behind it:
    add its root to `useVerticalDismiss`'s `ignore` selector (currently
    `'.seek-input, .icon-button, .play'`).
  - QA Scenario: `grep -n "ignore:" src/views/NowPlayingView.vue` shows the sheet's selector added;
    SFC compiles via `@vue/compiler-sfc`; `npm run build` exits 0.

- [x] **T15: Strings**
  - Files: `src/i18n/en.js`, `src/i18n/el.js`.
  - Every `playlists.*` and `trackMenu.*` key in BOTH files, in the same order in each. Greek must
    read naturally — it is the user's own language, no calques.
  - QA Scenario: extract and diff the key sets of both files — they must be identical;
    `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` is empty.

### Phase D — QA

- [x] **T16: Scripted QA additions (written, NOT run)**
  - Files: `scripts/qa-playback.sh`.
  - Append a section in the existing style (`pass`/`fail`/`step` at `:37-40`, the `js()` CDP helper
    at `:106`, `launch_app` at `:120-128`) covering: the fifth tab appears and is reachable; a
    favourite survives `am force-stop` + relaunch; a created playlist appears with the right
    count; a reorder persists; the options sheet opens and closes. Drive everything through the
    app's own JS bridge and DOM, never `input tap` with pixel coordinates.
  - **Every check must be able to FAIL.** A check that re-reads a value without performing an
    action is worse than no check — that exact placebo pattern was caught in this project's last
    plan and had to be rewritten.
  - The section is authored only. It CANNOT be executed: the user withdrew device access and the
    emulator is retired. Mark it in the script's own comment and in the notepad as never run.
  - QA Scenario: `bash -n scripts/qa-playback.sh` exits 0; `git diff scripts/qa-playback.sh | grep -c '^-'`
    prints `0` (pure addition — no existing check weakened, Must NOT Have 9).

## Final Verification Wave

- [x] **F1: Build integrity** — `npm run build` exits 0; `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'`
  prints `0`; `npm run apk` prints `BUILD SUCCESSFUL`. Record the debug APK size and compare it to
  the 61M baseline — Room's artifacts should add little, and a surprise is worth investigating.
- [x] **F2: Contract integrity** — every new plugin method exists in all four places
  (`PlayerPlugin.kt`, `player.js` JSDoc, `player.web.js` mock, and its caller in
  `usePlaylists.js`). `grep` each name across those files; a method missing from the mock breaks
  `npm run dev` and any offline QA.
- [x] **F3: i18n integrity** — the `playlists.*` / `trackMenu.*` key sets in `en.js` and `el.js`
  are identical, and `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("`
  is empty.
- [x] **F4: Scope review of the complete diff** — REJECTED on first pass, then fixed and
  re-verified. It found "view in folder" doing nothing from Now Playing (a stub handler whose
  completion each of T13 and T14 had documented as the other's job) and the options sheet never
  closing on that action, hiding the result behind its own fixed backdrop. Both fixed: the sheet
  now closes itself centrally in `TrackMenu.vue` (one unskippable call site, since the bug WAS a
  forgotten call in one of three copies), and Now Playing's handler does the real
  expand/tab/navigate work. Also fixed a falsehood in the app's own UI that F4 called
  non-blocking: `settings.about.body` still claimed "no network, no database". The re-verification
  was done by the orchestrator against F4's specific findings (grep + reading the diffs + build),
  NOT by a second full review pass — the user asked to finish quickly and the findings were
  precise enough to check exactly. Original criterion below.
  <!-- original: against Must Have and Must NOT Have by an
  independent reviewer, including:
  `find . -name "*.ts" -not -path "./node_modules/*" -not -path "./android/*" -not -path "./src/potoken/*" -not -name "vite.potoken.config.ts" | wc -l`
  prints `0`; `grep -rn "IS_FAVORITE\|createFavoriteRequest" android/ src/ --include=*.kt --include=*.js --include=*.vue | wc -l`
  prints `0`; `grep -rn "pinia\|sqlite\|@capacitor/filesystem" package.json | wc -l` prints `0`;
  `grep -rn "kefi.player" android/app/src/main/java/com/kefi/player/library/ | wc -l` prints `0`;
  and the four existing library tabs plus search, swipe and artwork still behave as before
  (read the diff for regressions — this plan restructures a component every tab uses). -->
- [x] **F5: Hands-on QA by the user (DEVICE-ONLY)** — **PASSED on the user's connected device,
  2026-08-21.** The user reported testing all device scenarios successfully, including the
  things no static check can cover: the 5-tab strip on a real 360px screen; whether the options
  button crowds the row at 360px; whether Favourite being two taps deep in the menu (decision 7)
  feels right in daily use; drag-to-reorder feel; a favourite surviving an app restart; Recently added being
  meaningful on their library; setting a real ringtone and a real notification tone; and "view in
  folder" landing on the right folder.
- [x] **F6: Documentation** — `README.md` gains a Playlists section: the smart lists and what
  "Recently added" means (newest 15 by `DATE_ADDED`, which is when MediaStore indexed the file,
  not when the user acquired it), that playlists and favourites live in a Room/SQLite database
  keyed by relative path, and the accepted consequence that moving or renaming a file loses its
  membership.

## Success Criteria

1. `cd /mnt/X/myProjects/kefi-player && npm run build` exits `0`.
2. `cd /mnt/X/myProjects/kefi-player/android && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.
3. `cd /mnt/X/myProjects/kefi-player && npm run apk` prints `BUILD SUCCESSFUL`.
4. `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` prints nothing.
5. `find . -name "*.ts" -not -path "./node_modules/*" -not -path "./android/*" -not -path "./src/potoken/*" -not -name "vite.potoken.config.ts" | wc -l` prints `0`.
6. `grep -rn "IS_FAVORITE\|createFavoriteRequest\|pinia\|@capacitor/filesystem" src/ android/app/src/main/java/ package.json | wc -l` prints `0`.
7. `bash -n scripts/qa-playback.sh` exits `0` and `git diff scripts/qa-playback.sh | grep -c '^-'` prints `0`.
8. F4 returns APPROVE.
9. F5 is reported honestly as not run (device access withdrawn) unless the user reports back.
