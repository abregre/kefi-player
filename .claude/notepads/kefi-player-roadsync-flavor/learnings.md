# Learnings — roadsync flavor

## Verified facts carried in from the planning session (do not re-derive)

- `android/app/build.gradle:66` `namespace = "com.kefi.player"` and `:69`
  `applicationId "com.kefi.player"` are SEPARATE settings. Only applicationId is in scope.
- `defaultConfig { }` (with its nested `ndk { }` and `aaptOptions { }`) closes immediately before
  the line `def keystorePropsFile = rootProject.file('keystore.properties')` at :95. That is T1's
  insertion point.
- `android/app/src/main/res/values/strings.xml:3-4` already defines `app_name` and
  `title_activity_main`. A flavor `resValue "string", "app_name", ...` therefore FAILS the build
  with a duplicate-resource error. Use a flavor source set instead. This was checked, not assumed.
- `strings.xml:5-6` also define `package_name` and `custom_url_scheme` with the literal package id.
  They are dead Capacitor-template strings referenced nowhere. LEAVE THEM.
- Every `.kt` file under `android/app/src/main/java/com/kefi/player/` begins with
  `package com.kefi.player`. A naive find-and-replace of the package string hits ~19 files and
  breaks compilation. NEVER run one.
- `AndroidManifest.xml:56` uses `${applicationId}.fileprovider` — follows the flavor automatically.
- `PlayerWidget.kt` PendingIntents are all EXPLICIT (`Intent(context, PlayerWidget::class.java)`,
  `ComponentName(context, MediaButtonReceiver::class.java)`), so the two installs cannot steal each
  other's widget presses despite the shared action strings at :69-70.
- Once a flavor exists the aggregate `assembleDebug` still resolves, but fans out to BOTH flavors.
  Tooling must use flavor-qualified task names (`:app:assembleStandardDebug`).
- `aapt` is at `/home/nonos/Android/Sdk/build-tools/36.0.0/aapt`.
- Build env for every gradle invocation:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`

## Why this flavor exists

Honda RoadSync (`com.honda.ms.dm.sab`, Drivemode SDK) picks media apps from a hardcoded allowlist
of package names, not by enumerating MediaBrowserService apps. Verified on device: only the two
allowlisted packages that were installed (SoundCloud, VLC) appeared in its Music Source list, while
Shazam/Stremio/ReVanced YouTube — all publishing a MediaBrowserService — did not. Commit ee71f09's
browse work is correct and stays; it just cannot fix RoadSync.

## T1 execution notes (flavorDimensions + productFlavors)

- Inserted the `flavorDimensions "distribution"` / `productFlavors { }` block right after
  `defaultConfig { }`'s closing `}` (was :86) and before the `// Optional release signing.`
  comment (was :88) — i.e. between the two, not between the comment block and
  `def keystorePropsFile`. Both readings in the task description turned out to point at the
  same gap once you notice the release-signing comment sits immediately before
  `keystorePropsFile` with no blank separation to split on; the closing-brace-of-defaultConfig
  reading is the one that's unambiguous, so that's what I used. Gradle accepted it with zero
  complaint — AGP doesn't care whether `productFlavors` appears before or after
  `signingConfigs`/`buildTypes` inside `android { }`, only that all three are direct children
  of the same `android { }` block.
- Reworded the first comment line per the task instruction: "`standard` is the real app and
  inherits ..." → "`standard` is the real app, built under com.kefi.player." (matches that the
  code restates applicationId rather than inheriting it).
- No AGP warnings at all in the `:app:tasks --all` output — clean, no deprecation notices about
  flavors, no missing-dimension warnings.
- **Verification command mismatch worth flagging**: the plan's check
  `grep -cE "^assemble(Standard|Roadsync)Debug"` prints **6**, not 2, because the regex has no
  `$`/word-boundary anchor after `Debug` — it also matches `assembleStandardDebugAndroidTest` and
  `assembleStandardDebugUnitTest` (and the roadsync equivalents), which AGP always generates
  alongside the plain `assemble<Variant>Debug` task per flavor. The two flavors ARE correctly
  wired: exact matches are `assembleStandardDebug` and `assembleRoadsyncDebug`, one each, nothing
  else. A future task that reuses this check should anchor with `Debug$` or grep the `- Assembles`
  description line's variant name instead, to get a literal 2.
