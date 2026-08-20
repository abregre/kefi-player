# Plan: Kefi Player — the ytmp3 downloader as a hidden third tab

## Context

`kefi-player` is a finished local music player (26/26 boxes of
`.claude/plans/kefi-player-full-android-player.md`, plus a round of post-release work: icon and
splash, the six-colour palette, row artwork, a Folders tab, swipe between library tabs, a
home-screen widget, and four device-reported fixes). `ytmp3` is a separate, working
YouTube→MP3 downloader. This plan ports the downloader's functionality into the player as a
**third tab that does not exist until the user finds an easter egg**: ten taps on the Settings
support card within fifteen seconds.

### The reversal, stated plainly

On **2026-08-19** the user reversed the "build the player inside ytmp3" decision precisely so
the two apps would stop merging (recorded in memory as `kefi-player-split-from-ytmp3`, and in
ytmp3's own `native-music-player-milestone-1.md`, which wanted the player to grow inside ytmp3
with the downloader becoming a tab). On **2026-08-20** the user asked for the mirror image:
the downloader becomes a tab inside the player. That is a deliberate change of direction, not
an oversight. What survives from the old decision: **`ytmp3` stays alive as its own app** and
remains **read-only** for this work.

### Decisions taken with the user (interview, 2026-08-20)

1. **`ytmp3` keeps shipping as a separate app.** Its code is copied and adapted here; the two
   copies are expected to diverge, and a future fix there does NOT automatically arrive here.
2. **arm64-v8a only, in release AND debug**, to keep the size down. Consequence, accepted:
   the x86_64 QA emulator cannot exercise the download engine (see T2, which measures rather
   than assumes this).
3. **The easter egg lives on the existing support card** in Settings. The card keeps looking
   exactly as it does now — no ripple, no cursor, no hint.
4. **Downloads appear in the library like any other music** (they land in MediaStore
   `Music/YTMP3`, which `TrackSource` already scans).
5. **Share-to-app is ported, but only becomes visible after unlock** — the app must not appear
   in the system share sheet before that.
6. **The downloader is a third bottom tab** once unlocked: Library | Downloads | Settings.
7. **The unlock is permanent** (a `kefi.*` preference). No re-lock gesture.
8. **No network in the automated QA.** The suite covers mechanics only (easter egg, routing,
   states, the web mock). Real downloads are the user's own manual test on their phone.

### Findings that shaped this plan (verified, not assumed)

- **The yt-dlp payload is a Maven dependency**, not a bundled asset:
  `io.github.junkfood02.youtubedl-android:library:0.18.1` (yt-dlp zipapp + CPython) and
  `:ffmpeg:0.18.1`, shipped as `lib*.zip.so` "fake" native libs that only work with
  `packaging.jniLibs.useLegacyPackaging = true`. There is **no way to defer this download until
  the easter egg is found** short of Play Feature Delivery, which is out of scope. Measured:
  ytmp3's release APK is **57MB** (arm64 only), debug with two ABIs **109MB**; kefi-player today
  is 6.0MB release / 8.2MB debug.
- **Notification ID collision — confirmed by decompiling `media3-session-1.11.0`.**
  `DefaultMediaNotificationProvider`'s default notification id is literally `1001`, and
  ytmp3's `DownloadService.NOTIFICATION_ID` is also `1001`. Notification ids are per-package:
  starting a download while music plays would silently replace the Now Playing notification.
  T5 fixes this before either service ships.
- **`ensureNativeToolsReady()` is already lazy** in ytmp3's `App.kt` — it is called only from
  `YtdlpPlugin` methods, never from `Application.onCreate()`. A user who never unlocks the tab
  never pays the ~40MB unpack. This plan must not "helpfully" hoist it into `onCreate()`.
- **The SEND intent-filter sits on `MainActivity` in ytmp3.** Copied as-is it would expose the
  hidden feature in the share sheet on first install. It must move to a separate
  `<activity-alias>` that ships disabled.
