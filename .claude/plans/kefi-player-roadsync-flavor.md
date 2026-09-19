# Plan: a `roadsync` product flavor that masquerades as an allowlisted music app

## Context

Honda RoadSync shows the current track on the bike but its play/pause and next buttons never
reach us. Commit `ee71f09` made `PlaybackService` a `MediaLibraryService` and added
`MediaBrowseTree` on the theory that RoadSync enumerates `android.media.browse.MediaBrowserService`
apps. That work is correct and verified, but it does not fix the symptom, because the premise was
wrong.

### What was actually established (device investigation, 2026-09-19)

Verified on the user's phone (OnePlus CPH2747, Android 16), with adb they explicitly authorised
for this one investigation:

- The app's browse service **is** published and resolvable:
  `adb shell cmd package query-services --brief -a android.media.browse.MediaBrowserService`
  lists `com.kefi.player/.PlaybackService` among 18 services. The installed build is 1.0.2
  (`lastUpdateTime=2026-09-19 03:08:29`), i.e. the build that carries `ee71f09`.
- RoadSync is `com.honda.ms.dm.sab` (version 26.8.10), built on the **Drivemode SDK**. Its base
  APK's dex contains one class per supported app —
  `com.drivemode.sdk.music.apps.{Spotify,AmazonMusic,AppleMusic,Deezer,Gaana,GooglePlayMusic,IHeart,Joox,Mp3Player,PlayerFM,PlayerFMDebug,Podcast,Poweramp,GenericMusicApp,ControllerProvider,Launchable,MusicApp}`
  — over an adapter layer `com.drivemode.sdk.music.adapter.{MediaBrowserAdapter,ControllerAdapter}`.
  Its manifest declares ~25 `queriesPackages` naming exactly those music apps.
- **Discovery is allowlist-based, not generic.** Of the allowlisted packages, exactly two are
  installed on the phone (`com.soundcloud.android`, `org.videolan.vlc`) and exactly those two
  appear in RoadSync's Music Source list. Shazam, Stremio and ReVanced YouTube are installed and
  all publish a `MediaBrowserService`, and none of them appears.

So no manifest, metadata or browse-tree change can ever make `com.kefi.player` appear in RoadSync.
The only lever left is the package id RoadSync looks for.

### Decisions taken with the user (interview, 2026-09-19)

1. **Product flavor, not an outright rename.** A `roadsync` flavor carries the fake
   `applicationId`; the default flavor keeps `com.kefi.player` untouched. If the masquerade fails
   on the bike, the user uninstalls the roadsync build and loses nothing.
2. **Impersonate `com.shaiban.audioplayer.mplayer`** (Muzio Player) first. It is a plain local-file
   player, so Drivemode most likely drives it through the generic `MediaBrowserAdapter` rather than
   app-specific commands. Not installed on the user's phone. Fallback if it fails:
   `another.music.player` (Pi Music Player). `com.maxmpz.audioplayer` was rejected — Drivemode has a
   dedicated `Poweramp` class that probably speaks Poweramp's custom intents.
3. **QA is the user's.** They will install and check the phone side themselves today, and test on
   the motorcycle themselves tomorrow. No agent runs `npm run qa`, `adb`, or anything against
   hardware. Device steps in this plan are written **for the user to execute**, and the executing
   agent must report them as not-run.
4. The user accepts the consequences: separate app data, no store distribution for that flavor, and
   a conflict with the real Muzio Player should it ever be installed.

### Considered and rejected

A gradle property (`applicationId project.hasProperty('roadsync') ? ... : ...`) would avoid every
tooling change below, but both builds would share one output path and one build directory — easy to
install the wrong APK on the bike, and a forced clean rebuild on every switch. The flavor's distinct
`outputs/apk/<flavor>/<type>/` paths are worth the script edits.

### Findings that shaped this plan (verified by reading the code, not assumed)