- The other two verification checks passed exactly as specified: `grep -c 'namespace =
  "com.kefi.player"'` → 1, `git diff --name-only` → only `android/app/build.gradle`.

## T2 — roadsync launcher label (completed)

- Created `android/app/src/main/res/values/strings.xml` sibling at
  `android/app/src/roadsync/res/values/strings.xml` (new dirs + file, nothing else under
  `android/app/src/roadsync/`), overriding `app_name` and `title_activity_main` to
  "Kefi (RoadSync)". Matched main file's exact formatting: `<?xml version='1.0' encoding='utf-8'?>`
  (single quotes), 4-space indent.
- Kept the explanatory comment about why a flavor source set is used instead of `resValue`
  (duplicate-resource collision with main's existing `app_name`).
- Did not touch `build.gradle`, `strings.xml` (main or values-el), or add any manifest/java/drawable
  under `android/app/src/roadsync/`.
- Verified: `find android/app/src/roadsync -type f | wc -l` → 1; `git status --porcelain` shows only
  `?? android/app/src/roadsync/` as untracked; XML parses well-formed via
  `xml.etree.ElementTree`. No gradle build attempted (T1 may still be in flight) — that check is
  deferred to the Final Verification Wave.

## T3 — scripts/build-apk.sh flavor argument (completed)

- Added `FLAVOR="${2:-standard}"` right next to `VARIANT="${1:-debug}"` at the top (line 13).
- Put the `standard|roadsync` validation immediately after the existing `debug|release` validation
  for `$VARIANT`, i.e. still AFTER `npm run build` / `npm run build:potoken` / `npx cap sync
  android` — that is where the script already validated its one existing input (`$VARIANT`), so
  this follows "next to where the script already validates its other inputs" literally. Note this
  means an invalid flavor still pays for a full `vite build` + `cap sync` before failing, exactly
  like an invalid variant already did before this change — pre-existing behavior, not something
  this task's scope covers changing.
- Collapsed the old two-armed `case "$VARIANT"` (which built `GRADLE_TASK` directly per arm) into a
  validation-only case, then derive `GRADLE_TASK=":app:assemble${FLAVOR_CAP}${VARIANT_CAP}"` from
  both capitalized variables — no four-way table.
- Capitalization: `FLAVOR_CAP="$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}"` (same
  pattern for `VARIANT_CAP`). Verified output tasks are exactly `:app:assembleStandardDebug`,
  `:app:assembleRoadsyncDebug`, `:app:assembleStandardRelease`, `:app:assembleRoadsyncRelease` —
  matches T1's flavor names (`standard`/`roadsync`) and AGP's `assemble<Flavor><BuildType>` naming
  (confirmed against T1's exact-match note above: `assembleStandardDebug`/`assembleRoadsyncDebug`
  exist as literal task names).
- Invalid-flavor message follows the "explain what went wrong + what to do" voice of the JDK/SDK
  checks (not just "invalid"): `Unknown flavor '$FLAVOR'. Pass 'standard' or 'roadsync' as the
  second argument and re-run.` Left the pre-existing `Unknown variant '...' (expected debug or
  release)` message untouched — task only asked for the new flavor validation, not a rewrite of the
  existing variant message.
- Output glob changed from `android/app/build/outputs/apk/$VARIANT` to
  `android/app/build/outputs/apk/$FLAVOR/$VARIANT`, matching AGP's per-flavor output directory
  layout noted above.
- `-PabiFilters` pass-through, JDK/SDK resolution, and the `npm run build` / `build:potoken` /
  `cap sync` sequence are byte-for-byte untouched.
- Verified (from repo root): `bash -n scripts/build-apk.sh` → exit 0; `grep -c
  ':app:assembleDebug' scripts/build-apk.sh` → 0; `grep -c ':app:assembleRelease'
  scripts/build-apk.sh` → 0; `bash scripts/build-apk.sh debug bogusflavor` → ran `npm run
  build`/`build:potoken`/`cap sync` (fast, no gradle) then exited 1 with a message containing both
  `standard` and `roadsync`. No gradle/APK build was attempted (T1's flavors may still be landing
  concurrently) — that check is deferred to the Final Verification Wave.
- `git diff --name-only` at the time of this check also listed `android/app/build.gradle` and
  `package.json` — those are sibling agents' concurrent T1/T4 edits, not mine. `git diff --
  scripts/build-apk.sh` confirms my change is scoped to exactly that one file.

### T3 follow-up: moved argument validation before the build steps

Coordinator caught a real defect in the first pass: putting the `case "$VARIANT"`/`case "$FLAVOR"`
checks "next to" the pre-existing VARIANT check (which sat after `npm run build`/`build:potoken`/
`cap sync android`) meant a typo'd flavor or variant still paid for a full vite build + cap sync
(tens of seconds) before failing. The JDK/SDK checks this task was told to match in *voice* also
match in *position* — they run before any work — so the fix was to move position too, not just
message style.

Moved both `case` blocks, plus `FLAVOR_CAP`/`VARIANT_CAP`/`GRADLE_TASK` derivation, up to
immediately after the `VARIANT`/`FLAVOR`/`ABIS` variable assignments (before `resolve_java_home`/
`resolve_android_home` are even defined). `GRADLE_TASK` is computed early now but only consumed
later at `GRADLE_ARGS=("$GRADLE_TASK")`, right before `./gradlew` — bash doesn't care about the gap.
Nothing else in the file moved: JDK/SDK resolution, the info `echo` block, and the three build
steps are still in their original order and position, just after validation now instead of before.

Re-verified: `bash -n` → exit 0; `bash scripts/build-apk.sh debug bogusflavor` and
`bash scripts/build-apk.sh bogusvariant` both fail in well under a second with only the one-line
error on stderr and exit 1 — no `JDK:`/`Android SDK:` echo, no vite/npm/cap output at all;
`grep -c ':app:assembleDebug'` / `':app:assembleRelease'` → 0/0 still; `git diff --name-only --
scripts/build-apk.sh` → only that file.

## T4 — package.json scripts (completed)

- Added `"apk:roadsync": "bash scripts/build-apk.sh debug roadsync"` immediately after
  `apk:release` (line 12 originally).
- Changed `install:apk` from `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
  to `adb install -r android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`.
- Added `"install:apk:roadsync": "adb install -r android/app/build/outputs/apk/roadsync/debug/app-roadsync-debug.apk"`
  immediately after the updated `install:apk`.
- `apk` script left untouched, as specified.
- File indentation is 2 spaces; matched exactly, no reordering/reformatting elsewhere.
- Verified: all 4 node/git checks from the task pass. Note: the plan's stated check
  "`git diff --name-only` lists ONLY `package.json`" actually also lists
  `android/app/build.gradle` and `scripts/build-apk.sh` at execution time — this is expected
  because siblings for T1 and T3 are editing those files concurrently, not because this task
  touched them. Confirmed with `git diff -- package.json` that the isolated diff is exactly the
  3 intended script changes (2 additions, 1 modification), nothing else.
- Did not run `adb`, `npm install`, or any gradle/emulator/qa command.

## T5 — README false applicationId rule (completed)

- Rewrote the single bullet at `README.md` (originally lines 320-323) under
  `## Android facts worth knowing before editing`. New text: "The `standard`
  flavor's `applicationId` (**`com.kefi.player`**) must not change: Android
  identifies an app by it, so a rename is a different app — existing installs
  cannot update and lose their data. The `roadsync` flavor deliberately builds
  under a different `applicationId`, so it is a separate app with separate
  data, by design. The launcher label (`app_name` in
  `android/app/src/main/res/values/strings.xml`) and the icon are free to
  change." Kept the launcher-label/icon sentence verbatim in meaning.
- **Wrapping gotcha found the hard way**: the verification check
  `grep -c 'must not change' README.md` requires that literal 3-word phrase to
  sit on ONE line — a natural 80-col wrap that splits "must not" / "change:"
  across two lines makes the grep return 0, not 1. Also `grep -A2 'must not
  change'` only inspects the matched line plus the next 2 lines, so "standard"
  has to appear within that 3-line window, not earlier in the bullet. Solved
  both by keeping "The `standard` flavor's `applicationId` ... must not
  change:" together on the bullet's first line (rewrote `applicationId` /
  `com.kefi.player`) to a parenthetical to compensate length), even though
  that line runs to ~84 chars — checked against the file's existing prose
  lines (up to 98 chars elsewhere) and that's within existing precedent, so
  not a style violation.
