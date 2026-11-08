# Decisions (taken with the user, 2026-08-20)

1. User-created playlists ARE in scope, not just smart lists.
2. A real database owns playlists + favourites, Kotlin-only behind the plugin bridge.
   Room preferred; a hand-written SQLiteOpenHelper is the PRE-AUTHORISED fallback if KSP
   fights Capacitor's generated Gradle build (this build has never had an annotation processor).
3. Identity = the file's relative path (`folder + '/' + displayName`), NOT the MediaStore id.
   Accepted consequence: membership survives a rescan, dies on a move or rename. No content
   hashing to chase it.
4. Recently added = the newest 15 by DATE_ADDED, ties broken by the shared collator on title.
5. Full CRUD including drag-to-reorder (user playlists only; smart lists have a defined order).
6. An options button on every track row AND on Now Playing. Menu: Favourite/Unfavourite,
   Add to playlist, Set as ringtone, Set as notification, View in folder.
7. NO favourite heart anywhere — the user said "no one tap favorote, add it in the menu".
   So a row gains exactly ONE new control.
8. The Playlists tab is a list of lists; tapping one opens a detail screen.
9. T2 verdict: **OUTCOME A — Room via kapt, not KSP, not the SQLiteOpenHelper fallback.** No KSP
   release exists for this project's Kotlin 2.2.10 (confirmed against Maven Central: KSP jumped
   from 2.2.0 straight to a decoupled 2.3.x scheme targeting Kotlin 2.3, skipping
   2.2.10/2.2.20/2.2.21 entirely), but kapt ships inside `org.jetbrains.kotlin:kotlin-gradle-plugin`
   already on this project's buildscript classpath, so it has no separate version to reconcile
   against Kotlin — the problem that blocked KSP does not apply to kapt. `androidx.room:room-runtime`
   `2.8.4` + `room-ktx` `2.8.4` are normal dependencies; `androidx.room:room-compiler:2.8.4` is
   wired through `kapt(...)`. T3 builds `LibraryDatabase.kt`/`Playlist.kt`/`PlaylistItem.kt`/
   `FavoriteTrack.kt`/`LibraryDao.kt` as real Room entities/DAO/`@Database`, NOT a
   `SQLiteOpenHelper`. Full diagnosis (KSP ruled out, then kapt verified) in `learnings.md` under
   `## T2`.

## Orchestrator scope split, T5 vs T12 (2026-08-20)

The plan gives T12 (ringtone/notification) a JS contract entry + web mock in
`src/plugins/player.js` / `player.web.js` — the same two files T5 owns. To keep one writer per
file, **T5 also lands `setAsRingtone`'s contract entry and mock**, and **T12 is native-only**
(`PlayerPlugin.kt` + `AndroidManifest.xml`). Same end state, no concurrent edits.

Likewise: exactly ONE task may write `src/i18n/*` (T15). Every other task records the keys it
needs, with proposed EN/EL strings, in `learnings.md` under its own heading. T7 has already
recorded `trackMenu.open` this way.

## Orchestrator: who mounts TrackMenu (2026-08-21)

T8 builds `TrackMenu.vue` plus the shared "which track's menu is open" state, and records a
mounting recipe in `learnings.md`. It does NOT mount it anywhere, because the views it would mount
into (`LibraryView.vue`, `PlaylistView.vue`) are owned by T9 and T10 in the same batch.

A later WIRING pass mounts it in all three consumers at once (Songs tab, Folders' nested rows,
and the playlist detail screen) and hooks the menu's actions up. Folded into T13, which already
owns `LibraryView.vue` + `FolderList.vue` for view-in-folder. Until that pass lands, the kebab
button emits `options` into nothing — expected, not a defect.

## Orchestrator: how the wiring pass is split (2026-08-21)

T8's sheet needs mounting in three places and the hardware-back seam needs a guard. To keep one
writer per file, the wiring is split by FILE rather than by feature:

- **T11** owns `src/views/PlaylistView.vue`: drag-to-reorder AND mounting `TrackMenu` there.
- **T13** owns `src/views/LibraryView.vue`, `src/components/FolderList.vue`, `src/router/index.js`:
  view-in-folder navigation, mounting `TrackMenu` for the Songs tab, forwarding `options` from
  the Folders drill-down, and the 4-line `wireHardwareBack` guard T8 documented (close the sheet
  and stop, instead of also letting the router navigate — Capacitor fires every `backButton`
  listener, so a second listener cannot pre-empt the existing one).
- **T14** owns `src/views/NowPlayingView.vue`: its options button plus its own mount.