- `android/app/build.gradle:66` `namespace = "com.kefi.player"` and `:69`
  `applicationId "com.kefi.player"` are **separate settings**. Only the second changes. The Kotlin
  package stays `com.kefi.player`, so no source directory is touched.
  **Correction from execution:** "no `.kt` file is touched" turned out to be wrong — see T9. Code
  that builds a *class* name from `context.packageName` breaks the moment the two diverge.
- No `productFlavors` or `flavorDimensions` exist anywhere in `android/app/build.gradle`,
  `android/build.gradle` or `android/variables.gradle` today. This is a clean introduction.
- `AndroidManifest.xml:56` uses `android:authorities="${applicationId}.fileprovider"` — it follows
  the flavor automatically and must **not** be hardcoded.
- `PlayerWidget.kt:69-70` defines the literal action strings `"com.kefi.player.widget.NEXT"` /
  `".PREVIOUS"`, but every `PendingIntent` is **explicit**: `command()` at `:227-234` builds
  `Intent(context, PlayerWidget::class.java)` and `mediaButton()` at `:214-224` sets
  `component = ComponentName(context, MediaButtonReceiver::class.java)`. Two installed builds
  therefore cannot steal each other's widget presses. The strings are cosmetically stale only —
  **leave them alone.**
- `PlaybackStateStore.kt:25`, `SoundEffectsStore.kt:27`, `ResumePointStore.kt:30` all use the
  SharedPreferences file name `"kefi.player"`, and `LibraryDatabase.kt:34` uses
  `"kefi-library.db"`. These live inside the per-app sandbox, which the new applicationId gives a
  fresh copy of. They are **not** derived from the package id and must not be renamed.
- `scripts/build-apk.sh:66-67` hardcodes `:app:assembleDebug` / `:app:assembleRelease`, and `:76`
  globs `android/app/build/outputs/apk/$VARIANT`. Both shapes change under flavors.
- `package.json:16` `install:apk` hardcodes `android/app/build/outputs/apk/debug/app-debug.apk`.
- `README.md:320-323` states verbatim that the applicationId *"must not change"*. After this plan
  that is false as written and must be rewritten, not deleted.
- `android/app/build.gradle:1-63` computes `androidVersionCode`/`androidVersionName` from git and
  refuses any task whose lowercased name **contains** `"release"` unless HEAD is on an exact
  `vMAJOR.MINOR.PATCH` tag. That substring check is flavor-agnostic — `assembleRoadsyncRelease`
  is caught by exactly the same test as `assembleRelease`. No change needed there.
  **Correction from execution:** HEAD currently sits exactly on tag `v1.0.2`, so on this commit the
  guard *permits* release tasks rather than refusing them. See F4b.
- `android/app/src/main/res/values/strings.xml:3-4` already defines `app_name` and
  `title_activity_main`. A flavor `resValue "string", "app_name", ...` would therefore fail the
  build with a duplicate-resource error. The flavor must override the label through a **flavor
  source set** (`android/app/src/roadsync/res/values/strings.xml`) instead.
- `android/app/capacitor.build.gradle` and `android/capacitor.settings.gradle` are generated by
  `cap sync` and must never be hand-edited. Neither mentions `applicationId` at all — flavors are
  declared entirely inside the hand-owned `android { }` block.
- `android/app/src/main/res/values/strings.xml:5-6` defines `package_name` and
  `custom_url_scheme`, both with the literal value `com.kefi.player`. Neither is referenced by the
  manifest or any Kotlin file — they are vestigial Capacitor-template strings. They are **not**
  part of this change and must not be "cleaned up" while nearby.
- Once any flavor exists, the aggregate `assembleDebug` task still exists but fans out to **both**
  `assembleStandardDebug` and `assembleRoadsyncDebug`. It would therefore not fail loudly — it
  would silently build both flavors on every `npm run apk` and leave `find ... -print -quit`
  reporting whichever APK it happened to hit first. This is why T3 must use flavor-qualified task
  names, not the aggregate.
- `aapt` is available at `/home/nonos/Android/Sdk/build-tools/36.0.0/aapt` (verified), so the built
  APKs can be inspected directly rather than through gradle intermediates.