- **Stable yt-dlp cannot download YouTube at all** as of 2026-08 (YouTube forces SABR;
  web-client formats come back without URLs, other clients 403). ytmp3 force-installs the
  **nightly** channel on first launch and re-checks at most daily. This is functional, not
  cosmetic, and the port inherits it — along with the standing maintenance burden of an
  extraction arms race.
- **`ytmp3` is not a git repository** and has no notepads; its `README.md` is the only recorded
  history. There is no commit history to mine and no way to diff a future divergence.
- **ytmp3's two music-player plans were never executed** (0/18 and 0/14 boxes). There is no
  player code there to reconcile — only design decisions, already superseded here.
- **The custom SAF folder picker defeats decision 4.** Files written into a user-picked tree
  never enter MediaStore, so they would never appear in the library. It is deliberately not
  ported (see Must NOT Have 3).
- `kefi-player` already declares `INTERNET` (Capacitor scaffold, currently unused by any JS).
  `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE_DATA_SYNC` and legacy `WRITE_EXTERNAL_STORAGE`
  are new.
- ytmp3's UI is **English-only, hardcoded**. Every string it shows must be translated into the
  el/en dictionaries here, where `grep` in QA already forbids hardcoded UI text.

### Fixed constants (used verbatim by every task)

- `PKG` = `com.kefi.player`; `EMU` = `adb -s emulator-5554`; source tree `SRC` =
  `/mnt/X/myProjects/ytmp3` (**read-only**).
- Build env for Gradle: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`
- Unlock preference key: `kefi.downloader.unlocked` (value `"1"`), namespaced like the existing
  `kefi.locale` (`src/i18n/index.js:7`).
- Easter egg: **10 taps**, **15000 ms** window, counted on the Settings support card
  (`src/views/SettingsView.vue:77`).
- Downloads notification: channel `downloads`, **notification id `2001`** (NOT 1001).
- Downloads land in MediaStore `Music/YTMP3` (`MediaStoreExporter.ALBUM_DIR = "YTMP3"`).
- Route: `/downloader`. Tab order once unlocked: Library | Downloads | Settings.

## Must Have

1. **One `App.kt`** carrying `instance`, the existing playback scope, a separate download
   scope, and ytmp3's lazy `ensureNativeToolsReady()`. Neither responsibility may be dropped.
2. **`YtdlpPlugin.kt` ported with its contract intact**: `prepare`, `getInfo`, `download`,
   `cancel`, `updateYtdlp`, `checkForUpdate`, `mintPoToken`, `openFile`, `getStorageLocation`,
   `consumeSharedUrl`; events `progress`, `completed`, `failed`, `cancelled`, `sharedUrl`.
   Payload shapes and the `"CANCELLED"` reject code stay byte-identical to ytmp3's.
3. **`DownloadService.kt`** as a `dataSync` foreground service on channel `downloads` with
   notification id **2001**, plus an explicit `setNotificationId(1001)` (or any distinct value)
   documented on the Media3 provider so the two can never collide again.
4. **`MediaStoreExporter.kt` + a reduced `StorageExporter.kt`** (MediaStore path only),
   `PoTokenGenerator.kt`, `SharedUrlHolder.kt`.
5. **Gradle**: both `youtubedl-android` artifacts, `packaging.jniLibs.useLegacyPackaging = true`,
   `abiFilters` wired to the `-PabiFilters` property the existing `scripts/build-apk.sh` already
   passes, defaulting to `arm64-v8a`.
6. **Manifest**: `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE_DATA_SYNC`,
   `WRITE_EXTERNAL_STORAGE maxSdkVersion="28"`, the `DownloadService`, and a
   `<activity-alias android:name=".ShareTarget" android:enabled="false">` carrying the only
   `ACTION_SEND` filter.
7. **Web layer in plain JS + JSDoc**: `src/plugins/ytdlp.js`, `src/plugins/ytdlp.web.js`,
   `src/composables/useDownloads.js`, `src/views/DownloaderView.vue`,
   `src/components/JobCard.vue`, new helpers merged into `src/utils/format.js`.
8. **Every downloader string through `t()`**, in both `src/i18n/en.js` and `src/i18n/el.js`.
9. **Styling from this app's tokens only** — no CSS copied from ytmp3 (its token names collide
   with ours but mean different colours).
10. **The easter egg**: `src/composables/useEasterEgg.js`, wired to the support card, persisting
    `kefi.downloader.unlocked` via `@capacitor/preferences`.
11. **Conditional third tab** in `TabBar.vue` and a router guard that redirects `/downloader`
    to `/library` while locked.
12. **The share alias enabled from the same flag**, reasserted on every `App.onCreate()` so its
    state can never drift from the preference.
13. **The PO-token build** (`src/potoken/index.ts`, `vite.potoken.config.ts`, the
    `build:potoken` script) copied as the single, documented TypeScript exception, with `sync`
    and `build-apk.sh` both running it.
14. **QA**: `scripts/qa-playback.sh` keeps its existing checks green and gains offline checks
    for the easter egg, the router guard, the tab, and the downloader UI driven through the web
    mock. Plus `scripts/qa-downloader.sh`: device-gated, skips loudly when no arm64 device is
    attached.

## Must NOT Have

1. **No modification of anything under `/mnt/X/myProjects/ytmp3`** — not a file, not a build, not
   a `cap sync`. It is a source to read.
2. **No TypeScript in the Vue app.** The only `.ts` files permitted are `src/potoken/index.ts`
   and `vite.potoken.config.ts`, which are a separate IIFE bundle for an offscreen WebView and
   never enter the app bundle. No `vue-tsc`, no `tsconfig.app.json`, no typed `.vue` files.
3. **No SAF custom-folder picker** (`StorageLocation.kt`, `pickStorageLocation`,
   `clearStorageLocation`, the "Change/Default" UI). Files in a picked tree never enter
   MediaStore, which would break decision 4 outright.
4. **No network in `npm run qa`.** No real YouTube call, no fixture HTTP server.
5. **No visible trace of the downloader before unlock**: not in the tab bar, not in the share
   sheet, not in Settings, not in the router (a typed `#/downloader` must redirect).
