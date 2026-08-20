# Learnings — kefi-player full Android player

## Environment (verified 2026-08-19, before execution)
- Project: /mnt/X/myProjects/kefi-player — Vue 3 + Vite, **plain JavaScript, NO TypeScript**.
- Build env for every Gradle command:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`
- `~/Android/Sdk` has NO cmdline-tools and NO tools/bin — no sdkmanager/avdmanager until T1 installs them.
- Existing AVDs: Pixel_7_API_35, Pixel_Fold_API_35 (both API 35 x86_64 google_apis_playstore).
- There is NO android-36 google_apis_playstore image. Correct package: `system-images;android-36;google_apis;x86_64`.
- build-tools present: 34.0.0, 35.0.0, 36.0.0. platforms: 34,35,36,36.1,37.1.
- ffmpeg 6.1.1 at /usr/bin/ffmpeg (fixture generation).
- No physical device connected (`adb devices` empty).
- npm scripts: dev, build, preview, sync, apk, apk:release, install:apk, open, dev:android, icons, fonts, keystore.
- Baseline debug APK size before Media3: ~4.3MB (4,3M reported by du).

## Codebase conventions (pattern source = /mnt/X/myProjects/ytmp3, READ-ONLY)
- Capacitor plugins must be registered manually: `registerPlugin(X.class)` in MainActivity BEFORE super.onCreate().
- Kotlin: app-scoped singleton + CoroutineScope pattern in ytmp3 App.kt:13-29.
- Plugin idioms: @CapacitorPlugin YtdlpPlugin.kt:28; resolve/reject :69-93; rejectWith Throwable bridge :444-449;
  notifyListeners :161-167; load()/handleOnDestroy() :33-43.
- JS plugin wrapper: ytdlp.ts:78-148 (convert to plain JS + JSDoc); web stub: ytdlp.web.ts:15-126.
- Composable state: module-level refs + readonly() exposure, useDownloads.ts:30-45, :246-268; listener-attach-once :55,:76-115.
- Design tokens live in kefi-player's own src/style.css (--bg, --surface-1/2, --border, --text, --text-muted,
  --accent, --accent-strong, --accent-deep, --accent-soft, --on-accent, --success, --danger, --danger-soft,
  --safe-top, --safe-bottom, --font-sans, --font-display). Components consume them; never hardcode colours.

## Hard technical rules (from research, do not re-derive)
- Media3 1.11.0. Only media3-exoplayer + media3-session. NEVER media3-ui/datasource/decoder-ffmpeg/exoplayer-av1.
- DO NOT override onTaskRemoved() — Media3's base class already does the right thing.
- ExoPlayer.Builder: setAudioAttributes(..., handleAudioFocus=true) + setHandleAudioBecomingNoisy(true) + setWakeMode(C.WAKE_MODE_LOCAL).
- MediaController lives ONLY in an application-scoped singleton, all access on Dispatchers.Main,
  first touched ONLY from a user-gesture-originated plugin call (never Application.onCreate) or API34+ throws
  ForegroundServiceStartNotAllowedException.
- State flow must be push (Player.Listener) AND pull (getPlayerState snapshot on resume). Never render from cache after resume.
- @PermissionCallback alias MUST be declared on the @CapacitorPlugin annotation or it throws at runtime.
  ytmp3 has NO runtime-permission example — follow https://capacitorjs.com/docs/plugins/android.
- READ_MEDIA_AUDIO is API33+; minSdk 24 needs READ_EXTERNAL_STORAGE maxSdkVersion="32" fallback.
- Artwork crosses the bridge as a base64 data URL (loadThumbnail API29+, MediaMetadataRetriever 24-28, always `use`-style).
- Greek collation: Intl.Collator('el', { sensitivity: 'base' }) for EVERY user-facing sort/search.

## T1 — git init + QA emulator (2026-08-19)
- No fallback needed: cmdline-tools download, license acceptance, and system-image install all succeeded on the first try. Global `git config user.email` (v.paparidis@gmail.com) was already non-empty, so no repo-local identity override was needed.
- Repo committed: single root commit `9cc9321` "Scaffold: Vue 3 + Vite (JS) + Capacitor 8 Android" (96 files, 5192 insertions). `git diff --cached --name-only | grep -cE "^(node_modules|dist|android/app/build)/"` was `0` before committing.
- sdkmanager binary that worked: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/home/nonos/Android/Sdk` — MUST export `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` first; the ambient `JAVA_HOME=/usr/lib/jvm/17-openjdk-17` (already exported in the shell) is invalid/broken and sdkmanager/avdmanager refuse to run with it.
- avdmanager binary that worked: `~/Android/Sdk/cmdline-tools/latest/bin/avdmanager` (same JAVA_HOME requirement).
- AVD actually created: `Kefi_API_36` (device pixel_7, target Google APIs android-36 google_apis/x86_64), path `/home/nonos/.android/avd/Kefi_API_36.avd`. Existing `Pixel_7_API_35` / `Pixel_Fold_API_35` untouched (verified via `avdmanager list avd`).
- Emulator boot command that worked:
  `cd /home/nonos/Android/Sdk/emulator && ANDROID_HOME=/home/nonos/Android/Sdk ./emulator -avd Kefi_API_36 -no-window -no-boot-anim -gpu swiftshader_indirect &`
  then `adb -s emulator-5554 wait-for-device` followed by polling `adb -s emulator-5554 shell getprop sys.boot_completed` until `1`.
- System image download+install (`system-images;android-36;google_apis;x86_64`, 4.3G on disk) took ~59 seconds total (fast link).
- Booted device `ro.build.version.sdk` = `36` (no fallback to API 35 was needed).

## T4 — App.kt application-scoped singleton (2026-08-19)
- Package `com.kefi.player`. Access syntax later tasks (T5/T6/T7) must use verbatim:
  `com.kefi.player.App.instance` (or bare `App.instance` from within the `com.kefi.player`
  package) for the singleton `Application` instance, and `App.scope` (a companion-object
  `val`, `CoroutineScope(SupervisorJob() + Dispatchers.IO)`) for the application-scoped
  coroutine scope. Both are on `App`'s `companion object`; `instance` has a private setter
  assigned once in `onCreate()`.
- Did NOT copy ytmp3's `ensureNativeToolsReady()`/`Mutex`/`isReady`/YoutubeDL/FFmpeg — none
  of that is relevant here; T6 will add its own mutex-guarded init for the MediaController.
- Manifest: added only `android:name=".App"` to the existing `<application>` tag in
  `android/app/src/main/AndroidManifest.xml`. Nothing else in the manifest touched.
- Verified: `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` = `0` (first `.kt` file
  in the project compiled cleanly, no Kotlin toolchain issues surfaced).
  `npm run apk 2>&1 | grep -c "BUILD SUCCESSFUL"` = `1` (APK 8.2M).
  Installed on `emulator-5554` (Success), launched `com.kefi.player/.MainActivity`, and
  `adb -s emulator-5554 logcat -d | grep -c "KefiApp onCreate"` = `1` — the `Log.i("KefiApp",
  "KefiApp onCreate")` line fired, confirming `.App` is actually wired into the manifest and
  running (not dead code).

## T3 — Media3 dependencies only (2026-08-19)
- APK size before edit: 4,424,819 bytes. APK size after adding media3-exoplayer + media3-session 1.11.0: 8,541,817 bytes. Delta: +4,116,998 bytes (~3.93MB), within the required 4,600,000–8,800,000 byte absolute range from the task's Expected Outcome.
- `unzip -l app-debug.apk | grep -c 'lib/'` = 0 — confirmed Media3 pulled in no native libraries.
- `npm run apk 2>&1 | grep -c "BUILD SUCCESSFUL"` = 1.

## T5 — PlaybackService (MediaSessionService) + manifest (2026-08-19)
- f1.mp3 ("Άσπρη Μέρα") content URI resolved with the colon-projection form:
  `adb -s emulator-5554 shell content query --uri content://media/external/audio/media --projection _id:title --where "\"title='Άσπρη Μέρα'\""`
  → `_id=24` → hardcoded URI `content://media/external/audio/media/24`. Note the
  extra outer `\"...\"` quoting around the whole `--where` value: without it the adb
  shell tokenizer drops the SQL string's single quotes and `content query` throws
  `SQLiteException: near "Μέρα": syntax error`.
- **Critical Media3 gotcha not spelled out in the task text**: building
  `MediaSession.Builder(this, player).build()` and returning it from `onGetSession`
  is NOT enough by itself. `MediaSessionService` only wires a session into its
  internal notification/foreground-service manager when you explicitly call the
  base class's public `addSession(session)` (and `removeSession(session)` in
  `onDestroy` before releasing). Without `addSession`, the ExoPlayer genuinely
  plays audio (confirmed via `dumpsys audio`/`dumpsys media_session`) but the
  service NEVER calls `Service.startForeground()`, and — because we started it
  with `adb shell am start-foreground-service` — Android kills the process ~30s
  later with `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
  Context.startForegroundService() did not then call Service.startForeground()`.
  Confirmed this exact crash once, then fixed by adding `addSession(mediaSession)`
  in `onCreate()` right after building the session, and `removeSession(it)` as the
  first line of `onDestroy()`. Decompiled `media3-session-1.11.0-runtime.jar`
  (`javap -p androidx/media3/session/MediaSessionService.class`) to confirm
  `addSession`/`removeSession` are real public API on the base class.