- There is no `lint`, `typecheck` or `test` npm script, and no real unit tests — only two untouched
  Capacitor template stubs. The build itself plus `:app:compileDebugKotlin` is the automated gate.

### Fixed constants (used verbatim by every task)

- `REPO` = `/mnt/X/myProjects/kefi-player`
- Build env: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`
- `STD_PKG` = `com.kefi.player` (unchanged, default flavor)
- `RS_PKG` = `com.shaiban.audioplayer.mplayer` (roadsync flavor)
- Flavor dimension name: `distribution`. Flavor names: `standard`, `roadsync`.
- `AAPT` = `/home/nonos/Android/Sdk/build-tools/36.0.0/aapt` (verified present)
- Standard debug APK after this plan:
  `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- RoadSync debug APK: `android/app/build/outputs/apk/roadsync/debug/app-roadsync-debug.apk`

## Must Have

1. A `distribution` flavor dimension with two flavors in `android/app/build.gradle`: `standard`
   (inherits `applicationId "com.kefi.player"` from `defaultConfig`, overrides nothing) and
   `roadsync` (`applicationId "com.shaiban.audioplayer.mplayer"`).
2. A roadsync-only string override so the two installs are distinguishable on the launcher, via the
   flavor source set `android/app/src/roadsync/res/values/strings.xml`.
3. `scripts/build-apk.sh` able to build either flavor, defaulting to `standard` so
   `npm run apk` and `npm run apk:release` keep meaning exactly what they mean today — the bare
   commands must still produce the `com.kefi.player` artifact and nothing else.
4. `package.json` scripts updated for the new paths, plus a way to build and install the roadsync
   variant.
5. `README.md` corrected: the "must not change" claim rewritten to describe the real rule, and a
   short section explaining the roadsync flavor and why it exists.
6. Both variants build clean, and the packaged manifests prove each carries its intended package.

## Must NOT Have

- **No change to `namespace`** in `android/app/build.gradle`. It stays `com.kefi.player`.
- ~~**No `.kt` or `.java` file modified.**~~ **OVERTURNED BY EXECUTION — see T9.** This constraint
  rested on the premise that nothing in Kotlin derives a *class* name from the applicationId. That
  premise was false, and the roadsync build crashed on every launch because of it. Exactly one
  Kotlin line changes (`PlayerPlugin.kt:1057`). Everything else in the original constraint still
  holds: no `package` declarations, no `PlayerWidget` action string literals, no source directory
  moved, nothing else touched.
- **No source directory renamed or moved** (`android/app/src/main/java/com/kefi/player/` stays).
- **No rename of `"kefi.player"` SharedPreferences or `"kefi-library.db"`.**
- **No data migration** between the two installs. The roadsync build starting empty is intended.
- **No edits to `android/app/capacitor.build.gradle` or `android/capacitor.settings.gradle`** —
  they are generated.
- **No change to `capacitor.config.json`.** Its `appId` is consumed when Capacitor scaffolds or
  syncs the native project, not to set the gradle `applicationId`; changing it would be a
  no-op at best and would confuse `cap sync` at worst. Leave it at `com.kefi.player`.
- **No release build, and no change to the release-tag guard** at `android/app/build.gradle:1-63`.
- **No agent-run device testing**: no `adb`, no `npm run qa`, no `npm run fixtures`, no emulator.
- **No mass find-and-replace** of `com.kefi.player` across `.claude/plans/`, `.claude/notepads/` or
  historical docs. Those are dead history and stay as they are.
- **No second flavor for the fallback package** (`another.music.player`). If Muzio fails on the
  bike, that is a one-line edit later, not a variant to build now.
- **No new icon, splash, theme or any other cosmetic divergence** between the flavors beyond the
  label string in Must Have 2. A distinct icon was considered and deliberately dropped: the label
  is enough to tell the two launcher entries apart, and a second icon set is scope the user did
  not ask for.
- **No touching `package_name` or `custom_url_scheme`** at
  `android/app/src/main/res/values/strings.xml:5-6`. They are dead Capacitor-template strings that
  happen to contain the package id; cleaning them up is not this change.