6. **No re-lock gesture**, no "hidden features" toggle in Settings.
7. **No change** to `applicationId`, app name, icon, splash, or the existing palette.
8. **No hoisting `ensureNativeToolsReady()` into `Application.onCreate()`** — a user who never
   unlocks must never pay the unpack.
9. **No Play Feature Delivery / dynamic feature modules**, no runtime download of the engine.
10. **No Pinia, no Room, no new state library.** Module-level composables, as everywhere else here.
11. **No weakening of the existing 38 checks** to make room for new ones.
12. **No auto-update of yt-dlp on app start** — only when the downloader tab is actually opened
    (a locked user must generate no network traffic).

## TODOs

### Phase A — measure before building

- [x] **T1: Baseline capture**
  - Files: none (records only, into `.claude/notepads/kefi-player-hidden-downloader-tab/learnings.md`).
  - Record: `npm run qa` result (expect `38/38`), `du -b` of both current APKs, and
    `adb -s emulator-5554 shell getprop ro.product.cpu.abilist`.
  - QA Scenario: `npm run qa` exits `0` and prints `38/38 checks passed`; the notepad file
    exists and contains the three numbers.

- [x] **T2: Can the emulator exec an arm64 binary?** — **VERDICT: EXEC-OK** (2026-08-20).
  `ro.enable.native.bridge.exec=1`; an arm64 `libqjs.so` ran on `emulator-5554` and evaluated
  `1+1` → `2`. Downloader QA is therefore NOT device-only: T18 gates on capability, and the
  emulator passes that gate.
  - Files: `.claude/notepads/.../learnings.md` (findings only).
  - The AVD reports `x86_64,arm64-v8a` in `ro.product.cpu.abilist`, but that covers install
    matching and JNI translation — NOT `exec()` of a standalone ARM64 ELF, which is exactly
    what `YoutubeDL.execute()` does (`ProcessBuilder` spawning `libpython.so`). Determine it:
    `$EMU shell getprop ro.enable.native.bridge.exec`, then push the arm64 `libqjs.so` extracted
    from the `youtubedl-android` AAR in `~/.gradle/caches` to `/data/local/tmp`, `chmod 755`,
    and run it.
  - QA Scenario: the notepad records one of exactly two verdicts, with the command output
    pasted: **EXEC-OK** (binary runs) or **EXEC-NO** (`Exec format error`). If EXEC-NO, T18's
    device-gated script is the only place downloads can ever be exercised, and the plan says so
    rather than pretending otherwise.