- Did not touch any other bullet in that section, did not add a new README
  section (that's T6's job), and did not touch `android/app/build.gradle`,
  `package.json`, `scripts/build-apk.sh`, or anything under
  `android/app/src/`.
- Verified from repo root: `grep -c 'must not change' README.md` → 1;
  `grep -A2 'must not change' README.md | grep -c 'standard'` → 1;
  `grep -c 'roadsync' README.md` → 1; `git diff --stat -- README.md` → single
  file, 1 hunk (7 insertions, 4 deletions); `git diff -- README.md` shows
  exactly one hunk replacing the one bullet. `git diff --name-only` also lists
  `android/app/build.gradle`, `package.json`, `scripts/build-apk.sh` — those
  are concurrent sibling T1/T3/T4 edits, not mine.

## T6 — README "The RoadSync flavor" section (completed)

- Inserted a new `## The RoadSync flavor` section into `README.md` between the
  end of `## The hidden downloader tab` ("...this repo is not a fork that ever
  merges back into it.") and the start of `## The PO-token bundle (the one
  TypeScript file)`. ~32 lines, matched the doc's declarative "why this is the
  way it is" voice and ~80-col wrap (the hidden-downloader section was the
  model).
- Content covers, in order: RoadSync (`com.honda.ms.dm.sab`, Drivemode SDK)
  uses a hardcoded package allowlist, not `MediaBrowserService` enumeration;
  the two load-bearing facts as evidence (dex has one
  `com.drivemode.sdk.music.apps.*` class per supported app; on-device test
  showed only SoundCloud+VLC — the two installed allowlisted packages — while
  Shazam/Stremio/ReVanced YouTube, all MediaBrowserService publishers, did not
  appear); `ee71f09`'s browse work is correct and still needed for real
  Android Auto/AVRCP; the `roadsync` flavor's bet (impersonating
  `com.shaiban.audioplayer.mplayer`, Muzio's real id, on the unverified
  assumption Drivemode drives Muzio via its generic `MediaBrowserAdapter`) and
  that `another.music.player` is the fallback if that bet loses; build/install
  via `npm run apk:roadsync && npm run install:apk:roadsync`; sideload-only,
  never store-acceptable under that id, conflicts with the real Muzio Player
  install, keeps separate app data; the label may show as "Muzio Player" or
  "MP3 Player" in RoadSync's UI; explicitly not tested on the motorcycle yet.
- Did not touch the `## Android facts worth knowing before editing`
  `applicationId` bullet (T5's) or any other existing text — no reflow, no
  reformatting.
- Did not describe or suggest patching/reverse-engineering the RoadSync app
  itself; the dex-class evidence is stated as an established fact only.
- Verified from repo root: `grep -c '^## The RoadSync flavor' README.md` → 1;
  `grep -c 'com.shaiban.audioplayer.mplayer' README.md` → 1; `grep -c
  'apk:roadsync' README.md` → 1; `grep -c 'must not change' README.md` → 1
  (T5's bullet undisturbed); `git diff --name-only` lists `README.md` plus
  `android/app/build.gradle`, `package.json`, `scripts/build-apk.sh` — those
  three are pre-existing sibling T1/T3/T4 edits already in the working tree
  before this task started, not touched by this task (confirmed: only one
  Edit call was made, targeting README.md).

## T-crash-fix — PlayerPlugin.kt:1057 applicationId-vs-namespace crash (completed)

- **The trap, concretely**: `ComponentName(context, "${context.packageName}.ShareTarget")` looks
  correct because `ComponentName(Context, String)`'s docs say the second arg is "the class name
  ... fully qualified", and in the `standard` flavor `context.packageName` (`com.kefi.player`,
  the applicationId) happens to equal the gradle `namespace` (also `com.kefi.player`), which is
  what the compiled manifest actually uses for `android:name` on manifest entries declared without
  a leading dot. Nothing breaks until a second flavor gives those two settings different values.
  `roadsync`'s applicationId is `com.shaiban.audioplayer.mplayer`; its namespace is still
  `com.kefi.player`. The `ShareTarget` activity-alias's real class in BOTH flavors' packaged
  manifests is `com.kefi.player.ShareTarget` (verified in T1-T6 planning). Building the class
  string from `context.packageName` therefore asks PackageManager to toggle
  `com.shaiban.audioplayer.mplayer.ShareTarget` — a class that does not exist in that package —
  and `setComponentEnabledSetting` throws `IllegalArgumentException` synchronously, which is fatal
  because the call happens in `App.onCreate()` (`App.kt:31`), so the app dies before its first
  frame on every launch of that flavor.
  - **General lesson for this codebase (and any multi-flavor Android app)**: `context.packageName`
    is ALWAYS the applicationId of the installed build. It is correct for anything that must match
    what's actually installed (`.fileprovider` authorities, `package:` Settings URIs,
    `getLaunchIntentForPackage` — all audited and confirmed correct elsewhere in this file and in
    `YtdlpPlugin.kt`/`PlayerWidget.kt`). It is WRONG for reconstructing an implicitly-qualified
    manifest class name (`android:name=".Foo"` or a bare relative name), because manifest classes
    resolve against gradle's `namespace`, not applicationId. When those two differ, only an
    explicit fully-qualified literal (or a class reference from something known to live in the
    namespace) is safe. This bug class is invisible in single-flavor/single-namespace apps, which
    is exactly why it survived until a second applicationId was introduced.