- **No touching `scripts/qa-playback.sh`, `scripts/qa-fixtures.sh`, or
  `.claude/plans/kefi-player-hidden-downloader-tab.md`.** They hardcode `PKG=com.kefi.player`,
  which stays correct, because the standard flavor's applicationId does not change. The
  hidden-downloader plan is mid-execution at T16 and must not be disturbed.
- **No use of the aggregate `assembleDebug` / `assembleRelease` tasks** anywhere in the tooling
  after this change — see the note in Findings about them silently building both flavors.

## TODOs

### Phase A — Gradle

- [x] **T1: Add the `distribution` flavor dimension and the two flavors**
  - Files: `android/app/build.gradle`
  - Insert immediately **after** the closing brace of `defaultConfig { }` (which ends at the line
    following the `aaptOptions { ... }` block, before the `// Optional release signing.` comment)
    and before the `def keystorePropsFile = ...` line:
    ```groovy
    // Two application ids from one code base. `standard` is the real app and inherits
    // applicationId com.kefi.player from defaultConfig above. `roadsync` exists for exactly
    // one reason: Honda RoadSync picks media apps from a hardcoded allowlist of package names
    // (verified 2026-09-19 -- its dex carries a com.drivemode.sdk.music.apps.* class per
    // supported app), so a correct MediaBrowserService is not enough to be listed. Building
    // under an allowlisted package id is the only way in. See README, "The RoadSync flavor".
    flavorDimensions "distribution"
    productFlavors {
        standard {
            dimension "distribution"
            // Restated rather than inherited from defaultConfig, so that reading either
            // flavor tells you which app it installs as without cross-referencing.
            applicationId "com.kefi.player"
        }
        roadsync {
            dimension "distribution"
            applicationId "com.shaiban.audioplayer.mplayer"
        }
    }
    ```
  - Do **not** touch `namespace` at `:66`, `applicationId` at `:69`, the `signingConfigs` block, the
    `buildTypes` block, or the versioning header.
  - QA Scenario: `cd $REPO/android && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk ./gradlew :app:tasks --all 2>/dev/null | grep -cE "^assemble(Standard|Roadsync)Debug"`
    prints `2`. **Corrected during execution:** as written the pattern prints `6`, because it also
    counts AGP's `...DebugAndroidTest` and `...DebugUnitTest` tasks. A plain `$` anchor does not
    work either — `tasks --all` prints `<task> - <description>` on each line. The pattern that
    yields exactly `2` is `grep -cE "^assemble(Standard|Roadsync)Debug( |$)"`, verified.
  - QA Scenario: `grep -c 'namespace = "com.kefi.player"' $REPO/android/app/build.gradle` prints `1`
    (unchanged).

- [x] **T2: Give the roadsync flavor its own launcher label**
  - Files: `android/app/src/roadsync/res/values/strings.xml` (new directory + file)
  - Content:
    ```xml
    <?xml version='1.0' encoding='utf-8'?>
    <resources>
        <!--
            Overrides main/res/values/strings.xml:3-4 for this flavor only. A flavor source set
            is used rather than `resValue "string", "app_name", ...` in build.gradle, because
            app_name already exists in the main source set and resValue would collide with it
            as a duplicate resource.
        -->
        <string name="app_name">Kefi (RoadSync)</string>
        <string name="title_activity_main">Kefi (RoadSync)</string>
    </resources>
    ```
  - Create no other file under `android/app/src/roadsync/` — no manifest, no java, no drawables.
  - QA Scenario: after T3, `grep -o 'Kefi (RoadSync)' $REPO/android/app/build/intermediates/merged_res/roadsyncDebug/**/values*.xml 2>/dev/null | head -1` is non-empty, or equivalently the
    build in F1 succeeds and F2's manifest check passes.

### Phase B — Build tooling

