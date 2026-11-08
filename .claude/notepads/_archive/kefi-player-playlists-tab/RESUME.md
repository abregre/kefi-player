# RESUME — the Playlists tab

Checkpoint written 2026-08-21. Plan: `.claude/plans/kefi-player-playlists-tab.md`.
Resume with `/start-work .claude/plans/kefi-player-playlists-tab.md`. The ticked checkboxes in
that file are ground truth; this file only explains the state around them.

## Where the work stands: 20 of 22 ticked — implementation COMPLETE

**Every implementation task (T1-T16) is done, and three of the four automatable gates pass.**

- **T15 (strings) landed**: 138 keys, identical set AND order in both locales, all 134 keys the
  code references present, hardcoded-text gate empty. It also found a key my "authoritative" list
  had missed — `library.tab.playlists`, invisible to static extraction because
  `LibraryView.vue:337` builds it as `t(\`library.tab.${name}\`)`. It swept for other
  template-literal keys and found the two that exist (`settings.language.*`, `player.repeat.*`)
  already had entries.
- **T16 (QA) landed**: 35 new pass/fail sites, `bash -n` clean, zero deletions (pure addition).
  **NEVER EXECUTED** — device access withdrawn, emulator retired. Its reorder check is honest
  about its limit: CDP cannot synthesise a timed long-press-drag, so it calls the same plugin
  method `onDragCommit` calls and says in a comment that it proves persistence, not the gesture.
- **F1 passes**: `npm run build` clean, `compileDebugKotlin` 0 errors, `npm run apk`
  BUILD SUCCESSFUL, debug APK **61M — unchanged from the pre-feature baseline**, so Room + kapt
  cost no measurable size.
- **F2 passes**: all 11 plugin methods present in 4 layers (Kotlin / JSDoc / web mock / caller).
- **F3 passes**: see T15 above.

Implementation summary, all verified by reading the diff rather than trusting reports:

- **Native/data (T1-T4, T12)**: `dateAdded` + `displayName` on the track contract (dead
  `ALBUM_ID` removed); Room 2.8.4 via **kapt**; `kefi-library.db` v1 with three tables and its
  exported schema JSON; the DAO; ten `@PluginMethod`s (18 -> 28); `setAsRingtone` +
  `WRITE_SETTINGS`.
- **Contract/state (T5, T6)**: eleven documented methods, a real in-memory web mock, and
  `usePlaylists.js` with `pathOf` as the single path derivation.
- **UI (T7-T11, T13-T16)**: `TrackRow` restructured; `TrackMenu.vue` + `useTrackMenu.js`; the
  fifth tab + `PlaylistList.vue`; `/playlist/:id` + guard; drag-to-reorder via
  `useDragReorder.js`; view-in-folder; the sheet mounted in all three consumers; Now Playing's
  options button; all strings; the QA section.
- **F6**: README documents the feature and amends three now-false claims in older text.

## What is actually left

- **F4** — independent scope review of the full diff. Was dispatched at checkpoint time; if its
  verdict is not recorded below, re-run it. Any REJECT is fixed by delegation, then re-reviewed.
- **F5 — the user's, and the only real unknown.** NOTHING in this plan has been run on hardware.
  Specifically unfelt: the drag-reorder thresholds (400 ms hold, 10 px slop), whether the sheet
  and Now Playing's dismiss gesture fight each other, whether five tabs still fit a 360px strip,
  the ringtone permission round trip, and whether a playlist survives a real app restart.
- **The commit.** 29 files uncommitted, deliberately. Includes a USER hand-edit adding a 192 kbps
  bitrate to the downloader (`src/plugins/ytdlp.js`, `src/views/DownloaderView.vue`) — verified
  harmless: native passes the bitrate straight to `--audio-quality` and `"192"` was already its
  own default. Split it from the feature work when committing, as with the previous plan.

## The rules that shaped every decision here

- **No emulator. No `adb`. No release builds.** The user withdrew all three
  ("stamata na anoigeis ton emulator", "stop testing on my mobile device", "no release apk, it is
  not finished"). The only gates are `npm run build`, `./gradlew :app:compileDebugKotlin`,
  `npm run apk`, greps, and reading the diff.
- **Only the orchestrator builds, with every agent idle.** Parallel builds corrupt each other.
- **One writer per file, per batch.** Enforced by hand; the splits are recorded in `decisions.md`
  (T5 took T12's JS contract; T15 owns both locale files; the sheet-mounting was split by file
  across T11/T13/T14).
- Agents verify with `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`),
  `node --check`, `bash -n`, and greps — never a build.

## What review actually caught (why the diff-reading is not optional)

Five agent claims failed verification in this plan:

1. **T3 claimed "every `androidx.room.*` annotation has a matching import — checked per file."**
   False for `FavoriteTrack.kt`. Kotlin does not error on an unresolved annotation, so `@PrimaryKey`
   was silently degraded and `favorites` would have shipped with NO primary key. Only
   `kaptDebugKotlin`'s javac pass on the generated stub failed, with
   `@error.NonExistentClass`. **That string always means a missing import, never a Room bug.**
2. **T3's `appendToPlaylist` was not idempotent** while the shipped JS contract
   (`player.js:102`) promised it was — duplicate rows, and `deleteItem` would have removed both at
   once. Fixed with a UNIQUE `(playlistId, path)` index plus an in-transaction filter.
3. **T11's reorder silently deleted unresolved paths.** A playlist with a renamed file, reordered
   by dragging an unrelated row, permanently lost that entry. Fixed by `reorderPlaylistPaths`
   carrying the unresolved tail. The five-path trace is in `learnings.md`'s `## T11`.
4. **T2's first verdict was "fall back to SQLiteOpenHelper"** on solid KSP evidence — but it had not
   considered kapt, which ships inside the Kotlin plugin already on the classpath. One resume got
   Room, which is what the user asked for.
5. **T13 revealed a plan error of mine**: I wrote that module-level state was "already the
   established pattern in `LibraryView.vue`'s own `tab` handling". It was not — `tab` was
   per-instance, as was `FolderList`'s `expanded` despite its comment. Both are now module-level.

## Open items for the user, not blockers

- **Two behaviour changes need their blessing** (both in `learnings.md` `## T13`, both in the
  README): folder expansion now survives leaving the Library (its comment always claimed this);
  and the Library's selected tab now survives navigation too, where it used to reset to Songs. The
  second was NOT in the plan — it fell out of needing to set the tab from outside the component.
  If they want the reset back, the alternative is a shared "pending folder target" that
  `LibraryView` consumes on mount.
- The Playlists tab deliberately ignores the search box, so the field sits visible and inert on
  one tab out of five (`issues.md`, T9).
- Deleting a playlist uses `window.confirm` — the only unstyled system dialog in the app. Now that
  `TrackMenu` exists, a styled confirm is a small swap (`issues.md`, T10).
- A `transform` on `NowPlayingView`'s `.page` makes it the containing block for the
  `position: fixed` sheet, so the sheet travels with the page during a drag that starts outside it.
  Fix, if they ever notice it, is `<Teleport to="body">` (`issues.md`, T14).
- Drag thresholds (400 ms hold, 10 px slop) and everything about how the sheet feels are reasoned
  defaults nobody has touched on a phone.