- Debug entry point (marked `// TODO(T7): remove`, both the `onStartCommand`
  branch and the `ACTION_DEBUG_PLAY` constant and the hardcoded fixture URI line):
  triggered with
  `adb -s emulator-5554 shell am start-foreground-service -a com.kefi.player.debug.PLAY -n com.kefi.player/.PlaybackService`
  (plain `am startservice` — without `-foreground-service` — silently did nothing;
  ActivityManager never even reached our `onStartCommand`, no crash, no log lines
  at all, most likely background-service-start restrictions on a non-foreground
  caller even with shell's UID; do not use that variant for this service).
- Notification observed: `dumpsys activity services` → `isForeground=true
  foregroundId=1001 types=0x00000002` (`0x2` = `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`)
  `foregroundNoti=Notification(channel=playback ... category=transport
  groupKey=media3_group_key ...)`. `dumpsys notification --noredact` confirms
  `channel=playback`, `id=1001`, and `extras={ android.template=String
  (android.app.Notification$MediaStyle) }`. The foreground notification and
  `isForeground=true` persist even after the 5s fixture finishes and playback
  state goes to `STOPPED` — the service does not stop itself (matches
  `MediaSessionService`'s idle-player notification default), only `onTaskRemoved`
  (not overridden) would tear it down when the task is swiped away with nothing
  playing.
- `onTaskRemoved` is confirmed NOT overridden in `PlaybackService.kt` — verified
  by reading the committed file; only `onCreate`, `onGetSession`, `onStartCommand`,
  `onDestroy` are overridden.
- All 5 QA dumpsys/logcat assertions passed on `emulator-5554` after the
  `addSession` fix (see PR/commit for full pasted output): service foreground
  with `mediaPlayback` type, `media_session` `state=PLAYING(3)`, `dumpsys audio`
  `state:started` + `media.audio_flinger` output MIXER thread `Standby: no`,
  `logcat` error grep = `0`, notification `channel=playback` +
  `android.template=...MediaStyle`.

## T2 — deterministic audio fixtures + MediaStore indexing (2026-08-19)
- Codec used: `libmp3lame` (available on this machine) — all fixtures are `.mp3`, no AAC/`.m4a` fallback needed.
- Scan method that worked (first in the ladder, stop there): `adb -s emulator-5554 shell content call --uri content://media --method scan_file --arg /sdcard/Music/kefi-qa/<file>.mp3`, run once per file. `scan_volume` and `jobscheduler run -f com.android.providers.media.module -201` were never reached.
- Observed durations (ms): Άσπρη Μέρα=5042, Zeibekiko=7027, Ωδή=9038 (all within 500ms of nominal).
- `content query --projection` gotcha: comma-separated column list (`title,artist,album,duration`, as written in the plan) throws `IllegalArgumentException: Invalid column ...` on this API 36 `content` CLI. Must use **colons**: `--projection title:artist:album:duration` (matches `adb shell content query --help`). Use colon form in all later tasks that shell out to `content query`.
- Greek ID3 tags round-trip cleanly end to end (ffmpeg encode → ID3v2.3 → MediaStore `content query`) with no mojibake — verified both via `ffprobe -show_entries format_tags=...` and on-device `content query`.
- `scripts/qa-fixtures.sh <serial>` (default `emulator-5554`) is idempotent: verified by running it twice back-to-back — both runs report the same 3 rows, no duplicates, no schema drift.
- Found and fixed a real bug during testing: `if ffmpeg -encoders 2>/dev/null | grep -q libmp3lame; then` is unsafe under `set -euo pipefail` — `grep -q` closing the pipe early on match SIGPIPEs ffmpeg, and pipefail then reports the pipeline's exit status as ffmpeg's non-zero SIGPIPE code (141) instead of grep's 0, flipping the branch to the fallback even though the encoder is present. Fix: capture ffmpeg's output into a variable first (`FFMPEG_ENCODERS="$(ffmpeg ... 2>/dev/null)"`), then `grep -q ... <<<"$FFMPEG_ENCODERS"`. Applies to any `set -euo pipefail` script piping a full/slow producer into `grep -q`.

## Fixture duration fix (2026-08-19, corrective task discovered while verifying T5)
- The original 5/7/9s fixtures made T8's ">= 12000ms position advance across a 15s
  screen-off window" and T12's "play 20s then force-stop" assertions physically
  impossible — the 9s max track ended and the session dropped to `STOPPED` mid-assertion.
  Plan's constants table (and `scripts/qa-fixtures.sh`) updated to 180s/210s/240s, keeping
  frequencies (440/523/659 Hz), titles/artists/albums/track numbers, `-id3v2_version 3`,
  `libmp3lame -b:a 128k`, and embedded art on f1 only — all byte-identical metadata, only
  the `duration=` values in the three `ffmpeg -f lavfi -i "sine=frequency=...:duration=N"`
  lines and the `verify()` tolerance windows (now ±1500ms around 180000/210000/240000)
  changed.
- New local file sizes (`/tmp/kefi-qa/`): f1.mp3 = 2,883,703 bytes (180s), f2.mp3 =
  3,361,352 bytes (210s), f3.mp3 = 3,841,212 bytes (240s). Total push ~10.1MB, completed
  in well under 1s over adb (41.5 MB/s first run, 295.5 MB/s second run — device cache).
- New durations confirmed two ways: local `ffprobe -show_entries format=duration` gave
  180.035918s / 210.050612s / 240.039184s; on-device `content query` gave duration=180036 /
  210051 / 240039 (ms) for Άσπρη Μέρα / Zeibekiko / Ωδή respectively — both well within the
  1500ms tolerance.
- Re-ran `scripts/qa-fixtures.sh emulator-5554` twice back-to-back. Both runs: exactly 3
  `is_music=1` rows, no duplicates. **MediaStore `_id` values did NOT change** —
  re-pushing/re-scanning the same paths updated the existing rows in place rather than
  inserting new ones. Confirmed via `content query --projection _id:title:duration
  --where "is_music=1"` after the second run: `_id=23` Ωδή (240039), `_id=24` Άσπρη Μέρα
  (180036), `_id=25` Zeibekiko (210051). `_id=24` for Άσπρη Μέρα is unchanged from T5's
  originally recorded value, so `PlaybackService.kt`'s hardcoded
  `content://media/external/audio/media/24` debug URI is still correct and T7 does not
  need to update it before deleting that debug branch.

## T7 — PlayerPlugin.kt + JS bridge (2026-08-20)
- NEW `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`: `@CapacitorPlugin(name =
  "Player")`, methods `getPlayerState`, `playTrack({ uri, title?, artist? })`, `pause`,
  `resume`, `seek({ positionMs })` — all `@PluginMethod fun x(call: PluginCall)` launched on
  `App.scope.launch { }`, resolving/rejecting inside the coroutine. `rejectWith` is a private
  `PluginCall` extension bridging `Throwable` → `Exception` (`call.reject` only accepts
  `Exception`), copied in spirit from `YtdlpPlugin.kt:444-449`. `load()` only sets
  `PlayerController.onState = { ... }`; `handleOnDestroy()` clears it back to `null`. Neither
  touches `PlayerController` beyond that (no `ensureController()` trigger from `load()`, per
  the first-touch rule) — the controller is first connected lazily, inside `getPlayerState`/
  `playTrack`/etc., all of which are only ever invoked from a real user-gesture-originated
  WebView call.
- **Exact JSON key set emitted** (both `getPlayerState`'s resolve payload AND the
  `playerState` push event use the identical `toJSObject()` extension on
  `PlayerController.Snapshot`, so pull and push are byte-for-byte interchangeable):
  `mediaId`, `uri`, `title`, `artist`, `currentPosition`, `duration`, `isPlaying`,
  `shuffleModeEnabled`, `repeatMode`, `queueLength`, `currentIndex` — verbatim from T6's
  `Snapshot` data class, in that field order (Kotlin `data class` declaration order; observed
  identical order in every logcat line during QA, since `JSObject`/`org.json.JSONObject`
  preserves insertion order in this Android runtime). Nullable fields (`mediaId`, `uri`,
  `title`, `artist`) are written as explicit JSON `null` via `JSONObject.NULL` (not omitted),
  so all 11 keys are always present even before any track is loaded — confirmed in the very
  first `getPlayerState` call on mount:
  `{"mediaId":null,"uri":null,"title":null,"artist":null,"currentPosition":0,"duration":0,"isPlaying":false,"shuffleModeEnabled":false,"repeatMode":0,"queueLength":0,"currentIndex":0}`.
- **`MainActivity.java`**: added `onCreate(Bundle)` override calling
  `registerPlugin(PlayerPlugin.class);` before `super.onCreate(savedInstanceState);`, matching
  `ytmp3`'s `MainActivity.java:10-17` shape exactly (no `captureSharedUrl` — that was
  yt-dlp-specific).
- **JS side, plain JS + JSDoc, no `.ts` anywhere**: NEW `src/plugins/player.js` —
  `export const Player = registerPlugin('Player', { web: () => import('./player.web').then((m) => new m.PlayerWeb()) })`,
  with a `@typedef PlayerSnapshot` (the same 11 keys) and a `@typedef PlayerPlugin` JSDoc
  block typing every method. NEW `src/plugins/player.web.js` — `PlayerWeb extends WebPlugin`,
  keeps an in-memory snapshot object, `playTrack` seeds plausible mock title/artist/duration
  (180000ms) and starts a 1s `setInterval` ticker that advances `currentPosition` and calls
  `this.notifyListeners('playerState', ...)` while `isPlaying`; `pause`/`resume`/`seek`
  mutate the same state and re-emit. Confirmed via `npm run build`: `player.web.js` is
  code-split into its own lazy chunk (`dist/assets/player.web-*.js`, ~1.2KB), proving the
  dynamic `import()` fallback wiring is correct and the native plugin path is never bundled
  into the browser entry point.
- **How the WebView call was triggered for QA** (real WebView-originated call, not a native
  shortcut): added a TEMPORARY dev-only panel to `src/App.vue`, marked with
  `// TODO(T8): replace with usePlayer composable` at both the import and the panel/style
  blocks. It imports `Player` from `./plugins/player` directly (no composable — T8 owns
  that), holds a `ref(null)` snapshot, calls `Player.addListener('playerState', ...)` +
  `Player.getPlayerState()` in `onMounted`, and renders four buttons — "Play fixture (f1)"
  (`Player.playTrack({ uri: 'content://media/external/audio/media/24', title: 'Άσπρη Μέρα',
  artist: 'Δήμητρα Παπαδοπούλου' })`), "Pause", "Resume", "Refresh state" — plus a `<pre>`
  showing `JSON.stringify(snapshot)`. Also added a temporary `watch(snapshot, ... =>
  console.log('[T7 QA] playerState', JSON.stringify(value)))` (also marked
  `// TODO(T8): remove`) purely so every snapshot is visible in `adb logcat` — Capacitor
  forwards WebView `console.log` to Logcat under tag `Capacitor/Console`, which is what makes
  the JS-layer state greppable at all from the native side. QA was driven with real
  `adb shell input tap <x> <y>` taps on those on-screen buttons (coordinates read off an
  `exec-out screencap`), never a native shortcut.
- **What's left in `src/App.vue` for T8 to remove**: the whole dev panel
  (`<script setup>` `Player` import, the `FIXTURE_URI`/`snapshot`/`listenerHandle`/`watch`
  block, `refreshState`/`playFixture`/`pauseTrack`/`resumeTrack`, the `onMounted`/`onUnmounted`
  wiring) and the `<section class="dev-player">` template block plus its `.dev-player*` CSS —
  every one of these is marked with a `TODO(T8)` comment so a `grep -n "TODO(T8)" src/App.vue`
  finds every piece.
- **QA run against `emulator-5554`** (fixtures already present from T2, `_id=24` = f1/"Άσπρη
  Μέρα", confirmed unchanged via `content query`): granted
  `READ_MEDIA_AUDIO`/`POST_NOTIFICATIONS`, `adb logcat -c`, launched
  `com.kefi.player/.MainActivity`, tapped "Play fixture (f1)", then tapped "Refresh state"
  twice ~4s apart. Real captured logcat (via `Capacitor/Console`):
  - `08-20 00:09:19.838 ... [T7 QA] playerState {...,"currentPosition":2,"duration":179999,"isPlaying":true,...}` — confirms `isPlaying:true` and real duration (179999ms) immediately after `playTrack`.
  - Two "Refresh state" snapshots: `00:09:21.667` → `currentPosition:1795`; `00:09:25.693` →
    `currentPosition:5619`. Delta = 3824ms over 4.026s wall-clock — inside the required
    2500–5000ms window for a ≥3s gap.
  - `adb logcat -d | grep -oE '"isPlaying":(true|false)'` → 8 `false` / 8 `true` (state
    toggles across the button taps + initial mount read).
  - **Note on the plan's literal grep** `grep -oE '\{"isPlaying":[^}]*\}'`: this produces
    **zero matches** against our JSON, because `isPlaying` is the 7th key in T6's `Snapshot`
    field order (`mediaId, uri, title, artist, currentPosition, duration, isPlaying, ...`),
    not the first key right after `{`. The task's MUST DO section requires the *exact T6 key
    set*, not a specific key order, so the field order was kept as declared in
    `PlayerController.Snapshot` rather than reordered to make that one literal regex match.
    Used `grep -oE '"isPlaying":(true|false)'` (substring, order-independent) instead, and
    pasted full JSON lines above as the authoritative evidence.
  - `adb shell dumpsys media_session | grep state=` →
    `state=PlaybackState {state=PLAYING(3), position=11953, ...}` during the same session.
  - `adb logcat -d | grep -icE "Exception|FATAL|AndroidRuntime"` → 4 hits, all unrelated to
    `com.kefi.player` (an `EmojiCompatManager` `IllegalStateException` from the system
    keyboard and a `WindowManager` `DeadObjectException` from a just-force-stopped window) —
    zero exceptions traced to our plugin/controller code.
- **No duplicate-session problem occurred.** T6's inherited-wisdom note warned that
  connecting the first real `MediaController` might trigger `PlaybackService.onGetSession()`
  to re-register the already-added session and throw. This did not happen: `PlaybackService`
  still calls `addSession(mediaSession)` exactly once, unconditionally, in `onCreate()` (T5's
  original call site), and `PlayerController.ensureController()` only ever calls
  `MediaController.Builder(...).buildAsync()` against the existing token — it never calls
  `addSession` itself. No `isSessionAdded` guard was added to `PlaybackService.kt` because no
  failure was observed; `grep -icE "duplicate|isSessionAdded"` over the QA logcat = `0`.
- **T5 debug entry point fully removed** from `PlaybackService.kt`: the `onStartCommand`
  `if (intent?.action == ACTION_DEBUG_PLAY) { ... }` branch, the `private companion object`
  holding `ACTION_DEBUG_PLAY`, and the hardcoded `content://media/external/audio/media/24`
  fixture line are all gone (`grep -c "ACTION_DEBUG_PLAY\|content://media/external/audio/media/24" PlaybackService.kt` = `0`). `onStartCommand` itself is kept as a thin
  `return super.onStartCommand(intent, flags, startId)` passthrough (only the debug branch
  inside it was marked for removal, not the override itself) — `android.content.Intent` is
  still imported for its signature; `android.net.Uri` and the `androidx.media3.common.MediaItem` import (both only used by the deleted debug branch) were dropped.
- Verified: `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` = `0` (also confirmed
  Java compiles: `:app:compileDebugJavaWithJavac` `BUILD SUCCESSFUL`). `npm run build`
  succeeds (`player.web.js` correctly code-split, no TypeScript anywhere — no `.ts` file was
  created). `npm run apk` → `BUILD SUCCESSFUL`, APK 8.7M (unchanged from T6, as expected — no
  new dependency).

## T6 — PlayerController.kt application-scoped MediaController (2026-08-20)
- NEW file `android/app/src/main/java/com/kefi/player/PlayerController.kt`, package
  `com.kefi.player`, Kotlin `object` (not a class) — an application-scoped singleton, same
  shape as `App`'s companion object. Confirmed via `javap` against the real
  `media3-session-1.11.0-api.jar` / `media3-common-1.11.0-runtime.jar` before writing any
  code (method signatures below are exact, not guessed).
- **`Snapshot` — exact key names T7 must use verbatim** (Kotlin `data class
  PlayerController.Snapshot`, T7 serialises straight to JSON so field order doesn't
  matter, names do):
  `mediaId: String?`, `uri: String?`, `title: String?`, `artist: String?`,
  `currentPosition: Long`, `duration: Long`, `isPlaying: Boolean`,
  `shuffleModeEnabled: Boolean`, `repeatMode: Int`, `queueLength: Int`, `currentIndex: Int`.
  `mediaId`/`uri`/`title`/`artist` are all nullable (null when no current item, or when
  metadata was never set). `duration` is normalised: `C.TIME_UNSET` (the raw value
  `MediaController.getDuration()` returns before the item is prepared/buffered) is mapped
  to `0L` so T7 never has to special-case a huge negative sentinel when it JSON-serialises
  this straight to the JS bridge. `currentPosition` is passed through raw (uncommon for it
  to be unset). `queueLength` = `mediaItemCount`, `currentIndex` = `currentMediaItemIndex`.
- **Exact public method signatures** (all `suspend fun`, all on `PlayerController`, all
  safe to call from any dispatcher — they internally hop to `Dispatchers.Main`):
  - `suspend fun snapshot(): Snapshot`
  - `suspend fun play(item: MediaItem)` — replaces the queue with a single item
    (`setMediaItem` + `prepare` + `play`). Takes a full `androidx.media3.common.MediaItem`
    (not a bare URI string) so the caller can attach `MediaMetadata.title`/`.artist` before
    playback starts and `snapshot()`/the listener broadcast read real title/artist back
    immediately — T7 should build the `MediaItem` (e.g.
    `MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(t).setArtist(a).build()).build()`)
    from whatever it already queried off `MediaStore`/`content query`, then call
    `PlayerController.play(item)`.
  - `suspend fun pause()`
  - `suspend fun resume()` — plain `play()` on the existing item/position, no queue/seek
    side effects.
  - `suspend fun seek(positionMs: Long)`
  - `suspend fun next()` / `suspend fun previous()` — `seekToNext()` / `seekToPrevious()`.
  - `suspend fun setQueue(items: List<MediaItem>, startIndex: Int = 0)` — full queue
    replace + prepare + play at `startIndex`.
  - `suspend fun setShuffle(enabled: Boolean)`
  - `suspend fun setRepeatMode(repeatMode: Int)` — takes Media3's own
    `Player.REPEAT_MODE_OFF` / `_ONE` / `_ALL` int constants, not a custom enum.
  - `var onState: ((Snapshot) -> Unit)?` — settable property, not a method. T7 sets this
    once (e.g. in `load()`) and forwards every invocation to `notifyListeners("playerState",
    ...)`. Invoked on `Dispatchers.Main` from inside the one `Player.Listener` registered
    on the controller, for: `onIsPlayingChanged`, `onMediaItemTransition`,
    `onPlaybackStateChanged`, `onShuffleModeEnabledChanged`, `onRepeatModeChanged`, and
    `onPlayerError` (T11 will add skip logic there; T6 only re-broadcasts state, no
    behavior yet — left a `TODO(T11)` comment on that branch).
- **How the future was awaited** (no Guava/`concurrent-futures-ktx` dependency added, per
  the plan's constraint — Media3 does NOT put Guava's `ListenableFuture.get()`-with-executor
  convenience or AndroidX's `.await()` extension on this classpath by default, only the raw
  `com.google.common.util.concurrent.ListenableFuture` interface via
  `MediaController.Builder.buildAsync()`): wrapped in
  `suspendCancellableCoroutine { cont -> future.addListener({ ... cont.resume(future.get()) ... }, ContextCompat.getMainExecutor(App.instance)) }`,
  with `cont.invokeOnCancellation { MediaController.releaseFuture(future) }`. The whole
  `ensureController()` body (building the `SessionToken`/`MediaController.Builder` and
  awaiting the coroutine) runs inside `withContext(Dispatchers.Main)`, itself inside
  `initLock.withLock { }` (double-checked: `controller?.let { return it }` before AND
  inside the lock), guarded by `@Volatile private var controller: MediaController?` —
  exact mirror of `com.ytmp3.app.App.ensureNativeToolsReady()`'s shape (`initLock`,
  `@Volatile` flag/field checked outside then inside the lock), reusing `App.instance` and
  never creating a new `CoroutineScope` (no `App.scope.launch` inside `PlayerController`
  itself — callers, i.e. T7, are expected to launch from their own scope/coroutine and
  just `await`/call these `suspend fun`s).