- **Fix**: replaced the interpolated class string with the literal
  `"com.kefi.player.ShareTarget"` plus a 6-line comment above the `ComponentName(...)` call naming
  the applicationId-vs-namespace distinction, the roadsync divergence, and the crash mechanism —
  so a future "simplification" back to `context.packageName` is pre-empted. Package half of the
  `ComponentName` is untouched: `ComponentName(context, String)` already resolves the package from
  `context.packageName`, so the installed app still gets the alias toggled in the right place.
- Considered but rejected: deriving the package from `MainActivity::class.java.name` — the file's
  existing style near this function favors short literals over reflection-y class lookups for a
  one-line fix, and `ShareTarget` is already documented elsewhere as "an activity-alias for
  MainActivity", so a literal reads more directly than routing through an unrelated class.
- Did NOT touch `App.kt`, `AndroidManifest.xml`, `build.gradle`, or any other
  `context.packageName` call site (`YtdlpPlugin.kt:412`, `PlayerWidget.kt:157/204`,
  `PlayerPlugin.kt:994`/`:1040` — all correctly use the applicationId and were left alone).
- Verified: `cd android && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk ./gradlew :app:compileRoadsyncDebugKotlin` → BUILD SUCCESSFUL; same for
  `:app:compileStandardDebugKotlin` → BUILD SUCCESSFUL. `git diff --name-only` also lists
  `README.md`, `android/app/build.gradle`, `package.json`, `scripts/build-apk.sh` — all four are
  pre-existing sibling T1/T3/T4/T5/T6 edits already in the working tree before this task started
  (confirmed against this notepad's own T1-T6 entries above), not touched by this task. Only one
  Edit call was made, targeting `PlayerPlugin.kt`.