- [x] **T3: Teach `scripts/build-apk.sh` about flavors, defaulting to `standard`**
  - Files: `scripts/build-apk.sh` (lines 66-67 task selection, line 76 output glob)
  - Add a second positional argument `FLAVOR="${2:-standard}"` next to the existing
    `VARIANT="${1:-debug}"`, and validate it against `standard|roadsync` with a clear error
    naming both, in the style of the existing JDK/SDK failure messages in the same script.
  - Build the gradle task name from both: capitalise the flavor's first letter and the variant's
    first letter, e.g. `:app:assembleStandardDebug`, `:app:assembleRoadsyncDebug`. Keep the
    existing `-PabiFilters` pass-through untouched.
  - Update the output search at `:76` from `android/app/build/outputs/apk/$VARIANT` to
    `android/app/build/outputs/apk/$FLAVOR/$VARIANT`.
  - Pattern: follow the existing argument handling and `echo`/`exit 1` error style already in
    `scripts/build-apk.sh:1-60`.
  - QA Scenario: `bash -n $REPO/scripts/build-apk.sh` exits `0`.
  - QA Scenario: `grep -c 'assembleStandardDebug\|assembleDebug' $REPO/scripts/build-apk.sh` — the
    literal `:app:assembleDebug` must no longer appear:
    `grep -c ':app:assembleDebug' $REPO/scripts/build-apk.sh` prints `0`.

- [x] **T4: Update `package.json` scripts for the new paths and the new flavor**
  - Files: `package.json` (the `scripts` block; `install:apk` is at `:16`)
  - `"apk"` stays `bash scripts/build-apk.sh debug` (now implicitly `standard`).
  - `"install:apk"` becomes
    `adb install -r android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`.
  - Add `"apk:roadsync": "bash scripts/build-apk.sh debug roadsync"`.
  - Add `"install:apk:roadsync": "adb install -r android/app/build/outputs/apk/roadsync/debug/app-roadsync-debug.apk"`.
  - Do not add a `roadsync` release script — release builds are out of scope.
  - QA Scenario: `node -e "const s=require('$REPO/package.json').scripts; console.log([s['apk:roadsync'],s['install:apk:roadsync']].every(Boolean) && !s['install:apk'].includes('apk/debug/'))"`
    prints `true`.

### Phase C — Documentation

- [x] **T5: Correct the false applicationId rule in the README**
  - Files: `README.md:320-323`
  - Replace the bullet that currently reads *"`applicationId` / `appId` is **`com.kefi.player`** and
    must not change: ..."* with one that states the rule that is actually true after this change:
    the **`standard`** flavor's applicationId is `com.kefi.player` and must not change, because
    Android identifies an app by it and a rename means existing installs cannot update and lose
    their data; the `roadsync` flavor deliberately builds under a different one and is therefore a
    separate app with separate data, by design. Keep the existing sentence about the launcher label
    and icon being free to change.
  - QA Scenario: `grep -c 'must not change' $REPO/README.md` prints `1`, and
    `grep -A2 'must not change' $REPO/README.md | grep -c 'standard'` prints at least `1`.

- [x] **T6: Document the flavor and the finding behind it**
  - Files: `README.md` (new `## The RoadSync flavor` section, placed after the existing
    `## The hidden downloader tab` section at `README.md:255-300`)
  - Must state, briefly: that RoadSync picks media apps from a hardcoded allowlist rather than
    enumerating `MediaBrowserService` apps, how that was established (the dex's
    `com.drivemode.sdk.music.apps.*` classes, and only the two installed allowlisted packages
    appearing in its list); that `ee71f09`'s browse work is still correct and still needed for real
    Android Auto and AVRCP browsing; how to build and install the flavor
    (`npm run apk:roadsync && npm run install:apk:roadsync`); that it is sideload-only, will never
    be accepted by a store under that id, conflicts with the real Muzio Player, and keeps its own
    separate app data; and that `another.music.player` is the fallback id if Muzio is not driven by
    Drivemode's generic adapter.
  - QA Scenario: `grep -c '^## The RoadSync flavor' $REPO/README.md` prints `1`.
  - QA Scenario: `grep -c 'com.shaiban.audioplayer.mplayer' $REPO/README.md` is at least `1`.

### Phase C2 — Defect found by device testing