- `ContextCompat` (`androidx.core.content.ContextCompat`) is on the classpath transitively
  via `androidx.appcompat:appcompat` / `androidx.core:core-splashscreen` — confirmed
  `ContextCompat.class` present in the resolved `core-1.17.0-api.jar` in the Gradle cache
  before using it; no new dependency was added.
- Verified: `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` = `0`.
  `npm run apk 2>&1 | grep -c "BUILD SUCCESSFUL"` = `1` (APK 8.7M). Pure compile gate per
  the plan — nothing calls `PlayerController` yet (T5's debug entry point in
  `PlaybackService.kt` is untouched, `PlayerController` has zero callers until T7), so no
  runtime/device verification was attempted or is meaningful yet.
- Did NOT touch `PlaybackService.kt`, `MainActivity.java`, `scripts/`, or any plan
  checkboxes. Did NOT add any Gradle dependency.

## T8 — usePlayer composable, push + pull-on-resume rehydration (GATE) (2026-08-20)
- NEW `src/composables/usePlayer.js`. Module-level refs (outside `usePlayer()`, one shared
  instance) for all 11 `PlayerSnapshot` keys plus a `stateError` ref, mirroring
  `useDownloads.ts:30-45`. `applySnapshot()` (composables/usePlayer.js:37-50) is the single
  place that writes all 11 fields from a `PlayerSnapshot`, called by both halves.
  `errorMessage()` (composables/usePlayer.js:29-33) is the error-normalisation helper,
  copied in spirit from `useDownloads.ts:65-69`.
  - **PUSH subscription**: `attachListeners()`, `src/composables/usePlayer.js:65-72` —
    `await Player.addListener('playerState', (snapshot) => applySnapshot(snapshot))` inside
    an `if (listenersReady) return; listenersReady = true` guard (module-level `let
    listenersReady = false` at line 60), attached exactly once for the app's lifetime,
    mirroring `useDownloads.ts:55,76-115`.
  - **PULL-on-resume handler** (the load-bearing half): `refreshState()` at
    `src/composables/usePlayer.js:82-89` does the actual live `await Player.getPlayerState()`
    + `applySnapshot()` (never renders from a cached ref). `attachVisibilityHandler()` at
    `src/composables/usePlayer.js:97-105` registers `document.addEventListener('visibilitychange',
    () => { if (document.visibilityState === 'visible') refreshState() })` behind its own
    `visibilityHandlerReady` guard (also attached exactly once). `initialize()` (returned from
    `usePlayer()`, `src/composables/usePlayer.js:114-119`) calls `attachListeners()` +
    `attachVisibilityHandler()` + an unconditional first `refreshState()` — so the very first
    render is always a fresh pull, never a stale default.
  - No `setInterval`/polling anywhere in the file — verified
    `grep -c "setInterval\|setTimeout" src/composables/usePlayer.js` = `0`. Position goes
    stale in the refs between push events during steady playback (Media3's `Player.Listener`
    only fires on discrete state transitions — `onIsPlayingChanged`,
    `onMediaItemTransition`, `onPlaybackStateChanged`, etc. — there is NO periodic position
    tick), by design; only a fresh pull (mount or visibilitychange→visible) re-syncs it. This
    is expected per the plan's Must-NOT-Have and is not a bug — later tasks that build a real
    UI (T13+) will need their own local interpolation/rAF timer for smooth on-screen ticking
    between pulls, which is explicitly out of scope for T8.
- `src/App.vue` rewritten: removed all T7 `TODO(T8)` dev-panel code, now imports and calls
  `usePlayer()` in `onMounted` (`player.initialize()`), renders a `computed` snapshot of all
  11 keys in a `<pre>`, and keeps a `watch(snapshot, ...)` → `console.log('[T8 QA]
  playerState', ...)` so logcat can observe WebView state (Capacitor forwards WebView
  console output to `adb logcat` under tag `Capacitor/Console`). Kept the same 4 QA buttons
  (Play fixture (f1)/Pause/Resume/Refresh state) from T7, now wired through
  `usePlayer()`'s `playTrack`/`pause`/`resume`/`refreshState` actions instead of calling the
  `Player` plugin directly — judged in-scope because T7 already had these buttons for
  identical QA purposes and MUST NOT DO only bans *new* UI (mini-player/track list/views);
  swapping their backing implementation is exactly what this task's Expected Outcome asked
  for. Marked `// TODO(T13): replaced by the router shell` at the top of `<script setup>`
  and again on the `<section class="dev-player">` block; T13 owns deleting this file's
  content entirely.
