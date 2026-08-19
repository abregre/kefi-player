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