- [x] **T9: Fix the applicationId-vs-namespace conflation that crashes the roadsync build**
  - Files: `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt:1057`
  - `setShareTargetEnabled` builds `ComponentName(context, "${context.packageName}.ShareTarget")`.
    `context.packageName` is the **applicationId**; the alias's class name comes from the
    **namespace**. Both packaged manifests declare `android:name="com.kefi.player.ShareTarget"`.
    Under `standard` the two coincide, so the bug was invisible. Under `roadsync` the code asks
    `PackageManager` for `com.shaiban.audioplayer.mplayer.ShareTarget`, which does not exist:
    `IllegalArgumentException` inside `App.onCreate` → the app dies before its first frame.
  - Fix so the class is namespace-derived while the package stays the installed applicationId, with
    a comment naming the distinction so it is not "simplified" back into the crash.
  - **Audit result (all other `context.packageName` uses are correct and must not be touched):**
    `YtdlpPlugin.kt:412` (`.fileprovider` authority), `PlayerWidget.kt:157` (`RemoteViews`),
    `PlayerWidget.kt:204` (`getLaunchIntentForPackage`), `PlayerPlugin.kt:994` and `:1040`
    (`package:` Settings URIs) all genuinely need the applicationId. Line 1057 was the only defect.
  - QA Scenario: `cd android && ./gradlew :app:compileRoadsyncDebugKotlin :app:compileStandardDebugKotlin`
    exits `0`; then a rebuilt roadsync APK launches without a `FATAL EXCEPTION` in logcat.

### Phase D — QA (the user runs every step in this phase)

- [x] **T7: Phone-side check — the app is listed by RoadSync** *(user, today)*
  - Not executable by the agent. The agent writes this into the handover and reports it not-run.
  - Steps for the user:
    1. `npm run apk:roadsync` then `npm run install:apk:roadsync`.
    2. Confirm a second launcher icon labelled **Kefi (RoadSync)** appears alongside Kefi Player,
       and that the original Kefi Player still opens with its playlists and favourites intact.
    3. Open the new app once and grant it audio permission, so its library is populated and a
       track has played at least once.
    4. Open RoadSync → Advanced settings → Music Source.
  - Expected: the list now offers a third entry beside SoundCloud and VLC. It may be labelled
    "Muzio Player" or "MP3 Player" rather than Kefi — RoadSync may use its own name for the
    allowlisted package. That is still success.
  - If it does not appear: the masquerade is rejected for a reason beyond the package id; stop and
    report, do not start guessing at other ids.

- [ ] **T8: Bike-side check — the controls actually work** *(user, tomorrow)*
  - Not executable by the agent. Reported not-run.
  - Steps: select the new entry as Music Source, start playback, then from the handlebar controls
    press play/pause, next and previous.
  - Expected: all three act on Kefi's playback. Partial success (appears and displays, but buttons
    dead) means Drivemode drives that package with app-specific commands rather than the generic
    `MediaBrowserAdapter` — the next move is then to retry with `another.music.player`, a one-line
    change to T1's `applicationId`.

## Final Verification Wave

- [x] **F1: Both variants build** — `cd $REPO && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk bash scripts/build-apk.sh debug standard`
  and the same with `roadsync` both exit `0` and each print an APK path under their own
  `outputs/apk/<flavor>/debug/` directory.
- [x] **F2: Each APK carries its intended package and label** — inspect the built artifacts
  themselves, not gradle intermediates:
  `$AAPT dump badging <standard-debug-apk> | grep -E "^package:|application-label:"` reports
  `name='com.kefi.player'` and label `Kefi Player`; the same on `<roadsync-debug-apk>` reports
  `name='com.shaiban.audioplayer.mplayer'` and a **different** label. Additionally the roadsync
  packaged manifest
  (`android/app/build/intermediates/packaged_manifests/roadsyncDebug/*/AndroidManifest.xml`)
  still contains `android.media.browse.MediaBrowserService` and
  `com.google.android.gms.car.application`, and its FileProvider authority reads
  `com.shaiban.audioplayer.mplayer.fileprovider`.