### Phase B — native plumbing, nothing reachable

- [x] **T3: Merge `App.kt`**
  - Files: `android/app/src/main/java/com/kefi/player/App.kt`.
  - Pattern: keep the existing file's shape (`kefi-player/.../App.kt:20-30`); add
    `downloadScope` as a second `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and copy
    `ensureNativeToolsReady()` verbatim from `$SRC/android/app/src/main/java/com/ytmp3/app/App.kt:42-52`
    (double-checked `isReady` + `Mutex`). Existing `scope` keeps its name and its users.
  - QA Scenario: `npm run apk` prints `BUILD SUCCESSFUL`; `npm run qa` still `38/38`;
    `$EMU logcat -d -s KefiApp | grep -c "KefiApp onCreate"` ≥ 1 after a relaunch, and
    `grep -c "ensureNativeToolsReady" android/app/src/main/java/com/kefi/player/App.kt` = 1
    while `grep -c "ensureNativeToolsReady" .../MainActivity.java` = 0 (proof it stayed lazy).

- [x] **T4: Gradle — dependencies, packaging, ABI**
  - Files: `android/variables.gradle`, `android/app/build.gradle`.
  - Add `youtubedlAndroidVersion = '0.18.1'`; add `io.github.junkfood02.youtubedl-android:library`
    and `:ffmpeg`, `androidx.documentfile:documentfile:1.1.0`,
    `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`. Add
    `packaging { jniLibs { useLegacyPackaging = true } }` and the `META-INF` excludes, and wire
    `defaultConfig.ndk.abiFilters` to `project.findProperty('abiFilters')?.split(',') ?: ['arm64-v8a']`
    — `scripts/build-apk.sh:69` already passes `-PabiFilters`, it is currently inert.
    Pattern: `$SRC/android/app/build.gradle`.
  - QA Scenario: `npm run apk` prints `BUILD SUCCESSFUL`;
    `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c 'lib/arm64-v8a/libpython.zip.so'`
    prints `1`; `unzip -l ... | grep -c 'lib/x86_64/'` prints `0`; the APK still installs
    (`$EMU install -r` → `Success`) and `npm run qa` is `38/38`.

- [x] **T5: Notification identity — kill the 1001 collision**
  - Files: `android/app/src/main/java/com/kefi/player/PlaybackService.kt`,
    NEW `.../DownloadService.kt`.
  - `DefaultMediaNotificationProvider`'s default id is 1001 (decompiled from
    `media3-session-1.11.0`), and ytmp3's `DownloadService` also uses 1001. Pin the media
    provider explicitly with `.setNotificationId(1001)` and give the download service **2001**,
    each with a comment naming the other.
  - QA Scenario: with music playing and a download running (or, offline, by starting the
    service through `adb shell am start-foreground-service`), `$EMU shell dumpsys notification --noredact | grep -c "pkg=com.kefi.player"`
    is `2`, and the two records show different ids. `npm run qa` `38/38`.

- [x] **T6: Port the downloader's native files**
  - Files: NEW `android/app/src/main/java/com/kefi/player/{YtdlpPlugin.kt,DownloadService.kt,MediaStoreExporter.kt,StorageExporter.kt,PoTokenGenerator.kt,SharedUrlHolder.kt}`;
    NEW `android/app/src/main/res/xml/file_paths.xml` if absent.
  - Package renamed to `com.kefi.player`; `App.scope` references become `App.downloadScope`.
    `StorageExporter` keeps only the MediaStore branch (no `StorageLocation`, no tree URI), and
    `YtdlpPlugin` drops `pickStorageLocation`/`clearStorageLocation` while keeping
    `getStorageLocation` returning the fixed default label. Everything else — argument names,
    resolve payloads, event names, `humanError()` mapping, the `"CANCELLED"` code — is copied
    verbatim from `$SRC/android/app/src/main/java/com/ytmp3/app/YtdlpPlugin.kt`.
  - QA Scenario: `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints
    `0`; the SAF picker is absent AS CODE:
    `grep -rn "fun pickStorageLocation\|fun clearStorageLocation\|import androidx.documentfile" android/app/src/main/java/ | wc -l`
    prints `0`, `android/app/src/main/java/com/kefi/player/StorageLocation.kt` does not exist, and
    `grep -c documentfile android/app/build.gradle` prints `0`. `npm run qa` `38/38`.
    (Corrected twice on 2026-08-20. First wording grepped for `StorageLocation`, which the
    deliberately-kept `getStorageLocation` contains as a substring. Second wording still matched
    the COMMENTS that document the omission — which are exactly what should be there. A criterion
    must test the code, not the prose about it.)

