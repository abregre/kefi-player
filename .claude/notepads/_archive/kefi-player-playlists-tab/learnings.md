# Learnings

Seeded by the orchestrator from the previous plan's execution. Append, never overwrite.

## Hard rules for THIS project (learned the expensive way)

- **No emulator, ever.** The user retired it: "stamata na anoigeis ton emulator, testarw apo
  android studio se alithini suskeui".
- **No `adb` against the user's phone.** They withdrew access mid-run: "stop testing on my
  mobile device". An earlier run `pm clear`ed their app data and left fixture files in their
  Music folder. Do not offer to test on hardware.
- **No release builds.** "no release apk, it is not finished".
- Therefore the ONLY automatable gates are: `npm run build`, `./gradlew :app:compileDebugKotlin`,
  `npm run apk`, greps, and reading the diff. Anything else goes to the user as a hands-on item
  and is reported NOT RUN — never ticked because the code looks right.

## Verification tricks that actually caught bugs

- **`@vue/compiler-sfc` via `node -e`** (`parse` → `compileScript` → `compileTemplate`) catches
  what re-reading a diff cannot. An agent last session reported "bindings all resolve" on a file
  that failed the build with `Identifier 'dragging' has already been declared`. `compileScript`
  is the step that raises it. Use it whenever builds are serialised and you cannot run one.
- **`bash -n`** for shell scripts.
- Six agent claims across this project have failed review. Read the diff, not the report.

## Conventions that are non-negotiable here

- Plain JS + JSDoc. NO TypeScript (only `src/potoken/index.ts` + `vite.potoken.config.ts`).
- Every user-facing string through `t()`, present in BOTH `src/i18n/en.js` and `el.js`.
  The gate: `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("`
  must be empty. Greek must read naturally — it is the user's own language.
- Module-level composables, `readonly()` at the return boundary — `usePlayer.js:8-32` and
  `:266-293` are the pattern of record.