- [x] **F3: The diff touches no source** — **FAILED AS WRITTEN, AND CORRECTLY SO.** Device testing
  found that the roadsync build crashed on every launch, because `PlayerPlugin.kt:1057` built a
  component *class* name out of the applicationId. Exactly one Kotlin file therefore changes
  (`android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`, one line plus its comment — see T9).
  The intent behind F3 — that the Kotlin package, the source layout and the app's behaviour are not
  disturbed by the flavor — still holds and is verified: `git diff --name-only -- '*.kt' '*.java'`
  returns that one file and nothing else, no `package` declaration changed, no directory moved.
- [x] **F4: The standard app is still the same app** — `namespace` and `defaultConfig`'s
  `applicationId` in `android/app/build.gradle` are both still `com.kefi.player`, and the
  `standard` flavor declares the same id. A bare `npm run apk` (no arguments) still succeeds and
  its final `APK:` line points at an artifact that `$AAPT dump badging` reports as
  `name='com.kefi.player'`.
- [x] **F4b: The release guard was not weakened per-flavor** — **this check was written wrong and
  has been rewritten.** As drafted it said `./gradlew :app:assembleRoadsyncRelease` must FAIL on
  the exact-tag guard. Run during execution, it SUCCEEDED — because `git describe --tags` returns
  a bare `v1.0.2`, i.e. HEAD sits exactly on a release tag, so the guard correctly permits a
  release here. The draft check assumed an untagged HEAD and was therefore unfalsifiable on this
  commit. Worse, running it built `app-roadsync-release.apk`, violating the project's standing
  debug-only rule; that artifact was deleted.
  The honest verification, performed instead: the guard at `android/app/build.gradle:1-63` is
  **unmodified by this plan's diff** (`git diff android/app/build.gradle` touches only the inserted
  `flavorDimensions`/`productFlavors` block), and it matches on a lowercased substring of the task
  name, so `assembleRoadsyncRelease` is caught by exactly the same test as `assembleRelease`.
  No per-flavor bypass was introduced. **Do not re-run the original command** — on a tagged HEAD it
  produces a release build nobody asked for.
- [x] **F5: Scope review of the complete diff** against Must Have / Must NOT Have, by an
  independent reviewer, returning APPROVE or REJECT with reasons. Particular attention to the
  Must NOT list: no source files, no prefs/db renames, no capacitor config change, no mass
  find-and-replace across `.claude/`.
- [ ] **F6: Hands-on device QA (T7, T8)** — DEVICE-ONLY, run by the user. The executing agent must
  record this as **not run** and hand over the steps verbatim. Claiming it passed is a failure of
  this plan.

## Success Criteria

Every command below is run from `$REPO` with
`JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`, needs no
user intervention, and must produce the stated output.

1. `bash scripts/build-apk.sh debug standard` → exit `0`
2. `bash scripts/build-apk.sh debug roadsync` → exit `0`
3. `ls android/app/build/outputs/apk/roadsync/debug/app-roadsync-debug.apk` → the file exists
4. `$AAPT dump badging android/app/build/outputs/apk/roadsync/debug/app-roadsync-debug.apk | grep '^package:'`
   → contains `name='com.shaiban.audioplayer.mplayer'`
5. `$AAPT dump badging android/app/build/outputs/apk/standard/debug/app-standard-debug.apk | grep '^package:'`
   → contains `name='com.kefi.player'`
6. The two APKs' `application-label:` lines differ
7. `git diff --name-only -- '*.kt' '*.java'` → empty
8. `grep -c 'namespace = "com.kefi.player"' android/app/build.gradle` → `1`
9. `grep -c ':app:assembleDebug' scripts/build-apk.sh` → `0`
10. `cd android && ./gradlew :app:assembleRoadsyncRelease` → **non-zero exit**, with the
    exact-tag/keystore GradleException
11. `grep -c '^## The RoadSync flavor' README.md` → `1`
12. F5 returns `APPROVE`
13. F6 is reported honestly as **not run**, with T7 and T8 handed to the user verbatim