- [x] **T7: Manifest — permissions, service, share alias (disabled)**
  - Files: `android/app/src/main/AndroidManifest.xml`.
  - Add the three permissions, the `DownloadService` (`exported="false"`,
    `foregroundServiceType="dataSync"`), and:
    `<activity-alias android:name=".ShareTarget" android:targetActivity=".MainActivity" android:enabled="false" android:exported="true">`
    with the ONLY `ACTION_SEND`/`text/plain` filter. `MainActivity` itself must not gain that
    filter.
  - QA Scenario: `grep -c 'android:name=".ShareTarget"' android/app/src/main/AndroidManifest.xml`
    = 1 and the alias block contains `android:enabled="false"`; after install,
    `$EMU shell cmd package query-activities -a android.intent.action.SEND -t text/plain | grep -c com.kefi.player`
    prints `0`.

- [x] **T8: `MainActivity` — second plugin, shared URLs**
  - Files: `android/app/src/main/java/com/kefi/player/MainActivity.java`.
  - `registerPlugin(YtdlpPlugin.class)` next to the existing `registerPlugin(PlayerPlugin.class)`
    **before** `super.onCreate` (`MainActivity.java:22`), keep the existing WebViewListener and
    insets code untouched, add `captureSharedUrl(getIntent())` after `super.onCreate` and an
    `onNewIntent` override that calls `super.onNewIntent(intent)` first (BridgeActivity
    dispatches it to plugins). Pattern: `$SRC/.../MainActivity.java:16-32`.
  - QA Scenario: `npm run qa` `38/38` (proves renderer recovery and insets still work);
    `$EMU shell am start -n com.kefi.player/.MainActivity` then in the WebView
    `Capacitor.Plugins.Ytdlp.consumeSharedUrl()` resolves `{"url":null}` — i.e. the plugin is
    registered and reachable.

### Phase C — the web layer, reachable only by typing the route

- [x] **T9: Plugin bridge + web mock in plain JS**
  - Files: NEW `src/plugins/ytdlp.js`, NEW `src/plugins/ytdlp.web.js`.
  - Convert `$SRC/src/plugins/ytdlp.ts` and `ytdlp.web.ts`: TS types become JSDoc `@typedef`s,
    exactly as `src/plugins/player.js` already does. The web mock keeps its fake progress loop —
    it is what the offline QA drives.
  - QA Scenario: `npm run build` succeeds;
    `find src -name "*.ts" | wc -l` prints `0` (the potoken bundle arrives in T15 and lives
    under `src/potoken/`, which this check must then exclude — see F4).

