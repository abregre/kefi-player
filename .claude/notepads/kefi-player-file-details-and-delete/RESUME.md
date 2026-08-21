# RESUME — file details and delete from device

Paused by the user 2026-08-21, mid-execution. Plan:
`.claude/plans/kefi-player-file-details-and-delete.md`. Resume with
`/start-work .claude/plans/kefi-player-file-details-and-delete.md`. The ticked checkboxes in that
file are ground truth; this file only explains the state around them.

## Where the work stands: 3 of 16 ticked, tree clean and compiling

- **T1** (`mimeType` on the track contract) — done, verified against the diff.
- **T2** (`getFileDetails({ uri })` — MIME/bitrate/sample-rate/absolute-path, on demand) — done,
  verified against the diff.
- **T4** (the IntentSender-launching mechanism, `MainActivity.java`) — done. This is the plan's
  named highest-risk task. It initially FAILED a real `compileDebugJavaWithJavac` run (an
  "illegal forward reference" — a field initializer's lambda read a sibling field declared later
  in the source; Java's rule is purely textual, independent of when the lambda actually runs).
  Fixed by reordering two field declarations, no logic change, re-verified by the orchestrator
  independently with a real compile.
- Two agents were dispatched for the next batch (**T3**: the details step in `TrackMenu.vue`;
  **T5**: `deleteTrack` native implementation) and were STOPPED by the orchestrator when the user
  asked to pause, before either wrote anything. Confirmed by reading the diff: `TrackMenu.vue` is
  completely untouched; `PlayerPlugin.kt`'s diff is exactly T1+T2's 93 lines, no `deleteTrack`
  anywhere. **Nothing half-written exists.** `./gradlew :app:compileDebugKotlin
  :app:compileDebugJavaWithJavac` is green right now.

## What's next, in order

1. **T3 and T5 in parallel** (re-dispatch — the prompts used are worth reusing near-verbatim, they
   were correct, just interrupted before any output):
   - T3: the "File details" step in `TrackMenu.vue`, reading `activeTrack` for the free fields
     and calling `getFileDetails` on entry for the on-demand ones. Must NOT edit the locale
     files — record keys with EN/EL text in the notepad instead (T8 is sole writer).
   - T5: `deleteTrack({ uri })` in `PlayerPlugin.kt` — three `Build.VERSION.SDK_INT` branches
     (< 29 plain delete, == 29 catch `RecoverableSecurityException` + retry on approval, >= 30
     `createDeleteRequest` + no retry). Must call T4's `MainActivity.launchIntentSenderForResult`
     exactly as documented in `learnings.md`'s `## T4` section — that section is T5's
     specification. T5 is explicitly PERMITTED to compile itself once (the one exception to the
     no-build rule in this plan), since it's the first real consumer of T4's brand-new mechanism
     and a coroutine/callback-bridging bug would otherwise only surface at the orchestrator's
     next checkpoint.
   - These two don't conflict (T3: `TrackMenu.vue`+notepad; T5: `PlayerPlugin.kt`).
2. **T6** (JS contract + web mock for `deleteTrack`) — after T5, same-file conflict
   (`player.js`/`player.web.js`, which T1/T2 also touched — all sequential on that file family).
3. **T7** (wire "Delete from device" into `TrackMenu.vue`) — after T6 AND after T3 (same file,
   `TrackMenu.vue`). Must NOT edit locale files either.
4. **T8/T9/T10 in parallel** (Strings — sole writer of `src/i18n/*`; QA additions to
   `qa-playback.sh` — written only, cannot be executed this session; README) — after T7.
5. **F1–F6** — the Final Verification Wave. F5 is the user's; nothing in this plan can be run on
   real hardware this session.

## The rules that shaped every decision here (same as the last three plans on this project)

- No emulator, no `adb` against the user's phone, no release builds — standing instructions.
  The only automatable gates are `npm run build`, `./gradlew :app:compileDebugKotlin`,
  `npm run apk`, greps, and reading the diff.
- Only the orchestrator runs builds, with every agent idle.
- One writer per file, per batch — enforced by hand where the plan's own task list doesn't spell
  it out. `decisions.md` records this plan's two resolutions: T1→T2→T5→T6 are strictly sequential
  (all touch `PlayerPlugin.kt`/`player.js`/`player.web.js`); T8 is the sole writer of
  `src/i18n/*`, with T3/T7 recording their needed keys in the notepad instead of editing the
  locale files directly (despite the plan's literal file list naming them).

## What review has already caught in this plan (why the diff-reading is not optional)

Two real defects surfaced so far, neither from a self-report claiming success:

1. **T4's mechanism failed a real Java compile** (`compileDebugJavaWithJavac`) even though
   `compileDebugKotlin` alone passed and the agent's own manual trace read as correct — because
   nothing in Kotlin calls into the new `MainActivity` method yet, so only the Java-specific
   compile task exercised the file at all. Lesson recorded in `learnings.md`'s `## T4`: never
   trust one compile task to stand in for "the whole tree compiles," especially for a file no
   other language's compiler touches yet.
2. **The orchestrator's own bookkeeping slipped once**: T2 was verified against the diff and
   announced as done, but the checkbox edit was never actually run — caught and fixed a few
   turns later by re-deriving the tick count from the plan file itself rather than trusting the
   running tally. Re-derive the `[x]` count from the plan file at every checkpoint; don't just
   increment a number in your head.

## Metadata editing — still explicitly out of scope

If anyone (including a future version of this same session) is tempted to add an edit-tags
feature while resuming this plan: don't. See the plan's own Context section — `ContentResolver
.update()` cannot change `TITLE`/`ARTIST`/`ALBUM`/`ALBUM_ARTIST`/`YEAR`/`TRACK` for any file, ever,
confirmed by reading AOSP's `MediaProvider.java` source directly. This was a deliberate user
decision to drop the feature, not an oversight to "helpfully" complete.
