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
