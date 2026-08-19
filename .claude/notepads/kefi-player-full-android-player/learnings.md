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

## T3 — Media3 dependencies only (2026-08-19)
- APK size before edit: 4,424,819 bytes. APK size after adding media3-exoplayer + media3-session 1.11.0: 8,541,817 bytes. Delta: +4,116,998 bytes (~3.93MB), within the required 4,600,000–8,800,000 byte absolute range from the task's Expected Outcome.
- `unzip -l app-debug.apk | grep -c 'lib/'` = 0 — confirmed Media3 pulled in no native libraries.
- `npm run apk 2>&1 | grep -c "BUILD SUCCESSFUL"` = 1.