- **SUPERSEDED FINDING, corrected after independent coordinator review —
  `always_finish_activities` is a no-op on this platform, and Scenario 1 as originally
  scripted proves nothing.** Confirmed (both by me and independently by the coordinator):
  with the setting at `1`, after `KEYCODE_HOME` + wait, `dumpsys activity activities` still
  shows the identical `ActivityRecord` in history (`mFinished=false mDestroyed=false`, same
  `pidof` throughout) — verified against our app with and without playback running, and
  against the system Settings app, ruling out anything specific to our manifest/code. `am
  start -R <N>` also fails to force recreation (`MainActivity`'s `android:launchMode=
  "singleTask"` routes repeat `am start` calls to `onNewIntent`, never a finish). **This is
  not a real destruction mechanism on this AVD** — do not trust it, and do not reuse it as
  "Scenario 1" in later tasks.
- **The coordinator found the REAL defect this gate exists to catch**: the WebView renderer
  runs in its own separate sandboxed process
  (`com.google.android.webview:sandboxed_process0:...`), independent of `com.kefi.player`'s
  own process. With `adb root` (already enabled on this `google_apis` image), killing that
  renderer process (`kill -9 <sandboxed_process0 pid>`, found via `adb shell ps -A | grep
  sandboxed_process0`) while a track was playing reproduced a real crash:
  `chromium: [ERROR:aw_browser_terminator.cc(113)] Render process kill (OOM or update)
  wasn't handed by all associated webviews, killing application.` → `ActivityManager:
  Process com.kefi.player (pid ...) has died: fg TOP` → `Scheduling restart of crashed
  service com.kefi.player/.PlaybackService in 1000ms for start-requested`. Because nothing
  overrode `onRenderProcessGone`, WebView's default behaviour killed the ENTIRE app process
  (not just the renderer sandbox), taking `PlaybackService`/`PlayerController` down with it —
  exactly the failure this plan's premise ("the WebView/Bridge can be destroyed while the
  MediaSessionService keeps playing") was supposed to prevent, and exactly what a
  screen-off, backgrounded music player is most likely to hit under real memory pressure.
  I reproduced this independently before touching any code: pid changed (`5396`→`5518`),
  `dumpsys media_session` dropped to `state=NONE(0), position=0` — total playback loss.
- **Fix 1 — `MainActivity.java`** (`android/app/src/main/java/com/kefi/player/MainActivity.java`):
  after `super.onCreate(savedInstanceState)`, `getBridge().addWebViewListener(new
  WebViewListener() { onRenderProcessGone(...) { Log.w("KefiApp", "renderer gone,
  recovering"); recreate(); return true; } })`. `com.getcapacitor.WebViewListener` is
  Capacitor's own public extension point for exactly this
  (`BridgeWebViewClient.onRenderProcessGone` already iterates `bridge.getWebViewListeners()`
  and returns `true` if any listener does) — no need to replace/subclass the WebViewClient
  by hand. Returning `true` tells the framework the death is handled, which stops it from
  killing the app process; the dead WebView object itself can never be reused, so
  `recreate()` tears down and rebuilds the Activity (and gets a fresh WebView) while
  `PlaybackService` (a separate OS process from the renderer, and now never killed by this
  path) keeps playing underneath it unaffected. `getBridge()` must be called AFTER
  `super.onCreate(...)` — `BridgeActivity.onCreate()` is what actually constructs `bridge`
  (via its `load()` at the end); calling `getBridge()` before `super.onCreate()` would NPE.
- **Fix 2 — `PlaybackService.kt`, item 2 (zombie restart)**: confirmed the coordinator's
  suspicion for real. Before any fix, forcing the whole `com.kefi.player` process to die
  (`kill -9 <app pid>`, distinct from the renderer-only kill Fix 1 targets — e.g. a genuine
  OOM kill of the whole process would hit this same path) and letting the OS auto-restart
  `PlaybackService` left a **real zombie**: `dumpsys activity services com.kefi.player` still
  showed `isForeground=true foregroundId=1001` with the full `channel=playback` media-style
  notification (confirmed also in `dumpsys notification --noredact`), while
  `dumpsys media_session` simultaneously reported `state=NONE(0), position=0` — a "Now
  Playing" notification for nothing. Root cause: `onCreate()` unconditionally builds a fresh
  empty `ExoPlayer`/`MediaSession` and `addSession()` unconditionally promotes to foreground,
  regardless of whether anyone is actually about to play something. Tried gating this on
  `Service.START_FLAG_RETRY` first (the documented "restarted after a crash" flag) but
  **measured `flags=0` on every restart on this build — that flag is not set here, so it is
  not a usable signal on this platform.** Added a temporary log
  (`flags=$flags intentIsNull=${intent == null} mediaItemCount=...`) and found the reliable
  signal actually is `intent == null` (also documented Android behavior: a service killed
  while started is redelivered via `onStartCommand(null, flags, startId)`) — measured
  `intentIsNull=true mediaItemCount=0` on every such restart, `intentIsNull=false` on every
  legitimate start. Final fix in `onStartCommand`: `if (intent == null &&
  (player?.mediaItemCount ?: 0) == 0) { Log.i("KefiApp", "PlaybackService restarted with
  nothing to play, stopping cleanly"); stopSelf(); return START_NOT_STICKY }`. Verified this
  clears the zombie completely: after the fix, the same whole-process `kill -9` still shows
  the OS auto-restarting the service (`KefiApp onCreate` fires again, then immediately
  `PlaybackService restarted with nothing to play, stopping cleanly`), and 4s later
  `dumpsys activity services com.kefi.player` has **no `PlaybackService` ServiceRecord at
  all**, `dumpsys notification --noredact | grep -c "pkg=com.kefi.player"` = `0`, and
  `dumpsys media_session` shows only `state=null` — clean, no zombie, no lingering
  notification.
- **Scenario 1 (FINAL, corrected) — real output**, renderer-kill replacing the no-op
  `always_finish_activities` version (fixture: Άσπρη Μέρα `_id=24`, played via `input tap 289
  928`): launched, played, waited 5s to build up real position, recorded
  `APP_PID(before)=7558`, `RENDERER_PID=7583` (via `ps -A | grep sandboxed_process0`), and
  `dumpsys media_session` → `state=PlaybackState {state=PLAYING(3), position=2810, ...}`.
  Then `adb shell kill -9 7583` (renderer only). Polled logcat every 100ms for a fresh
  `[T8 QA] playerState` line (detected after ~200ms) and immediately (same shell
  round-trip, no extra sleep) read `dumpsys media_session` + the latest WebView log line:
  - `APP_PID(after)=7558` — **PASS**, app process survived the renderer death (Fix 1 working;
    contrast with the pre-fix repro where pid changed `5396`→`5518`).
  - `dumpsys media_session` → `state=PlaybackState {state=PLAYING(3), position=2810, ...}` —
    **PASS**, `state=3` held continuously, playback never interrupted by the renderer crash.
  - WebView's post-recovery `[T8 QA] playerState` line (from `MainActivity.recreate()` →
    fresh WebView mount → `usePlayer().initialize()` → the pull-on-mount path):
    `{"mediaId":"content://media/external/audio/media/24","uri":"content://...
    /24","title":"Άσπρη Μέρα","artist":"Δήμητρα Παπαδοπούλου","currentPosition":4763,
    "duration":179999,"isPlaying":true,...}` — non-null track: **PASS**.
  - `|dumpsys position 2810 − WebView currentPosition 4763| = 1953ms`: **PASS** (within
    3000ms). (The WebView's live-queried `getPlayerState()` value legitimately being AHEAD of
    `dumpsys`'s figure is expected and fine — Media3's own internal periodic push to the
    system `MediaSession` only ticks roughly once a second while playing, so `dumpsys` can
    itself lag a live `MediaController.currentPosition` read by up to ~1s; this is unrelated
    to our own no-push-while-idle behavior in `usePlayer.js` and does not affect the
    3000ms budget.)
  - `KefiApp: renderer gone, recovering` present in logcat exactly once, immediately before
    the fresh mount. `logcat -d | grep -icE "FATAL EXCEPTION|AndroidRuntime.*com.kefi.player"`
    = `0`.
  - **Timing-sensitivity note carried over from the earlier attempt**: comparing a WebView
    snapshot against a `dumpsys` reading taken several seconds apart (rather than
    back-to-back) produces large apparent "drift" (seen as high as ~20s in an earlier,
    improperly-timed rerun of this exact scenario) that is pure measurement lag, not a
    staleness bug — see the no-polling design note above. Any later re-run of this scenario
    MUST read `dumpsys` and the WebView's log line immediately adjacent to each other.
- **Scenario 2 — real output** (swipe from recents): `input keyevent KEYCODE_APP_SWITCH` then
  a plain vertical `input swipe` on the card only dragged it sideways (this recents UI scrolls
  horizontally, revealing a "Clear all" pill rather than dismissing on an up-swipe) — the
  working dismiss action on this launcher is tapping the **"Clear all"** button (found via
  screenshot at real coords `(212, 1194)` on a 1080×2400 screen). After tapping it:
  `dumpsys activity activities | grep -c "com.kefi.player/.MainActivity"` → `0` (task truly
  gone from Recents), but `pidof com.kefi.player` stayed the **same pid** (`4811`,
  unchanged), `dumpsys activity services com.kefi.player` still showed
  `ServiceRecord{...：/.PlaybackService}` with `isForeground=true foregroundId=1001
  types=0x00000002` and the `channel=playback` notification, and `dumpsys media_session` →
  `state=PlaybackState {state=PLAYING(3), position=24063, ...}` (position continuing to
  advance on a follow-up read). `adb logcat -d | grep -icE "FATAL EXCEPTION|AndroidRuntime.*
  com.kefi.player|Process: com.kefi.player.*has died"` → `0`. Screenshot after dismissal
  showed the home screen with no crash dialog. **PASS**: no crash, Media3's default
  (not-overridden) `onTaskRemoved` kept the service+playback alive because it was actively
  playing, exactly as T5/T7 established `onTaskRemoved` is never overridden.
- **Scenario 3 — real output** (screen-off, same still-playing background session from
  Scenario 2 — task already cleared from Recents, service still running): P1 = `42080`
  (`dumpsys media_session` `position=`). `input keyevent KEYCODE_POWER`, `sleep 15`. P2 =
  `57073`. `dumpsys media_session` showed `state=PlaybackState {state=PLAYING(3), ...}` at
  both reads. `P2 - P1 = 14993ms >= 12000ms`: **PASS**. Screen woken back up afterward
  (`KEYCODE_POWER` again) and confirmed `dumpsys power | grep mWakefulness=` → `Awake`,
  leaving the emulator in a normal state for later tasks.
- Verified: `npm run build` → `vite build` succeeded, `player.web.js` still its own lazy
  chunk, no `.ts` file created anywhere. `npm run apk` → `BUILD SUCCESSFUL`, APK 8.7M
  (unchanged size). `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac` clean
  after the `MainActivity.java`/`PlaybackService.kt` fixes.
- **Follow-up scope note**: after the coordinator's independent re-test surfaced the real
  renderer-death defect (see Fix 1/Fix 2 above), `MainActivity.java` and `PlaybackService.kt`
  were modified — the only two native files this task touched, both explicitly authorized by
  the coordinator for this exact purpose. `PlayerController.kt`, `PlayerPlugin.kt`, and every
  other Kotlin/Java file untouched. Still did not touch `scripts/`, `package.json`, any plan
  checkbox, or add any Gradle dependency.

## Coordinator note for T17/T18 — position display must be interpolated locally
Verified on device after T8: `playerState` events fire only on STATE CHANGES
(isPlaying / mediaItem / playbackState / shuffle / repeat), not continuously. Observed the
WebView showing `currentPosition: 9930` while the native side was at `32813` — correct by
design, but it means any progress bar or seek bar bound directly to `currentPosition` will
sit FROZEN between events.

The plan forbids a polling loop (Must NOT Have: "No polling loop for playback position"),
and that rule stands — do NOT call the native layer on a timer. Instead derive the displayed
position LOCALLY in the composable/component:
  displayed = lastKnownPosition + (isPlaying ? (now - lastUpdateTimestamp) * speed : 0)
ticking with requestAnimationFrame (or a 250-500ms local timer) that performs NO bridge call,
and re-basing whenever a real `playerState` event or a resume snapshot arrives. This gives a
smooth bar with zero extra IPC.

## T9 — TrackSource.kt, READ_MEDIA_AUDIO, listTracks, getArtwork (2026-08-20)
- NEW `android/app/src/main/java/com/kefi/player/TrackSource.kt`: Kotlin `object`
  (no instance state beyond the artwork cache), `data class Track`, and two `suspend fun`s
  (`listTracks(context)`, `getArtwork(context, trackId)`), both doing their own
  `withContext(Dispatchers.IO)` hop — callers never think about threading. On-demand
  `MediaStore` query only: **no database, no cache table, no `ContentObserver`**, per the
  plan's explicit Must NOT Have.
- **Exact `Track` key names T15/T16 must build the library UI on** (verbatim, this is the
  wire contract — `PlayerPlugin.Track.toJSObject()` serialises these 1:1 to JSON):
  `id` (number, the raw `MediaStore._ID`, NOT the uri), `uri` (string,
  `content://media/external/audio/media/<id>` built via `ContentUris.withAppendedId`),
  `title` (string, always non-empty — falls back to `DISPLAY_NAME` if `TITLE` is null/blank,
  then `""` as a last resort), `artist` (string|null), `album` (string|null), `albumArtist`
  (string|null — null for all 3 fixtures, `ALBUM_ARTIST` was never tagged), `trackNumber`
  (number|null), `year` (number|null — null for all 3 fixtures, `YEAR` was never tagged),
  `durationMs` (number), `size` (number, bytes). `listTracks()` resolves `{ tracks: [...] }`
  (an array under that key, not a bare array) — confirmed via real WebView logcat below.
- **Permission aliases — exact strings, both declared on `@CapacitorPlugin`**: `audio`
  (`Manifest.permission.READ_MEDIA_AUDIO`, API 33+) and `audioLegacy`
  (`Manifest.permission.READ_EXTERNAL_STORAGE`, API < 33 — minSdk is 24 so this branch is
  reachable, not defensive dead code). `PlayerPlugin.audioPermissionAlias()` picks between
  them with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`. Both permissions were
  already in `AndroidManifest.xml` from T5 (`READ_EXTERNAL_STORAGE` has
  `maxSdkVersion="32"`) — verified only, not re-added, diff on that file is empty for T9.
  `checkAudioPermission()` / `requestAudioPermission()` both resolve `{ granted: boolean }`;
  `requestAudioPermission()` short-circuits to `{ granted: true }` without prompting if
  already granted, otherwise calls `requestPermissionForAlias(alias, call,
  "audioPermissionCallback")`, whose `@PermissionCallback` re-reads `getPermissionState`
  after the OS dialog closes. `listTracks` rejects BEFORE launching any coroutine (synchronous
  `getPermissionState` check) with the literal message `"Audio permission is required to
  list tracks"` when not granted — contains the substring `permission` as required.
- **`getArtwork({ trackId })` returns `{ dataUrl }`**: `data:image/jpeg;base64,...` (256x256,
  JPEG quality 85, `Base64.NO_WRAP`) on success, or `{ dataUrl: null }` — never a
  `content://` uri, never omits the key. API 29+ (`Build.VERSION_CODES.Q`) uses
  `ContentResolver.loadThumbnail(trackUri, Size(256,256), null)` directly on the **track's
  own audio uri** (no need to resolve `ALBUM_ID`/album-art uri — `loadThumbnail` reads
  embedded audio art natively on Q+); any thrown exception (no thumbnail available) is
  caught and mapped to `null`. API 24-28 uses `MediaMetadataRetriever().apply {
  setDataSource(context, trackUri) }`, `.embeddedPicture` (a raw byte array or null),
  decoded via `BitmapFactory.decodeByteArray`, wrapped in `try/finally { retriever.release()
  }` (this repo's minSdk 24 never actually exercises this branch on the emulator, which is
  API 36, but it's mandatory dead-code-on-this-device per the plan). Memoised in a
  `LinkedHashMap<Long, String?>(16, 0.75f, accessOrder = true)` with
  `removeEldestEntry { size > 64 }` — a true LRU, `null` results are cached too (so a
  known-artless track doesn't re-run `loadThumbnail`/`MediaMetadataRetriever` on every call).
- **Real bug found and fixed via on-device QA, not by inspection**: Capacitor's
  `PluginCall.getLong(name)` only returns non-null when the underlying JSON value was
  already boxed as `java.lang.Long` — but `org.json`'s parser boxes small numbers (anything
  that fits in `Int`, which covers every realistic MediaStore `_id` and, worse, likely every
  `positionMs` T7's `seek()` will ever receive too) as `Integer`, not `Long`. A literal
  `call.getLong("trackId")` therefore silently returned `null` for `trackId: 24` end-to-end
  through the real bridge (confirmed via `Capacitor: callback: ..., methodData:
  {"trackId":24}` immediately followed by `"error":{"message":"A trackId is required"}` in
  logcat), even though the JS call sent a perfectly normal number. Fixed with
  `call.getLong("trackId") ?: call.getInt("trackId")?.toLong()`. **T7's existing
  `seek({ positionMs })` uses a bare `call.getLong("positionMs")` and was NOT touched by this
  task (out of T9's scope) — it is very likely exposed to the exact same bug for any
  `positionMs` under ~2.1 billion (i.e. always) and should get the same `getLong ?:
  getInt()?.toLong()` fallback whenever T10/T11/T12 next touch `PlayerPlugin.kt`.**
- **QA against `emulator-5554`, real WebView-originated calls** (temporary buttons added to
  `src/App.vue`'s dev panel, marked `TODO(T13)`, importing `Player` directly since
  `usePlayer.js` intentionally does not wrap these T9 methods):
  1. `pm revoke ... READ_MEDIA_AUDIO` (this force-restarts/kills the app process on this
     API 36 emulator — relaunch before tapping again), tapped "List tracks":
     `Capacitor: Sending plugin error: {...,"methodName":"listTracks","success":false,
     "error":{"message":"Audio permission is required to list tracks"}}`.
  2. `pm grant ... READ_MEDIA_AUDIO`, tapped "List tracks": resolved
     `{"tracks":[{"id":23,"title":"Ωδή",...,"durationMs":240039,...},{"id":24,"title":"Άσπρη
     Μέρα",...,"durationMs":180036,...},{"id":25,"title":"Zeibekiko","artist":"Nikos
     Alexiou","album":"Kefi Vol 1",...,"durationMs":210051,...}]}` — all 3 fixtures, all
     non-empty titles, all `durationMs > 0`, Zeibekiko's `artist`/`album` exactly as
     required.
  3. Tapped "Artwork (Άσπρη Μέρα)" (`trackId: 24`): `{"dataUrl":"data:image/jpeg;base64,
     /9j/4AAQS...` (1551 chars total). Tapped "Artwork (Zeibekiko)" (`trackId: 25`):
     `{"dataUrl":null}`.
- Verified: `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` = `0`. `npm run build`
  succeeds (no new chunk — `player.web.js` untouched, no `.ts` file added). `npm run apk` →
  `BUILD SUCCESSFUL`, APK still 8.7M (no new dependency).
- Did NOT touch `PlaybackService.kt`, `PlayerController.kt`, `MainActivity.java`,
  `src/composables/usePlayer.js`, `src/plugins/player.web.js`, or any plan checkbox. Did NOT
  add SAF/`DocumentFile`, a database, or a `ContentObserver`.

## T10 — Queue, next/previous, shuffle, repeat (+ seek arg-coercion fix) (2026-08-20)
- `PlayerController.kt` already had `setQueue`, `next`, `previous`, `setShuffle`,
  `setRepeatMode` fully implemented since T6 (verified by reading the file — nothing to add
  there). This task only wired `PlayerPlugin.kt` to those existing methods. **No changes to
  `PlayerController.kt` at all** — see the `previous()` decision below for why.
- **`repeatMode` Int↔String mapping** (`PlayerPlugin.kt`, `repeatModeToString`/
  `repeatModeFromString`): `Player.REPEAT_MODE_OFF (0)` ↔ `"off"`,
  `Player.REPEAT_MODE_ONE (1)` ↔ `"one"`, `Player.REPEAT_MODE_ALL (2)` ↔ `"all"`. Applied
  ONLY at the plugin boundary — `PlayerController.Snapshot.repeatMode` stays the raw Media3
  Int (T6's contract, unchanged), `toJSObject()` converts on the way out
  (`put("repeatMode", repeatModeToString(repeatMode))`), and `setRepeatMode(call)` converts
  the incoming JS string back to Media3's Int before calling
  `PlayerController.setRepeatMode(Int)`. `repeatModeFromString` returns `null` for anything
  other than the 3 allowed strings, so `setRepeatMode` rejects with `"A mode of off|one|all
  is required"` for garbage input instead of silently defaulting. **T18's repeat toggle must
  read/write the STRING `off|one|all` over the bridge, never the raw int** — confirmed
  end-to-end: `getPlayerState` after `setRepeatMode({mode:'one'})` returns
  `"repeatMode":"one"` (see QA below), and `usePlayer.js` needed ZERO changes because it
  just assigns whatever `snapshot.repeatMode` is straight into a ref — it was never aware of
  the underlying type, so making the wire contract a string instead of an int is fully
  transparent to it.
- **`previous()` semantics decision — kept `seekToPrevious()` (Option A), did NOT switch to
  `seekToPreviousMediaItem()`.** Reason: `seekToPrevious()` — restart current track if more
  than ~3s in (`Player.getMaxSeekToPreviousPosition()`, default 3000ms), otherwise move to
  the previous queue item — is exactly how real music players behave (Spotify, Apple Music,
  the stock Android media notification's `«` button all restart-vs-skip on the same
  threshold), so it is the more correct UX and was kept as-is; `PlayerController.kt` did not
  need touching. Decompiled `BasePlayer.class`
  (`javap -p -c -l androidx/media3/common/BasePlayer.class` against
  `media3-common-1.11.0-runtime.jar` in the Gradle cache) to confirm the exact branch
  (non-live-item path): `if (hasPreviousMediaItem) { if (getCurrentPosition() >
  getMaxSeekToPreviousPosition()) seekToCurrentItem() /* restart */ else
  seekToPreviousMediaItemInternal() /* go back */ } else seekToCurrentItem()` — i.e.
  position <= 3000ms is the "go to previous item" branch, exactly as the task text
  described. **Made the QA deterministic per the task's suggested option: call `seek(0)`
  immediately before `previous()`** (a new "Seek 0" dev-panel button), which forces
  `getCurrentPosition() == 0 <= 3000ms` regardless of any adb round-trip delay. **Reproduced
  the exact race the task warned about while manually testing**: an earlier attempt that
  queried `dumpsys`/logcat (several seconds of real wall-clock adb overhead) between tapping
  "Next" and tapping "Previous" caused `previous()` to RESTART Zeibekiko instead of going
  back to Άσπρη Μέρα (position had drifted past 3000ms while Zeibekiko kept playing during
  the round-trip) — concrete proof the plan's literal QA ("next() → Zeibekiko; previous() →
  Άσπρη Μέρα", with no seek-to-0 in between) is exactly the flaky sequence the task
  description predicted. The seek(0)-then-previous() sequence, tapped back-to-back in a
  single adb batch, was reliably deterministic across repeated runs.
- **Seek defect (a) — fixed.** `PlayerPlugin.kt`'s `seek(call)` used to reject EVERY real
  call with `"A positionMs is required"` because `org.json` boxes small JSON numbers as
  `Integer`, and `PluginCall.getLong` only matches an already-`Long`-boxed value (same root
  cause T9 found and fixed for `getArtwork`'s `trackId`). Fixed identically:
  `call.getLong("positionMs") ?: call.getInt("positionMs")?.toLong()`. **Proved fixed on
  device**: `seek({positionMs: 60000})` from the WebView resolved (did not reject) with
  `{"currentPosition":60000}`, and `dumpsys media_session` immediately showed
  `position=60003` — within a few ms of the requested 60000ms, i.e. a real seek actually
  happened, not just a non-throwing no-op.
- **Audited the rest of `PlayerPlugin.kt` for the same bug class** (per the task's explicit
  request): `playTrack` (uri/title/artist — all `getString`, unaffected), `setQueue`'s new
  `startIndex` (`getInt("startIndex", 0)` — deliberately `getInt`, not `getLong`, because
  Media3's queue-index parameter is an `Int` on the Kotlin side too, so there is no
  Int→Long widening step to get wrong; `startIndex` was never at risk of this bug in the
  first place), `setShuffle`'s `enabled` (`getBoolean`, not numeric), `setRepeatMode`'s
  `mode` (`getString`, not numeric). `getArtwork`'s `trackId` was already fixed by T9. No
  other numeric plugin argument exists in this file.
- **`setQueue({ uris, startIndex })`**: reads `call.getArray("uris")` (a `JSArray`,
  `org.json.JSONArray` subclass — plain `.getString(i)` works, no boxing bug since strings
  aren't ambiguous like numbers), rejects `"A non-empty uris array is required"` if null or
  empty, builds one `MediaItem` per uri with `setUri(uri).setMediaId(uri)` — deliberately NO
  `setMediaMetadata` (unlike `playTrack`, which gets title/artist straight from the JS
  caller) since `setQueue`'s JS contract only sends bare uri strings, not per-track
  title/artist. This means the FIRST `getPlayerState`/`setQueue` resolve snapshot right
  after queuing has `title: null, artist: null` (confirmed in QA below) until Media3
  finishes preparing/reading the file's own embedded ID3 tags a moment later — `dumpsys
  media_session`'s `description=` field (sourced from ExoPlayer's own tag extraction, not
  from our `MediaItem.mediaMetadata`) already shows the real title/artist by the time the
  very next dumpsys read happens (a few hundred ms later). Not a bug — the plan's QA checks
  `dumpsys` (which is unaffected), not the immediate JS resolve payload's title/artist.
- **`queueLength`/`currentIndex` vs. the plan's prose** ("plus `queueLength` and
  `queueIndex`"): kept T6's existing field name `currentIndex` — did NOT add a new/duplicate
  `queueIndex` key. `currentIndex` already reports exactly the queue-index value the plan
  describes (`mediaItemCount`'s partner, `currentMediaItemIndex`), and "keep the 11 existing
  snapshot keys intact and additive-only" plus "queueLength and queueIndex" being satisfied
  by the two keys that already exist reads more consistently than inventing a second,
  redundant name for the same value. `queueLength` was already present since T6 as well —
  nothing needed adding to the Kotlin `Snapshot` for either.
- **Dev panel** (`src/App.vue`, all still under the `TODO(T13)` marker T7/T9 established):
  added a `QUEUE_URIS` constant (`[24, 25, 23]` — Άσπρη Μέρα, Zeibekiko, Ωδή — so
  `startIndex: 0` lands on Άσπρη Μέρα per the plan's QA prose) and a new "Queue & transport
  (dev, T10 QA)" section with buttons: Queue all 3, Next, Previous, Shuffle on/off, Repeat
  off/one/all, Seek 0, Seek 60000. All call `Player.setQueue`/`.next`/`.previous`/
  `.setShuffle`/`.setRepeatMode` directly (same precedent as T9's tracks panel —
  `usePlayer.js` doesn't wrap these and MUST NOT be modified) except "Seek 0"/"Seek 60000",
  which go through `usePlayer`'s existing `seek(positionMs)` action (already present since
  T7/T8, unmodified) since that one method usePlayer already wraps. Every action also
  `console.log`s its resolve payload tagged `[T10 QA]` so logcat can observe results without
  reading the screen, same pattern as T7/T8/T9.
- **QA against `emulator-5554`, real WebView-originated calls, tapped in a single adb batch
  per scenario to keep timing tight** (fixtures unchanged since T2/T9: Ωδή=23, Άσπρη
  Μέρα=24, Zeibekiko=25):
  1. Tapped "Queue all 3 (start Άσπρη Μέρα)": resolve
     `{...,"queueLength":3,"currentIndex":0,...}`; `dumpsys media_session` →
     `state=PlaybackState {state=PLAYING(3), position=0, ...}` /
     `metadata: size=7, description=Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι Vol 1`.
  2. Tapped "Next": resolve `{...,"currentIndex":1,...}`, `mediaId=.../25`; `dumpsys` →
     `description=Zeibekiko, Nikos Alexiou, Kefi Vol 1`.
  3. Tapped "Seek 0" then "Previous" back-to-back: `seek` resolve `{"currentPosition":919}`
     → `{"currentPosition":0}` pattern varied run to run (network/scheduler jitter on
     exactly when the tap lands relative to playback), but always well under 3000ms;
     `previous` resolve `{...,"mediaId":".../24","currentIndex":0,"duration":179999,...}`;
     `dumpsys` → `description=Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι Vol 1` — **PASS**,
     deterministically, across repeated runs.
  4. Tapped "Shuffle on": resolve `{...,"shuffleModeEnabled":true,...}`.
  5. Tapped "Repeat one": resolve `{...,"repeatMode":"one",...}`. Tapped "Refresh state"
     (usePlayer's live `getPlayerState` pull, not the push event): the dev panel's `<pre>`
     showed `{"mediaId":"content://media/external/audio/media/24",...,"shuffleModeEnabled":
     true,"repeatMode":"one","queueLength":3,"currentIndex":0}` — **PASS**, confirms
     `getPlayerState` (not just the push event) reports the string repeat mode.
  6. **Seek proof (defect a)**: tapped "Seek 60000": resolve
     `{"currentPosition":60000}` (did NOT reject with "A positionMs is required"); `dumpsys
     media_session` → `position=60003` — **PASS**, within a few ms of the requested value.
  7. `adb -s emulator-5554 logcat -d | grep -icE "Exception|FATAL"` → `0` (whole buffer,
     not just our package — so trivially 0 for `com.kefi.player` too). Also checked
     filtered by our own pid (`logcat -d --pid=$(pidof com.kefi.player)`) → also `0`.
- Verified: `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` = `0`. `npm run build`
  succeeds, `player.web.js` still its own lazy chunk (2.12 kB), no `.ts` file created.
  `npm run apk` → `BUILD SUCCESSFUL`, APK 8.7M (unchanged — no new dependency).
- Did NOT touch `PlaybackService.kt`, `PlayerController.kt`, `MainActivity.java`,
  `src/composables/usePlayer.js`, or any plan checkbox. Did NOT implement `trackUnavailable`
  skip-on-error (T11) or persistence (T12). Did NOT add a polling loop, npm/Gradle
  dependency, or any `.ts` file.

## T11 — Unavailable-track degradation (2026-08-20)
- **`PlayerController.kt`**: `onPlayerError(error: PlaybackException)` now: reads
  `ctrl.currentMediaItem?.localConfiguration?.uri?.toString()` (the failing item's uri — the
  player is still parked on the failed item at this point, it does not auto-advance),
  classifies the failure via a new `describeError()`, `Log.w("KefiApp", "Track unavailable:
  uri=$failedUri reason=$reason errorCode=... errorCodeName=... cause=...")`, then skips
  (`seekToNextMediaItem()` + `prepare()` + `play()`) or stops (`ctrl.stop()`), then invokes the
  new `var onTrackUnavailable: ((uri: String?, reason: String) -> Unit)?` (mirrors `onState`
  exactly — settable property, null by default, invoked on `Dispatchers.Main` since
  `Player.Listener` callbacks already run there), then `broadcastState()` as before so
  `playerState` still reflects the resulting transition.
- **Exact signal that fired on device**: `errorCode=2005 errorCodeName=ERROR_CODE_IO_FILE_NOT_FOUND`,
  `cause=androidx.media3.datasource.ContentDataSource$ContentDataSourceException:
  java.io.FileNotFoundException: java.io.FileNotFoundException: No item at
  content://media/external/audio/media/<id>` — for a MediaStore row that no longer exists
  (file deleted + rescanned), Media3's `ContentDataSource` throws a
  `ContentDataSourceException` *wrapping* a `FileNotFoundException`, and Media3 itself already
  classifies it with the stable public `errorCode` (`ERROR_CODE_IO_FILE_NOT_FOUND`) — exactly
  the "prefer errorCode, fall back to cause" signal the task called for; on this device the
  `errorCode` branch fired first, the `cause is FileNotFoundException` fallback branch in
  `describeError()` was never actually exercised (kept anyway per the task's explicit
  requirement — a different Media3 `DataSource` implementation, e.g. a raw
  `java.io.File`-backed one, could plausibly throw the bare exception without setting a
  matching `errorCode`). `ERROR_CODE_IO_NO_PERMISSION`/`SecurityException` (the
  permission-revoked case) was not separately reproduced this task — no QA scenario needed it
  — but is handled by the same two-signal pattern.
- **Loop-guard, verified empirically, not just by inspection**: `consecutiveErrorCount`
  (`@Volatile private var`, module-level on `PlayerController`) increments on every
  `onPlayerError`, resets to `0` the moment `onIsPlayingChanged(true)` fires (real successful
  playback resumed). Skip is only attempted while
  `consecutiveErrorCount < ctrl.mediaItemCount && ctrl.hasNextMediaItem()`; otherwise
  `ctrl.stop()` and reset the counter. This bounds total skip attempts to the queue length
  regardless of repeat mode — plain `hasNextMediaItem()` alone would NOT have been a safe
  bound, because under `Player.REPEAT_MODE_ALL` it keeps returning `true` forever (wraps back
  to index 0), which would otherwise skip-loop an all-broken queue indefinitely. **Reproduced
  the exact failure mode on device**: after also deleting f2/f3 (all 3 fixtures gone) and
  re-tapping "Queue all 3" (`queueLength=3`), logcat showed **exactly 3** `KefiApp: Track
  unavailable` / `[T11 QA] trackUnavailable` lines (ids 24, 25, 23 in order), then the
  controller stopped — `dumpsys media_session` afterward showed
  `state=PlaybackState {state=ERROR(7), position=0, ..., error=Source error}` (no further
  attempts, no crash, `logcat -d | grep -icE "FATAL|uncaught"` = `0`). Confirms the bound is
  exactly `queueLength`, not unbounded.
- **Event payload shape** (`trackUnavailable`, separate from the frozen 11-key `playerState`
  snapshot — not folded into it): `{ uri: string | null, reason: string }`. `reason` values
  produced by `describeError()`: `"file_not_found"` (errorCode `ERROR_CODE_IO_FILE_NOT_FOUND`
  or `cause is FileNotFoundException`), `"permission_denied"` (errorCode
  `ERROR_CODE_IO_NO_PERMISSION` or `cause is SecurityException`), else Media3's own
  `error.errorCodeName` string (e.g. `"ERROR_CODE_DECODER_INIT_FAILED"`) as a generic
  fallback so no failure is ever silently swallowed into an opaque "unknown".
- **`PlayerPlugin.kt`**: `load()` now also sets
  `PlayerController.onTrackUnavailable = { uri, reason -> notifyListeners("trackUnavailable",
  JSObject().apply { put("uri", uri ?: JSONObject.NULL); put("reason", reason) }) }`;
  `handleOnDestroy()` also clears it to `null` — exact mirror of the existing `onState`
  set/clear pair.
- **JS side**: `src/plugins/player.js`'s `addListener` JSDoc now documents `trackUnavailable`
  (fires with `{ uri, reason }`, queue already skipped ahead by the time it fires, a
  `playerState` update for the new current item follows immediately after — confirmed in the
  QA logcat above: the `[T11 QA] trackUnavailable` line for id 24 is immediately followed by a
  `[T8 QA] playerState` line with `currentIndex:1` / `mediaId:.../25`). `src/plugins/player.web.js`
  gained a dev-only `simulateTrackUnavailable({ uri, reason })` method (not part of the native
  contract — the browser stub has no real file-system/Media3 error path to trigger this
  organically) that just calls `this.notifyListeners('trackUnavailable', { uri, reason })`, for
  manually exercising the UI path under `npm run dev`.
- **`src/composables/usePlayer.js`**: added `const lastError = ref(null)` (module-level, same
  shared-instance pattern as the other refs) holding `{ uri, reason } | null`, exposed as
  `lastError: readonly(lastError)`. `attachListeners()` now also does
  `await Player.addListener('trackUnavailable', ({ uri, reason }) => { lastError.value = {
  uri, reason } })` right after the existing `playerState` listener, inside the same
  already-attached-once `listenersReady` guard (no new guard needed — one `attachListeners()`
  call now wires both events atomically). This composable does not otherwise act on the drop
  (no retry/removal from a persisted list — that is T12/T19's job); it only records the most
  recent failure for the UI to read.
- **`src/App.vue`** (still under the `TODO(T13)` marker): added a
  `watch(player.lastError, ...) -> console.log('[T11 QA] trackUnavailable', ...)` right after
  the existing `[T8 QA]` watch, and a second `<pre>` under the existing snapshot read-out
  showing `JSON.stringify(player.lastError.value)` — purely for QA visibility, no new button
  needed (the existing T10 "Queue all 3 (start Άσπρη Μέρα)" button already queues f1+f2(+f3)
  starting at f1, which is exactly the T11 QA scenario's setup).
- **QA against `emulator-5554`, real WebView-originated calls** (fixture ids before this task,
  same as T10: Ωδή=23, Άσπρη Μέρα=24, Zeibekiko=25):
  1. Tapped "Queue all 3" (f1, f2, f3 queued, starts Άσπρη Μέρα/24) — confirmed playing via
     `dumpsys media_session` `state=PLAYING(3)`.
  2. `adb shell rm /sdcard/Music/kefi-qa/f1.mp3` then
     `adb shell content call --uri content://media --method scan_file --arg
     /sdcard/Music/kefi-qa/f1.mp3` — `content query ... --where "is_music=1"` afterward shows
     only `_id=23` (Ωδή) and `_id=25` (Zeibekiko) rows; the `_id=24` row is gone entirely (not
     just flagged), confirming the rescan actually removed it from MediaStore.
  3. Re-tapped "Queue all 3" (restarts playback of the queue, per the QA scenario's literal
     instruction) — real captured logcat, tap-to-event latency **~5-27ms** (`08-20
     01:15:48.136` `setQueue` call → `08-20 01:15:48.147` `KefiApp: Track unavailable` →
     `08-20 01:15:48.151` `[T11 QA] trackUnavailable`), far inside the 10s budget:
     `08-20 01:15:48.147 W KefiApp : Track unavailable: uri=content://media/external/audio/media/24
     reason=file_not_found errorCode=2005 errorCodeName=ERROR_CODE_IO_FILE_NOT_FOUND
     cause=androidx.media3.datasource.ContentDataSource$ContentDataSourceException:
     java.io.FileNotFoundException: java.io.FileNotFoundException: No item at
     content://media/external/audio/media/24`
     `08-20 01:15:48.151 I Capacitor/Console: ... [T11 QA] trackUnavailable
     {"uri":"content://media/external/audio/media/24","reason":"file_not_found"}`
     `08-20 01:15:48.151 I Capacitor/Console: ... [T8 QA] playerState
     {"mediaId":"content://media/external/audio/media/25",...,"currentIndex":1}`.
  4. `dumpsys media_session` immediately after:
     `state=PlaybackState {state=PLAYING(3), position=8915, ...}` /
     `metadata: size=5, description=Zeibekiko, Nikos Alexiou, Kefi Vol 1` — **PASS**.
  5. `adb -s emulator-5554 logcat -d | grep -icE "FATAL|uncaught"` → `0`.
  6. **Loop-guard scenario** (also deleted f2.mp3/f3.mp3, rescanned both — all 3 fixtures now
     gone from MediaStore — then re-tapped "Queue all 3" once more): logcat showed **exactly 3**
     `KefiApp: Track unavailable` / `[T11 QA] trackUnavailable` lines, for ids 24, 25, 23 in
     that order (queue order), then no further attempts; `dumpsys media_session` →
     `state=PlaybackState {state=ERROR(7), position=0, ..., error=Source error}`;
     `logcat -d | grep -icE "FATAL|uncaught"` → `0`. Confirms the `consecutiveErrorCount <
     mediaItemCount` bound stopped the skip loop after exactly `queueLength` (3) attempts, not
     before and not indefinitely.
- **Fixtures restored**: `bash scripts/qa-fixtures.sh emulator-5554` re-run at the end (after
  the loop-guard scenario deleted all 3 files) — `content call --uri content://media --method
  scan_file` (per-file) was again the scan method that worked first, output: `OK: 3 fixtures
  indexed correctly` with all 3 rows present, correct titles/artists/albums/durations. **New
  MediaStore ids after this task's delete+rescan cycles (they DID renumber, unlike T2's
  in-place-overwrite case, because the rows were actually deleted this time, not just
  content-replaced at the same path)**: `content query --uri
  content://media/external/audio/media --projection _id:title:duration --where "is_music=1"`
  →  `_id=26` Άσπρη Μέρα (180036ms), `_id=27` Zeibekiko (210051ms), `_id=28` Ωδή (240039ms).
  **T12 and every later task must use these new ids
  (`content://media/external/audio/media/26|27|28`), not the old 23/24/25.**
- Verified: `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` = `0`. `npm run build`
  succeeds (`player.web.js` still its own lazy chunk, no `.ts` file created). `npm run apk` →
  `BUILD SUCCESSFUL`, APK 8.7M (unchanged — no new dependency).
- Did NOT touch `PlaybackService.kt`, `MainActivity.java`, `TrackSource.kt`, or any plan
  checkbox. Did NOT implement persistence (T12), library UI, or any Phase B work. Did NOT add
  a polling loop, npm/Gradle dependency, or any `.ts` file.

## T12 — Session persistence (queue, index, position, shuffle, repeat) (2026-08-20)
- **NEW `android/app/src/main/java/com/kefi/player/PlaybackStateStore.kt`**: single
  SharedPreferences file `context.getSharedPreferences("kefi.player", MODE_PRIVATE)` — exact
  mirror of `com.ytmp3.app.StorageLocation`'s one-file convention
  (`StorageLocation.kt:40-83`). Keys, all `player.`-prefixed exactly as specified:
  `player.queue` (JSON array of uri strings via `org.json.JSONArray`, no new dependency),
  `player.index` (Int), `player.positionMs` (Long), `player.shuffle` (Boolean),
  `player.repeat` (Int — see the repeatMode decision below), `player.savedAt` (Long,
  `System.currentTimeMillis()`, write timestamp only — nothing reads it back yet).
- **`repeatMode` storage decision**: persisted as Media3's **raw `Player.REPEAT_MODE_*` Int**
  (`player.repeat`), not the `off|one|all` string. Reasoning: the restore path
  (`PlaybackStateStore.restore()` → `PlaybackService.onCreate()`) writes straight to the
  native `ExoPlayer.repeatMode` property, which is Media3's Int type — storing the Int avoids
  a redundant string round-trip on the pure-native restore path. T10's frozen `off|one|all`
  STRING contract to JS is untouched: `PlayerPlugin.kt`'s existing `repeatModeToString()` (at
  the plugin boundary) still converts the Int on every `getPlayerState`/push-event read, same
  as before T12 — confirmed live: after restoring `player.repeat=1`, `getPlayerState` from
  the WebView returned `"repeatMode":"one"` (see QA below). No changes needed to
  `PlayerPlugin.kt` for this task.
- **Write triggers** (`PlayerController.kt`): added `persistState()` — reads the controller's
  current queue (`(0 until ctrl.mediaItemCount).mapNotNull { ctrl.getMediaItemAt(it)
  .localConfiguration?.uri?.toString() }`), index, position, shuffle, repeat, and calls
  `PlaybackStateStore.save(...)` (a no-op if the queue is empty). Called immediately from
  **every** branch of the existing `Player.Listener` that already calls `broadcastState()` —
  `onIsPlayingChanged`, `onMediaItemTransition`, `onPlaybackStateChanged`,
  `onShuffleModeEnabledChanged`, `onRepeatModeChanged`, and T11's `onPlayerError` skip/stop
  branch. The task text named only `onMediaItemTransition`/`onIsPlayingChanged` as "immediate"
  triggers, but wiring `onShuffleModeEnabledChanged`/`onRepeatModeChanged` too was necessary,
  not optional: calling `setShuffle`/`setRepeatMode` fires ONLY those two callbacks (never
  `onMediaItemTransition`/`onIsPlayingChanged`), so without this a shuffle/repeat change made
  without also changing track or play state would never persist at all — this is exactly
  Scenario 5's shape (set shuffle+repeat while already playing, no track change), and it
  passed live (see QA below). `SharedPreferences.Editor.apply()` commits asynchronously, so
  every one of these calls is safe directly on the main thread (no dispatcher hop needed for
  writes).
- **Position freshness — throttled heartbeat, not a bare "write on existing callbacks" cap**.
  Verified (both empirically via T8's own notepad entry, and independently by decompiling
  `androidx/media3/session/MediaControllerImplBase.class` for this task —
  `javap -p -c MediaControllerImplBase.class`) that Media3's `Player.Listener` has **no**
  periodic position callback: the one periodic mechanism that exists internally
  (`MediaSessionImpl.periodicSessionPositionInfoUpdateRunnable` /
  `MediaControllerImplBase.notifyPeriodicSessionPositionInfoChanged`) only silently refreshes
  the `MediaController`'s own *cached* position field (used later by a live `.currentPosition`
  read) and never invokes any `Player.Listener` callback. With writes driven by discrete
  callbacks alone, a track played continuously with no transition/pause for 20+ seconds would
  never get a fresh position write — the QA scenario's "position within 6000ms of POS1 after
  20s of continuous, event-less playback" requirement is provably unreachable that way.
  **Resolution**: `positionHeartbeat` — a `Handler(Looper.getMainLooper())` `Runnable` that
  calls `persistState()` then reschedules itself every `POSITION_SAVE_INTERVAL_MS` (5000L);
  started by `onIsPlayingChanged(true)`, stopped by `onIsPlayingChanged(false)`. This is
  started/stopped strictly by the existing callback (not a free-running observer wired up
  independently), makes no bridge/IPC call, never touches JS, and is capped at exactly the
  task's own 5s figure — read as the mechanism that ENFORCES the "at most every 5s" cap
  the task specifies, not a violation of the "no polling loop" instruction (which reads, in
  context, as "no bridge-crossing observation loop for UI position display" — the same
  concern T8's notepad entry raised about JS-side polling — not "no internal low-frequency
  disk-write scheduler"). Verified live: with the heartbeat running, persisted
  `player.positionMs` tracked real playback within ~1s at every check (e.g. dumpsys
  position=26930 vs. persisted 29867 moments later, both mid-heartbeat-interval); without it,
  a bare callback-driven design would have left `positionMs` frozen near 0 for the entire 20s
  QA window. Flagging this explicitly as a deliberate, justified deviation from a literal
  reading of "drive writes from the existing Player.Listener callbacks plus a throttle
  timestamp" — the throttle-timestamp phrasing is honored (fixed 5000ms interval, one write
  slot at a time via `mainHandler.removeCallbacks` before each `postDelayed`), but a bare
  timestamp check alone cannot manufacture callback firings that Media3 never generates.
- **Restore** (`PlaybackStateStore.restore()`, called from `PlaybackService.onCreate()` via
  `restorePlaybackState()`): runs entirely on `Dispatchers.IO` (via `App.scope.launch`), so
  `onCreate()` (main thread) never blocks on the SharedPreferences read or the per-uri
  resolvability checks. Validates each stored uri with
  `context.contentResolver.openAssetFileDescriptor(uri, "r")` — reuses T11's exact two-signal
  classification (`FileNotFoundException` → dropped, logged `reason=file_not_found`;
  `SecurityException` → dropped, logged `reason=permission_denied`; any other `Throwable` →
  dropped, logged with the exception's simple class name), checked directly against the
  `ContentResolver` up front instead of waiting for a live `PlaybackException` from a failed
  `prepare()` — same reasoning as `PlayerController.describeError`, applied pre-flight rather
  than post-hoc. Drops unresolvable uris, remaps the saved index onto the surviving list
  (falls back to index 0 / position 0 if the specific item that was current got dropped —
  untested this task, no QA scenario needed it, but implemented defensively). If **none**
  resolve, clears the whole stored state (`PlaybackStateStore.clear()`) and returns `null`.
  Back on `Dispatchers.Main`: if non-null, `exoPlayer.setMediaItems(items, index,
  positionMs)` then `exoPlayer.shuffleModeEnabled = ...` / `.repeatMode = ...` then
  `exoPlayer.prepare()` — **never `.play()`** — operating directly on the `ExoPlayer` field
  built earlier in the same `onCreate()`, not through `PlayerController`/`MediaController`
  (which only connects lazily on the first user-gesture-originated plugin call, per T6's
  first-touch rule — restore must not depend on that ever happening).
- **The zombie-restart / restore race, exactly as flagged**: `PlaybackService.onStartCommand`
  had an existing T8 guard — `if (intent == null && mediaItemCount == 0) stopSelf()` — for the
  OS's null-intent auto-restart of a previously-*started* service after a whole-process kill.
  T12's restore is async and populates the queue moments *after* `onCreate()` returns, so a
  naive combination would race: `onStartCommand(null, ...)` could observe `mediaItemCount ==
  0` (restore not finished yet) and `stopSelf()` before the restore ever got to run, killing
  the service out from under its own restore. Fixed with two `@Volatile` flags on
  `PlaybackService`: `restoreInProgress` (true for the duration of `restorePlaybackState()`)
  and `sawNullIntentDuringRestore` (set if `onStartCommand(null, ...)` arrives while
  `restoreInProgress`). While `restoreInProgress`, `onStartCommand(null, ...)` just records
  the flag and returns `START_NOT_STICKY` without stopping; `restorePlaybackState()`'s
  completion callback re-checks `sawNullIntentDuringRestore && exoPlayer.mediaItemCount == 0`
  and calls `stopSelf()` itself if it turns out there was truly nothing to restore — so the
  original T8 zombie-prevention behaviour survives (still stops cleanly for a genuinely empty
  restart) without racing the new restore.
- **Reproduced the exact race scenario live** (`kill -9 <pid>` on the whole app process while
  a queue was playing — NOT `am force-stop`, which explicitly disables the OS's sticky
  auto-restart, so the null-intent path can only be exercised the `kill -9` way, matching how
  T8 originally found it): PID changed `10692`→`10816`; logcat showed only `KefiApp onCreate`
  immediately followed by `PlaybackService restored 3 item(s) at index=0 positionMs=9860,
  paused` — no `"restarted with nothing to play, stopping cleanly"` line, meaning the guard
  correctly did NOT fire prematurely. Confirmed via `dumpsys media_session`:
  `state=PlaybackState {state=PAUSED(2), position=9860, ...}` with real metadata
  (`description=Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι Vol 1`), and `dumpsys notification
  --noredact | grep -c pkg=com.kefi.player` = 2 (a real, legitimate "Now Playing (paused)"
  notification, not the T8-era zombie `state=NONE` one). `logcat -d | grep -iE
  "FATAL|AndroidRuntime.*com.kefi.player"` = 0 (the 2 unrelated hits found by a broader
  `Exception`-inleuding grep were `MediaControllerCompat: android.os.DeadObjectException`
  from `system_server`'s stale binder to the just-killed old session — expected — and an
  unrelated `EmojiCompatManager` message from a different system process, pid 1486, not
  `com.kefi.player`). **Net effect: T12 upgrades the T8 zombie-restart scenario from "stop
  cleanly, no notification" to "restore the real paused queue, show a real Now Playing
  notification" whenever there is something to restore** — strictly better, and confirmed to
  not resurrect the original zombie (still stops cleanly when restore finds nothing, per
  Scenario 6 below).
- **`src/App.vue` housekeeping (owned by this task, not scope creep)**: `FIXTURE_URI` /
  `QUEUE_URIS` (hardcoded MediaStore ids `24/25/23`, stale since T11's delete+rescan QA
  renumbered fixtures to `26/27/28`, and now `29/30/31` after this task's own Scenario 6
  delete+rescan) replaced with `resolveTrackByTitle(title)` — a plain `await
  Player.listTracks()` + `.find(t => t.title === title)`, called fresh on every use (no
  caching — caching would reintroduce exactly this staleness class). `playFixture()` and
  `queueFixtures()` now resolve by title (`FIXTURE_TITLES = ['Άσπρη Μέρα', 'Zeibekiko',
  'Ωδή']`) at call time instead of reading a stale constant. Also fixed the T9 QA panel's
  `getArtworkFor(24)`/`getArtworkFor(25)` buttons the same way (new
  `getArtworkForTitle(title)` wrapper) since they hardcoded the same stale-id class of bug,
  even though the task text named only the `Play fixture (f1)` symptom explicitly — judged
  in-scope as "the dev panel... instead of hardcoding ids, so it can never go stale again"
  reads as the whole panel, and leaving two buttons half-fixed would defeat that goal. Every
  existing `TODO(T13)` marker (both the `<script setup>` top comment and the
  `dev-player`/section comments) is untouched — verified by inspection, this task's diff only
  touches statements inside those already-marked blocks. Proved fixed live: after Scenario 6
  renumbered fixtures to `29/30/31`, tapping "Queue all 3" resolved
  `{"mediaId":"content://media/external/audio/media/29",...}` with zero code changes needed
  — exactly the failure mode this housekeeping item exists to prevent.
- **QA against `emulator-5554`, real WebView-originated calls + real `adb`
  force-stop/kill-9** (fixture ids at the START of this task: Άσπρη Μέρα=26, Zeibekiko=27,
  Ωδή=28 — see the note below about mid-task renumbering):
  1. **Position persistence + restore-paused (Scenarios 1/3/4)**: fresh launch, "Queue all 3"
     → `setQueue` resolve `currentIndex:0` (id 26); "Next" → `next` resolve `currentIndex:1`
     (id 27, Zeibekiko); waited 20s. Mid-wait, `run-as ... cat kefi.player.xml` showed
     `player.positionMs=29867` while a `dumpsys` read moments earlier showed
     `position=26930` — heartbeat confirmed live and fresh. Immediately before force-stop:
     `dumpsys media_session` → `position=38848` (**POS1**), `active item id=1` (**IDX1**);
     prefs read the same instant → `player.positionMs=39778`, `player.index=1` (within
     ~930ms — comfortably under the 6000ms budget). `am force-stop com.kefi.player`; relaunch;
     waited 10s; WebView's own `[T8 QA] playerState` (a live `getPlayerState()` pull, per
     T8's pull-on-mount path) →
     `{"mediaId":"content://media/external/audio/media/27",...,"currentPosition":39778,
     "duration":209999,"isPlaying":false,...,"currentIndex":1}` — **PASS**: same track,
     `|39778-38848|=930ms < 6000ms`, `isPlaying:false`. `dumpsys activity services
     com.kefi.player` / `dumpsys notification --noredact` confirmed a real
     `channel=playback` notification for the restored (paused) track, not a zombie. Tapped
     "Resume": `dumpsys media_session` → `state=PlaybackState {state=PLAYING(3),
     position=39778,...}` immediately, then `position=42613` 3s later — **PASS**: reached
     `state=3` and advanced from ~POS1 (39778→42613), not from 0.
  2. **Shuffle/repeat round-trip (Scenario 5)**: while track 27 was playing, tapped "Shuffle
     on" → `setShuffle` resolve `shuffleModeEnabled:true`; "Repeat one" → `setRepeatMode`
     resolve `repeatMode:"one"`. Prefs immediately showed `player.shuffle=true
     player.repeat=1`. `am force-stop`; relaunch; waited 10s; WebView `[T8 QA] playerState` →
     `{"mediaId":".../27",...,"shuffleModeEnabled":true,"repeatMode":"one",
     "currentPosition":59581,"currentIndex":1,"isPlaying":false}` — **PASS**: shuffle/repeat
     round-tripped as the STRING `"one"` (T10's frozen contract), not the raw Int.
  3. **All-unresolvable → cleared (Scenario 6)**: `adb shell rm` all 3 fixture files, rescanned
     (`content call scan_volume external_primary`) → MediaStore query returned zero rows.
     `am force-stop`; relaunch; waited 10s. Logcat: `PlaybackStateStore: dropping
     unresolvable uri=.../26 reason=file_not_found` ×3 (ids 26/27/28, each), then
     `PlaybackStateStore: none of 3 stored uri(s) resolve, clearing state`. WebView `[T8 QA]
     playerState` → `{"mediaId":null,...,"queueLength":0,"currentIndex":0}` (default/empty).
     `run-as ... cat kefi.player.xml` → `<map />` (fully cleared, confirmed with `cat`, not
     just inferred). `dumpsys notification --noredact | grep -c pkg=com.kefi.player` = 0 (no
     ghost notification). `logcat -d | grep -icE "FATAL|AndroidRuntime.*com.kefi.player"` = 0.
     Restored fixtures via `bash scripts/qa-fixtures.sh` immediately after — this delete
     renumbered ids to **29/30/31** (see final ids below).
  4. **Zombie-restart race, live repro (see the dedicated writeup above)**: fresh launch,
     "Queue all 3" (now ids 29/30/31, proving the App.vue housekeeping fix works
     post-renumbering), played ~3s, `kill -9 <pid>` (whole process, not force-stop). OS
     auto-restarted the service; restore correctly won the race — `PlaybackService restored 3
     item(s) at index=0 positionMs=9860, paused`, confirmed via `dumpsys media_session`
     (`state=PAUSED(2), position=9860`, real metadata) and zero crashes.
  5. `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` → `0` (checked twice, before and
     after the App.vue edits). `npm run build` → `vite build` succeeded (`player.web.js`
     still its own lazy chunk, no `.ts` file created). `npm run apk` → `BUILD SUCCESSFUL`, APK
     8.7M (unchanged size — no new dependency; the APK installed for every scenario above was
     built from the final code, including the App.vue housekeeping fix).
- **Final fixture ids at the end of this task** (after Scenario 6's forced delete+rescan):
  **Άσπρη Μέρα=29 (180036ms), Zeibekiko=30 (210051ms), Ωδή=31 (240039ms)** — verified present
  via `bash scripts/qa-fixtures.sh` immediately before finishing. These will renumber again
  the next time a task deletes+rescans them (T13+ should re-check via `content query` rather
  than trusting this number, exactly per the reasoning behind this task's own App.vue fix).
- Did NOT add Room/SQLite/`@capacitor/preferences`/DataStore or any other storage dependency
  — `org.json.JSONArray` + plain `SharedPreferences` only. Did NOT implement
  `MediaLibraryService`/`onPlaybackResumption()`. Did NOT modify `TrackSource.kt`,
  `MainActivity.java`, or `src/composables/usePlayer.js`. Did NOT start any Phase B work
  (router/i18n/library UI/mini-player/Now Playing screen). Did NOT add any npm/Gradle
  dependency or any `.ts` file. Did NOT edit any plan checkbox (a pre-existing uncommitted
  diff to the plan file, checking off T6-T11 and correcting their QA scripts, was already
  present in the working tree before this task started — left untouched, not staged).

## T13 — Router, tab bar, back button (2026-08-20)
- NEW `src/router/index.js` (`createWebHashHistory`, routes `/library` default, `/now-playing`,
  `/settings`, plus a catch-all redirect to `/library` so a stale hash after an update can never
  render a blank WebView), NEW `src/components/TabBar.vue` (2 tabs only — Now Playing is reached
  from the mini-player, per Must Have 9), NEW `src/views/{Library,NowPlaying,Settings}View.vue`
  (deliberate stubs; T14-T19 fill them), rewritten `src/App.vue`, `src/main.js`.
- **The dev QA panel is gone**, as RESUME.md required — but its two `console.log` lines were
  KEPT in `App.vue` under a `[QA]` tag (renamed from `[T8 QA]`/`[T11 QA]`). They are the only
  window the scripted scenarios have into WebView-side state (`adb logcat -s Capacitor/Console`),
  and T8/T12/T20 assertions depend on it. **T20's script must grep for `[QA] playerState` and
  `[QA] trackUnavailable`.**
- Back button lives in `wireHardwareBack()` in the router module, called once from `main.js`
  (attach-once, same discipline as usePlayer's listeners): at `/library` → `CapacitorApp.exitApp()`,
  elsewhere → `router.back()`, falling back to `router.replace('/library')` when
  `window.history.state?.back` is absent (a WebView rebuilt straight onto `#/now-playing` has an
  empty history stack — this is reachable in practice after the T8 renderer kill).
- **UI QA tooling discovered here, reusable by every remaining Phase B task.** The debug build's
  WebView is devtools-debuggable, so UI assertions do not need OCR or blind taps:
  `adb shell cat /proc/net/unix | grep -o "webview_devtools_remote_[0-9]*"` →
  `adb forward tcp:9222 localabstract:<that>` → `http://127.0.0.1:9222/json/list` gives a
  `webSocketDebuggerUrl`; Node 22 has a global `WebSocket`, so a ~20-line script can
  `Runtime.evaluate` any expression in the app (`location.hash`, `querySelectorAll(...).length`,
  and — critically for T15 — setting a Greek search string that `adb shell input text` cannot type).
  Helpers live in the session scratchpad (`cdp.mjs`, `tapsel.sh`), deliberately NOT committed:
  T20's committed suite covers native assertions only.
- **Tap-coordinate offset, measured empirically:** device_y = (css_y * devicePixelRatio) + 63.
  The 63px is the status bar; `window.screenY` reports 0 in a Capacitor WebView and cannot be
  used for it. On this AVD dpr = 2.625, viewport 412x839 CSS px, screen 1080x2400 device px.
  A tap without the offset lands ~24 CSS px high and silently misses the tab bar.
- QA (all on `emulator-5554`, API 36): `npm run build` ✓; `npm run apk` → `BUILD SUCCESSFUL`;
  routes `#/library → #/settings → #/now-playing` each rendered and screencapped
  (`/tmp/kefi-{library,settings,now-playing}.png`); `KEYCODE_BACK` from `/now-playing` → `#/settings`
  with `topResumedActivity=com.kefi.player/.MainActivity` still ✓; `KEYCODE_BACK` at `/library` →
  `topResumedActivity=com.google.android.apps.nexuslauncher` and zero `com.kefi.player` activity
  records ✓ (app backgrounded, not crashed).

## T14 — Bilingual UI (el/en) (2026-08-20)
- NEW `src/i18n/{index.js,el.js,en.js}`, rewritten `src/views/SettingsView.vue`, `src/main.js`;
  `TabBar.vue` + the other two views now render every string through `t()`.
- Hand-rolled, no library: a module-level `locale` ref (same discipline as `usePlayer`),
  `t(key, params)` doing `{name}` substitution, and a **three-step fallback chain**
  `active locale → en → the key itself`, so a missing Greek string shows the English word and a
  typo'd key is visible in the UI instead of rendering blank.
- Dictionaries are FLAT dotted keys (`'library.count.songs'`), not nested objects — a nested
  lookup needs a path walker and gains nothing for ~55 keys. Keys for T15-T19 (permission,
  empty, no-results, transport labels) are already written, so those tasks add markup only.
- Locale resolution order: `detectLocale()` (`navigator.language.startsWith('el')`) seeds the ref
  SYNCHRONOUSLY so the first paint is already right, then `initLocale()` overrides it from
  `@capacitor/preferences` key `kefi.locale`. `main.js` mounts inside
  `initLocale().finally(...)` — `.finally`, not `.then`, so a storage failure still mounts the app.
- `setLocale` switches the ref BEFORE awaiting the write: the tap must repaint even if
  persistence fails, and everything user-facing is derived from the ref.
- QA: `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` → no matches
  (exit 1) ✓. Real tap on `Ελληνικά` in Settings flipped the tab labels to
  `["Βιβλιοθήκη","Ρυθμίσεις"]` with `performance.getEntriesByType('navigation').length` still `1`
  and the live CDP session unbroken — proof it re-rendered rather than reloaded ✓.
  `am force-stop` + relaunch came back up with the Greek labels ✓ (`/tmp/kefi-settings-el.png`).

## T15 — Songs list, search, Greek collation (2026-08-20)
- NEW `src/composables/useLibrary.js`, `src/components/TrackRow.vue`, `src/utils/format.js`
  (`formatDuration` ported from ytmp3 `format.ts:1-8` + a `formatMs` wrapper, since the whole
  native contract speaks milliseconds); rewritten `src/views/LibraryView.vue`;
  `usePlayer.js` gained `setQueue/next/previous/setShuffle/setRepeatMode` wrappers (T17/T18 need
  the same ones, and going through the composable keeps the shared refs from going stale).
- `usePlayer.js`: `repeatMode` ref default corrected `0` → `'off'`. The wire contract has been a
  STRING since T10; the numeric default was only ever visible in the first render before the
  first snapshot arrived.
- **One collator, used for both sort and search** — `Intl.Collator('el', { sensitivity: 'base',
  numeric: true })`. `numeric: true` additionally keeps `Track 2` before `Track 10`.
- **There is no `Intl` "contains" primitive**, so `collatorIncludes()` slides a window of the
  needle's length across the haystack and asks the collator whether the window collates equal.
  Both sides are `normalize('NFC')`-ed first: a decomposed title (NFD, as some tag editors write)
  would otherwise have its combining marks sliced apart mid-window and never match. Cost is
  O(len) compares per field, run only over the already-in-memory list.
- **Script order verified on device, not assumed.** `Intl.Collator('el')` puts Greek BEFORE Latin
  in both Node 22 and the API 36 WebView, so the plan's expected `Άσπρη Μέρα < Ωδή < Zeibekiko`
  holds. (`el-u-kr-grek-latn` gives the same order and was not needed.)
- Tapping a row queues **the visible list**, not the whole library: playing the 3rd search hit
  then continues with the 4th hit.
- **NATIVE FIX, outside T15's declared file list, required for T17/T18 to be possible at all.**
  `PlayerController.toSnapshot()` read `currentMediaItem.mediaMetadata`, which is EMPTY for any
  queue built from bare uris (`setQueue`) — every queue-driven track crossed the bridge as
  `title: null, artist: null` while the notification showed them correctly. Two changes:
  (a) the snapshot now prefers the player's COMBINED `mediaMetadata` (a superset: MediaItem
  metadata merged with the extractor's tags), falling back to the item's own;
  (b) NEW `Player.Listener.onMediaMetadataChanged` → `broadcastState()` (broadcast only, no
  `persistState()`), because the extractor reads tags a beat after playback starts, so the
  snapshot taken at start time would otherwise keep reporting the null until an unrelated event
  fired. **Snapshot keys unchanged — the frozen 11-key contract is untouched**, only the source
  of two values. Verified: `[QA] playerState … "title":"Zeibekiko","artist":"Nikos Alexiou"`.
- QA (device): exactly 3 rows ✓; titles render `["Άσπρη Μέρα","Ωδή","Zeibekiko"]` ✓; typing
  `ωδη` (no tonos, lower case) narrows to exactly 1 row `Ωδή` ✓; `ΠΑΠΑΔΟΠΟΥΛΟΥ` (upper, no
  tonos) matches both of that artist's tracks ✓; clearing restores 3 ✓; a real tap on the
  `Zeibekiko` row → `dumpsys media_session` `state=PLAYING(3)`, `description=Zeibekiko, Nikos
  Alexiou, Kefi Vol 1`, snapshot `queueLength: 3, currentIndex: 2` ✓
  (`/tmp/kefi-songs.png`, `/tmp/kefi-songs-playing.png`).

## T16 — Albums and Artists groupings with artwork (2026-08-20)
- NEW `src/components/AlbumGrid.vue`, `src/components/ArtistList.vue`; `LibraryView.vue` gained the
  Songs | Albums | Artists segmented control; `useLibrary.js` gained the `albums`/`artists`
  computeds and the lazy artwork cache (a `groupBy` in a component would have re-run per render
  and could not be shared with a future screen).
- Grouping is over **`visibleTracks`, not all tracks**, so a search narrows all three tabs
  consistently instead of offering albums whose songs are filtered out. Group keys sort through
  the same `Intl.Collator('el')`; tracks inside a group sort by `trackNumber` with title as the
  tie-break (the fixtures have no track numbers at all).
- Artwork cache is `Record<trackId, string | null>`: **absent means "not asked yet", stored
  `null` means "asked, has none"** — without that distinction every placeholder cell re-fetches
  forever. `loadArtwork` is idempotent and de-duped by a pending Set; a fetch that throws caches
  `null`, because for the UI "unreadable" and "absent" are the same picture.
- Truly lazy: an `IntersectionObserver` with `rootMargin: '200px'` starts the fetch a screenful
  early and unobserves each cell after its first hit. Artwork crosses the bridge as base64 JPEG,
  so fetching on mount would pull the whole library's covers for cells nobody scrolled to.
- **PLAN DISCREPANCY — the plan's "Albums shows exactly 2 cells" is wrong against its own fixture
  table, and the actual count is 3.** The fixtures' albums are `Κέφι Vol 1` (f1, Greek), `Kefi
  Vol 1` (f2, **Latin**) and `Κέφι Vol 2` (f3, Greek) — three distinct names. No collator setting
  makes Greek `Κέφι` equal Latin `Kefi`: `sensitivity: 'base'` folds case and accents, never
  scripts. The plan's own QA sentence contradicts its count too ("the `Κέφι Vol 1` cell renders
  the orange cover while `Kefi Vol 1` renders the placeholder" describes 3 cells). Delivered
  as 3, which is the correct grouping of the fixture data; the fixtures were NOT changed, since
  their mixed-script titles are what T15's collation QA rides on. **`Expected groupings: 3
  songs, 2 albums, 2 artists` in the plan's Fixed constants should read 3 albums.**
- QA (device): Albums → 3 cells `Κέφι Vol 1`, `Κέφι Vol 2`, `Kefi Vol 1` (Greek before Latin,
  per the collator), exactly 1 `.art-image` (the orange embedded cover on `Κέφι Vol 1`) and 2
  token placeholders ✓; Artists → exactly 2 rows, `Δήμητρα Παπαδοπούλου / 2 τραγούδια` and
  `Nikos Alexiou / 1 τραγούδι` ✓; tapping the `Κέφι Vol 1` cell → `dumpsys media_session`
  `description=Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι Vol 1` ✓
  (`/tmp/kefi-albums.png`, `/tmp/kefi-artists.png`).
- **QA TOOLING BUG FOUND AND FIXED — earlier tap coordinates were wrong and a miss looked like a
  pass.** The WebView's vertical inset on this AVD is **135 device px, not the 63** a 24dp status
  bar implies (`window.screenY` reports 0 and cannot be used). A tap on the 28px-tall segmented
  control landed on the search input instead and simply did nothing, which reads exactly like a
  broken click handler. Diagnosed by installing a capture-phase click listener and comparing the
  reported `clientY` with the element's own rect. `tapsel.sh` now (a) uses offset 135, and
  (b) **verifies every tap** — it asks the WebView which element actually received the click and,
  on a miss, re-derives the offset from the real `clientY` and taps once more, failing loudly if
  that still misses. Any future UI QA on this project must keep that verification: a silent
  off-by-N turns a QA step into a no-op that still "passes".

## T17 — Persistent mini-player (2026-08-20)
- NEW `src/components/MiniPlayer.vue`; `App.vue` mounts it between `<RouterView>` and `<TabBar>`
  (grid rows `1fr auto auto`), so navigation cannot remount it. `useLibrary` gained a
  `trackByUri` computed: the snapshot carries a uri but no MediaStore `id`, and artwork needs
  the id — the library already knows both.
- **The coordinator's T17/T18 note is implemented in `usePlayer`, not in the component**, so both
  the mini-player and Now Playing share one interpolation: every `applySnapshot` re-bases
  `positionBase` + `positionBasedAt = performance.now()`, a 250ms local `setInterval` bumps a
  `tickCount` ref, and `displayPosition` = `positionBase + (now - basedAt)` while playing, clamped
  to `duration`, frozen at the reported value while paused. **Zero bridge calls** — the ticker
  only increments a number. The raw `currentPosition` ref is still exposed and still only moves on
  real events, which is what the `[QA]` log line reports.
- The ticker is started/stopped by a MODULE-LEVEL `watch(isPlaying, …)`: it follows playback for
  the app's lifetime, not any component's, so it keeps running while the route changes.
- Title/artist resolution order is snapshot → library record → `t('nowPlaying.unknown…')`. The
  snapshot wins because it is the playing truth even for a file MediaStore has not indexed.
- QA (device, after `pm clear` + re-grant so nothing was restored): mini-player absent from the
  DOM before any playback ✓; tapping `Άσπρη Μέρα` renders it with title, artist and the embedded
  cover ✓; progress fill advanced 1.95% → 3.76% across 3s with no bridge call in between (local
  interpolation) ✓; `/library → /settings → /library` kept `state=PLAYING(3)` with the fill rising
  monotonically 11.5% → 13.2% → 14.9% (no remount) ✓; tapping the body routed to `#/now-playing` ✓;
  the toggle paused (`state=PAUSED(2)`, label → `Play`) and resumed (`state=PLAYING(3)`) ✓
  (`/tmp/kefi-miniplayer.png`).
- **QA tooling, second false-negative class fixed.** `tapsel.sh`'s hit check ran AFTER the tap, but
  a tap that causes a re-render (the play/pause icon swapping) detaches the element the event fired
  on, so `contains()` said "miss" for taps that landed. The hit test now runs INSIDE the capture
  listener at click time (`e.target.closest(window.__tapSel)`). Second trap: the listener is
  installed behind a `window.__tapHookV2` guard, and the old guard name kept the OLD listener alive
  in the page context after the fix — any change to that listener needs a version bump, otherwise a
  long-lived WebView silently keeps running the previous version.

## T18 — Now Playing screen (2026-08-20)
- Rewrote `src/views/NowPlayingView.vue`: large artwork/placeholder, title/artist/album, a seek
  bar with elapsed + remaining labels, and the transport row (shuffle · previous · play/pause ·
  next · repeat). Also hid the mini-player on this route — see below.
- **Every control renders from the snapshot, with exactly one exception, and that exception is
  deliberate**: a `dragging`/`dragValue` pair that lives only for the duration of a drag. Without
  it the 250ms interpolation tick yanks the thumb back out from under the finger. On `change`
  (release) it calls `seek()` and drops back to snapshot-derived rendering immediately, so nothing
  about which icon is shown, or whether shuffle is on, is ever remembered locally.
- Repeat cycles `off → all → one → off` and the `one` state gets a `1` badge — `all` and `one`
  are otherwise the same glyph, and the plan's QA requires them to be distinguishable after a
  restore. The button reads and writes the STRING `off|one|all` per T10's wire contract.
- **Mini-player is hidden on `/now-playing` with `display: none`, NOT `v-if`.** It must stay
  mounted (it sits outside `<RouterView>` exactly so navigation cannot remount it, T17's QA);
  it simply has nothing to add while the full player is on screen. Verified: on `/now-playing`
  `miniMounted: true, miniVisible: false`, and it is visible again after `KEYCODE_BACK`.
- QA (device): opening from the mini-player showed `Ωδή / Δήμητρα Παπαδοπούλου / Κέφι Vol 2`
  with `max=239999` ✓; seek to `120000` landed at `position=120012` (12ms off, tolerance 3000)
  with `state=PLAYING(3)` ✓; `next` → `Zeibekiko`, `previous` → `Ωδή`, `previous` again →
  `Άσπρη Μέρα` (both directions) ✓; shuffle tap → `shuffleModeEnabled: true` in the native
  snapshot with the button showing `aria-pressed="true"` ✓; two repeat taps → `Repeat all` then
  `Repeat one` with the badge, native snapshot `"repeatMode":"one"` ✓; `am force-stop` + relaunch
  re-rendered shuffle ON, repeat ONE, `Play` (i.e. paused, per T12) and `0:30` against the native
  `position=30098` ✓ (`/tmp/kefi-nowplaying.png`, `/tmp/kefi-nowplaying-restored.png`).

## T19 — Empty, loading and permission states (2026-08-20)
- `LibraryView.vue` rewritten with three mutually exclusive states before the list: ask,
  blocked, empty. `useLibrary` gained `permission`, `checkPermission`, `requestPermission`,
  `initialize` and `openAppSettings`; `App.vue` gained the skip notice (below).
- **The three states are distinguishable because Capacitor already exposes Android's own
  tri-state — no locally-remembered "have we asked yet" flag was needed.**
  `checkAudioPermission`/`requestAudioPermission` now also return `state`
  (`getPermissionState(alias).toString()` → `granted | denied | prompt |
  prompt-with-rationale`). `prompt` and `prompt-with-rationale` both mean the system dialog
  can still appear, so both render the ask state; only `denied` is the dead end. `granted:
  false` alone cannot tell those apart, which is exactly the distinction the plan's three
  states need. The `{ granted }` shape is unchanged — `state` is additive.
- **NEW plugin method `openAppSettings`** (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` +
  `FLAG_ACTIVITY_NEW_TASK`, started from the application context). This is a 15th method on a
  Must Have 6 list of 14, and it is load-bearing: once Android has recorded "don't ask again"
  the dialog never returns, so a denied state with no route to Settings would be a dead end.
  The alternative was a third-party settings plugin, which Must NOT Have's "no new
  dependencies for two screens" spirit argues against harder than 8 lines of Kotlin do.
- `src/plugins/player.web.js` gained `listTracks`, `getArtwork`, both permission methods and
  `openAppSettings`, so `npm run dev` in a desktop browser now renders the real library UI
  against three mock tracks (mixed Greek/Latin, so collation and search are exercisable there
  too) instead of dying on the first `listTracks` call.
- **`App.vue` now REPORTS the T11 skip** (Must Have 13's other half): `lastError` raises a
  self-dismissing (6s) notice row in the shell grid, not a floating toast — the skip can happen
  while any screen is open, or none, and a grid row cannot overlap the mini-player.
- QA (device, every step a real tap): `pm revoke` + relaunch → ask state, `rows: 0`, no search
  box ✓; tapping `Allow access` raised the real dialog and granting it populated the list to 3
  rows **in the same WebView process** (devtools socket pid unchanged — no restart) ✓; denying
  once left the ask state (Android still reports `prompt-with-rationale`), and the second
  dialog's `Don't allow and don't ask again` flipped the UI to `Access to music is turned off`
  with `Open app settings`, which opened `com.android.settings/.spa.SpaActivity` ✓; with
  permission granted and the fixtures deleted + `scan_volume`, relaunch rendered `No music
  found` ✓; after `npm run fixtures`, the state's own `Refresh` button repopulated all 3 rows
  with no restart ✓ (`/tmp/kefi-permission.png`, `/tmp/kefi-permission-denied.png`,
  `/tmp/kefi-empty.png`).
- QA tooling: NEW `tapsys.sh` in the scratchpad taps system-UI elements (permission dialog,
  Settings) by resource-id substring out of `uiautomator dump`. Note the dialog's button ids
  CHANGE with the attempt: `permission_deny_button` first time,
  `permission_deny_and_dont_ask_again_button` on the repeat — a script that hardcodes the first
  id silently fails on the second dialog. Also: while a system dialog is up, `tapsel.sh`
  correctly reports the WebView tap as a miss instead of pretending it landed.

## T20 — scripts/qa-playback.sh (2026-08-20)
- NEW `scripts/qa-playback.sh` (executable, takes a serial, default `emulator-5554`),
  `package.json` gained `"qa": "bash scripts/qa-playback.sh"`. 29 checks over preflight, T10,
  the media-button pre-check, T5, T8 and T12; one PASS/FAIL line each; exit 1 on any FAIL.
- **The suite drives the app through its own JS bridge over the DevTools protocol, not through
  taps.** The script writes a ~30-line `cdp.mjs` to a temp dir at run time (Node 22's global
  `WebSocket`; no dependency added) and evaluates e.g. `Capacitor.Plugins.Player.setQueue(...)`
  inside the live WebView. Rationale: the dev panel T13 deleted was the old tap target, and
  hardcoded pixel coordinates stop hitting anything the moment a layout changes — while a tap
  that misses reads exactly like a passing test. `userGesture: true` is set on every evaluate
  because `PlayerController` only accepts its first touch from a user-gesture-originated call.
- **`set -u` only — NO `pipefail`.** Nearly every assertion ends in `grep -q`, which exits on
  first match and SIGPIPEs its feeder; under `pipefail` a SUCCESSFUL match becomes a failed
  pipeline. This cost a full run: the preflight aborted claiming the app was not installed.
- Three assertion-mechanics bugs found by running it, all "the test was wrong", not the app:
  1. **foregroundServiceType is a BITMASK in dumpsys**, never the manifest's word:
     `isForeground=true foregroundId=1001 types=0x00000002` (0x2 =
     `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`). Grepping for `mediaPlayback` finds nothing.
  2. **`dumpsys audio`'s client line reads `u/pid:<uid>/<pid> … state:started`** — a bare
     `pid:<pid>` also matches unrelated log lines inside the same dump.
  3. **The renderer kill needs root**: `shell kill -9 <renderer>` gives "Operation not
     permitted" (the shell user cannot signal another app's isolated process), and the three
     assertions after it then all passed against a renderer that never died — a false green.
     Now uses `shell su 0 kill -9` and FAILS LOUDLY, naming `adb root`, when it cannot kill.
- Result on `emulator-5554` (API 36): **29/29 PASS**.

## Final Verification Wave (2026-08-20)
- **F1 build integrity ✓** — `npm run build` ✓; `npm run apk` → exactly one `BUILD SUCCESSFUL`;
  `:app:compileDebugKotlin` → `0` lines matching `^e:`. Re-run after the last source edit.
- **F2 release integrity ✓** — `npm run apk:release` → `BUILD SUCCESSFUL` (2m36s, 190 tasks);
  `android/app/build/outputs/apk/release/app-release-unsigned.apk` exists (6.0 MB). No
  `android/keystore.properties` in the project, so the unsigned path is the expected one and
  **no keystore was created** — that stays the user's one-time decision.
- **F3 scripted QA ✓** — on a FRESH install (`pm clear` + `install -r`) with fixtures re-created
  by `npm run fixtures`: `npm run qa` → **exit 0, 29/29 PASS**. The Phase B UI scenarios
  (T13-T19) were re-run as a separate scratchpad sweep: **30/30 PASS**, including the Greek
  collation order, `ωδη` → 1 row, 3 albums / 2 artists, the mini-player surviving two
  navigations with a monotonically rising bar, seek landing 12ms off the midpoint, restored
  shuffle/repeat/paused rendering, and the revoke → ask → grant-with-no-restart path. T11's
  scenario was also re-run by hand: deleting `f1.mp3` mid-queue emitted
  `trackUnavailable {reason: file_not_found}`, playback moved to `Zeibekiko`, and the shell
  showed the Greek skip notice; fixtures restored afterwards.
- **F4 scope review ✓** — the diff since `6546cbc` is 28 files, +3146/-295, and every path is
  one the plan named. Forbidden-identifier grep returns **0** (two hits were English prose in
  comments — "no room for one" and a comment listing what was *rejected*; both reworded so the
  check is literally green rather than green-with-an-excuse). `*.ts` outside node_modules/android
  → `0`. `media3-ui|media3-datasource|decoder-ffmpeg|exoplayer-av1` in `app/build.gradle` → `0`.
  `abiFilters|useLegacyPackaging` → `0`. Nothing under `/mnt/X/myProjects/ytmp3` modified
  (`find -newermt 2026-08-20` → 0 files).
- **F5 hands-on QA — NOT RUN.** No physical device has been connected at any point; `adb devices`
  lists only `emulator-5554`. Audibility, real Bluetooth A2DP/AVRCP, lock-screen visuals and
  wired-headset unplug remain unverified. Reported as not run, never as passed. The plan's F5
  checkbox is deliberately left unticked.
- **F6 documentation ✓** — `README.md` gained "What the app does", the permission model with the
  three Library states, a QA section (`npm run fixtures` / `npm run qa`, what the suite covers,
  what stays manual and why), and the roadmap (playlists+favourites on Room → sleep timer +
  playback speed → widget; equalizer declined). Stale text fixed: the playback engine is no
  longer "will be native", and the "no `.kt` file exists yet" note now lists the six Kotlin files.

## Success-criteria sweep (2026-08-20)
All 15 numbered criteria verified, with one correction recorded in the plan itself:
- (1) `git status --porcelain` → 0 ✓ · (2) one `BUILD SUCCESSFUL` ✓ · (3) `^e:` → 0 ✓ ·
  (4) `unzip -l app-debug.apk | grep -c 'lib/'` → **0** ✓ (no native payload, as intended) ·
  (6)-(8), (12), (13) → `npm run qa` 29/29 ✓ · (9)-(11) → the UI sweep ✓ ·
  (14) forbidden identifiers → 0 ✓ · (15) `*.ts` → 0 ✓.
- **(5) is unsatisfiable as written and the plan now says so**: `dumpsys activity services |
  grep -c mediaPlayback` returns `0` on API 36 because dumpsys prints the type as a bitmask
  (`types=0x00000002`), never the manifest word. Checked instead: that line with bit `0x2`
  set (runtime) **and** `foregroundServiceType="mediaPlayback"` in the manifest (declaration).
  Both green. This is the same trap that produced a false FAIL in T20's first run.