- [x] **T10: `useDownloads.js`**
  - Files: NEW `src/composables/useDownloads.js`.
  - Convert `$SRC/src/composables/useDownloads.ts` to plain JS + JSDoc, keeping the
    module-level singleton shape (identical to `src/composables/usePlayer.js:12-35`), the
    attach-once listener guard, and the job state machine
    (`queued → downloading ⇄ converting → done | failed | cancelled`). Keep the
    once-per-24h nightly auto-update, but move its trigger from "app start" to "downloader
    screen mounted" (Must NOT Have 12).
  - QA Scenario: in the browser (`npm run dev`), `useDownloads().start()` against the web mock
    drives a job to `done` with `progress` reaching 100 — asserted in T17's suite through the
    CDP bridge, not by hand.

- [x] **T11: Downloader UI**
  - Files: NEW `src/views/DownloaderView.vue`, NEW `src/components/JobCard.vue`.
  - Port the composer (URL field + paste, look-up, preview card, bitrate chips, primary
    action), the jobs list and the engine/update line from `$SRC/src/App.vue:179-302` and
    `$SRC/src/components/JobCard.vue`. **Rewrite every style against this app's tokens** — the
    same variable names mean different colours here. Layout follows `src/views/LibraryView.vue`
    (page padding, `min-height: 100%`, `--safe-*`).
  - QA Scenario: with the route reachable, the CDP suite finds `.downloader-page`, exactly 3
    bitrate chips, and a disabled primary button while the URL field is empty.

- [x] **T12: Strings**
  - Files: `src/i18n/en.js`, `src/i18n/el.js`.
  - Every literal from T11 as `downloader.*` keys in BOTH dictionaries; Greek written with
    correct accents (the document `lang` already fixes uppercase rendering).
  - QA Scenario: `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("`
    returns nothing; `node -e "const en=..., el=...; process.exit(Object.keys(en).every(k=>k in el)?0:1)"`
    exits `0`.

- [x] **T13: Route, still hidden**
  - Files: `src/router/index.js`.
  - Add `/downloader → DownloaderView`, ABOVE the catch-all. No tab yet, no guard yet — this
    task exists so Phase C can be QA'd before the easter egg is written.
  - QA Scenario: `location.hash = '#/downloader'` renders the view; `npm run qa` `38/38`.

- [x] **T14: Merge the format helpers**
  - Files: `src/utils/format.js`.
  - Add `formatEta`, `formatBytes`, `looksLikeUrl`, `looksLikeYoutube` from
    `$SRC/src/utils/format.ts`. `formatDuration` is already identical here — do not duplicate it.
  - QA Scenario: `node -e "import('./src/utils/format.js').then(m=>{...})"` asserts
    `formatBytes(0) === ''`, `formatEta(90) === '2m left'`, `looksLikeYoutube('https://youtu.be/x') === true`.

- [x] **T15: The PO-token bundle (the one TypeScript exception)**
  - Files: NEW `src/potoken/index.ts`, NEW `vite.potoken.config.ts`, `package.json`,
    `scripts/build-apk.sh`, `README.md`.
  - Copy both verbatim (output path `android/app/src/main/assets/potoken/potoken.js`), add
    `bgutils-js` + a `typescript` devDependency, add `"build:potoken"`, and make BOTH
    `sync` and `scripts/build-apk.sh` run it — ytmp3's build script does not, which silently
    ships a stale bundle. Document in `README.md` why this one file is TypeScript.
  - QA Scenario: `npm run build:potoken` produces
    `android/app/src/main/assets/potoken/potoken.js`; `grep -c "build:potoken" scripts/build-apk.sh`
    ≥ 1; `npm run apk` then `unzip -l ...app-debug.apk | grep -c assets/potoken/potoken.js` = 1.