- Icon buttons are 2.4-2.6rem with ~1.4rem glyphs (`MiniPlayer.vue`'s `.toggle` is 2.6rem/1.4rem;
  `NowPlayingView.vue`'s `.icon-button` is the 2.4rem secondary variant). No `:active` state
  exists anywhere in that idiom — do not invent one.
- Comments explain WHY, never what. This codebase's comments are unusually good; match them.
- `prefers-reduced-motion` carve-outs on every animation (`MiniPlayer.vue:179-183`).

## Landmines in the files this plan touches

- `dragging` is ALREADY taken as an identifier in `NowPlayingView.vue` (the seek bar's drag
  state). A redeclaration broke the build last session. Name new state distinctly.
- `TrackRow.vue`'s root is a `<button>`; nesting a button inside it is invalid HTML. The root
  must become a `<div>` with sibling buttons.
- `FolderList.vue`'s `expanded` ref is documented as module-level but is declared INSIDE
  `<script setup>`, so folder expansion does NOT survive navigation. Pre-existing bug; T13 fixes
  it, which visibly changes existing behaviour.
- The mini-player and tab bar live OUTSIDE `<RouterView>` and are never remounted by navigation.
- `src/App.vue`'s shell is a CSS grid; grid rows do NOT clip children, so an absolutely
  positioned hit area can steal taps from the row above. Cost one review cycle last session.
- Two touch gestures already exist and disagree on purpose: `useHorizontalSwipe` is passive and
  never calls `preventDefault`; `useVerticalDismiss` is non-passive and does, gated behind a
  commitment latch. A third gesture (T11's reorder) must justify itself against BOTH.

## T1

- `ALBUM_ID` was confirmed dead before deletion: grepped both `TrackSource.kt` and
  `PlayerPlugin.kt` for `ALBUM_ID` — the only hit was the projection line itself (the artwork
  path uses per-track `_ID`, not `ALBUM_ID`, per the comment at `metadataForIds` about album-art
  grouping bugs). Removed cleanly; `grep -c ALBUM_ID TrackSource.kt` now prints `0`.
- Column-index variable names added in the `listTracks` cursor loop: `dateAddedCol` (new) and
  reused the existing `displayNameCol` — did NOT add a second lookup for `DISPLAY_NAME` since it
  was already fetched for the title fallback.
- NULL `DATE_ADDED`: `dateAdded = if (cursor.isNull(dateAddedCol)) 0L else cursor.getLong(dateAddedCol)`.
  Documented on the `Track.dateAdded` KDoc that `0` means the column came back NULL and sorts
  last (oldest) under any "newest first" sort — consumers doing `sortByDescending { dateAdded }`
  get untagged files pushed to the bottom for free, no special-casing needed downstream.
- `displayName` on `Track` falls back to `""` if `DISPLAY_NAME` is somehow null (mirrors how the
  existing `title` fallback chain already treats it as nullable).
- Mock `dateAdded` values in `player.web.js` (epoch seconds, descending = newest first):
  id 1 (`Άσπρη Μέρα`) → `1_755_680_400`, id 2 (`Zeibekiko`) → `1_750_000_000`, id 3 (`Ωδή`) →
  `1_700_000_000`. All three are distinct, so a "newest N" sort in the browser will visibly
  order them 1, 2, 3. Mock `displayName`s: `Aspri Mera.mp3`, `02 Zeibekiko.mp3`, `Odi.mp3`.

## T7

- **`useLazyArtwork` query, confirmed**: `useLazyArtwork.js:29` does
  `root.value.querySelectorAll('[data-track-id]')` (any element, no tag restriction) and reads
  `entry.target.dataset.trackId` at `:39`. It does not care whether the element is a button —
  it just needs a real, laid-out DOM node inside the observed `root`. `data-track-id` stays on
  the new root `<div class="row track-row">`, not on `.row-main`: that div is still exactly "the
  row" — its box spans the full row width (both buttons sit inside it), so the intersection the
  observer sees is unchanged from when the row itself was the `<button>`. `FolderList.vue`'s
  nested rows are still watched by no root at all — a pre-existing gap (noted in the plan), not
  touched or worsened here.
- **Structure**: root `<div class="row track-row">` (carries `:class="{ active }"` and
  `data-track-id`) → `<button class="row-main">` (art/body/duration, emits `select`, unchanged
  payload/timing) + `<button class="row-menu">` (24x24 three-dot/kebab glyph, `fill="currentColor"`,
  emits `options` with the track object). `defineEmits(['select', 'options'])`.
- **Tap targets**: `.row-menu` is 2.4rem box / 1.4rem glyph, transparent background,
  `var(--text-muted)`, `border-radius: 50%`, no `:active` rule — the icon-button convention,
  exact low end of the 2.4-2.6rem range (matches `NowPlaying.icon-button`/`MiniPlayer.side`
  rather than `.toggle`, since this is a secondary control, not the primary tap target).
  `.row-main` kept the old `.row`'s padding (`0.6rem 0.25rem`) and its `:active` background;
  `.row-menu` gets a `margin-right: 0.25rem` to mirror the old row's right inset. The row-main
  button still owns the whole tap area minus the menu button, one thumb, no dead zones.
- **i18n key**: `trackMenu.open`, used as `t('trackMenu.open')` for the menu button's
  `aria-label`. NOT added to `src/i18n/{en,el}.js` (T15 owns those files). Until T15 runs,
  `t()`'s fallback chain (`src/i18n/index.js`'s `translate`: `dictionaries[locale] ?? en[key] ??
  key`) returns the literal string `"trackMenu.open"` as the aria-label — expected, not a defect.
  Proposed strings for T15 to add:
  - `en.trackMenu.open` = `"Track options"`
  - `el.trackMenu.open` = `"Επιλογές κομματιού"`
- **`min-width: 0` needed?** Added it explicitly to the NEW `.row-main` (it is now a flex item of
  `.row`, alongside `.row-menu`, whereas before the whole row WAS the flex container). `.body`
  already had `min-width: 0` (line 213, unchanged) and is the only child carrying unbreakable
  text, so `.row-main`'s own min-content contribution should already be small without the
  override — but adding it directly on `.row-main` costs nothing and guards the exact class of
  bug `App.vue:92-107` documents, one flexbox level up from where `.body` already guards it. Not
  visually verified (no browser/build available in this session per Must Not Do); verified only
  via `@vue/compiler-sfc` compiling the SFC cleanly and by re-reading the resulting flex chain by
  hand: `.row` → `.row-main` (flex:1, min-width:0) → `.body` (flex:1, min-width:0) → `.title`
  (`white-space: nowrap; overflow: hidden; text-overflow: ellipsis`, unchanged).
- **`FolderList.vue` left untouched.** Its nested `<TrackRow>` (`:91`) only listens for `select`,
  which keeps its exact payload/timing, so nothing there breaks by TrackRow gaining an unhandled
  `options` emitter (an unlistened Vue emit is a silent no-op, not an error). Decided NOT to wire
  `@options` through `FolderList` now, even though it would be a harmless one-line addition:
  neither `FolderList` nor `LibraryView`'s Songs tab has anywhere to send an `options` event yet
  (T8's `TrackMenu` doesn't exist), so forwarding it here would be a half-connected wire with
  nothing on the other end — and T8/T13 will need to wire `options` consistently across
  `LibraryView`, `FolderList`, and `NowPlayingView` in one pass anyway. Leaving it out keeps this
  diff to exactly the file the task named.
- Verification run: `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) on
  `src/components/TrackRow.vue` — all three steps clean, no errors. `grep -c "<button"
  src/components/TrackRow.vue` → `2`. `grep -n "data-track-id" src/components/TrackRow.vue` →
  present on the root div (line 38) plus two comment mentions. Hardcoded-text gate
  (`grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/components/TrackRow.vue | grep -v "t("`) → empty.
  `FolderList.vue` not modified, so not recompiled.

## T5

- Landed 11 `@property` entries on the `PlayerPlugin` typedef in `player.js` (the 10 from T4's
  block plus T12's `setAsRingtone`, per the orchestrator's one-writer-per-file split recorded in
  `decisions.md`), inserted after `openAppSettings` and before `addListener`, grouped: playlist
  CRUD (listPlaylists, createPlaylist, renamePlaylist, deletePlaylist, addToPlaylist,
  removeFromPlaylist, reorderPlaylist, listPlaylistPaths), then favourites (listFavorites,
  setFavorite), then setAsRingtone last.
- The `path` identity note lives in exactly one place readers will hit it: on `addToPlaylist`'s
  doc (the first entry that actually carries a `paths` array) — `folder + '/' + displayName`,
  linking to `PlayerTrack.displayName` (which T1 already documents as the identity source) and
  adding the one thing that doc did NOT say: it does not survive a move or rename. Every other
  path-taking entry (`removeFromPlaylist`, `reorderPlaylist`, `listPlaylistPaths`,
  `listFavorites`, `setFavorite`) relies on that single definition rather than repeating it.
- `renamePlaylist`, `deletePlaylist`, `addToPlaylist`, `removeFromPlaylist`, `reorderPlaylist` all
  resolve `Promise<void>` (empty) — matches the plan's T4 block, which lists no return payload
  for any of them.
- **Web mock seed data** (`player.web.js`, `PlayerWeb` instance fields, in-memory only, reset on
  reload):
  - `playlists`: one playlist, `{ id: 1, name: 'Kefi Mix', paths: ['Music/kefi-qa/Aspri Mera.mp3',
    'Music/kefi-qa/02 Zeibekiko.mp3'] }` — the first two of T1's three mock tracks (Άσπρη Μέρα,
    Zeibekiko), by their real `folder + '/' + displayName`.
  - `favoritePaths`: one favourite, `'Music/kefi-qa/vol2/Odi.mp3'` — T1's third mock track (Ωδή).
    Chosen deliberately distinct from the playlist's two paths so a browser run exercises all
    three mock tracks across the two seed structures at once.
  - Verified by reading `listTracks`'s three mock objects: `folder`/`displayName` concatenation
    matches all three seeded paths exactly (`Music/kefi-qa/Aspri Mera.mp3`,
    `Music/kefi-qa/02 Zeibekiko.mp3`, `Music/kefi-qa/vol2/Odi.mp3`).
  - `nextPlaylistId = 2` (seed already took `1`); `createPlaylist` does `id =
    this.nextPlaylistId++`, so ids increase monotonically and never collide with the seed.
- **No-op vs. reject decisions** (mock must never throw, per the task): `renamePlaylist`,
  `deletePlaylist`, `addToPlaylist`, `removeFromPlaylist`, `reorderPlaylist`, `listPlaylistPaths`
  all treat an unknown playlist `id` as a silent no-op (find-and-skip, or an empty `{ paths: [] }`
  for the read), never a rejection — commented in `renamePlaylist` (the first one) that a
  rejection would surface as a UI error for a state the browser mock simply does not have (e.g. a
  stale id from a previous session). `addToPlaylist` is idempotent per path (checked with
  `includes` before pushing) so replaying it is also safe. `setAsRingtone` always resolves
  `{ granted: false }` — a browser has no ringtones — matching the native contract's
  "permission absent" branch rather than a special mock-only shape.
- Verification: `node --check src/plugins/player.js` and `node --check src/plugins/player.web.js`
  both pass. `grep -c` for the 11 method names shows each present in both files (individually
  verified per-name with `grep -c "<name>" <file>` — all counts >= 1 in both files;
  `createPlaylist` counts 2 in the mock because both the method definition and the
  `nextPlaylistId` doc comment mention it, and `listFavorites` counts 2 in `player.js` because
  `listPlaylists`'s doc comment also references it — neither is a missing-name problem).
- Files touched: `src/plugins/player.js`, `src/plugins/player.web.js` only. Did not touch
  `android/`, any `.vue` file, `src/composables/`, `src/i18n/*`, or the plan file, per the task's
  Must NOT Do.

## T12

- Native-only scope confirmed: T5 (running in parallel) owns `setAsRingtone`'s entry in
  `src/plugins/player.js` and its mock in `player.web.js` per `decisions.md`'s T5/T12 split. This
  task touched only `PlayerPlugin.kt` and `AndroidManifest.xml`.
- `@PluginMethod` count: **17 before → 18 after** (`grep -c "@PluginMethod"
  android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`).
- Import added: `android.media.RingtoneManager` only — one new line, inserted alphabetically
  between `android.content.pm.PackageManager` and `android.net.Uri`. `android.provider.Settings`
  and `android.net.Uri` were BOTH already imported (used by `openAppSettings`), so nothing else
  was added.
- Intent flags copied from `openAppSettings` (`:359-367` before this edit): `Intent.FLAG_ACTIVITY_NEW_TASK`
  only. Reason (same as that method's need): a `Plugin` is not itself an `Activity`, so starting
  one from `context.startActivity` outside an existing task requires `FLAG_ACTIVITY_NEW_TASK` or
  Android throws `AndroidRuntimeException` at launch. Followed the plan's explicit instruction to
  build the target `Uri` as `Uri.parse("package:${context.packageName}")` for
  `ACTION_MANAGE_WRITE_SETTINGS` — note this differs from `openAppSettings`'s own
  `Uri.fromParts("package", context.packageName, null)` for `ACTION_APPLICATION_DETAILS_SETTINGS`;
  both produce an equivalent `package:<name>` URI, `fromParts` vs `parse` is just two ways to
  build the same string, and `ACTION_MANAGE_WRITE_SETTINGS` is documented/commonly used with the
  `Uri.parse` form specifically, so kept that form rather than unifying it with `fromParts`.
- No SDK-version guard added on `Settings.System.canWrite` — confirmed against
  `android/variables.gradle` (`minSdkVersion = 24`); `canWrite` needs API 23+, so it is
  unconditionally available at this app's minSdk. No existing guard elsewhere in the file was
  touched (e.g. `Build.VERSION_CODES.TIRAMISU` check in `audioPermissionAlias()` is untouched).
- Validation order, matching `setShareTargetEnabled`'s style: reject on missing/blank `uri` first
  (`isNullOrBlank()`), then reject on a `type` that maps to neither `RingtoneManager.TYPE_RINGTONE`
  nor `TYPE_NOTIFICATION` (a `when` with `else -> null`, checked with `== null`) — BOTH validation
  failures are `call.reject(...)`, never resolved, since they are caller/programmer errors, unlike
  the `canWrite() == false` case which is a legitimate runtime state resolved as `{ granted: false }`.
- `RingtoneManager.setActualDefaultRingtoneUri`'s known failure modes for the UI task (T8/T14) to
  handle downstream: it can throw (e.g. a `SecurityException` if `canWrite` was true at check time
  but the OS revoked it before the call, or an `IllegalArgumentException`-class failure for a
  content URI the app cannot actually read/open a stream from — e.g. a `uri` for a file that has
  since been deleted or moved, which decision 3's path-keying already accepts as a known
  limitation). All such failures are caught in a `try/catch (t: Throwable)` and surfaced via the
  file's existing `rejectWith` helper (`PluginCall.reject` only accepts `Exception`, and a
  coroutine-style failure here can arrive as a bare `Throwable`) — so the UI should treat any
  `setAsRingtone` REJECTION as "genuinely failed, show an error", and a RESOLVED
  `{ granted: false }` as "not yet permitted, offer to open Settings" (Settings was already
  launched automatically in that branch).
- Method placed directly after `setShareTargetEnabled`, immediately before the `companion object`
  block — grouped with the other system-integration methods (`requestWidgetPin`, `openAppSettings`,
  `setShareTargetEnabled`) per the plan's placement guidance, not wedged into the playback block,
  and not disturbing T1's `toJSObject()` changes at the bottom of the file.
- Verification run (no Gradle, no adb, per Must Not Do): `grep -n "WRITE_SETTINGS"` on the
  manifest — present with its WHY comment; `grep -n "canWrite\|setActualDefaultRingtoneUri\|
  ACTION_MANAGE_WRITE_SETTINGS"` on `PlayerPlugin.kt` — all three present; `@PluginMethod` count
  17→18; brace balance on the whole file via a quick Python count — 106 open / 106 close, matched.
  Compilation itself (`compileDebugKotlin`) is the orchestrator's job per this batch's build
  serialization rule — not run here.

## T2

**OUTCOME A — Room via kapt.** (Revised from an initial Outcome B after the orchestrator
pointed out kapt as a second annotation-processor path — see "kapt" subsection below. The KSP
diagnosis that follows is kept in full: it is the reason kapt was chosen over KSP, and it stands
as the record for why nobody should try wiring KSP here again without a materially different
Kotlin version.)

- **Versions found** (exactly as asked, file:line):
  - Kotlin: `ext.kotlinVersion = '2.2.10'` — `android/build.gradle:4`.
  - AGP: `classpath 'com.android.tools.build:gradle:8.13.0'` — `android/build.gradle:11`.
  - `compileSdkVersion = 36` / `minSdkVersion = 24` — `android/variables.gradle:3,2`.
- **Local Gradle cache is empty for both**: `ls ~/.gradle/caches/modules-2/files-2.1/ | grep -iE
  "room|ksp|symbol-processing"` and the broader `find ~/.gradle/caches -iname "*ksp*"` /
  `*room*` and `find ~/.m2 -iname "*ksp*"` all returned nothing. Neither `~/.gradle` nor `~/.m2`
  has ever fetched Room or KSP on this machine — a first build would need live network access to
  `google()`/`mavenCentral()`, which is itself a second risk layered on top of the one below.
- **Which Gradle files are Capacitor-generated (do not hand-edit, ever)**:
  `android/app/capacitor.build.gradle` and `android/capacitor.settings.gradle` both carry
  `// DO NOT EDIT THIS FILE! IT IS GENERATED EACH TIME "capacitor update" IS RUN` as their first
  line. Hand-written files are `android/build.gradle`, `android/app/build.gradle`,
  `android/variables.gradle`, `android/settings.gradle` — those are where any future Room/KSP
  attempt (or the SQLiteOpenHelper fallback) belongs.
- **The one honest attempt, and why it stopped**: drafted the idiomatic wiring for this project
  (buildscript-classpath style, matching how AGP/Kotlin are already applied here — no
  `plugins{}` DSL is used anywhere in this build, so KSP was added the same way: `ext.kspVersion`
  next to `ext.kotlinVersion` in `android/build.gradle`'s buildscript block, `classpath
  "com.google.devtools.ksp:symbol-processing-gradle-plugin:$kspVersion"`, then `apply plugin:
  'com.google.devtools.ksp'` in `android/app/build.gradle`, `roomVersion` added to
  `android/variables.gradle` alongside `media3Version`/`youtubedlAndroidVersion`, and
  `room-runtime`/`room-ktx`/`ksp("androidx.room:room-compiler")` added to the app's
  `dependencies {}` block). Before leaving that in, verified whether the KSP version actually
  exists — **it does not.**
  - Direct Maven Central Solr API queries (`search.maven.org/solrsearch/select`, `core=gav`) for
    `g:com.google.devtools.ksp AND a:symbol-processing-gradle-plugin AND v:2.2.10-2.0.2` →
    `numFound: 0`. Wildcard `v:2.2.10*` → `0`. Wildcard `v:2.2.2*` (would catch 2.2.20, 2.2.21) →
    `0`. Repeated against the plugin-marker artifact (`a:com.google.devtools.ksp.gradle.plugin`)
    with the same three queries → `0` every time. A known-good control query
    (`v:2.1.20*`) correctly returned 11 real versions, so the wildcard mechanism itself works —
    the absence for 2.2.10/2.2.1x/2.2.2x is real, not a query bug.
  - The full version list for this artifact (100 rows fetched) shows the old `<kotlin>-<ksp>`
    naming scheme's last entries are the `2.2.0-*` releases (`2.2.0-2.0.2` being the newest), then
    it jumps straight to bare, kotlin-version-decoupled releases: `2.3.7`, `2.3.8`, `2.3.9`, etc.
    **KSP never published a build pinned to Kotlin 2.2.10, 2.2.20, or 2.2.21** — it skipped from
    2.2.0 straight to the new independent 2.3.x versioning scheme. This is corroborated by
    `github.com/google/ksp` issues: #2619 ("ksp-2.2.0-2.0.2 is too old for kotlin-2.2.20") and
    #2669 ("Support Kotlin 2.2.21", closed, KSP 2.3.0+ moved to K2-only/decoupled versions).
  - This leaves two real options for Kotlin 2.2.10, both bad:
    1. **`2.2.0-2.0.2`** (one Kotlin feature-release behind) — KSP enforces a strict version
       check; issue #2619 shows the "KSP older than Kotlin" direction is a hard **build-failing
       error** ("Please upgrade ksp or downgrade kotlin-gradle-plugin"), not a warning. 2.2.10 is
       newer than 2.2.0 in exactly the same way 2.2.20 was in that issue, so this would very
       likely hard-fail the same way.
    2. **A 2.3.x release** (e.g. `2.3.7`) — decoupled versioning "targets Kotlin 2.3" per its own
       release notes. Issue #2375 shows the *opposite* mismatch direction ("KSP too new for an
       older Kotlin") is only a **warning**, not a hard error, which is more promising — but
       whether K2 compiler-plugin ABI compatibility actually holds three feature-releases back
       (2.3.x KSP against a 2.2.10 compiler) is unverifiable from documentation alone, and I
       cannot invoke Gradle to find out empirically (forbidden by this task, and the Kotlin
       version itself is also off-limits to bump per Must Not Do).
  - Given neither KSP path has a confirmed-safe version, and confirming either requires a Gradle
    invocation this task explicitly forbids, KSP alone met the "cannot be made to work in a
    reasonable attempt" bar the plan pre-authorised the fallback for. At this point in the task I
    reverted all three edits with `git checkout --` and drafted an Outcome B (SQLiteOpenHelper)
    verdict. **The orchestrator then pointed out kapt as a second, real annotation-processor
    path that does not share KSP's blocker — see the "kapt" subsection below, which supersedes
    the SQLiteOpenHelper recommendation.** KSP itself remains ruled out for exactly the reasons
    above; nobody should re-attempt KSP here without a materially different Kotlin version.

## kapt (Outcome A — the path actually used)

- **Why kapt sidesteps the whole KSP problem**: KSP is a separate Gradle plugin artifact
  (`com.google.devtools.ksp:...`) versioned as `<kotlin-version>-<ksp-revision>`, which is exactly
  what had no build for 2.2.10. kapt is a *feature of* `org.jetbrains.kotlin:kotlin-gradle-plugin`
  itself — the same artifact already on this project's buildscript classpath at
  `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10` (`android/build.gradle:13`, unchanged). There
  is no second version to reconcile against Kotlin, so the entire failure mode above cannot occur.
- **Confirmed kapt still works, isn't removed**: `developer.android.com/build/migrate-to-ksp`
  states kapt is "now in maintenance mode" (recommended against for speed, ~2x slower since it
  generates Java stubs for the whole module) but explicitly still functional — "You can run kapt
  and KSP alongside each other in your project while you're migrating" — and names Room by name as
  a library that ships both a kapt processor and a KSP processor in the same artifact.
- **The one real risk investigated and ruled out**: `developer.android.com/agents/skills/build-system/agp/agp-9-upgrade/references/ksp-kapt`
  documents `org.jetbrains.kotlin.kapt` as "incompatible with **built-in Kotlin**" — but "built-in
  Kotlin" is a new AGP **9.0** feature (Jan 2026) that embeds the Kotlin compiler into AGP,
  replacing the standalone `kotlin-gradle-plugin` classpath dependency. This project is on AGP
  `8.13.0` (`android/build.gradle:11`, well before AGP 9) using the classic standalone
  `kotlin-gradle-plugin` classpath entry, i.e. exactly the setup kapt was designed for — the
  incompatibility does not apply here.
- **Room version chosen: `2.8.4`**, verified against the *authoritative* index for `androidx.*`
  artifacts — Google's Maven repo group-index XML, not Maven Central's search (which does not
  index `androidx.*` groups at all: confirmed by querying `search.maven.org/solrsearch` for
  `g:androidx.room AND a:room-runtime` and `g:androidx.core AND a:core-ktx`, both `numFound: 0`,
  proving the index gap rather than the artifacts' absence). Fetched
  `https://dl.google.com/android/maven2/androidx/room/group-index.xml` directly: `room-runtime`,
  `room-compiler`, and `room-ktx` each list versions from `2.0.0-alpha1` through `2.8.4`, with
  `2.8.4` the newest stable release across all three, matching (androidx developer docs also state
  2.8.4, released 2025-11-19; Room 2.x is now in maintenance mode with only patch releases, ahead
  of an in-progress Room 3.0 KMP rewrite that requires KSP only and lives under a different group,
  `androidx.room3` — irrelevant here since we're deliberately staying on the 2.x line).
  `room-compiler:2.8.4` still generates Java stubs for kapt (not KSP-only) per the migrate-to-ksp
  page's "For KAPT or Java only projects, Room will still generate Java sources."
- **Diff landed** (all four hand-written files; none of the Capacitor-generated ones touched):
  - `android/variables.gradle`: added `roomVersion = '2.8.4'` next to `media3Version`/
    `youtubedlAndroidVersion`.
  - `android/app/build.gradle`: `apply plugin: 'org.jetbrains.kotlin.kapt'` added right after the
    existing `apply plugin: 'org.jetbrains.kotlin.android'` (full-id style, matching how this file
    already names Kotlin plugins — no short alias). `implementation
    "androidx.room:room-runtime:$roomVersion"`, `implementation
    "androidx.room:room-ktx:$roomVersion"`, and `kapt "androidx.room:room-compiler:$roomVersion"`
    added to the existing `dependencies {}` block, grouped right after the coroutines line. A
    `kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }` block added after
    `dependencies {}` and before `apply from: 'capacitor.build.gradle'` — this both silences Room's
    "no exportSchema location" build warning and gives T3 a real migration-testing schema dump at
    `android/app/schemas`, so T3 does NOT need to set `exportSchema = false` on `@Database`.
  - `android/build.gradle`: **no change** — unlike KSP, kapt needed no new `classpath` entry.
  - `android/.gitignore`: added `app/schemas` (with a comment), same precedent as the existing
    `app/src/main/assets/potoken` entry — generated output, never committed.
- **Expected APK-size impact**: Room's runtime (`room-runtime`/`room-ktx`) adds a modest amount of
  code to the APK (Room itself is fairly small; most of its size cost is generated code + SQLite
  bindings, which are already present via the Android platform's `android.database.sqlite`). Given
  the 61M debug baseline, expect a small, non-alarming bump — worth F1 noting but not a red flag.
  `room-compiler` is `kapt`-only (annotation processing time, not packaged into the APK).
- Verification performed (no Gradle, per Must Not Do): re-read the full edited `android/app/build.gradle`
  for brace balance and placement by eye; confirmed `android/build.gradle` and
  `android/variables.gradle` diffs are minimal single-line/single-block additions; `git diff`
  reviewed on all four touched files before finishing.

## F1-partial: orchestrator's serialized build after batch 1+2 (2026-08-20)

Run with every agent idle. `cd android && ./gradlew :app:compileDebugKotlin` → **BUILD SUCCESSFUL**,
33s. Proof of several things at once:

- **kapt really applies.** `:app:kaptGenerateStubsDebugKotlin` and `:app:kaptDebugKotlin` both
  EXECUTED (not skipped). T2's Outcome A is confirmed by a real build, not by reasoning.
- **Room 2.8.4 resolves** from Google's Maven (`dl.google.com/.../androidx/room/group-index.xml`
  was the right index to check — Maven Central's search does not cover `androidx.*` at all, which
  is a trap worth remembering: a `numFound: 0` there means nothing).
- T1's `TrackSource.kt` (`DATE_ADDED`, `displayName`, `ALBUM_ID` removed) and T12's
  `setAsRingtone` + `WRITE_SETTINGS` manifest entry compile clean.

**Expected warning, do NOT "fix" it:**
`warning: The following options were not recognized by any processor: '[room.schemaLocation, kapt.kotlin.generated]'`
There is no `@Database`/`@Entity` in the tree yet, so `room-compiler` has nothing to process and
never sees its option. It will be recognised once T3 lands the schema. Removing the
`room.schemaLocation` argument to silence it would throw away migration-test schema export.

## T3

Real Room entities/DAO/@Database, via kapt, per decisions.md item 9 — no SQLiteOpenHelper. Five
files, all new, all in `android/app/src/main/java/com/kefi/player/library/`, package
`com.kefi.player.library`:

**Post-review correction (2026-08-20)**: the first version of `appendToPlaylist` inserted every
path unconditionally, diverging from the JS contract of record (`src/plugins/player.js`'s
`addToPlaylist`: "Idempotent per path: a path already in the playlist is not appended a second
time") and its web mock (`includes()`-checked before pushing). Fixed by filtering against
existing + within-batch paths inside the same `@Transaction`, adding a UNIQUE index on
`(playlistId, path)` to `PlaylistItem` as a schema-level backstop, switching `insertItems` to
`OnConflictStrategy.IGNORE`, and defensively deduping `reorderPlaylist`'s input the same way.
The bullets below already reflect the corrected, final signatures — this note exists only so
the divergence and the fix are on record.

- **`Playlist.kt`** — `@Entity(tableName = "playlists")`: `id: Long` (`@PrimaryKey(autoGenerate =
  true)`, default `0`), `name: String`, `createdAt: Long`.
- **`PlaylistItem.kt`** — `@Entity(tableName = "playlist_items", foreignKeys = [...], indices =
  [Index(["playlistId", "position"]), Index(["playlistId", "path"], unique = true)])`: `id: Long`
  (autogen PK), `playlistId: Long`, `path: String`, `position: Int`. FK onto `Playlist::class`
  (`parentColumns = ["id"]`, `childColumns = ["playlistId"]`, `onDelete = ForeignKey.CASCADE`).
  The UNIQUE `(playlistId, path)` index enforces "at most one row per path per playlist" at the
  schema level — added post-review (see note above) so that invariant cannot be silently broken
  by a future method that forgets to filter. Does NOT need a schema version bump: nothing has
  shipped, still version 1, no user has this database on disk.
- **`FavoriteTrack.kt`** — `@Entity(tableName = "favorites")`: `path: String` (`@PrimaryKey`, no
  autogen — it's a string), `addedAt: Long`.
- **`LibraryDao.kt`** — `@Dao interface LibraryDao`, every method `suspend`, no `Flow` anywhere.
  Exact signatures T4 codes against (this is the contract):
  - `suspend fun listPlaylistsWithCounts(): List<PlaylistWithCount>` — raw `@Query`, `LEFT JOIN
    playlist_items ON playlist_items.playlistId = playlists.id`, `COUNT(playlist_items.id) AS
    count`, `GROUP BY playlists.id`, `ORDER BY playlists.createdAt ASC`. LEFT JOIN (not INNER) so
    a zero-item playlist still appears with `count = 0` — verified by reasoning through SQL
    semantics (no Gradle available to run it): an INNER JOIN has no `playlist_items` row to match
    against for an empty playlist, so that row vanishes entirely from the result set; LEFT JOIN
    keeps the `playlists` row and `COUNT` of zero matched rows correctly yields `0`, not `1`
    (which `COUNT(*)` would wrongly give by counting the one NULL-padded joined row — using
    `COUNT(playlist_items.id)` specifically avoids that trap).
  - `suspend fun insertPlaylist(playlist: Playlist): Long` — `@Insert`, returns the new row id.
  - `suspend fun renamePlaylist(id: Long, name: String)` — `@Query("UPDATE playlists SET name =
    :name WHERE id = :id")`.
  - `suspend fun deletePlaylist(id: Long)` — `@Query("DELETE FROM playlists WHERE id = :id")`;
    items cascade-delete via the FK, no separate call needed.
  - `suspend fun appendToPlaylist(playlistId: Long, paths: List<String>)` — `@Transaction`,
    default-bodied (not abstract): **idempotent per path**, matching the JS contract exactly.
    Reads `listPlaylistPaths(playlistId)` into a `MutableSet`, then `paths.filter { seen.add(it) }`
    — this drops both a path already stored in the playlist AND a path repeated within the same
    `paths` call, keeping only the first occurrence of each. Then computes `start =
    (maxPosition(playlistId) ?: -1) + 1` and inserts only the surviving (non-duplicate) paths
    with `position = start + offset` for each, via `insertItems`. Atomic because all of it —
    the existing-paths read, the `MAX(position)` read, and the insert — runs inside one
    `@Transaction`: nothing else can insert between those reads and the write, so no race can
    reintroduce a duplicate or land two items on the same position. Empty `paths`, or a `paths`
    that is entirely duplicates of existing membership, short-circuits to a no-op.
  - `suspend fun maxPosition(playlistId: Long): Int?` and `suspend fun insertItems(items:
    List<PlaylistItem>)` — the two primitives `appendToPlaylist`/`reorderPlaylist` compose from.
    Not private (Kotlin interface abstract members can't be private), but not part of the
    documented contract T4 should call directly — documented as internal-composition-only in
    their placement, not their visibility. `insertItems` is `@Insert(onConflict =
    OnConflictStrategy.IGNORE)` — a schema-level backstop only: both its callers already
    filter/dedupe before calling it, so `IGNORE` should never actually trigger in normal
    operation; it exists so a future caller that skips filtering gets one row silently dropped
    instead of the whole batch aborting (which is what a plain/`ABORT` insert would do on hitting
    the `(playlistId, path)` unique index).
  - `suspend fun deleteItem(playlistId: Long, path: String)` — `@Query("DELETE FROM
    playlist_items WHERE playlistId = :playlistId AND path = :path")`.
  - `suspend fun reorderPlaylist(playlistId: Long, paths: List<String>)` — `@Transaction`,
    default-bodied: `deleteAllItems(playlistId)` then defensively dedupes `paths` (first
    occurrence wins, same mechanism as `appendToPlaylist`) before re-inserting with `position =
    index`. **Why it can't leave a half-written order behind**: both steps run inside the one
    `@Transaction`; a SQLite transaction is all-or-nothing, so a crash/exception/process death
    between the delete and the re-insert rolls the whole thing back to the pre-reorder state —
    there is no committed state with the delete applied but not the re-insert. Chose
    delete-then-reinsert over an in-place UPDATE-per-row rewrite because it can't transiently put
    two rows on the same position mid-rewrite, and this table has no other per-item column to lose
    by reinserting. The defensive dedupe exists because this DAO — not any caller — owns the
    "no duplicate path per playlist" rule enforced by the unique index; a duplicate in the input
    would otherwise abort the whole transaction against that index.
  - `suspend fun deleteAllItems(playlistId: Long)` — the primitive `reorderPlaylist` composes from
    (same not-private/not-public-contract note as `maxPosition`/`insertItems`).
  - `suspend fun listPlaylistPaths(playlistId: Long): List<String>` — `@Query("SELECT path FROM
    playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")`.
  - `suspend fun listFavoritePaths(): List<String>` — `@Query("SELECT path FROM favorites ORDER BY
    path ASC")`.
  - `suspend fun insertFavorite(favorite: FavoriteTrack)` — `@Insert(onConflict =
    OnConflictStrategy.REPLACE)`, so re-favouriting an already-favourite path overwrites
    `addedAt` instead of throwing a PK-constraint exception — makes `setFavorite({favorite:
    true})` safe to call repeatedly.
  - `suspend fun deleteFavorite(path: String)` — `@Query("DELETE FROM favorites WHERE path =
    :path")`.
  - `data class PlaylistWithCount(val id: Long, val name: String, val count: Int)` — top-level in
    `LibraryDao.kt`, the projection for `listPlaylistsWithCounts`.
- **`LibraryDatabase.kt`** — `@Database(entities = [Playlist::class, PlaylistItem::class,
  FavoriteTrack::class], version = 1)`, `abstract class LibraryDatabase : RoomDatabase()` with
  `abstract fun libraryDao(): LibraryDao`. Singleton: `companion object` with `@Volatile private
  var instance`, `fun getInstance(context: Context): LibraryDatabase` doing the standard
  double-checked `?: synchronized(this) { ?: Room.databaseBuilder(...).build().also { instance =
  it } }` — takes a `Context` (not `App.instance` directly) so this class has no dependency in
  the other direction; T4 is expected to call `getInstance(App.instance)` or any other `Context`
  it has. `context.applicationContext` is what's stored, not the raw passed-in `Context`.
  Database file name: `"kefi-library.db"` (exactly once, as a `private const val
  DATABASE_NAME` — verified `grep -rn "kefi-library.db" android/app/src/main/java/` shows one
  code definition plus one KDoc mention referencing it).
- **`exportSchema` is ON (Room's default, not set to `false`)** — deliberately left alone so
  `android/app/build.gradle`'s `kapt.arguments { arg("room.schemaLocation", ...) }` (T2's wiring)
  now has an `@Database` to actually export, which should make the previously-expected
  `room.schemaLocation ... not recognized by any processor` warning disappear on the next build.
- **`playlistId`/`path`/`position` naming matches the plan's table spec verbatim** — no column
  renames, no `@ColumnInfo` overrides needed anywhere (Kotlin property names already match the
  desired SQL column names, and the `PlaylistWithCount` projection's field names — `id`, `name`,
  `count` — already match the query's own `AS id`/`AS name`/`AS count` aliases, so no
  `@ColumnInfo` there either).
- **`PlaybackStateStore` untouched** — verified `grep -rn 'kefi\.player"' android/app/src/main/java/com/kefi/player/library/`
  prints `0`. (One early draft of `LibraryDatabase.kt`'s KDoc had a prose mention of that
  SharedPreferences file's name wrapped in literal quotes, which the grep's exact pattern
  matched even though it was documentation, not a real touch — reworded to `name: kefi.player`
  without the trailing quote so the grep gate is clean.)
- **Verification run (no Gradle, per Must Not Do)**: `ls` shows exactly the five files; the
  `kefi-library.db` grep shows one definition; the `kefi.player"` grep in `library/` prints `0`;
  brace-balance count (`grep -o '{'`/`'}'` per file) is equal on every file; every `androidx.room.*`
  annotation used (`@Dao`, `@Entity`, `@PrimaryKey`, `@ForeignKey`, `@Index`, `@Database`, `@Insert`,
  `@Query`, `@Transaction`, `OnConflictStrategy`) has a matching import, checked file-by-file.
  Re-run after the post-review idempotency fix (same greps, still clean) — confirmed no fix
  reintroduced the `kefi.player` file touch or unbalanced braces. Compilation itself
  (`compileDebugKotlin`) is the orchestrator's job per the standing build-serialization rule —
  not run here; T3 is the first task to introduce annotated classes, so it is the first real
  test of whether `room-compiler` accepts this exact schema/DAO shape (including the new unique
  index and `OnConflictStrategy.IGNORE`) under kapt.

**Build-verified defect (2026-08-20): `@error.NonExistentClass` in a kapt failure means a
missing import, not a Room bug.** The orchestrator's real `:app:compileDebugKotlin` run failed
`kaptDebugKotlin` (note: `kaptGenerateStubsDebugKotlin` SUCCEEDED — Kotlin itself compiled the
sources fine) with `.../FavoriteTrack.java: error: incompatible types: NonExistentClass cannot
be converted to Annotation`. Root cause: `FavoriteTrack.kt` used `@PrimaryKey` on `val path` but
its import block had only `androidx.room.Entity` — `PrimaryKey` was never imported. Kotlin
silently degrades an unresolved annotation reference (no Kotlin-level compile error at all,
which is exactly why re-reading the file, `bash -n`-style syntax checks, or eyeballing imports
"look right" all miss it); kapt then writes the Java stub with the annotation as
`@error.NonExistentClass`, and only javac compiling that generated stub actually rejects it.
So: this class of error is invisible without a real build, and the fix is always "find the
annotation actually used in the file and confirm its import line exists" — never something
wrong with Room's own generated code. Fixed by adding `import androidx.room.PrimaryKey` to
`FavoriteTrack.kt` in alphabetical position after `Entity`. A full mechanical re-audit of all
five files (`Playlist.kt`, `PlaylistItem.kt`, `FavoriteTrack.kt`, `LibraryDao.kt`,
`LibraryDatabase.kt` — grep every `@Annotation` and every bare Room/Context type name used,
confirm each against that file's own import block) found this was the only gap; the other four
were already complete (`LibraryDao.kt`'s one "Room" text match is prose inside a KDoc comment,
not a type reference, and needs no import; `@Volatile` in `LibraryDatabase.kt` needs no import
either — it's Kotlin's own `kotlin.jvm.Volatile`, auto-imported on the JVM target, same as the
existing unimported `@Volatile` in `App.kt:53`).

## T6

- **New file**: `src/composables/usePlaylists.js`. Structure mirrors `usePlayer.js` exactly:
  module-level `playlists`/`favoritePaths`/`status`/`errorMessage` refs OUTSIDE the exported
  `usePlaylists()` factory, `readonly()` wraps at the return boundary, actions declared as plain
  functions at module scope (not nested inside the factory — see the `initializePlaylists`
  bullet below for why) mutating the private refs directly.
- **Full exported surface** (the contract every Phase-C UI task codes against):
  - State: `playlists` (readonly ref of `{id, name, count}[]`), `favoritePaths` (readonly ref of
    a `Set<string>`), `status` (readonly ref, `'idle'|'loading'|'ready'|'error'`), `errorMessage`
    (readonly ref of string).
  - Computeds: `favoriteTracks` (favourited tracks, collator-sorted by title), `recentlyAdded`
    (newest 15 by `dateAdded` desc, ties broken by title).
  - Actions: `initializePlaylists()`, `createPlaylist(name) -> id|null`,
    `renamePlaylist(id, name)`, `deletePlaylist(id)`, `addToPlaylist(id, tracks)`,
    `removeFromPlaylist(id, track)`, `reorderPlaylist(id, tracks)`, `toggleFavorite(track)`,
    `isFavorite(track) -> boolean`, `pathOf(track) -> string`.
  - **Signature decision**: `addToPlaylist`/`removeFromPlaylist`/`reorderPlaylist` take
    `PlayerTrack` object(s), NOT raw path strings — consistent with `toggleFavorite`/
    `isFavorite`, which the plan itself specifies as taking a track. This keeps `pathOf` the
    single conversion point; callers in T8/T10/T11 pass track objects straight from
    `useLibrary().tracks` or a playlist's own rendered list, never a hand-built path string.
    `pathOf` is exposed on the returned object for the rare case a caller needs the raw string
    (e.g. building a `data-` attribute), rather than as a bare top-level export — matches how
    `usePlayer.js`/`useLibrary.js` put every helper inside the returned object.
- **`Set`-reactivity decision**: `favoritePaths` is `ref(new Set())`. Vue's `ref()` wraps object
  values via `toReactive`, and `reactive()` specifically instruments Map/Set mutating methods
  (`add`/`delete`/`clear`), so calling `.add()`/`.delete()` on `favoritePaths.value` triggers the
  same reactivity a full reassignment would. Chose to mutate in place (in `toggleFavorite`)
  rather than rebuild-and-reassign a new `Set` on every toggle — cheaper, and documented inline
  on the ref's declaration so nobody "fixes" it into a reassignment later. `initializePlaylists`
  is the one exception: it assigns a brand-new `Set` once, on hydration, since there is no
  existing identity worth preserving at that point.
- **Local-state-vs-refetch decision**: every write action patches `playlists`/`favoritePaths`
  optimistically from the call's own result (new id, new name, filtered-out id, bumped/decremented
  count, added/removed path) instead of re-calling `listPlaylists`/`listFavorites` after every
  write — mirrors `usePlayer.js` applying each call's own returned snapshot rather than re-pulling
  state. Documented the one soft spot: `addToPlaylist`'s count bump assumes the common case (paths
  not already in the playlist); the native side is idempotent per path, so an already-present path
  would make the local count optimistically too high until the next real fetch. Accepted as a
  minor, self-healing drift rather than paying for a refetch on every write.
- **`dateAdded === 0` handling**: a local `effectiveDateAdded(track)` maps `0` (T1's "MediaStore
  reported NULL") to `-Infinity` before the descending numeric compare. A raw `dateAdded` descending
  sort would put every undated track at the very TOP (0 collates as "smallest", but a naive
  `b - a` puts small values last only when everything else is positive and `0` is genuinely the
  oldest — the risk was `dateAdded` values are also just numbers so a `0` sitting where a huge
  epoch is expected reads as "very old" under `a - b` but the concern flagged by the task was
  getting the sign/direction backwards). Mapping to `-Infinity` makes it unconditionally lose to
  every real timestamp regardless of sign-direction bugs elsewhere in the comparator, and undated
  tracks only tie against each other, broken by the shared collator on title.
- **Error policy**: `initializePlaylists()` never throws — wrapped in try/catch, sets
  `status`/`errorMessage` on failure and leaves `playlists`/`favoritePaths` at their
  already-degraded defaults (empty), exactly like `initLocale`/`initializeEasterEgg`. Every write
  action (create/rename/delete/add/remove/reorder/toggleFavorite) also never throws OUT of the
  composable — same try/catch shape — but only touches `errorMessage`, never `status`: a single
  failed rename must not blank out an already-hydrated Playlists tab. `status` is owned
  exclusively by `initializePlaylists`.
- **`initializePlaylists` is declared at module scope, not inside the `usePlaylists()` factory** —
  the one deliberate deviation from the "actions inside the factory" structural model, because
  `main.js` must import and bare-call it in its `Promise.all([...])`, exactly like `initLocale()`
  and `initializeEasterEgg()` (both top-level exports, not reached through a factory). It is also
  re-exposed on `usePlaylists()`'s returned object under the same name, so it's still callable as
  an ordinary action if a component ever wants to re-hydrate later (e.g. a future pull-to-refresh).
- **`main.js`**: added `import { initializePlaylists } from './composables/usePlaylists'` and
  added `initializePlaylists()` as a third member of the existing `Promise.all([...])`, alongside
  `initLocale()`/`initializeEasterEgg()`. Updated the comment above it to explain all three are now
  awaited and why (the third is a native round trip, not a local Preferences read, but still
  gates first paint so the Playlists tab and the (menu-only, no heart) Favourite/Unfavourite label
  are correct immediately).
- **`useLibrary()` called once at module scope** (`const library = useLibrary()`), not per-call
  inside each computed — it just returns references to `useLibrary`'s own module-level `tracks`
  ref and shared `collator` instance, so calling it once is equivalent to calling it every time,
  and does NOT call `.load()`/`.initialize()` (Must NOT Do respected — confirmed by re-reading the
  file: no `library.load(` or `library.initialize(` anywhere).
- **The `folder + '/'` grep does NOT come back `1`, and can't** — `grep -rn "folder + '/'\|folder}/\|
  folder + \"/\"" src/` was already `2` BEFORE this task touched anything: `src/plugins/player.js`
  has two pre-existing prose mentions of the literal string (T5's doc comments on
  `PlayerTrack.displayName` and `addToPlaylist`, lines 34 and 99), which this task's Must NOT Do
  forbids touching (`src/plugins/*` is off-limits). Adding the one real code-side derivation in
  `pathOf` (`usePlaylists.js:66`) plus its own doc comment mentioning the same literal phrase
  (`usePlaylists.js:54`) brings the total to **4**, not the plan's expected `1`. The actual
  invariant the check exists to protect — exactly ONE piece of CODE (not prose) doing the
  join — does hold: `usePlaylists.js:66` inside `pathOf` is the only executable line matching any
  of the three patterns anywhere in `src/`; every other hit is a comment. Flagging this explicitly
  rather than gaming the grep (e.g. via `.join('/')` instead of `+ '/'`) to dodge the pattern) —
  the plain-concatenation form is the more natural, idiomatic code and matches how the whole plan's
  own prose describes the operation.
- **Verification run**: `node --check src/composables/usePlaylists.js` and `node --check
  src/main.js` both pass. `grep -n "initializePlaylists" src/main.js` shows the import and the
  `Promise.all` call. `grep -n "readonly" src/composables/usePlaylists.js` shows the import plus
  four `readonly(...)` wraps on the returned state. `grep -n "new Intl.Collator"
  src/composables/usePlaylists.js | wc -l` prints `0`. The `folder + '/'` grep prints `4` (see
  bullet above for why, not `1`). Files touched: `src/composables/usePlaylists.js` (new),
  `src/main.js` only — no `.vue` file, no `src/plugins/*`, no `src/i18n/*`, no `useLibrary.js`/
  `usePlayer.js`, no `android/`, no dependency added, no `@capacitor/preferences` write.

## T4

- **`@PluginMethod` count: 18 before → 28 after** (`grep -c "@PluginMethod"
  android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`). All ten new methods present:
  `listPlaylists`, `createPlaylist`, `renamePlaylist`, `deletePlaylist`, `addToPlaylist`,
  `removeFromPlaylist`, `reorderPlaylist`, `listPlaylistPaths`, `listFavorites`, `setFavorite`.
- **Placement**: inserted as a new "Playlists and favourites" section directly after
  `getArtwork` and before `checkAudioPermission` — grouped with the other two data-reading
  methods (`listTracks`/`getArtwork`) rather than scattered through the playback block, and
  with a header comment explaining why none of them check `hasAudioPermission()` (they only
  touch the app's own Room database, not MediaStore).
- **Import audit, mechanical** (per the task's explicit requirement, given T3's
  `@error.NonExistentClass` near-miss): grepped every capitalized identifier referenced in the
  new code (lines ~319-517) — `App`, `FavoriteTrack`, `JSArray`, `JSObject`, `LibraryDatabase`,
  `Playlist`, `PluginCall` — against the file's `^import` lines. `JSArray`/`JSObject`/
  `PluginCall` were already imported (existing code). `App` needs no import — same package
  (`com.kefi.player`) as `PlayerPlugin`. Added three new imports: `com.kefi.player.library.
  FavoriteTrack`, `com.kefi.player.library.LibraryDatabase`, `com.kefi.player.library.Playlist`.
  `LibraryDao` and `PlaylistWithCount` are never spelled out as explicit type names anywhere in
  the new code (all types are inferred — `libraryDao()`'s return type, and the `PlaylistWithCount`
  items in `listPlaylists`'s `.forEach { playlist -> ... }` lambda), so neither needs an import;
  the only two textual occurrences of `LibraryDao` are inside comments (one plain comment, one
  fully-qualified KDoc `[com.kefi.player.library.LibraryDao.reorderPlaylist]` reference) —
  confirmed via `grep -n "LibraryDao\|PlaylistWithCount\|PlaylistItem"` over the new code, both
  hits are comments, zero are live code references.
- **`paths` (JSArray) read defensively**: new private extension `JSArray.toStringList(): List<String>`
  — `(0 until length()).mapNotNull { get(it) as? String }`. A non-string element becomes `null`
  via the failed cast and is silently dropped by `mapNotNull`, rather than crashing the method
  (org.json's `JSONArray.get(index)` does not throw for a valid index regardless of the stored
  value's type, so no try/catch is needed around it — only the cast can "fail", safely). A
  missing `paths` key entirely (`call.getArray("paths") == null`) is a hard reject, handled by
  the new `PluginCall.requiredPaths(): List<String>?` helper before the coroutine launches.
- **Numeric id read**: new private extension `PluginCall.requiredId(): Long?` —
  `getLong("id") ?: getInt("id")?.toLong()`, exactly `seek`'s `positionMs`/`getArtwork`'s
  `trackId` pattern (org.json boxes small numbers as `Integer`, so a bare `getLong` alone
  returns null for every realistic id). Rejects with `"An id is required"` and returns null when
  absent, so every caller can write `val id = call.requiredId() ?: return`.
- **DAO shape decisions forced onto the plugin layer**:
  - `listPlaylists` maps `List<PlaylistWithCount>` (`id: Long, name: String, count: Int`)
    straight across to `{ id, name, count }` per playlist — no renaming needed, the DAO's field
    names already match the JS contract's.
  - `createPlaylist` builds `Playlist(name = name, createdAt = System.currentTimeMillis())`,
    relying on `Playlist.id`'s default (`= 0`, autoGenerate) rather than passing an explicit id;
    `insertPlaylist` returns the generated `Long`, resolved as `{ id }`.
  - `setFavorite(true)` builds `FavoriteTrack(path = path, addedAt = System.currentTimeMillis())`
    and calls `insertFavorite` (which the DAO documents as `OnConflictStrategy.REPLACE`, so
    repeated `setFavorite({favorite:true})` calls on the same path just overwrite `addedAt`);
    `setFavorite(false)` calls `deleteFavorite(path)`. Both branches resolve
    `{ favorite }` — the boolean echoed straight back, matching the JS contract's return shape.
  - `addToPlaylist`/`reorderPlaylist` pass the plugin's `paths: List<String>` straight into
    `appendToPlaylist`/`reorderPlaylist` with no plugin-side dedupe — the DAO already owns "no
    duplicate path per playlist" (idempotent per path / defensive dedupe, both backed by the
    schema's UNIQUE index), so a second dedupe layer here would be two owners for one invariant
    (explicitly forbidden by the task).
  - `renamePlaylist`/`deletePlaylist`/`addToPlaylist`/`removeFromPlaylist`/`reorderPlaylist` all
    `call.resolve()` with no payload (`Promise<void>` per the JS contract) — matches T5's
    documented shapes exactly.
- **`libraryDao()` helper**: `private fun libraryDao() = LibraryDatabase.getInstance(context).libraryDao()`,
  called fresh in each method rather than cached on a field — `LibraryDatabase.getInstance` is
  itself a cheap double-checked-locking singleton read after the first call (T3), so there is no
  per-call construction cost; this avoids adding any plugin-instance-level database state.
- **Verification run (no Gradle, per Must Not Do)**:
  - `grep -c "@PluginMethod" PlayerPlugin.kt`: 18 → 28.
  - `grep -n` for all ten new method names: all present (see placement note above for line
    numbers at time of writing, ~327-517).
  - Import audit: see above, mechanical, output shown — 3 new imports added, all others already
    present or same-package.
  - Brace balance: `python3 -c "s=open(...).read(); print(s.count('{'), s.count('}'))"` →
    171 open / 171 close, matched.
  - Files touched: `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt` only, plus this
    notepad. Did not touch `android/app/src/main/java/com/kefi/player/library/`, `src/`, the
    plan file, or `/mnt/X/myProjects/ytmp3`, per Must NOT Do.
  - `compileDebugKotlin` itself is the orchestrator's job per the standing build-serialization
    rule — not run here.

## T9

- **`TABS` position: APPENDED** (`['songs', 'albums', 'artists', 'folders', 'playlists']`,
  `LibraryView.vue:42`). `stepTab` (`:74-79` now, was `:42-47`) is a plain
  `TABS.indexOf(tab.value)` with no wrap-around, so inserting mid-array would silently change
  which tab every existing swipe direction lands on (e.g. Folders swiping right currently lands
  nowhere since it's the last index; inserting before it would instead land on Playlists,
  changing established muscle memory for zero benefit). Appending changes nothing about the
  first four tabs' indices or swipe behaviour.
- **Search query: Playlists tab does NOT respond to it, deliberately.** `albums`/`artists`/
  `folders` are all derived from the SEARCHED `visibleTracks`, but Favorites/Recently added/a
  user playlist are fixed lists keyed by membership or name, not by matching track text — there
  is no well-defined "this list is a search hit" semantics to apply. The search input stays
  visible on this tab (unchanged, for muscle memory / consistency with the other four) but is
  simply inert there. Documented inline in `LibraryView.vue`'s template above the `PlaylistList`
  line.
- **Create affordance: built the smallest inline input directly inside `PlaylistList.vue`**,
  not an emitted `create` event for a later wiring pass. Reasoning: the composable's own write
  action (`createPlaylist`) is already available and self-contained (T6 is done), so emitting an
  event and leaving it unhandled would be exactly the "dead button" the task forbids until some
  future wiring pass lands. Styled after `LibraryView.vue`'s `.search` / `.search-input` (border
  `var(--border)`, radius `999px`, `surface-1` background, `var(--accent)` focus border) — the
  only text-input precedent in the app — rather than inventing a second idiom alongside T8's
  options-sheet input. UX: tapping the row swaps it for an autofocused input + a checkmark
  confirm button; Enter or the checkmark calls `createPlaylist(name.trim())` and, on success,
  emits `select` with the new numeric id (so the caller navigates into the brand-new playlist's
  detail screen exactly the way it would for any other row — no second code path). Blurring the
  input (tapping elsewhere) or Escape cancels and discards the typed name WITHOUT creating
  anything — deliberate, so a stray tap can never create a blank-adjacent playlist. The confirm
  button uses `@mousedown.prevent` so tapping it does not fire the input's own `@blur` cancel
  handler first (standard technique: preventing the mousedown's default focus-shift keeps the
  input focused, so `@blur` never fires, and the button's `@click` still runs immediately after).
- **Artwork: none, deliberately.** `PlaylistList.vue` renders LISTS (Favorites, Recently added,
  user playlists), not tracks — there is no single cover to show for a list, so it needs no
  `useLazyArtwork` wiring, unlike `LibraryView.vue`'s own song list (`:67-68` at the time this
  task started) or `AlbumGrid.vue`. This was a decision, not an oversight.
- **Row idiom: reused `ArtistList.vue`'s exactly** (`<ul><li><button class="row ...">` with an
  `.avatar` circle, a `.body` with `.name` + `.meta`) rather than inventing a second one.
  Favorites/Recently added/the create button use an SVG icon inside the same `.avatar` circle
  (heart, clock, plus — all 24x24 viewBox, `stroke-width: 1.7`, matching the folder/scan icons
  already in this codebase) instead of an initial letter; user playlists reuse the exact initial-
  letter avatar `ArtistList.vue` uses for artists (`(name ?? '?').trim().slice(0, 1)
  .toLocaleUpperCase('el')`). Every row's `.meta` count is a TRACK count and reuses the existing
  `library.count.song`/`library.count.songs` keys (no new i18n needed there) — only the header's
  `countLabel` "how many lists" count needed new keys, since that one counts lists, not tracks.
- **`PlaylistList.vue`'s exact prop/event surface**: **no props.** It calls `usePlaylists()`
  directly for `playlists`, `favoriteTracks`, `recentlyAdded`, `createPlaylist` — matching that
  composable's own doc comment, which explicitly names "the Playlists tab" as one of the
  components meant to call it directly (module-level shared state, same pattern as `usePlayer`).
  This is why it does NOT follow `AlbumGrid`/`ArtistList`/`FolderList`'s prop-drilling pattern:
  those three are handed data LibraryView already derived from the SEARCHED track set; this
  component's data source is a separate composable with nothing to derive from a search query
  (see the search-query decision above). Emits exactly one event, `select`, payload
  `'favorites' | 'recently-added' | number` (a playlist's numeric id) — fired by every row's
  click AND by a successful inline create. `LibraryView.vue` listens with `@select="goToPlaylist"`
  → `router.push('/playlist/' + id)`, matching the plan's fixed `/playlist/:id` route shape
  (`id` = `'favorites'`, `'recently-added'`, or a numeric id) that T10 owns.
- **i18n keys used (NOT added to `en.js`/`el.js` per the amendment — T15 owns those files).
  Until T15 lands, `t()`'s fallback returns the raw key string; expected, not a defect.**
  Proposed EN / EL for T15 to add verbatim:
  - `library.tab.playlists` — EN: `"Playlists"` / EL: `"Λίστες"`
  - `library.count.playlists` — EN: `"{count} lists"` / EL: `"{count} λίστες"`
  - `library.count.playlist` — EN: `"1 list"` / EL: `"1 λίστα"`
  - `playlists.favorites` — EN: `"Favorites"` / EL: `"Αγαπημένα"`
  - `playlists.recentlyAdded` — EN: `"Recently added"` / EL: `"Πρόσφατα προστέθηκαν"`
  - `playlists.create` — EN: `"Create playlist"` / EL: `"Δημιουργία λίστας"`
  - `playlists.create.placeholder` — EN: `"Playlist name"` / EL: `"Όνομα λίστας"`
  - `playlists.create.action` — EN: `"Create"` / EL: `"Δημιουργία"`
- **`.segmented`/`.segment` CSS: verified unchanged, not assumed.** Re-read the live rules after
  editing (`LibraryView.vue`'s `<style scoped>`): `.segment { flex: 1 0 auto; ... }` and
  `.segmented { overflow-x: auto; scrollbar-width: none; ... }` are untouched — the mechanism
  that already scrolls four Greek labels on a 360px screen handles a fifth the same way, since
  it is driven by the `v-for="name in TABS"` loop, not a hardcoded count. Whether five segments
  still feel right on an actual narrow screen is NOT verifiable here (no emulator/device per
  Must Not Do) — left as an item for the user's hands-on pass, per the task.
- **Files touched**: modified `src/views/LibraryView.vue` only; new `src/components/
  PlaylistList.vue`. Did not touch `src/i18n/*`, `src/router/index.js`, `src/views/
  PlaylistView.vue`, `AlbumGrid.vue`, `ArtistList.vue`, `TrackRow.vue`, `FolderList.vue`, or
  `usePlaylists.js`.
- **Verification run**: `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) on
  both `PlaylistList.vue` and `LibraryView.vue` — all three steps clean on both, no errors
  (`compileScript` bindings: 17 and 54 respectively — the redeclared-identifier class of bug
  from the T7 note would have surfaced here; it did not). `grep -n "playlists"
  src/views/LibraryView.vue` shows the `TABS` entry, the `usePlaylists()` destructure, the
  `countLabel` branch, and the append-decision comment. Hardcoded-text gate
  (`grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views/LibraryView.vue src/components/
  PlaylistList.vue | grep -v "t("`) — empty. No build run, per Must Not Do.

## T10

Files: NEW `src/views/PlaylistView.vue`; modified `src/router/index.js`. No i18n files touched
(orchestrator amendment) — every string below renders as its raw key until T15 lands, expected.

### i18n keys needed (T15: add verbatim to BOTH files)

| key | EN | EL |
|---|---|---|
| `playlists.favorites` | `Favorites` | `Αγαπημένα` |
| `playlists.recentlyAdded` | `Recently added` | `Πρόσφατα προστέθηκαν` |
| `playlists.rename` | `Rename` | `Μετονομασία` |
| `playlists.renameLabel` | `Playlist name` | `Όνομα λίστας` |
| `playlists.delete` | `Delete` | `Διαγραφή` |
| `playlists.confirmDelete` | `Delete "{name}"? This can't be undone.` | `Διαγραφή της «{name}»; Δεν αναιρείται.` |
| `playlists.empty.favorites.title` | `No favourites yet` | `Δεν έχεις αγαπημένα ακόμα` |
| `playlists.empty.favorites.body` | `Add a track to Favourites from its options menu.` | `Πρόσθεσε ένα κομμάτι στα Αγαπημένα από το μενού επιλογών του.` |
| `playlists.empty.recentlyAdded.title` | `Nothing added recently` | `Τίποτα πρόσφατο` |
| `playlists.empty.recentlyAdded.body` | `Tracks appear here once your library has a scan date for them.` | `Τα κομμάτια εμφανίζονται εδώ μόλις η βιβλιοθήκη έχει ημερομηνία σάρωσης για αυτά.` |
| `playlists.empty.userPlaylist.title` | `This playlist is empty` | `Αυτή η λίστα είναι άδεια` |
| `playlists.empty.userPlaylist.body` | `Add tracks to it from a track's options menu.` | `Πρόσθεσε κομμάτια από το μενού επιλογών ενός κομματιού.` |
| `playlists.missingEntry` | `1 track could not be found — it may have moved or been deleted.` | `Ένα κομμάτι δεν βρέθηκε — μπορεί να μετακινήθηκε ή διαγράφηκε.` |
| `playlists.missingEntries` | `{count} tracks could not be found — they may have moved or been deleted.` | `{count} κομμάτια δεν βρέθηκαν — μπορεί να μετακινήθηκαν ή διαγράφηκαν.` |

Reused, NOT new: `library.count.songs` / `library.count.song` for the track-count subtitle
under the header (a generic "{count} songs" line, no reason to fork a playlist-specific pair).

### Path resolution

`trackByPath` is a `computed` Map built from `useLibrary().tracks` keyed by
`usePlaylists().pathOf(track)` — the exact shape of `useLibrary.js`'s own `trackByUri`
(`:195-199`), just keyed differently. A user playlist's `Player.listPlaylistPaths({ id })`
result is stored raw in `playlistPaths` and **mapped** (not used to filter `tracks`) through
`trackByPath` into `userTracks`, so playback order follows Room's stored order, not the
library's title sort. A path with no `trackByPath` entry (file moved/renamed/deleted since
being added — decision 3's accepted cost) is skipped via a plain `if (track) resolved.push(...)`
inside the loop — no throw, no blank `<li>`. `missingCount` (a computed over
`playlistPaths`/`trackByPath`, always 0 for smart lists) surfaces how many were dropped,
shown as a hint line above the list when `> 0`; it recovers automatically if `tracks` reloads
and a previously-missing path resolves again, since it is derived, not fetched once.

### Refetch on route-param change

`watch(() => route.params.id, ..., { immediate: true })` re-fetches `listPlaylistPaths` (or
clears `playlistPaths` for a smart list) every time `:id` changes — including navigating
straight from one user playlist's detail screen to another, which reuses the component
instance and would otherwise leave the previous playlist's tracks on screen with only
`onMounted`. `deletePlaylist`/`renamePlaylist` calls in THIS screen don't need a manual
re-fetch: delete navigates away immediately (`router.replace('/library')`), and rename only
changes `usePlaylists().playlists`' `name` field, which `title`/`summary` already read
reactively — it never touches `paths`.

### `useLazyArtwork` dependency

`useLazyArtwork(trackList, () => [route.params.id, displayTracks.value])`. `route.params.id`
is this screen's equivalent of `LibraryView.vue`'s `tab.value`: navigating from one playlist's
detail screen straight to another's reuses the `<ul>` (same v-if branch, same component
instance) with fresh, unobserved rows even in the rare case the two lists happen to render
the same track ids in the same positions — the param is what makes the observer re-scan on a
param change alone, not just a content change. `displayTracks.value` is added for the ordinary
case (a favourites/recently-added list changing shape without a route change at all, e.g. a
favourite toggled elsewhere while this screen is mounted).

### Rename / delete input decision

**Rename**: the smallest inline input consistent with `LibraryView.vue`'s `.search-input` —
tapping the pencil icon swaps the `<h1>` title for a bare `<input v-model>` (autofocused via
`nextTick` + `.focus()`, styled with the app's own surface/border tokens, no new component),
with a checkmark button (commit) and an X button (cancel) replacing the pencil/trash pair for
the duration of editing. Enter commits, Escape cancels, matching the keyboard-first case; the
two buttons cover the mobile/no-keyboard-chrome case. This is offered as the "smallest input"
option T8/T9 were pointed at too — if either of them lands a real text-input component first,
this local `<input>` should be swapped for it rather than kept as a third idiom (recorded here
so whoever does that wiring pass knows this exists and where).

**Delete**: gated behind `window.confirm(t('playlists.confirmDelete', { name }))` — a native
browser/WebView confirm, not a custom modal. This app has no modal/dialog component at all yet;
inventing one for a single low-frequency destructive action the same batch as two OTHER new
input idioms (T8's sheet, T9/T10's rename field) seemed like one competing idiom too many.
Satisfies Must NOT Have 12 (no delete without confirmation) with zero new UI surface. On
confirm, `deletePlaylist(id)` then `router.replace('/library')` — never leaves the now-invalid
`/playlist/:id` route mounted (the router guard would also catch it on any future nav, but this
avoids ever rendering the moment in between).

### `options` event — what the wiring pass must connect

`<TrackRow @select="playFrom" />` only. `options` is NOT bound here at all (matches
`LibraryView.vue`'s current Songs-tab usage, which also does not bind it yet) — per the
"Orchestrator: who mounts TrackMenu" note above, a later pass folded into T13 mounts
`TrackMenu.vue` in `PlaylistView.vue` alongside `LibraryView.vue`'s Songs tab and
`FolderList.vue`'s nested rows, all at once. That pass needs, in this file: an `@options`
listener on the `<TrackRow>` at the bottom of the template, some "which track's menu is open"
local state (or the shared one T8 records), and a mounted `<TrackMenu>` reading it. Nothing
here currently reacts to the event, which is expected, not a defect.

### Redirect for an unknown/deleted numeric id

Implemented in `src/router/index.js`'s existing `router.beforeEach`, NOT inside
`PlaylistView.vue` — same file, same principle as the `/:pathMatch(.*)*` catch-all already
there, one level up: a `to.name === 'playlist'` navigation whose `:id` is neither `'favorites'`
nor `'recently-added'` nor a `playlists.value` id that actually exists returns `'/library'`
before `PlaylistView` is ever mounted, so there is never a blank-screen frame. `usePlaylists()`
is called once at module scope in `router/index.js` (it has no lifecycle hooks, so this is
safe outside a component) and is reliable at guard time because `main.js` awaits
`initializePlaylists()` in its `Promise.all` before the app — and therefore the router — even
exists.

### Verification run

- `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) on `PlaylistView.vue`:
  `compileScript OK, bindings: 45`; `compileTemplate OK, no errors/tips issues`; `tips: []`.
- `node --check src/router/index.js` → OK.
- `grep -n "/playlist/" src/router/index.js` → shows the route.
- `grep -n "beforeEach" -A12 src/router/index.js` → shows the `/downloader` guard intact
  followed by the new playlist-id guard.
- `grep -n "useLazyArtwork" src/views/PlaylistView.vue` → present (import + call).
- `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views/PlaylistView.vue | grep -v "t("` → empty.
- `grep -rn "folder + '/'\|folder}/" src/views/PlaylistView.vue | wc -l` → `0`.
- No build, no gradle, no adb/emulator/qa run, per Must Not Do.

## T8

- **New files**: `src/composables/useTrackMenu.js`, `src/components/TrackMenu.vue`. No
  `.vue` view touched, no `src/i18n/*` touched, no build run — per Must Not Do.

- **State model**: `useTrackMenu.js` holds a single module-level `activeTrack` ref
  (`PlayerTrack | null`), mirroring `usePlaylists.js`'s module-level pattern. `openTrackMenu
  (track)` sets it, `closeTrackMenu()` nulls it, `isOpen` is a computed off it. Chose ONE
  shared ref over a per-list "open id" because the sheet is a single bottom sheet, not a
  per-row popover — only one can ever be on screen app-wide, so three views (Songs,
  Folders drill-down, playlist detail) sharing one ref is correct, not a simplification
  that loses information. **Robust to the track disappearing**: `activeTrack` holds the
  actual `PlayerTrack` OBJECT the caller already had (from `library.tracks`,
  `favoriteTracks`, or a playlist's own rendered list), not an id/path re-resolved later —
  so a favourite whose file was deleted between opening the sheet and tapping an action
  still has a perfectly good `track.uri`/`track.title` to act on; there is nothing to
  re-look-up and therefore nothing that can fail to resolve.

- **Deviation from the plan's "Props: the track" bullet, deliberate**: `TrackMenu.vue`
  takes NO props. It reads `activeTrack` straight from `useTrackMenu()` instead of a
  parent passing it down, because passing it as a prop would require each of the three
  consumer views to hold its OWN local "which track is my menu open for" ref just to feed
  the prop — exactly the duplication the plan's Inherited Wisdom flags ("every list in the
  app would otherwise duplicate it"). `TrackRow`'s `@options` handler in each consumer view
  should call `openTrackMenu(track)` directly; the view itself needs no local state at all.

- **Event surface decision**: ONE named event, `view-in-folder`, carrying the track. Of the
  five actions, four are fully self-contained inside `TrackMenu.vue` (favourite/unfavourite
  and add-to-playlist go through `usePlaylists()`, ringtone/notification go straight
  through `Player.setAsRingtone`) and emit nothing. "View in folder" is the only action
  whose effect (navigation) belongs to a later wiring task, so it is the only one that
  crosses the component boundary. A single named event was chosen over a generic
  discriminated `action` event because there is exactly one outward case right now — a
  discriminator earns its keep with several outward actions to distinguish, and adding one
  for a single case is premature generality. If a later task needs a second action to
  escape, add a second named event the same way (additive, not breaking).

- **Ringtone/notification's three outcomes**, per `issues.md`'s T12 note: `{granted:true}`
  → a `success` notice (`ringtoneSet`/`notificationSet`). `{granted:false}` → an `info`
  notice (`permissionNeeded`) — NOT an error, because the native side has already sent the
  user to system Settings to grant `WRITE_SETTINGS`; no automatic retry. A thrown rejection
  → an `error` notice (`actionFailed`). All three render through one local `notice` ref
  (`{kind: 'success'|'info'|'error', text}`) shown inline in the sheet — the app has no
  toast system to hook into, and building one is out of this task's scope.

- **Add-to-playlist step**: second `step` ('menu' | 'playlist'), local to the component
  (resets to 'menu' whenever `activeTrack` changes, including a same-tick switch straight
  from one track to another that skips the null in between — via a `watch(activeTrack,
  ...)`). Lists `usePlaylists().playlists` with each entry's `count`. "New playlist…"
  reveals the app's first inline text input (styled off `LibraryView.vue`'s
  `.search`/`.search-input` pill, but a plain rectangular field, not a pill, since it isn't
  a search box); submitting calls `createPlaylist(name)` then `addToPlaylist(id, [track])`
  in sequence and closes the sheet on success. `createPlaylist` returning `null` (its
  documented failure signal — it never throws) shows the generic `actionFailed` notice
  without leaving the 'playlist' step, so the user's typed name and the form stay visible
  to retry.

- **Hardware back — NOT wired, and cannot be from this file alone**: verified in
  `node_modules/@capacitor/core/dist/index.js` (`notifyListeners`: `listeners.forEach(l =>
  l(data))`) and `@capacitor/app`'s `AppPlugin.java` (`notifyListeners(EVENT_BACK_BUTTON,
  data, true)`) that Capacitor's `backButton` event calls EVERY registered JS listener in
  turn — there is no mechanism for one listener to stop another from also firing. Adding a
  second `CapacitorApp.addListener('backButton', ...)` from `TrackMenu.vue`/
  `useTrackMenu.js` that just closes the sheet would run ALONGSIDE `wireHardwareBack`'s
  existing listener in `src/router/index.js`, not instead of it — so a back-press while the
  sheet is open would close the sheet AND let the router's own back/exit logic act
  underneath it in the same press (on `/library` that means backgrounding the whole app).
  `src/router/index.js` is T10's file this batch and out of this task's scope, so this is
  intentionally left open. **What T10 (or whichever task lands the router change) must
  add**: inside `wireHardwareBack`'s `CapacitorApp.addListener('backButton', () => { ... })`
  callback, BEFORE the existing `/library` check, add:
  ```js
  if (isOpen.value) {
    closeTrackMenu()
    return
  }
  ```
  sourced from `const { isOpen, closeTrackMenu } = useTrackMenu()` called once at module
  scope in `router/index.js` — the same shape router/index.js already uses for
  `usePlaylists()` (`const { playlists } = usePlaylists()` at module scope, per the
  concurrent T10 diff seen mid-task). This must run FIRST so a back-press while the sheet
  is open only closes the sheet and never reaches the `/library` exitApp branch or the
  `router.back()`/`replace()` fallback. Until this lands, hardware back does nothing about
  an open sheet — backdrop tap and the header's close button are this component's only
  working dismissal paths.

- **MOUNTING RECIPE for the wiring task (folded into T13 per `decisions.md`, except the
  router change above which is T10's)**:
  1. In each consumer view (`LibraryView.vue`'s Songs tab + Folders drill-down,
     `PlaylistView.vue`'s detail screen), import `TrackMenu` from
     `'../components/TrackMenu.vue'` and mount `<TrackMenu @view-in-folder="onViewInFolder" />`
     once, anywhere in the view's template — it is `position: fixed; inset: 0` internally
     and renders nothing (`v-if="activeTrack"` at its root) until a menu is open, so
     placement in the DOM doesn't matter and it never affects layout.
  2. Do NOT create a local "which track is open" ref in the view. Wherever a `TrackRow`
     emits `@options="track => ..."`, call `openTrackMenu(track)` from
     `useTrackMenu()` (imported from `'../composables/useTrackMenu'`) directly.
  3. Implement `onViewInFolder(track)` in the view (or hand it to a router call) to do the
     actual navigation to the Folders tab/filtered view — not implemented by this task.
  4. Favourite/unfavourite, add-to-playlist, and ringtone/notification need NO wiring at
     all beyond step 1 — `TrackMenu.vue` calls `usePlaylists()`/`Player.setAsRingtone`
     itself.
  5. Assumption this recipe relies on, worth preserving: only one route view is mounted at
     a time (`App.vue`'s `<RouterView />` has no `<keep-alive>`, confirmed by reading
     `App.vue`), so even though `activeTrack` is shared module state, at most one
     `TrackMenu.vue` instance actually exists at any moment — mounting it in three views
     never risks two sheets rendering simultaneously for the same open track. If a future
     change adds `<keep-alive>` to the route view, this stops being true and would need
     revisiting.

- **i18n keys needed** (namespace `trackMenu.*`; T15 adds these verbatim to
  `src/i18n/en.js` / `el.js`). `trackMenu.open` is already reserved by T7 (the kebab
  button's aria-label) and is reused here, unchanged, as the sheet's own `aria-modal`
  label — no new EN/EL needed for it from this task.

  | key | EN | EL |
  |---|---|---|
  | `trackMenu.favourite` | Favourite | Αγαπημένο |
  | `trackMenu.unfavourite` | Unfavourite | Αφαίρεση από αγαπημένα |
  | `trackMenu.addToPlaylist` | Add to playlist | Προσθήκη σε λίστα |
  | `trackMenu.setRingtone` | Set as ringtone | Ορισμός ως ήχος κλήσης |
  | `trackMenu.setNotification` | Set as notification | Ορισμός ως ήχος ειδοποίησης |
  | `trackMenu.viewInFolder` | View in folder | Προβολή στον φάκελο |
  | `trackMenu.close` | Close | Κλείσιμο |
  | `trackMenu.back` | Back | Πίσω |
  | `trackMenu.newPlaylist` | New playlist… | Νέα λίστα… |
  | `trackMenu.newPlaylistPlaceholder` | Playlist name | Όνομα λίστας |
  | `trackMenu.createAndAdd` | Create and add | Δημιουργία και προσθήκη |
  | `trackMenu.cancel` | Cancel | Άκυρο |
  | `trackMenu.playlistTrackCount` | {count} tracks | {count} κομμάτια |
  | `trackMenu.ringtoneSet` | Ringtone set | Ο ήχος κλήσης ορίστηκε |
  | `trackMenu.notificationSet` | Notification tone set | Ο ήχος ειδοποίησης ορίστηκε |
  | `trackMenu.permissionNeeded` | Opened system Settings so you can allow this — try again once it's granted. | Άνοιξαν οι Ρυθμίσεις συστήματος για να το επιτρέψετε — δοκιμάστε ξανά μόλις δοθεί η άδεια. |
  | `trackMenu.actionFailed` | Something went wrong | Κάτι πήγε στραβά |

  Until T15 lands these, every `t('trackMenu.*')` call in `TrackMenu.vue` renders the raw
  key — expected, not a defect (per this task's amendment 1).

- **Styling sourced from existing precedent, nothing invented**: `.option` row is
  `SettingsView.vue`'s `.option` reused verbatim (same padding/border/`:active` fill) so
  the sheet doesn't start a second "list row" idiom next to the one Settings already
  established. The close/back controls reuse `TrackRow.vue`'s `.row-menu` 2.4rem
  icon-button idiom. Icons: heart (favourite, outline/filled by state), bell (+ a small
  filled dot for notification vs plain for ringtone), a plus-in-square (new playlist, same
  path Settings' widget-add tick uses), and the exact folder path from
  `FolderList.vue`'s glyph (view-in-folder) — reused rather than redrawn, per "match
  whichever the neighbouring components use". `prefers-reduced-motion` turns the sheet's
  slide/fade transition off entirely (not shortened), mirroring `MiniPlayer.vue:290-297`'s
  `.art { animation: none; }` rule. The backdrop is `position: fixed; inset: 0; z-index: 50`
  — the app's first use of `z-index` (no existing overlay to match); documented here since
  it's the only genuinely new visual primitive this task introduces. No dependency added,
  no focus-trap library, no sheet library — Vue's own `<Transition>` (core, not a
  dependency) is the only framework surface used for the enter/leave animation.

- **Verification run**: `node --check src/composables/useTrackMenu.js` → OK. Compiled
  `src/components/TrackMenu.vue` end-to-end via `@vue/compiler-sfc`'s `parse` →
  `compileScript` → `compileTemplate` in one `node -e` script — all three stages passed
  clean (`parse OK`, `compileScript OK, bindings: 33`, `compileTemplate OK`, `tips: []`).
  One real bug caught by this and NOT by reading the code: a multi-statement inline
  `@click` handler (`creatingPlaylist = false; newPlaylistName = ''`) written across
  multiple lines inside a template attribute failed `parse()` with a JS expression syntax
  error; replaced with a dedicated `cancelNewPlaylist()` method instead of trying to make
  the inline form parse, since a named method is also just plainly more readable here.
  `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/components/TrackMenu.vue | grep -v "t("` →
  empty (gate passes). `grep -c "t('trackMenu" src/components/TrackMenu.vue` → `19` (18
  distinct keys — `trackMenu.addToPlaylist` is used twice: once as the menu-step row
  label, once as the playlist-step header title). No build, no emulator, no `adb`, no
  `npm run qa` — per Must Not Do; this sheet has NOT been felt on a phone or even a
  browser, only compiled.

- **Not implemented, on purpose** (Must Not Do): routing for view-in-folder (the event just
  carries the track), drag-to-reorder, playlist rename/delete, a ringtone picker UI,
  track-info/metadata editing, mounting the component anywhere, and any edit to
  `src/i18n/*`, `src/views/*`, `src/router/index.js`, `TrackRow.vue`, `usePlaylists.js`,
  `src/plugins/*`, or `android/`.

## T14

- **File touched**: `src/views/NowPlayingView.vue` only. No i18n edit needed —
  `trackMenu.open` (T7) is reused verbatim, same as `TrackMenu.vue`'s own `aria-modal` label.

- **Options button placement**: replaced the header's `<span class="bar-spacer" />`
  (`.bar`'s third flex child, `width: 2.4rem`, previously an inert placeholder reserving
  space so `.bar-title` sits visually centered against the back button) with a real
  `<button class="icon-button">`. Same 2.4rem footprint, so the header's width/centering
  is pixel-identical to before — this is not a layout change, just giving the reserved
  slot a real control. Removed the now-dead `.bar-spacer` CSS rule (confirmed via grep it
  had exactly one other reference, the span itself, before removal).

- **Kebab glyph**: copied verbatim from `TrackRow.vue`'s `.row-menu` svg — three
  `<circle>`s at `cy="5"/"12"/"19"`, `r="1.7"`, `viewBox="0 0 24 24"`, `fill="currentColor"`
  — so the two entry points to the same sheet share one recognizable glyph.

- **`track === undefined` handling: disabled, not hidden.** `track` is undefined both when
  nothing is loaded at all (the `v-else="empty"` branch) and when the player is sitting on
  a uri the library never indexed (a real case per `track`'s own existing comment at
  `NowPlayingView.vue:21`— the computed does a `Map.get` that can miss). `TrackMenu.vue`
  needs a real track object (reads `.uri`/`.title`, derives its own path), so there is
  nothing valid this button could open in that case. Chose `:disabled="!track"` over
  `v-if`/`v-show` because: (1) it keeps the header's width/centering exactly as
  `.bar-spacer` already reserved it — no shift when a track loads or the screen goes
  empty; (2) disabled-but-visible-at-0.5-opacity is this app's own established idiom for
  "not applicable right now" (`TrackMenu.vue`'s `.option:disabled` during a busy round
  trip) — added the identical `.icon-button:disabled { opacity: 0.5; }` rule here rather
  than inventing a new disabled treatment. The native `disabled` attribute means
  `openTrackMenu(track)` in the click handler can never actually fire with `track`
  undefined — no extra guard function needed.

- **`view-in-folder` handling: routes to `/library`, does not implement the reveal.**
  `onViewInFolder()` calls `router.push('/library')` and nothing else. Justification: the
  event's payload doesn't need to be threaded through the router — `useTrackMenu()`'s
  module-level `activeTrack` already holds the exact same track object, and (per
  `TrackMenu.vue`'s own EVENT SURFACE DECISION comment) it is deliberately left set/open
  after this action fires, not cleared. **What T13 (or whichever task wires
  `LibraryView.vue`/`FolderList.vue`) must still connect**: on reaching `/library` (or
  specifically its Folders tab), read `useTrackMenu().activeTrack` to know which track to
  scroll to/highlight, then call `closeTrackMenu()` once it has been handled — right now
  nothing closes the sheet after `view-in-folder` fires, so if T13's screen doesn't call
  `closeTrackMenu()`, the sheet stays open and renders (as `.backdrop`, `position: fixed;
  inset: 0`) on top of the Library screen until the user taps its backdrop or close
  button. Did not invent a new shared ref for "which track to reveal" — `activeTrack` is
  already exactly that, and building a second mechanism would risk duplicating/colliding
  with whatever T13 designs for its own screen.

- **`TrackMenu` mount point**: added as the last child inside `<section ref="page"
  class="page">`, right after the `v-else="empty"` block — i.e. a DOM descendant of
  `page`, per T8's MOUNTING RECIPE ("placement in the DOM doesn't matter, it renders
  nothing until opened"). That recipe note undersells one thing specific to this screen:
  because `useVerticalDismiss(page, ...)` attaches its touch listeners directly on `page`
  and touch events bubble, `TrackMenu` being a DOM descendant of `page` means a touch
  starting anywhere inside the mounted sheet WOULD otherwise bubble up and be seen by that
  listener. That is exactly why the `ignore` list needed the fix below — it is not
  optional decoration.

- **`ignore` selector after this change**:
  `'.seek-input, .icon-button, .play, .backdrop'`.
  - Confirmed by reading: the new options button's class is exactly `icon-button` (no
    extra class), so it was already covered by the pre-existing `.icon-button` entry —
    no change needed there, just verified.
  - Added `.backdrop` — `TrackMenu.vue`'s actual root element class (`<div v-if="activeTrack"
    class="backdrop">`; the `<Transition>` around it renders no DOM node of its own, so
    `.backdrop` is genuinely the component's root once open). Chose the outer `.backdrop`
    over the inner `.sheet` deliberately: `.sheet` is a descendant of `.backdrop`, so
    ignoring `.backdrop` already covers every touch that starts anywhere inside `.sheet`
    too (via `event.target.closest('.backdrop')` in `useVerticalDismiss.js:59`), while also
    covering the (mostly-hidden, but real) backdrop area around the sheet that `.sheet`
    alone would miss.

- **No identifier collision**: `dragging` (the seek bar's own ref, declared once at
  `NowPlayingView.vue:43`) is untouched. The composable's return is destructured as
  `dragging: sheetDragging` (pre-existing rename from T-whatever-added-drag-to-dismiss,
  not touched by this task) — grep of every `dragging`/`sheetDragging` hit in the file
  (reproduced above) shows exactly one declaration of each name, no redeclaration.

- **Verification run** (no build/adb/emulator, per Must Not Do):
  - `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) via `node -e`: all
    three stages clean — `parse errors: []`, `compileScript OK, bindings: 38`,
    `compileTemplate OK, errors: [] tips: []`.
  - `grep -n "dragging\|sheetDragging" src/views/NowPlayingView.vue` — every hit reproduced
    above; exactly one declaration of `dragging`, one destructure of `sheetDragging`, no
    collision.
  - `grep -n "ignore:" src/views/NowPlayingView.vue` — shows the updated selector with
    `.backdrop` appended.
  - `grep -n "TrackMenu\|openTrackMenu" src/views/NowPlayingView.vue` — shows the import,
    the composable destructure, the mount, and the click wiring.
  - `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views/NowPlayingView.vue | grep -v "t("` —
    empty (gate passes).
  - Not run: `npm run build`, Gradle, `adb`, `npm run qa`, any emulator/device interaction
    — per Must Not Do. In particular, the drag-to-dismiss-vs-sheet-drag interaction (the
    whole reason `.backdrop` was added to `ignore`) has NOT been felt on a device; this is
    exactly the kind of touch-gesture interaction the user needs to verify by hand once a
    build is available.

## T13

Files touched: `src/views/LibraryView.vue`, `src/components/FolderList.vue`, `src/router/index.js`.
No `src/i18n/*`, no `TrackMenu.vue`/`useTrackMenu.js`/`TrackRow.vue`/`PlaylistView.vue`/
`NowPlayingView.vue`, no build/gradle/adb/emulator/qa — per Must Not Do.

- **`expanded` hoist (the fix).** `FolderList.vue`'s `expanded` ref moved from inside
  `<script setup>` (component-instance scope — reset to `[]` every time `LibraryView` unmounts
  this component, e.g. on every trip to Now Playing or another tab and back, since `App.vue`'s
  `<RouterView />` has no `<keep-alive>`) into a plain, non-`setup` `<script>` block that runs
  once when the module is first imported (module scope), exported as `export const expanded =
  ref([])`. This is the SAME mechanism `usePlayer.js:8-32` uses (declared outside the exported
  factory function), just expressed as an SFC's two-script-block form instead of a plain `.js`
  file, since a `.vue` file's `<script setup>` body is per-instance by definition — a normal
  `<script>` block in the same file is the only way to get code that runs exactly once for the
  whole module. **Behaviour change**: a folder that used to silently collapse whenever you left
  the Library now stays open until you tap it closed again — this is what the old comment
  always claimed, now actually true. `grep -n "const expanded" src/components/FolderList.vue`
  → `18:export const expanded = ref([])`, at module scope, above `<script setup>` entirely.
- **`expandFolder(key)` exported** alongside `expanded` — the "expand this folder" entry point
  `LibraryView` (and `PlaylistView`, cross-view) drive without a prop drill: `if
  (!expanded.value.includes(key)) expanded.value = [...expanded.value, key]`.
- **`tab` ALSO hoisted to module scope, in `LibraryView.vue`** (not explicitly named in the
  plan's T13 checkbox, but required by the Expected Outcome's cross-view bullet, which assumes
  "expanded and tab are both shared state now"). Same mechanism as `expanded`: `export const tab
  = ref('songs')` in a plain `<script>` block above `<script setup>`. **Behaviour change,
  distinct from the `expanded` one and flagged separately**: the Library's selected tab now
  survives a trip to Now Playing / Settings / a playlist and back — it used to reset to Songs on
  every remount. This is a side effect of making it drivable from another view, not a separate
  goal; call this out to the user alongside the `expanded` change.
- **View-in-folder, same-view case**: `<TrackMenu @view-in-folder="onViewInFolder" />` mounted
  as a template root sibling of `LibraryView`'s `<section ref="page">` (Vue 3 multi-root
  fragment; `TrackMenu` is `position: fixed` and renders nothing until a menu is open, per T8).
  `onViewInFolder(track)` does: `expandFolder(track.folder)`, `tab.value = 'folders'`, `await
  nextTick()`, then scrolls the matching folder row into view. `track.folder` IS the folder
  group's key already — `useLibrary.js`'s `groupBy('folder', '')` sets `name = track.folder ||
  null` then `key = name ?? fallbackKey` with `fallbackKey = ''`, so a track with `folder ===
  ''` groups under key `''` too — the SAME value. There is therefore no "nonexistent key" case
  to special-case; every track's `folder` names a real, always-rendered group (the "root"
  group, shown via `t('library.folders.root')`), including the empty-string one. `expandFolder`
  and the `tab` switch both run synchronously before any `await`, so Vue queues one render
  flush for both; a single `nextTick()` resolves after that flush's DOM patch, which is the
  earliest point `<FolderList>` (freshly mounted if the previous tab wasn't Folders) has
  rendered the target folder's (already-expanded) row. The row carries a new `data-folder-key`
  attribute (added to `FolderList.vue`'s folder-row `<button>`, same idiom as `TrackRow.vue`'s
  `data-track-id`); the scroll target is found by scanning every `[data-folder-key]` element
  and comparing `el.dataset.folderKey === track.folder` in JS, NOT by interpolating the key into
  a `querySelector` string, so a folder path containing a quote character can never break the
  selector. `row?.scrollIntoView({ block: 'center', behavior: 'smooth' })`.
- **Cross-view case (`/playlist/:id` → Folders tab), what T11 must do.** The Expected Outcome
  only requires "the app ends up on the Library's Folders tab with the folder expanded" for
  this path — it does NOT require the folder to be scrolled into view (unlike the same-view
  case). Since `expanded` (module-level, `FolderList.vue`) and `tab` (module-level,
  `LibraryView.vue`) are both shared state now, T11's `PlaylistView.vue` needs exactly:
  ```js
  import { expandFolder } from '../components/FolderList.vue'
  import { tab } from '../views/LibraryView.vue'
  // in its own TrackMenu's @view-in-folder handler:
  function onViewInFolder(track) {
    expandFolder(track.folder)
    tab.value = 'folders'
    router.push('/library')
  }
  ```
  No scroll-into-view code needed on T11's side — when `LibraryView` mounts fresh at
  `/library`, `tab.value` is already `'folders'` and `expanded` already contains the key, so the
  Folders tab renders open to that folder immediately; there is nothing left for T11 to trigger
  after navigating. If T11 also wants the row scrolled into view cross-screen, that would need a
  new one-shot signal (not built here, since the Expected Outcome didn't ask for it) — flagging
  as an option, not doing it unilaterally.
- **Mounted `TrackMenu` for the Songs tab and forwarded `options` from `FolderList`.** Songs
  tab's `<TrackRow @options="openTrackMenu" />` and `FolderList`'s own `@options="openTrackMenu"`
  (which itself forwards its nested `TrackRow`'s `@options="$emit('options', track)"`) both call
  the SAME `openTrackMenu` from `useTrackMenu()` — one shared sheet, exactly per T8's mounting
  recipe. `defineEmits(['select', 'options'])` added to `FolderList.vue`.
- **Hardware-back guard, landed in `wireHardwareBack` (`src/router/index.js`)**, exactly the
  shape T8 documented: `const { isOpen: trackMenuOpen, closeTrackMenu } = useTrackMenu()` called
  once at module scope (mirrors the existing `usePlaylists()` line — both are lifecycle-hook-free
  factories, safe to call outside a component), then inside the `backButton` listener, BEFORE
  the existing `/library` check:
  ```js
  if (trackMenuOpen.value) {
    closeTrackMenu()
    return
  }
  ```
  **Back-press behaviour, stated plainly**: with the sheet open, one back-press closes the sheet
  and does nothing else (no navigation, no `exitApp`) — the `return` stops the listener before
  the `/library`/`router.back()`/`router.replace()` checks run. With the sheet closed, behaviour
  is byte-for-byte what it was before this task: `/library` backgrounds the app, everywhere else
  walks router history or falls back to `/library`. Verified by reading the code path only — no
  emulator/device, per Must Not Do, so this has not been felt on hardware.
- **No new i18n keys needed by this task** — `data-folder-key` and the wiring introduce no new
  user-facing strings; every string touched (`trackMenu.*`, `library.tab.playlists`, etc.) was
  already recorded by T7/T8/T9/T10.
- **What was checked to confirm the Library screen wasn't regressed**: read `LibraryView.vue` in
  full both before and after editing. Five `TABS` entries, `stepTab`'s index-based no-wrap logic,
  `useHorizontalSwipe`'s `ignore: '.segmented, .search'`, the segment scroll-into-view `watch`,
  `useLazyArtwork`'s `[tab.value, visibleTracks.value]` source, the scan button's `scanBusy`/
  `MIN_SPIN_MS` window, the permission/blocked/empty states, and T9's `PlaylistList` wiring are
  all untouched except for the additive lines listed above (confirmed via the full diff, not
  spot checks) — the only removed line is the old in-`<script setup>` `const tab = ref('songs')`,
  replanted verbatim (same initial value) in the new module-scope `<script>` block.
- **Verification run**: `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) on
  both `LibraryView.vue` (bindings: 59) and `FolderList.vue` (bindings: 13) — all three stages
  clean on both, no errors, no tips. `node --check src/router/index.js` → OK. `grep -n "const
  expanded" src/components/FolderList.vue` → module scope, confirmed above. `grep -n
  "TrackMenu\|openTrackMenu\|closeTrackMenu" src/views/LibraryView.vue src/router/index.js` →
  shows the mount, both `@options` wires, and the router guard. `grep -n "beforeEach" -A14
  src/router/index.js` → the `/downloader` guard and T10's playlist-id guard both intact,
  unmodified except for the new back-button guard sitting above `wireHardwareBack`'s body (a
  different function). Hardcoded-text gate
  (`grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views/LibraryView.vue src/components/FolderList.vue
  | grep -v "t("`) → empty. No build, no `adb`, no emulator, no `npm run qa` — per Must Not Do;
  the scroll-into-view and the back-press have been verified by reading the code only, not on a
  device or in a browser.

## T11

Files: `src/views/PlaylistView.vue` (modified, T10's file), NEW `src/composables/useDragReorder.js`,
and (post-review fix, 2026-08-21, explicitly authorised by the coordinator since T6 had finished
and no other agent was running against it) `src/composables/usePlaylists.js`. No `src/i18n/*`, no
`TrackMenu.vue`/`useTrackMenu.js`/`TrackRow.vue`/`useVerticalDismiss.js`/`useHorizontalSwipe.js`
touched, no `LibraryView.vue`/`FolderList.vue`/`router/index.js` touched (T13's, already landed by
the time this ran — see below), no build, no gradle, no adb/emulator/`npm run qa` — per Must Not
Do. **Drag thresholds below are reasoned
defaults, never felt on a device or emulator** (the user withdrew both, same posture T21/T22 took
in the previous plan) — they are for the user to judge, not verified empirically.

### Composable-vs-inline decision: extracted `useDragReorder.js`

Went with a NEW composable rather than inlining the gesture in `PlaylistView.vue`, for the same
reason `useHorizontalSwipe`/`useVerticalDismiss` are composables and not inlined in their own
views: the touch state machine (phase tracking, the long-press timer, row-height measurement,
target-index math, the per-row displacement formula) is a self-contained unit that has nothing to
do with playlists specifically — it operates on "a container of same-height rows" and reports back
`(fromIndex, toIndex)`, leaving the view to decide what a reorder MEANS for its own data. Keeping
it out of the view also keeps `PlaylistView.vue`'s own script to "what happens when a drop occurs"
(the playlist-specific part) rather than mixing that with raw touch-event arithmetic.

### The three gestures, and why this one's `preventDefault` policy differs from both

`useHorizontalSwipe` (mounted only in `LibraryView.vue`, confirmed via `grep -rn
"useHorizontalSwipe" src/` — the only hit outside its own file's self-referential comments is
`LibraryView.vue:14,62`; `PlaylistView.vue` is a separate route and never mounts it) stays passive
forever because its surface is a vertically scrolling list it must never break, and it can afford
to decide "swipe or not" entirely at `touchend`, after the browser has already scrolled. That
option doesn't exist here: a reorder drag has to visually carry the row and displace its neighbours
WHILE the finger is still down, which means deciding mid-gesture, not at the end.

`useVerticalDismiss` also decides mid-gesture and also calls a gated `preventDefault()`, but its
screen (Now Playing) never scrolls at all — stealing the touch there costs nothing extra. This
gesture's problem is exactly the one neither of those two ever has to solve: it lives INSIDE a
scrolling `<ul>` and still needs to steal the touch once (and only once) a drag is really
happening. The latch that makes that safe is two-stage, not one-stage like the other two's: a
touch must sit still (within `MOVE_CANCEL_PX` = 10px) for `LONG_PRESS_MS` = 400ms before it can
even ARM, and even once armed, the actual pickup + first `preventDefault()` only fires on the
first move after arming — never on the arming instant itself, and never on a move that happened
before arming. A scroll (moves immediately, never sits still that long) never arms. A tap
(releases well inside 400ms) never arms. A horizontal swipe attempt starting on a row also never
arms, for the same reason a scroll doesn't — any movement before the timer fires aborts the
pending pickup outright, so `useHorizontalSwipe` (on `LibraryView.vue`, a different screen; not
relevant on this one, but the same principle would protect it if it ever were) would see an
untouched, un-prevented-default touch. `LONG_PRESS_MS` = 400 was chosen just under Android's
default long-press timeout (500ms, `ViewConfiguration.getLongPressTimeout()`) so this gesture wins
any race against OS-level long-press handling instead of fighting it for the same touch;
`MOVE_CANCEL_PX` = 10 mirrors the small slop `useVerticalDismiss.js` already allows (`Math.abs(dy)
< 8`) before its own direction test, scaled up slightly since "hold still" needs to tolerate normal
finger jitter over 400ms, longer than an instantaneous direction check.

### TrackMenu mount and drag not fighting each other

`<TrackMenu @view-in-folder="onViewInFolder" />` mounted as a template-root sibling of
`<section class="page">` (Vue 3 multi-root fragment, same pattern T13 used in `LibraryView.vue`).
`<TrackRow @select="playFrom" @options="openTrackMenu" />` — `openTrackMenu` imported straight from
`useTrackMenu()`, no local "which track" state added, per T8's mounting recipe. The two gestures
sit on different elements on purpose: `useDragReorder`'s `ignore: '.row-menu'` means a touch
starting on the kebab button is rejected by the gesture at `touchstart` before anything else
happens, so the button's own `@click` fires completely normally (opens the menu) regardless of how
long it's held; conversely, a long-press-drag starting anywhere else on the row never touches
`.row-menu`'s click handler at all, since the drag's `preventDefault()` only ever fires on
`touchmove`, past the point the pickup already committed to being a drag rather than a tap. Only a
row (not the whole page) carries `data-row-index`, and only `[data-row-index]` elements are ever
found via `closest()` in `useDragReorder`'s `onStart` — a touch starting outside any row (e.g. the
header bar) is ignored entirely, same as `ignore` regions are.

### Only a user playlist is reorderable — reused the existing check

`data-row-index` is bound as `kind === 'user' ? index : null` on each `<li>` — the exact three-way
`kind` computed the file already had (`'favorites' | 'recentlyAdded' | 'user'`), not a second
"reorderable" flag. `useDragReorder` is still wired up unconditionally on `trackList` for every
kind; with no `[data-row-index]` present on a Favorites/Recently-added row, `onStart`'s `closest()`
lookup always fails on those screens and the gesture is a complete no-op there — no runtime
`if (kind === 'user')` branch needed around the wiring call itself, only around the one attribute.

### Live visual reorder while dragging, no animation library

`shiftFor(index)` (in the composable) returns `-rowHeight`/`+rowHeight`/`0` per row depending on
whether that row sits between the drag's start index and its live target index — the target index
itself is `dragIndex + round(dragOffset / rowHeight)`, clamped to the list bounds. The view applies
that as an inline `transform: translateY(...)`, with a static `.row-wrap { transition: transform
0.15s ease }` rule so OTHER rows slide smoothly as the target changes; the dragged row itself gets
`transition: none` inline so it tracks the finger with zero lag (a lagging carried row would read
as broken, not smooth). `.row-wrap.dragging` adds a `z-index`/`--surface-1` background lift, same
token `.row-main:active` already uses elsewhere in this file, so the "picked up" row reads as
raised without inventing a new surface. `prefers-reduced-motion` turns the shift transition off
entirely (not shortened) — same posture as every other animation in this codebase
(`MiniPlayer.vue`, `TrackMenu.vue`). No `<TransitionGroup>`, no FLIP library: the underlying array
is never spliced until the actual drop, so there's nothing for an animation library to animate
between — only the live translateY offsets, computed by hand.

### Commit: full new order, missing entries preserved (fixed post-review, see below)

**Post-review correction (2026-08-21)**: the first version of this task called
`reorderPlaylist(id, resolved)` — track objects only — which meant any path that had NOT
resolved to a track (decision 3's "file moved/renamed on disk" case, `missingCount`) was
silently dropped from storage forever the moment the user dragged an UNRELATED row, since
`reorderPlaylist` rewrites the playlist's entire stored order in one transaction and there was
no way to hand it a path with no matching track object. The coordinator caught this as
unacceptable: decision 3 accepted that a moved/renamed file loses VISIBILITY, not that an
entry the user never touched gets permanently deleted. Fixed as follows — this section
replaces the earlier "flagged rather than solved" note, which is no longer accurate.

**The fix, in `usePlaylists.js`**: split `reorderPlaylist` into a path-accepting primitive,
`reorderPlaylistPaths(id, paths)` — the ONE function that actually calls
`Player.reorderPlaylist`, taking `paths: string[]` directly — and kept `reorderPlaylist(id,
tracks)` as a one-line wrapper (`reorderPlaylistPaths(id, tracks.map(pathOf))`) for callers
whose whole new order is real tracks. Chose "paths-accepting sibling, tracks-taking function
becomes a thin wrapper" over "add an optional second parameter to the existing function"
because it keeps exactly one function reaching the plugin (satisfying "one place that can be
wrong") while leaving every OTHER existing caller of `reorderPlaylist(id, tracks)` (there are
none yet outside this task, but the exported contract stays the shape T6 documented) exactly
as it was — no call site has to learn a new optional-tail argument to keep working, and a
future caller with an all-resolved list still gets the simpler track-taking API. Both
functions share the same error handling (`errorMessage`, never throws) since
`reorderPlaylistPaths` is the only one that touches the plugin or `errorMessage` at all.
`reorderPlaylistPaths` is exported alongside `reorderPlaylist` on `usePlaylists()`'s returned
object.

**The fix, in `PlaylistView.vue`'s `onDragCommit`**: `resolved` (the dragged-and-spliced
`userTracks.value` copy) is mapped to `resolvedPaths`; every path in `playlistPaths.value`
that is NOT in that set is exactly `missingCount`'s own underlying set (paths with no
`trackByPath` entry) — reused directly via a `Set` diff rather than recomputed — and is
appended AFTER `resolvedPaths` to form `newPaths`. `newPaths.length` is always
`playlistPaths.value.length` (nothing added, nothing dropped): `resolvedPaths.length ===
userTracks.value.length` (a splice never changes array length) and the appended
`unresolvedPaths` is precisely the complement within the original `playlistPaths.value`.
`reorderPlaylistPaths(numericId.value, newPaths)` is what's actually sent, never
`reorderPlaylist`. Appending unresolved paths (rather than trying to reproduce their exact
prior interleaved position among the visible ones) was the chosen placement: they render as
nothing on screen, so there is no "correct" position the user ever saw for them, and
preserving their original interleaving would require tracking positions this UI never
displayed in the first place — appending is the simplest choice that loses no data, which is
the actual requirement.

**Walking the five-path scenario the coordinator posed**: playlist paths = `[A, B, C, D, E]`,
`E` renamed on disk so it doesn't resolve — `userTracks.value` shows 4 rows
`[trackA, trackB, trackC, trackD]`, `missingCount === 1`. User drags row 2 (`trackB`) above row
1 (`trackA`): `fromIndex=1, toIndex=0`. `resolved` becomes `[trackB, trackA, trackC, trackD]`
after the splice; `resolvedPaths = [B, A, C, D]`. `unresolvedPaths = playlistPaths.value.filter
(p => !resolvedPathSet.has(p))` — the original `[A,B,C,D,E]` with `A,B,C,D` removed — is `[E]`.
`newPaths = [B, A, C, D, E]`, length 5, matching the original 5 exactly. The write sends `{id,
paths: [B, A, C, D, E]}`; the DAO deletes all 5 existing rows and reinserts these 5 paths at
positions 0-4 — `E` ends up last (previously it may have been anywhere; that relative-order
cost is the deliberate "append" choice above, not a bug), but it still EXISTS, at position 4.
If the user later renames the file back so its path matches `E` again, the next
`listPlaylistPaths` fetch returns `[B,A,C,D,E]` and `trackByPath` resolves `E` once more — the
playlist shows all 5 tracks, `E` last. Nothing was lost; only its position (which was never
visible to begin with) changed.

### Revert-on-failure: YES, and why

`reorderPlaylistPaths()` never throws (`usePlaylists.js`'s error policy) — success unconditionally
clears `errorMessage` to `''`, failure unconditionally sets it to a message, so checking
`errorMessage.value` truthy immediately after the `await` is a precise (not heuristic) signal that
THIS call failed, regardless of what `errorMessage` held before the call. On that signal,
`onDragCommit` reverts `playlistPaths.value` back to its pre-drag snapshot. Chose to revert because
the alternative — leaving the optimistic order applied — is actively worse than a no-op: the next
time this playlist screen is opened, a fresh `listPlaylistPaths` fetch would silently snap the
order back to whatever's actually persisted, with nothing on screen to explain why the user's drag
"undid itself" minutes later. Reverting immediately, in the same interaction, keeps the screen
honest about what's actually stored.

### Cancelled drag (touchcancel, or a release that never armed) — no write, verified by construction

`useDragReorder` never mutates anything the view owns until `onDrop` fires, and `onDrop` only fires
from `onEnd` when `phase === 'dragging'` AND the target index actually differs from the start
index. A `touchcancel` (or any drop while still `'pending'`/`'armed'`) runs the same `reset()` that
zeroes `dragOffset`/`dragIndex` without ever calling `onDrop` — so "no write on cancel" isn't a
separate check to write, it falls out of `onDrop` only ever being reachable from the one committed
path.

### `view-in-folder`: used T13's already-landed mechanism, not a guess

T13 landed first (this batch) and exported exactly the shared state this needed —
`expandFolder(key)` (module-scope, `FolderList.vue`) and `tab` (module-scope, `LibraryView.vue`) —
and documented, in its own `## T13` notepad entry, the precise snippet "T11" should call. Used it
verbatim: `onViewInFolder(track)` calls `expandFolder(track.folder)`, sets `libraryTab.value =
'folders'` (imported as `tab as libraryTab` to avoid shadowing anything local), then
`router.push('/library')`. No scroll-into-view step needed here (T13's note: that's only required
for the same-view case, which `LibraryView.vue` itself already handles) — by the time
`LibraryView` mounts fresh at `/library`, `tab`/`expanded` are already set, so `FolderList` renders
open to the right folder with nothing left for this file to trigger. This supersedes an earlier
draft of this task that considered leaving the event unhandled / routing blind — T13's export
existed and was documented before this file's `onViewInFolder` was finalized, so using it directly
was the more honest option once it was available, not a guess at a mechanism that didn't exist yet.

### i18n

No new keys — this task added no new user-facing strings (drag has no label; `view-in-folder`'s
strings were already reserved by T8/T10).

### Verification run

- `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) on `PlaylistView.vue`:
  `compileScript OK, bindings: 58`; `compileTemplate OK, errors: 0, tips: 0` (re-run clean after
  the post-review `reorderPlaylistPaths` fix too — same binding count, since the destructured
  name swap doesn't change the count).
- `node --check src/composables/useDragReorder.js` → OK.
- `node --check src/composables/usePlaylists.js` → OK (post-review fix file).
- `grep -n "TrackMenu\|openTrackMenu\|expandFolder\|libraryTab" src/views/PlaylistView.vue` → shows
  the import, the mount, the `@options` wire, and the `onViewInFolder` body.
- `grep -n "reorderPlaylist" src/views/PlaylistView.vue src/composables/usePlaylists.js` → confirms
  `PlaylistView.vue` calls `reorderPlaylistPaths` only (never the track-taking `reorderPlaylist`),
  and `usePlaylists.js` shows `reorderPlaylistPaths` as the sole caller of
  `Player.reorderPlaylist`, with `reorderPlaylist` reduced to a one-line wrapper over it.
- `grep -n "preventDefault" src/composables/*.js src/views/PlaylistView.vue` → `useDragReorder.js`'s
  only real call sits inside the `dragging`-phase branch of `onMove`, gated exactly like
  `useVerticalDismiss.js`'s; `useHorizontalSwipe.js` still has none; `PlaylistView.vue` itself has
  none (the gesture logic lives entirely in the composable).
- `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views/PlaylistView.vue | grep -v "t("` → empty.
- `grep -rn "useHorizontalSwipe" src/` → only `useHorizontalSwipe.js` itself (definition + its own
  comments) and `LibraryView.vue:14,62` — confirms it is not mounted in `PlaylistView.vue`.
- No build, no gradle, no adb/emulator/`npm run qa`, per Must Not Do.
- No new i18n keys from this fix — `reorderPlaylistPaths` is an internal rename/split with no
  user-facing string, and the fix changes only which paths get persisted, not anything rendered.

## F6

Added a new `## Playlists and favourites` section to `README.md`, inserted between `## QA` and
`## Roadmap` (after the mainline feature docs, before the roadmap/release/hidden-tab sections, so
it reads as a core feature like the library groupings, not a hidden extra). Covers, in order:
what the tab is (fifth tab, list of lists, tap to open); what "Recently added" means (newest 15 by
`DATE_ADDED` = scanner index time, ties broken by the collator on title, `0`/unknown sorts last);
where the data lives (`kefi-library.db`, schema v1, three tables `playlists`/`playlist_items`/
`favorites` with exact columns/FK/unique-index read from
`android/app/schemas/com.kefi.player.library.LibraryDatabase/1.json`, no personal data, no network
call, kapt-not-KSP with the `2.2.10`/decoupled-`2.3.x` reasoning and a pointer to this file's `##
T2`); the path-keyed identity choice and its cost (move/rename drops membership, no hashing,
framed as deliberate per `decisions.md` item 3); what CRUD exists (create/rename/delete/add/
remove/drag-reorder for user playlists; Favorites/Recently added fixed and immutable); the track
options menu (kebab on row + Now Playing, the five actions verbatim from `decisions.md` item 6, no
one-tap heart per item 7, and the `WRITE_SETTINGS` Settings-round-trip from T12's learnings); and
the two behaviour changes from T13 (folder-expansion survives navigation, Library tab survives
navigation), called out explicitly as visible side effects.

**Existing README text judged inaccurate and amended:**
- "What the app does" bullet "Reads the whole device library with no database... no Room, no
  SQLite..." was now false at the app level (Room exists, just not for the listing itself).
  Reworded to scope the "no database" claim to the track listing specifically and cross-reference
  the new section, instead of dropping the sentence's substance.
- The "Deliberately not in this version" list named "playlists and favourites" as absent — no
  longer true, removed from that list (left the other absent features: sleep timer, playback
  speed, equalizer, streaming, network-outside-downloader).
- `## Roadmap` item 1 ("Playlists and favourites — the first feature that genuinely needs
  persistence beyond SharedPreferences; the intended home is Room") described shipped work as
  future work. Removed it and renumbered the remaining item (sleep timer/playback speed) to `1`.

**Judged NOT to need amending**: the "Permission model" section's "nothing leaves the device"
claims are scoped to network/permissions specifically and remain true (Room makes no network
call, needs no permission) — addressed instead inside the new section's own "where the data
lives" paragraph ("stores nothing beyond what's already on the device and makes no network
call"), rather than editing Permission model, since that section was never about databases. Left
the "Layout" section's non-exhaustive directory listing alone (it already omits `DownloaderView.vue`/
`useEasterEgg.js` from the previous plan, so `PlaylistView.vue`/`usePlaylists.js` being absent too
is consistent with existing practice, not a fresh omission this task created).

Verification: `grep -n "Recently added\|kefi-library\|kapt\|WRITE_SETTINGS" README.md` — 7 hits,
all inside the new section. `git diff --stat -- README.md` → `1 file changed, 65 insertions(+), 9
deletions(-)`, confirming README.md is the only tracked file touched. `git status --porcelain --
README.md .claude/notepads/kefi-player-playlists-tab/learnings.md` shows only those two paths
touched by this task (the notepad file itself is untracked/new, expected). No builds, no adb, no
emulator, no gradle — per Must Not Do.

## T15

Files touched: `src/i18n/en.js`, `src/i18n/el.js` only (plus this notepad). Landed all 37
authoritative keys, in the same order in both files, none duplicated, no existing key's value
touched.

### Placement

`library.count.playlist`/`library.count.playlists` inserted into the existing `library.count.*`
group, immediately after `library.count.folder` (same plural-then-singular order as the
`songs`/`song` and `folders`/`folder` pairs already there). The new `playlists.*` block (17 keys)
and `trackMenu.*` block (18 keys) were inserted together as one unit, right after
`library.empty.action` and before `permission.ask.title` — i.e. directly after the `library.*`
block, per the task's placement instruction, keeping both new feature blocks adjacent rather than
scattering `trackMenu.*` elsewhere in the file with no better anchor point available.

`playlists.*` internal order: list names first (`favorites`, `recentlyAdded`), then
create-related (`create`, `create.placeholder`, `create.action`), then
rename/delete-related (`rename`, `renameLabel`, `delete`, `confirmDelete`), then the missing-entry
pair (`missingEntry`, `missingEntries`), then the three empty-state title/body pairs
(`favorites`, `recentlyAdded`, `userPlaylist`).

`trackMenu.*` internal order follows the sheet's own template top-to-bottom: `open` (shared aria
label), the five actions in the order they appear (`favourite`/`unfavourite`, `addToPlaylist`,
`setRingtone`, `setNotification`, `viewInFolder`), `close`, then the add-to-playlist step's own
controls (`back`, `newPlaylist`, `newPlaylistPlaceholder`, `createAndAdd`, `cancel`,
`playlistTrackCount`), then the three notice strings (`ringtoneSet`, `notificationSet`,
`permissionNeeded`, `actionFailed`).

### Changes made to the proposed strings, and why

- **`trackMenu.favourite`/`unfavourite` (Greek only) — the one substantive fix.** The T8 proposal
  had EL `favourite` = «Αγαπημένο» and `unfavourite` = «Αφαίρεση από αγαπημένα». «Αγαπημένο» is
  just the adjective/tag "favourite" — it reads as a label, not something you tap to DO, which is
  exactly what Decision 7 / Inherited Wisdom warns against (this is a menu-row ACTION, no one-tap
  heart exists). It was also uncomfortably close to `playlists.favorites` = «Αγαπημένα» (the list
  NOUN) — same root, one letter apart, which risks reading as the same string reused rather than
  two deliberately different ones. Changed to a deverbal-noun action phrase, matching how every
  other action label in this file already works (`Ενημέρωση`, `Διαγραφή`, `Αναπαραγωγή`,
  `Μετονομασία` — noun-of-the-verb, not a bare adjective):
  - `trackMenu.favourite` EL: «Προσθήκη στα αγαπημένα» (was «Αγαπημένο»)
  - `trackMenu.unfavourite` EL: «Αφαίρεση από τα αγαπημένα» (was «Αφαίρεση από αγαπημένα» — added
    the missing article «τα», which the original was ungrammatical without)
  Now `playlists.favorites` («Αγαπημένα», the list) and `trackMenu.favourite`
  («Προσθήκη στα αγαπημένα», the action) are unambiguously different strings serving different
  grammatical roles, per the task's explicit requirement not to collapse them. EN left as
  `Favourite`/`Unfavourite` — a bare verb-as-button-label is idiomatic English UI (`Follow`), the
  problem was Greek-specific.
- **`trackMenu.permissionNeeded` (Greek only) — register fix.** The T8 proposal used formal/plural
  imperative (`επιτρέψετε`, `δοκιμάστε`), which is out of register with the rest of this file's
  informal singular voice (`Ενεργοποίησε ξανά…`, `Διάλεξε…`, `Αντέγραψε…`). Since the task flagged
  this as "the single most easily-mistranslated string in the set," fixed it to match house style:
  EL now «Άνοιξαν οι Ρυθμίσεις συστήματος για να το επιτρέψεις — δοκίμασε ξανά μόλις δοθεί η
  άδεια.» (was «...επιτρέψετε... δοκιμάστε...»). Kept the EN and the underlying meaning exactly as
  proposed (states what happened, tells the user what to do next, no error tone) — only the
  Greek's formality level changed.
- **`trackMenu.actionFailed` — aligned with the existing `error.generic` key.** The proposal had
  EN "Something went wrong" (no period) / EL «Κάτι πήγε στραβά» — functionally identical to the
  pre-existing `error.generic` ("Something went wrong." / «Κάτι πήγε λάθος.») but phrased with a
  different Greek word for "wrong" (`στραβά` vs `λάθος`) and inconsistent punctuation. Since both
  keys mean the same thing in the same app, made `trackMenu.actionFailed` textually identical to
  `error.generic` (added the closing period on both langs' text, changed EL to `λάθος`) rather than
  inventing a second Greek word for the same failure — `error.generic` itself was NOT touched, only
  matched.
- **`playlists.empty.recentlyAdded.title`/`.body` (Greek only) — smoothed a calque.** Title
  changed from the proposal's terse «Τίποτα πρόσφατο» to «Δεν προστέθηκε τίποτα πρόσφατα», to match
  the "Δεν [verb] ... τίποτα" construction this file already uses for an analogous empty state
  (`nowPlaying.empty.title`: «Δεν παίζει τίποτα»), rather than the noun-phrase construction used for
  the OTHER two Playlists empty titles by coincidence of English phrasing. Body reworded from a
  literal "έχει ημερομηνία σάρωσης" (has a scan date — overly technical, a normal user has never
  heard of a "scan") to «...αποκτήσει ημερομηνία προσθήκης για αυτά» (acquires an added date),
  which reads more naturally and ties back to the list's own name («Πρόσφατα προστέθηκαν» —
  "recently ADDED"), matching EN adjusted in parallel ("has a scan date" → "has an added date for
  them") so both languages describe the same, less jargon-heavy concept.
- Every other proposed string (from T7, T9, T10) landed as proposed, unchanged, after confirming
  each against its real call site (see the per-key grep list below) for length/context fit — none
  of the remaining ones needed a fix.

### Flag for the orchestrator: `library.tab.playlists` is called by code but is NOT one of the 37

`LibraryView.vue:337` renders the fifth segmented-control tab via `{{ t(\`library.tab.${name}\`) }}`
— a dynamic template-literal key, which is why a static `grep`/AST extraction of literal `t('...')`
calls (the method used to build the authoritative 37-key list) would miss it: `name` includes
`'playlists'` (from `TABS`, `LibraryView.vue:67`), so at runtime this resolves to
`t('library.tab.playlists')`. That key does NOT exist in either locale file (confirmed: it was not
present before this task, and is not among the 37). Per the Must NOT Do ("do NOT invent a key that
no code calls... if you believe one is wrong or missing, say so rather than silently adjusting"),
I did **not** add it — the 37-key list was stated as the exact, authoritative set and I have no
authority to expand it unilaterally. Until someone adds `library.tab.playlists` to both files, the
Playlists tab's own label in the segmented control renders the raw string `"library.tab.playlists"`
instead of "Playlists"/«Λίστες» — this is the one place left in the whole plan where a raw key
still shows. Proposed values, for whoever lands this (T9's own notepad entry already proposed the
same thing): EN `"Playlists"`, EL `«Λίστες»` (placed in `library.tab.*`, after
`library.tab.folders`, matching `TABS`' append-only order).

### Final EN / EL text for all 37 keys (reference)

| key | EN | EL |
|---|---|---|
| `library.count.playlist` | `1 list` | `1 λίστα` |
| `library.count.playlists` | `{count} lists` | `{count} λίστες` |
| `playlists.favorites` | `Favorites` | `Αγαπημένα` |
| `playlists.recentlyAdded` | `Recently added` | `Πρόσφατα προστέθηκαν` |
| `playlists.create` | `Create playlist` | `Δημιουργία λίστας` |
| `playlists.create.placeholder` | `Playlist name` | `Όνομα λίστας` |
| `playlists.create.action` | `Create` | `Δημιουργία` |
| `playlists.rename` | `Rename` | `Μετονομασία` |
| `playlists.renameLabel` | `Playlist name` | `Όνομα λίστας` |
| `playlists.delete` | `Delete` | `Διαγραφή` |
| `playlists.confirmDelete` | `Delete "{name}"? This can't be undone.` | `Διαγραφή της «{name}»; Δεν αναιρείται.` |
| `playlists.missingEntry` | `1 track could not be found — it may have moved or been deleted.` | `Ένα κομμάτι δεν βρέθηκε — μπορεί να μετακινήθηκε ή διαγράφηκε.` |
| `playlists.missingEntries` | `{count} tracks could not be found — they may have moved or been deleted.` | `{count} κομμάτια δεν βρέθηκαν — μπορεί να μετακινήθηκαν ή διαγράφηκαν.` |
| `playlists.empty.favorites.title` | `No favourites yet` | `Δεν έχεις αγαπημένα ακόμα` |
| `playlists.empty.favorites.body` | `Add a track to Favourites from its options menu.` | `Πρόσθεσε ένα κομμάτι στα Αγαπημένα από το μενού επιλογών του.` |
| `playlists.empty.recentlyAdded.title` | `Nothing added recently` | `Δεν προστέθηκε τίποτα πρόσφατα` |
| `playlists.empty.recentlyAdded.body` | `Tracks appear here once your library has an added date for them.` | `Τα κομμάτια εμφανίζονται εδώ μόλις η βιβλιοθήκη σου αποκτήσει ημερομηνία προσθήκης για αυτά.` |
| `playlists.empty.userPlaylist.title` | `This playlist is empty` | `Αυτή η λίστα είναι άδεια` |
| `playlists.empty.userPlaylist.body` | `Add tracks to it from a track's options menu.` | `Πρόσθεσε κομμάτια από το μενού επιλογών ενός κομματιού.` |
| `trackMenu.open` | `Track options` | `Επιλογές κομματιού` |
| `trackMenu.favourite` | `Favourite` | `Προσθήκη στα αγαπημένα` |
| `trackMenu.unfavourite` | `Unfavourite` | `Αφαίρεση από τα αγαπημένα` |
| `trackMenu.addToPlaylist` | `Add to playlist` | `Προσθήκη σε λίστα` |
| `trackMenu.setRingtone` | `Set as ringtone` | `Ορισμός ως ήχος κλήσης` |
| `trackMenu.setNotification` | `Set as notification` | `Ορισμός ως ήχος ειδοποίησης` |
| `trackMenu.viewInFolder` | `View in folder` | `Προβολή στον φάκελο` |
| `trackMenu.close` | `Close` | `Κλείσιμο` |
| `trackMenu.back` | `Back` | `Πίσω` |
| `trackMenu.newPlaylist` | `New playlist…` | `Νέα λίστα…` |
| `trackMenu.newPlaylistPlaceholder` | `Playlist name` | `Όνομα λίστας` |
| `trackMenu.createAndAdd` | `Create and add` | `Δημιουργία και προσθήκη` |
| `trackMenu.cancel` | `Cancel` | `Άκυρο` |
| `trackMenu.playlistTrackCount` | `{count} tracks` | `{count} κομμάτια` |
| `trackMenu.ringtoneSet` | `Ringtone set` | `Ο ήχος κλήσης ορίστηκε` |
| `trackMenu.notificationSet` | `Notification tone set` | `Ο ήχος ειδοποίησης ορίστηκε` |
| `trackMenu.permissionNeeded` | `Opened system Settings so you can allow this — try again once it's granted.` | `Άνοιξαν οι Ρυθμίσεις συστήματος για να το επιτρέψεις — δοκίμασε ξανά μόλις δοθεί η άδεια.` |
| `trackMenu.actionFailed` | `Something went wrong.` | `Κάτι πήγε λάθος.` |

### Verification run (real output)

- `node --check src/i18n/en.js` → `en.js OK`. `node --check src/i18n/el.js` → `el.js OK`.
- **Key-set parity** (`import` both files, compare `Object.keys()` arrays directly, not a count):
  `en count: 137 el count: 137`, `SAME ORDER AND SET: true`, `only in en: []`, `only in el: []`.
- **All 37 required keys present in both**, checked individually via `Object.prototype
  .hasOwnProperty.call`, not inferred from the parity check above: `ALL 37 PRESENT IN BOTH: true`,
  zero `MISSING` lines printed.
- Hardcoded-text gate (`grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v
  "t("`) → empty (grep exit 1 = no match = gate passes).
- `git diff --stat -- src/i18n/en.js src/i18n/el.js` → `el.js | 43 ++...`, `en.js | 41 ++...`,
  `2 files changed, 84 insertions(+)` — additions only, confirming no existing key's line was
  touched.
- `git status --porcelain -- src/i18n/en.js src/i18n/el.js .claude/notepads/kefi-player-playlists-tab/learnings.md`
  → only those two locale files modified; the notepad shows untracked/new (expected, same as every
  prior task's note). No other file touched. No build, no gradle, no adb/emulator/`npm run qa` —
  per Must Not Do.

## T16

Files: `scripts/qa-playback.sh` only (plus this notepad). **Written, NEVER RUN** — no `adb`, no
emulator, no `npm run qa`, per Must Not Do; the user withdrew device access and the emulator is
retired, exactly as the plan's own Inherited Wisdom records. The new section carries its own
top-of-section comment saying the same thing, plus what a first real run should watch for
(CDP/UI-timing flakiness around every `sleep`, T15's strings still being raw keys, the reorder
check's documented gap — see below).

- **Checks added: 38 `pass "`/`fail "` call sites**, across 6 `step` blocks appended immediately
  before the existing `step "Summary"`:
  1. "Playlists tab: five tabs, fifth reachable" (3 sites: segment count == 5; selecting the 5th
     segment renders >= 3 `.playlist-row` rows — the structural proof the plan asked for, not just
     a class toggle; the 5th segment itself carries `.selected`).
  2. "Playlists tab content: Favorites and Recently added show real counts" (2 sites: the
     Favorites row's `.meta` contains a digit; the Recently-added row's `.meta` digit equals
     `min(library size, 15)`, computed from the same `$TRACKS` snapshot the preflight section
     already captured).
  3. "Create a playlist through the DOM, then confirm it survives a relaunch" (4 guard `fail`s for
     a missing `.create-row`/`.create-input`/`.create-confirm`/post-submit row, 1 `launch_app`
     guard `fail`, and 2 pass/fail pairs: appears with a 0-track count; survives
     `am force-stop` + relaunch).
  4. "Favourite round trip: toggle via the options sheet, confirm it persists" (3 guard `fail`s —
     unresolved path, missing `.row-menu`, `.backdrop` never appearing — 1 `launch_app` guard
     `fail`, and 3 pass/fail pairs: the toggle flips the native `listFavorites()` membership in the
     expected direction; the Playlists tab's Favorites `.meta` digit reflects the same
     `listFavorites()` count; the flip survives `am force-stop` + relaunch).
  5. "Reorder persistence (persistence contract only)" (1 guard `fail` for a missing playlist id,
     1 `launch_app` guard `fail`, 1 pass/fail pair: the detail screen renders the swapped order
     first after a relaunch).
  6. "The options sheet opens and closes" (1 guard `fail` for a missing `.row-menu`, 2 pass/fail
     pairs: `.row-menu` click renders `.backdrop`; a backdrop click removes it).
- **Every one of the 38 is capable of failing.** None re-reads a value with no action in between —
  the previous plan's caught placebo pattern (two checks that re-read a DOM value across a 9-tap
  and 10-tap gesture with no real taps performed) was the explicit thing to avoid; every check here
  either performs a real `.click()`/`dispatchEvent()` through the app's own handlers first, or (for
  the two "content" checks in step 2) reads a value that is only non-empty/correct if the real
  computed (`favoriteTracks`/`recentlyAdded`) is wired up — deleting the feature would make both
  read empty/wrong, which is the "if the feature were deleted, does this print FAIL?" bar the task
  set.
- **Selectors depended on** (a future UI change touching any of these should re-check this
  section): `.segment` / `.segment.selected` (`LibraryView.vue`'s tab strip); `.playlist-row`,
  `.playlist-row .name`, `.playlist-row .meta`, `.create-row`, `.create-input`, `.create-confirm`
  (`PlaylistList.vue`); `.track-row` / `.title` (as `.track-row .title`, `TrackRow.vue`); `.row-menu`
  (`TrackRow.vue`'s options button — confirmed via grep this class string appears in no other
  `.vue` file); `.backdrop` and `.option` (`TrackMenu.vue` — both class names are ALSO used by
  `NowPlayingView.vue`/`SettingsView.vue` respectively, but those routes are never visited by this
  section, so there is no collision in practice); `.row-wrap .title` (`PlaylistView.vue`'s reorder
  list — confirmed `row-wrap` is unique to that file). All confirmed present in the actual markup by
  reading `PlaylistList.vue`, `PlaylistView.vue`, `TrackMenu.vue`, `TrackRow.vue`, `LibraryView.vue`
  directly before writing any check against them, not assumed from the plan's prose.
- **Reorder check decision: option (b) from the task**, called out explicitly. A real reorder is
  `useDragReorder.js`'s long-press-then-drag: a touch must sit still under 10px for 400ms before
  arming, then commits via `touchmove`/`touchend`. CDP's `Runtime.evaluate` (this script's only
  bridge into the WebView) synthesises one JS call at a time with `userGesture: true` — there is no
  way to fabricate a *timed* multi-event touch sequence that the composable's own listeners would
  treat as genuine, and dispatching synthetic `TouchEvent`s the composable was never built to trust
  would be exactly the kind of check that passes without exercising anything. So the check calls
  `Capacitor.Plugins.Player.reorderPlaylist({ id, paths })` directly — the exact call
  `PlaylistView.vue`'s `onDragCommit` makes on drop — with two seeded tracks' paths swapped, then
  force-stops + relaunches and asserts the detail screen renders the new (swapped) order, not the
  old one. This proves Room's `reorderPlaylist` persistence contract end-to-end. It does **not**
  exercise the drag gesture itself — said plainly in a comment directly above the check, and again
  in the section's top-of-file disclosure — which stays a hands-on-only item (plan's F5) until a
  device is available again.
- **Other decisions worth recording**:
  - The create-playlist check discovered (by reading `PlaylistList.vue`/`LibraryView.vue`, not by
    running anything) that a successful create navigates straight to `/playlist/:id`
    (`PlaylistList.vue`'s `submitCreate` emits `select` on success, and `LibraryView.vue`'s
    `goToPlaylist` pushes the route) — so the new row is NOT visible again until navigating back to
    the Playlists tab. The check accounts for this: it extracts the numeric id from
    `location.hash` right after creating, then navigates back to find the row by name.
  - The favourite round-trip check does not assume a clean starting state (no favourites yet): it
    reads whether the target track is already favourited from `listFavorites()` BEFORE toggling,
    computes the expected post-toggle state as the logical negation, and asserts against that —
    so the check is correct whether or not a previous partial run left a stray favourite behind.
  - `library.count.song`/`library.count.songs` already exist in both `en.js`/`el.js` (pre-dating
    this plan, confirmed by grep before relying on them) — the two "real counts" checks in step 2
    assert on the rendered digit precisely because this pair is one of the few strings NOT gated by
    T15, unlike every `playlists.*`/`trackMenu.*` label.
  - No `pm clear` anywhere in this section, so no `pm grant` re-grant was needed — every
    "survives force-stop + relaunch" check uses `am force-stop` only, mirroring the existing T12
    persistence section immediately above it in the file.
- **Verification run**: `bash -n scripts/qa-playback.sh` → exits 0. `git diff scripts/qa-playback.sh
  | grep -c '^-'` prints `1`, NOT `0` — but the one line is `--- a/scripts/qa-playback.sh`, git's
  own diff-header line that appears for every modified file regardless of content; filtering it out
  (`grep '^-' | grep -v '^--- ' | wc -l`) prints `0`, confirming the section is pure addition with
  no existing line touched (Must NOT Have 9). Flagging this literal-vs-substance gap rather than
  silently reporting "0" for a command that verifiably prints "1", since the orchestrator checks
  the diff, not the claim. No `npm run build`, no Gradle, no `adb`, no emulator — per Must Not Do.

## Orchestrator note: my own verification command was wrong twice (2026-08-21)

Two checks I handed to agents were badly specified, and both agents handled it better than the
instruction deserved. Recording it because the same mistakes are easy to repeat:

1. **`git diff <file> | grep -c '^-'` prints `1` for a pure addition**, because git's own
   `--- a/<file>` header starts with a dash. T16 reported the literal `1` AND the substantive
   result (`grep '^-' | grep -v '^--- '` → `0`) instead of silently claiming zero. The correct
   check is `git diff <file> | grep '^-' | grep -v '^--- ' | wc -l`.
2. **Extracting i18n keys by matching literal `t('…')` calls misses dynamic ones.**
   `LibraryView.vue:337` builds its tab label as `t(\`library.tab.${name}\`)`, so
   `library.tab.playlists` never appeared in the "authoritative" 37-key list I generated, and I
   told T15 that list was exact. T15 flagged it rather than adding an unauthorised key. Any future
   key audit must also grep for `t(\`` and for any key assembled from a variable.

The general lesson: a verification command is itself code and can be wrong. When an agent reports
a number that contradicts the expected one, the first question is whether the CHECK is broken, not
whether the work is.

## T15 addendum: the 38th key, `library.tab.playlists`

The coordinator confirmed the 37-key list's extraction method (static `t('...')` literal
matching) could not see `LibraryView.vue:337`'s `t(\`library.tab.${name}\`)`, and authorised
adding `library.tab.playlists` as the 38th key.

- **Second-miss check, as requested**: `grep -rn "t(\`" src/views src/components` finds every
  template-literal `t()` call in those two directories. Three hits total:
  - `SettingsView.vue:43` — `t(\`settings.language.${code}\`)` → resolves to
    `settings.language.el`/`settings.language.en`, both of which **already existed** in both
    locale files before this task touched anything (unaffected, not a gap).
  - `NowPlayingView.vue:256` — `t(\`player.repeat.${player.repeatMode.value}\`)` → resolves to
    `player.repeat.off`/`.all`/`.one`, all three of which **already existed** in both files
    (unaffected, not a gap).
  - `LibraryView.vue:337` — `t(\`library.tab.${name}\`)` → the one real gap, now fixed below.
  **No second miss.** These are the only three dynamic-key call sites in the two directories;
  the other two both already had every key their interpolation could produce.
- **Key chosen, placement**: `library.tab.playlists` inserted into the existing `library.tab.*`
  group, immediately after `library.tab.folders`, in both files (`en.js:18`, `el.js:17`) — same
  position in both, matching `TABS`' own append-only order (`['songs','albums','artists',
  'folders','playlists']`).
- **Values — kept the notepad's existing T9 proposal, unchanged**: EN `Playlists`, EL `Λίστες`.
  Re-justified against the coordinator's specific concern (a tight horizontally-scrolling strip
  that already scrolls with only four Greek labels): `Λίστες` (6 characters) is the SHORTEST of
  all five tab labels in the group it joins — `Τραγούδια` (9), `Άλμπουμ` (7), `Καλλιτέχνες` (11),
  `Φάκελοι` (7), `Λίστες` (6) — so it cannot be the label that makes the strip's overflow worse;
  it is also the plain, one-word noun a Greek music app would actually use for "Playlists" (not
  a calque of the English word, not a longer/more formal alternative like `Λίστες
  αναπαραγωγής` which would be both more literal AND the longest label in the row — the wrong
  direction for a space-constrained control). No better option was found; kept as-is.
- **Verification run (real output)**:
  - `node --check src/i18n/en.js` → `en.js OK`; `node --check src/i18n/el.js` → `el.js OK`.
  - Key-set parity, both directions: `en count: 138 el count: 138`, `SAME ORDER AND SET (en vs
    el): true`, `SAME ORDER AND SET (el vs en): true`, `only in en: []`, `only in el: []`.
  - All 38 keys (37 + `library.tab.playlists`) individually checked via
    `Object.prototype.hasOwnProperty.call` on both dictionaries: `ALL 38 PRESENT IN BOTH: true`,
    zero `MISSING` lines.
  - `git diff --stat -- src/i18n/en.js src/i18n/el.js` → `en.js | 42 ++...`, `el.js | 44 ++...`,
    `2 files changed, 86 insertions(+)` — additions only; the diff shows exactly one new line per
    file (`library.tab.playlists`), immediately after `library.tab.folders`, with every
    surrounding line unchanged.
  - Hardcoded-text gate (`grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep
    -v "t("`) → still empty, still passes.
  - No build, no Gradle, no `adb`/emulator/`npm run qa` — per Must Not Do. Only
    `src/i18n/en.js`, `src/i18n/el.js`, and this notepad touched.

## F4 fixes (T13/T14 reopened)

An independent scope review rejected this plan's diff on two blocking issues plus a false
statement in the app's own UI. All three are fixed. Plain note on how the first two happened:
**T14 documented `onViewInFolder` in `NowPlayingView.vue` as a stub for "T13 to connect", and
T13's own notepad entry documented the exact snippet `PlaylistView.vue` (T11's file, landed the
same batch) should call — but T13 never touched `NowPlayingView.vue` itself, and nothing in
either task's scope made either of them go back and wire the third consumer.** Two tasks each
correctly did their own job and each pointed at "the other task" for the remaining piece; the
piece itself fell in the gap between them. Read every cross-task handoff comment as an open
question until grepped for, not as a settled fact.

- **Blocking issue 1 — `NowPlayingView.vue`'s `onViewInFolder` was a no-op wrapped in a stale
  comment.** It only did `router.push('/library')`, discarding the track and never calling
  `expandFolder`/setting the Folders tab, so it landed on whatever tab was last selected with
  nothing expanded. Fixed by copying `PlaylistView.vue`'s exact shape (both files import
  `expandFolder` from `FolderList.vue` and `tab`/`libraryTab` from `LibraryView.vue`, T13's two
  module-scope exports):
  ```js
  function onViewInFolder(track) {
    expandFolder(track.folder)
    libraryTab.value = 'folders'
    router.push('/library')
  }
  ```
  `track.folder === ''` needs no special case — confirmed by reading `useLibrary.js`'s
  `groupBy(field, fallbackKey)` (`:162-169`): `name = track[field] || null` then
  `key = name ?? fallbackKey`; for `folder === ''`, `name` is `null` (empty string is falsy) so
  `key` becomes `fallbackKey`, which `groupBy('folder', '')` passes as `''` — the SAME value
  `track.folder` already holds. So `expandFolder('')` expands exactly the group a
  `folder === ''` track actually renders under; there is no mismatch to guard against, matching
  what T13's and T11's notepad entries already claimed (now independently re-verified, not just
  trusted).
  Ordering: `expandFolder`/`libraryTab.value` are both set synchronously, immediately before the
  (async) `router.push`, so by the time `/library` mounts fresh, `tab.value` is already
  `'folders'` and `expanded` already contains the key — no render where the Folders tab shows
  then flips, no gap for a half-applied state to be visible.
  Also rewrote the stale comment block above the old stub (previously described the handler as
  a T13 handoff point, which is exactly the false "someone else's job" claim that caused the
  gap) so a future reader doesn't reopen a closed question.

- **Blocking issue 2 — the sheet never closed after "View in folder" on ANY of the three
  screens.** `TrackMenu.vue`'s `.backdrop` is `position: fixed; inset: 0; z-index: 50`, so even
  the two paths that DID switch tabs (Library, Playlist) showed the destination hidden behind
  the still-open sheet until the user dismissed it by hand. **Chose to centralise the close
  inside `TrackMenu.vue`'s own `onViewInFolder`, not to add a `closeTrackMenu()` call to each of
  the three view handlers.** Argument: every current listener (`LibraryView`, `PlaylistView`,
  `NowPlayingView`) navigates away on this action with no exception, and the bug that triggered
  this whole review was exactly a THIRD copy of "call closeTrackMenu() here" being forgotten —
  more copies of the same one-line call is more places for a fourth consumer to forget it, not
  fewer. One place that cannot be skipped beats three places that can. The change:
  ```js
  function onViewInFolder() {
    if (!activeTrack.value) return
    const track = activeTrack.value // snapshot before closeTrackMenu() nulls the ref
    closeTrackMenu()
    emit('view-in-folder', track)
  }
  ```
  `closeTrackMenu` was already destructured in this file (`const { activeTrack, closeTrackMenu
  } = useTrackMenu()`, from `useTrackMenu.js`) — no new import needed. Consequence: `LibraryView
  .vue` and `PlaylistView.vue` needed NO edit for this issue (their existing `onViewInFolder`
  handlers already do the expand/tab/navigate work; the sheet is already closing, reactively,
  by the time those handlers run) — only `TrackMenu.vue` and `NowPlayingView.vue` changed.
  This OVERRULES `TrackMenu.vue`'s own EVENT SURFACE DECISION comment, which explicitly said
  view-in-folder "leaves the sheet open... matching how the other four actions behave until
  *they* decide to close it" — that comment is now wrong and was rewritten in place (not left
  contradicting the code) to explain why this one action closes itself while the other four
  still decide independently.

- **Third fix — the About string asserted things that are no longer true.** T2/T3 added a real
  Room database (playlists/favourites) and the previous plan added the hidden downloader (real
  network code, gated behind an unlock) — `src/i18n/en.js:159` still said "No accounts, no
  network, no database" and `el.js:161` still said "Χωρίς λογαριασμούς, χωρίς δίκτυο" (no
  database claim there, but the same network problem). Kept short (a Settings body line, not a
  changelog) and honest without asserting either absolute:
  - `en`: `'settings.about.body'`: `'A local music player. No accounts, and nothing leaves your
    device by default.'`
  - `el`: `'settings.about.body'`: `'Χωρίς λογαριασμούς, τίποτα δεν φεύγει από τη συσκευή σου
    από προεπιλογή.'` — kept the existing Greek string's brevity (it already omitted a
    translation of "A local music player", pre-existing and not this fix's concern) and its
    informal `σου` register, matching `settings.support.body`'s "στην αρχική σου οθόνη" rather
    than calquing the English sentence structure.
  No key added or removed; parity re-verified at 138/138, same order, both directions.

- **Files actually touched by this fix**: `src/views/NowPlayingView.vue`,
  `src/components/TrackMenu.vue`, `src/i18n/en.js`, `src/i18n/el.js`, this notepad.
  `src/views/LibraryView.vue` and `src/views/PlaylistView.vue` were in the permitted scope but
  ended up with NO diff — see the centralisation argument above for why. Nothing under
  `src/router/index.js`, `src/components/FolderList.vue`, `src/composables/*` (besides reading
  `useTrackMenu.js`, already imported where needed), `android/`, or `README.md` was touched.

- **Verification run (no build/adb/emulator/qa, per Must Not Do)**:
  - `@vue/compiler-sfc` (`parse` → `compileScript` → `compileTemplate`) via `node -e` on both
    touched `.vue` files: `NowPlayingView.vue` — parse errors `[]`, compileScript OK (40
    bindings), compileTemplate OK (no errors/tips). `TrackMenu.vue` — parse errors `[]`,
    compileScript OK (33 bindings), compileTemplate OK (no errors/tips).
  - `node --check src/i18n/en.js` → OK; `node --check src/i18n/el.js` → OK.
  - `grep -rn "closeTrackMenu" src/` → every hit: the composable's own definition
    (`useTrackMenu.js`), the pre-existing hardware-back guard (`router/index.js`), and
    `TrackMenu.vue`'s destructure + its one call site inside the now-updated `onViewInFolder`
    (plus the comment explaining it). `NowPlayingView.vue`'s own comment references
    `closeTrackMenu()` in prose only (explaining why it does NOT call it) — the actual close for
    all three consumers is that one call in `TrackMenu.vue`.
  - `grep -n "expandFolder\|tab.value\|libraryTab" src/views/NowPlayingView.vue` → shows the two
    new imports, the doc-comment mentions, and the two real assignments inside the rewritten
    handler — the real wiring, not a stub.
  - Key-set parity: extracted both files' key lists (`^\s*'([^']+)':` per line) — `en count: 138
    el count: 138`, `same order+set: true`.
  - Hardcoded-text gate (`grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep
    -v "t("`) → empty, still passes.
  - Confirmed `groupBy('folder', '')`'s empty-string-key behaviour by reading `useLibrary.js`
    directly (see Blocking issue 1 above) rather than trusting the prior notepad claims alone.