### Phase D — the easter egg

- [ ] **T16: Unlock gesture, persistence, tab, guard, share alias**
  - Files: NEW `src/composables/useEasterEgg.js`; `src/views/SettingsView.vue`,
    `src/components/TabBar.vue`, `src/router/index.js`,
    `android/app/src/main/java/com/kefi/player/{App.kt,PlayerPlugin.kt}`.
  - Composable: module-level `unlocked` ref hydrated from `kefi.downloader.unlocked`
    (pattern `src/i18n/index.js:47-56`), plus `registerTap()` keeping a timestamp array,
    dropping entries older than 15000 ms, unlocking at the 10th. Taps land on the existing
    support card (`SettingsView.vue:77`) via `@click` only — no class change, no `role`, no
    cursor, nothing a reader can see.
  - Tab: `TabBar.vue:9-12` gains a third entry rendered only while unlocked.
  - Guard: `router.beforeEach` redirects `/downloader → /library` while locked.
  - Alias: NEW `PlayerPlugin.setShareTargetEnabled({ enabled })` calling
    `PackageManager.setComponentEnabledSetting(ComponentName(context, "$packageName.ShareTarget"), …, DONT_KILL_APP)`,
    called on unlock AND re-asserted from `App.onCreate()` on every launch from the same
    preference, so the two can never disagree.
  - QA Scenario (all in T17): 9 taps → still locked; 10 taps over 16 s → still locked; 10 taps
    inside 15 s → unlocked, third tab appears, `#/downloader` stops redirecting; after
    `am force-stop` + relaunch it is still unlocked; `cmd package query-activities` now lists
    `com.kefi.player` for `ACTION_SEND`.

### Phase E — QA

- [ ] **T17: Extend `scripts/qa-playback.sh` (offline)**
  - Files: `scripts/qa-playback.sh`.
  - Append a section in the existing style (`pass`/`fail` helpers at `:37-38`, the `js()` CDP
    helper at `:106`): locked-state checks (no third tab, `#/downloader` redirects, share
    activity not queryable), the three gesture cases from T16, persistence across
    `am force-stop`, and a downloader-UI smoke test driven through the **web mock** — never the
    network.
  - QA Scenario: `npm run qa` exits `0` and reports at least `38 + 10` checks, with the original
    38 unchanged. Each new check must fail if its feature is reverted — verify by temporarily
    reverting the guard and observing a `FAIL` (record the evidence in the notepad).

- [ ] **T18: `scripts/qa-downloader.sh` (device-gated)**
  - Files: NEW `scripts/qa-downloader.sh`, `package.json` (`"qa:downloader"`).
  - Takes a serial; refuses to run unless the target reports `arm64-v8a` first in
    `ro.product.cpu.abilist` (or T2 recorded EXEC-OK); prints `SKIP` with the reason and exits
    `0` when there is no such device, so it can sit in CI-like runs harmlessly. Checks it CAN
    make offline: the plugin answers `prepare()`, the engine version string is non-empty, and
    `dumpsys notification` shows id 2001 while a job runs.
  - QA Scenario: on the x86_64 emulator it prints `SKIP: no arm64 device attached` and exits
    `0`; on a connected phone it runs and every line is `PASS`.

## Final Verification Wave

- [ ] **F1: Build integrity** — `npm run build`; `npm run apk` prints `BUILD SUCCESSFUL`;
  `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.
- [ ] **F2: Release integrity** — `npm run apk:release` prints `BUILD SUCCESSFUL` and the
  unsigned APK exists. Record its size; expect ~55-60MB and state the number in the notepad
  rather than glossing over it.
- [ ] **F3: Full QA** — `npm run qa` exits `0`, the original 38 checks are all present and
  green, and the new checks pass. `npm run qa:downloader` either runs green on a device or
  prints `SKIP` on the emulator.
- [ ] **F4: Scope review of the complete diff** — `git diff --stat` reviewed against Must Have
  and Must NOT Have; `find . -name "*.ts" -not -path "./node_modules/*" -not -path "./android/*" -not -path "./src/potoken/*" -not -name "vite.potoken.config.ts" | wc -l`
  prints `0`; `grep -rn "StorageLocation\|pickStorageLocation" src/ android/app/src/main/java/ | wc -l`
  prints `0`; nothing under `/mnt/X/myProjects/ytmp3` modified
  (`find /mnt/X/myProjects/ytmp3 -newer .claude/plans/kefi-player-hidden-downloader-tab.md -not -path "*/node_modules/*" | wc -l` prints `0`);
  `git status --porcelain` empty.
- [ ] **F5: Hands-on QA on the user's phone (BLOCKED-UNTIL-DEVICE)** — the four things no script
  here can do: a real download completes and the MP3 plays; the finished file appears in
  Songs/Albums/Artists and under Folders → YTMP3; a download running while music plays shows
  TWO notifications; sharing a YouTube link from the YouTube app offers Kefi Player only after
  unlock. Reported as not run if it is not run.
- [ ] **F6: Documentation** — `README.md` gains the downloader section (what it is, the easter
  egg, the nightly yt-dlp requirement, the size cost, the single TypeScript exception, and that
  `ytmp3` remains the separate upstream that will drift).

## Success Criteria

1. `cd /mnt/X/myProjects/kefi-player && git status --porcelain | wc -l` → `0`
2. `npm run apk 2>&1 | grep -c "BUILD SUCCESSFUL"` → `1`
3. `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` → `0`
4. `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c 'lib/x86_64/'` → `0`
5. `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c 'lib/arm64-v8a/libpython.zip.so'` → `1`
6. `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c 'assets/potoken/potoken.js'` → `1`
7. `npm run qa` exits `0`, prints `ALL PASS`, and the count is ≥ `48`
8. Fresh install, before any gesture:
   `adb -s emulator-5554 shell cmd package query-activities -a android.intent.action.SEND -t text/plain | grep -c com.kefi.player` → `0`
9. Fresh install, before any gesture: the WebView reports `document.querySelectorAll('.tab').length` → `2`,
   and setting `location.hash='#/downloader'` leaves `location.hash` as `#/library`
10. After 10 scripted taps inside 15 s: `document.querySelectorAll('.tab').length` → `3`,
    `#/downloader` sticks, and `cmd package query-activities …` → `1`
11. After `am force-stop com.kefi.player` and relaunch: still `3` tabs (persistence)
12. 9 taps, and 10 taps spread over 16 s, both leave `document.querySelectorAll('.tab').length` → `2`
13. `find . -name "*.ts" -not -path "./node_modules/*" -not -path "./android/*" -not -path "./src/potoken/*" -not -name "vite.potoken.config.ts" | wc -l` → `0`
14. `grep -rn "fun pickStorageLocation\|fun clearStorageLocation\|import androidx.documentfile" android/app/src/main/java/ | wc -l` → `0`;
    `grep -rn "\.pickStorageLocation(\|\.clearStorageLocation(" src/ | wc -l` → `0`;
    `ls android/app/src/main/java/com/kefi/player/StorageLocation.kt` reports no such file;
    `grep -c documentfile android/app/build.gradle` → `0`
    (`getStorageLocation` and the comments explaining the omission legitimately survive)
15. `grep -c "ensureNativeToolsReady" android/app/src/main/java/com/kefi/player/App.kt` → `1`,
    and `grep -rc "ensureNativeToolsReady" android/app/src/main/java/com/kefi/player/MainActivity.java` → `0`
16. `find /mnt/X/myProjects/ytmp3 -newermt "$(date +%Y-%m-%d)" -not -path "*/node_modules/*" -not -path "*/.gradle/*" -type f | wc -l` → `0`
