# Plan: Kefi Player — full Android music player (Vue 3 + Vite JS + Capacitor 8 + Media3)

## Context

`/mnt/X/myProjects/kefi-player` is a scaffold created 2026-08-19 and contains **no feature
code**: Vue 3 + Vite in plain JavaScript, Capacitor 8.5 with an Android platform, a
placeholder `src/App.vue`, design tokens in `src/style.css`, bundled Inter/Roboto woff2
(Latin + Latin-Ext + Greek), and working build scripts. `npm run apk` produces a 4.3MB
debug APK. This plan turns that scaffold into a usable music player.

The specs are mined from three plans written for a **different app layout** — the player
merged into the `ytmp3` downloader:
`/mnt/X/myProjects/ytmp3/.claude/plans/{native-music-player-milestone-1.md,music-player-shell-milestone-2.md,deferred-cuts-proposed-solutions.md}`.
On 2026-08-19 the user reversed that merge: **the two apps stay separate**. Those files are
**spec input only**. Every "decision", the FROZEN-SET CHECK, all milestone gating, all
downloader/`YtdlpPlugin`/`DownloadService`/WorkManager content, and the `com.ytmp3.app`
identity **do not carry over**.

`/mnt/X/myProjects/ytmp3` (TypeScript) remains the **pattern source** for Capacitor plugin
conventions, Kotlin idioms and Vue composable style. Its files are read-only reference; this
plan never modifies anything under `/mnt/X/myProjects/ytmp3`.

### Decisions taken with the user (interview, 2026-08-19)

1. **Persistence: `SharedPreferences`-class storage now, Room later.** Queue, index,
   position, shuffle and repeat survive process death. Playlists/favourites/play-counts and
   the Room schema they need are a **separate later plan**.
2. **One plan covers playback core *and* the full UI shell.** There is no legacy UI to
   protect, so the app becomes usable in a single pass. The playback core is still built and
   **gated first** (T8) because it carries all the technical risk.
3. **`git init` is task 1.** The project is not a git repo, and because the web layer is
   plain JS there is no typecheck gate either — git is the only rollback mechanism for
   multi-file Kotlin work.
4. **Library = all device audio via `READ_MEDIA_AUDIO`.** No SAF folder picker in this plan.
5. **Roadmap (explicitly *next* plans, not this one):** playlists + favourites on Room;
   sleep timer + playback speed; home-screen widget. Lock-screen media controls were also
   named by the user — those are **in this plan**, because `MediaSessionService` provides
   them and it would be wrong to defer something the core already delivers.
6. **QA runs on an API 36 emulator**, matching `targetSdk 36`, created as part of the work.
7. **UI ships in both Greek and English** with a user-visible switch and auto-detection from
   the device locale.

### Findings that shaped this plan (verified 2026-08-19, not assumed)

- **`sdkmanager` and `avdmanager` do not exist on this machine.** `~/Android/Sdk` has no
  `cmdline-tools/` and no `tools/bin/`. Creating the API 36 AVD therefore needs
  command-line tools installed first. `commandlinetools-linux-13114758_latest.zip` was
  confirmed downloadable (HTTP 200).
- **There is no `google_apis_playstore` system image for API 36** — only `google_apis`
  (`system-images;android-36;google_apis;x86_64` confirmed present in Google's repo). This
  is an advantage: the Play-store image ships YouTube Music, which the sibling plan proved
  empirically steals `input keyevent 85` media-button routing. A `google_apis` image has no
  YouTube Music, so that trap largely disappears — the routing pre-check stays anyway.
- **Media3 stable is 1.11.0** (2026-08-05), library `minSdk 23` (compatible with this app's
  24). `media3-exoplayer` + `media3-session` alone cover audio playback, the system
  notification, and lock-screen/Bluetooth/headset controls. 1.11.0 specifically fixes two
  `ForegroundServiceStartNotAllowedException` crashes in `MediaNotificationManager` /
  `MediaSessionService.stopSelfSafely()` — hence the >= 1.11.0 floor.
  (`https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/RELEASENOTES.md`)
- **`onTaskRemoved()` must NOT be overridden.** The sibling plan prescribed
  `if (!player.playWhenReady) stopSelf()`, but Media3's base class already implements exactly
  the right behaviour:
  `if (!isPlaybackOngoing() || !isAnySessionPlaying()) pauseAllPlayersAndStopSelf();`
  (`MediaSessionService.java:752-759` at the commit above). Overriding it re-implements
  upstream logic worse. **Do not override it.**
- **`setWakeMode(C.WAKE_MODE_LOCAL)`, not `WAKE_MODE_NETWORK`.** The sibling app streamed
  nothing but inherited a network wake lock from its download context; this player reads
  local `content://` files, so a WiFi lock is dead weight. Local wake mode is the correct
  choice here.
- **`setHandleAudioBecomingNoisy(true)` is a separate builder flag from audio focus.**
  Without it, unplugging headphones re-routes to the speaker at full volume.
- **Album artwork:** `MediaStore.Audio.Albums.ALBUM_ART` is deprecated and returns null on
  API 29+. `ContentResolver.loadThumbnail()` is the current API (29+); API 24-28 needs
  `MediaMetadataRetriever.getEmbeddedPicture()`. Artwork therefore crosses the bridge as a
  base64 data URL from a dedicated plugin method — **not** as a `content://` URI, because
  whether the Capacitor WebView can render `content://` through `convertFileSrc()` is
  unverified on this stack and must not be load-bearing.
- **ExoPlayer plays MediaStore `content://` URIs directly** via `DefaultDataSource` →
  `ContentDataSource`. No `media3-datasource` artifact needed.
- **`MediaMetadataRetriever` leaks native memory** if not closed — always `use`-style, on
  `Dispatchers.IO`.
- **The riskiest part is not Media3 — it is WebView lifecycle vs service lifecycle.** The
  WebView/Bridge is destroyed while the service keeps playing. A push-only design leaves a
  permanently stale Now Playing screen. Design must be push (`Player.Listener`) **and**
  pull-on-resume (live snapshot), proven with one hardcoded track before any list UI (T8).
- **`MediaController` must not live on the Activity or the Plugin** — both are Bridge-owned
  and torn down on recreation. It lives in an application-scoped singleton, and is
  **first touched only from a user-gesture-originated plugin call** — never from
  `Application.onCreate()` — or API 34+ throws `ForegroundServiceStartNotAllowedException`.
- **Capacitor 8 still requires manual `registerPlugin(...)`** in `MainActivity`; there is no
  annotation-processor auto-registration. (`https://capacitorjs.com/docs/android/custom-code`)
- **A `@PermissionCallback` alias must be declared on the `@CapacitorPlugin` annotation** or
  it throws at runtime. The `ytmp3` codebase has **no** runtime-permission example to copy
  (its only callback is `@ActivityCallback`, a different mechanism) — follow Capacitor docs.
- **`READ_MEDIA_AUDIO` exists only on API 33+.** minSdk 24 makes the
  `READ_EXTERNAL_STORAGE` + `maxSdkVersion="32"` fallback branch reachable and mandatory.
- **`MEDIA_SCANNER_SCAN_FILE` is deprecated since API 29** and the sibling plan could not
  confirm a working shell scanner on API 35. This app has no downloader to create library
  content, so T2 establishes a fixture pipeline with an explicit fallback ladder and records
  which method actually worked.
- **`ffmpeg 6.1.1` is installed** (`/usr/bin/ffmpeg`), so QA fixtures are generated locally
  and deterministically instead of depending on device content.
- **Audibility is unscriptable.** Verified empirically by the sibling research: `dumpsys
  audio` reports `state:started` and `media.audio_flinger` reports an active mixer track with
  or without a working host audio driver. Every scripted signal proves the app-side pipeline
  reached the mixer, never that sound is audible. Audibility, real Bluetooth A2DP/AVRCP
  routing, lock-screen visual appearance and wired-headset unplug stay manual (F5).

### Declared known gaps (accepted, not hidden)

- No physical Android device is currently connected (`adb devices` empty). F5 is written for
  a device and is the one wave that cannot run unattended; it is marked **BLOCKED-UNTIL-DEVICE**
  rather than silently skipped.
- If T1's cmdline-tools install fails, QA falls back to the existing `Pixel_7_API_35` AVD and
  Android 16 foreground-service behaviour goes unverified. T1 records which happened.

### Fixed constants (used verbatim by every task; no placeholders)

- `PKG` = `com.kefi.player`; launcher activity `com.kefi.player/.MainActivity`
- `EMU` = `adb -s emulator-5554`
- `AVD` = `Kefi_API_36` (created in T1; fallback `Pixel_7_API_35`)
- Emulator boot:
  `cd /home/nonos/Android/Sdk/emulator && ANDROID_HOME=/home/nonos/Android/Sdk ./emulator -avd Kefi_API_36 -no-window -no-boot-anim -gpu swiftshader_indirect &`
- Build env for every Gradle command:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`
- Build tools: `~/Android/Sdk/build-tools/36.0.0/{aapt2,apksigner}`
- Fixture directory on device: `/sdcard/Music/kefi-qa/`
- `content query --projection` takes **colon**-separated columns (`title:artist:album`).
  The comma form throws `IllegalArgumentException: Invalid column` on API 36 — verified in T2.
- Fixtures (generated in T2, distinct durations make every assertion unambiguous):
  | file | title | artist | album | duration | art |
  |---|---|---|---|---|---|
  | `f1.mp3` | `Άσπρη Μέρα` | `Δήμητρα Παπαδοπούλου` | `Κέφι Vol 1` | 180s | embedded |
  | `f2.mp3` | `Zeibekiko` | `Nikos Alexiou` | `Kefi Vol 1` | 210s | none |
  | `f3.mp3` | `Ωδή` | `Δήμητρα Παπαδοπούλου` | `Κέφι Vol 2` | 240s | none |

  **Durations are load-bearing, not cosmetic.** T8 asserts position advances >= 12000ms
  across a 15s screen-off window and T12 plays for 20s before a force-stop; the original
  5/7/9s fixtures made both assertions impossible because the track ended first (found
  empirically while verifying T5). Keep every fixture longer than the longest timed wait.
  Expected groupings: **3 songs, 2 albums, 2 artists**; mixed Greek/Latin titles exercise
  Greek collation and search.

---

## Must Have

1. `androidx.media3:media3-exoplayer` and `androidx.media3:media3-session` pinned at
   `1.11.0` through a `media3Version` constant in `android/variables.gradle`.
2. `App.kt` — an `Application` subclass providing `App.instance` and an application-scoped
   `CoroutineScope`, wired via `android:name=".App"` in the manifest. **Neither exists yet.**
3. `PlaybackService.kt` — a `MediaSessionService` owning one `ExoPlayer` and one
   `MediaSession`, with `setAudioAttributes(..., handleAudioFocus = true)`,
   `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_LOCAL)`, and a
   `DefaultMediaNotificationProvider` pinned to channel id `playback`.
   `onTaskRemoved()` is **not** overridden.
4. Manifest: the service with `android:foregroundServiceType="mediaPlayback"`,
   `android:exported="true"` and the `androidx.media3.session.MediaSessionService`
   intent-filter; `androidx.media3.session.MediaButtonReceiver`; and the permissions
   `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`,
   `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`).
5. `PlayerController.kt` — application-scoped singleton owning the `MediaController`,
   binding asynchronously, surviving Activity/WebView recreation, all controller access on
   `Dispatchers.Main`.
6. `PlayerPlugin.kt` — one Capacitor plugin exposing `getPlayerState`, `setQueue`,
   `playTrack`, `pause`, `resume`, `next`, `previous`, `seek`, `setShuffle`, `setRepeatMode`,
   `listTracks`, `getArtwork`, `checkAudioPermission`, `requestAudioPermission`; emitting
   `playerState` and `trackUnavailable` events.
7. Track listing with **no database**: one `MediaStore.Audio` query over all device audio,
   carrying `id, uri, title, artist, album, albumArtist, trackNumber, year, durationMs, size`.
8. Session persistence in `SharedPreferences`: queue, index, position, shuffle, repeat —
   restored **paused**, never auto-playing.
9. Vue shell: `vue-router` with `createWebHashHistory()`, Android hardware back wired through
   `@capacitor/app`, bottom tab bar (Library, Settings), a `MiniPlayer` mounted **outside**
   `<RouterView>`, and a Now Playing screen with seek, shuffle and repeat.
10. Library with **Songs / Albums / Artists** groupings plus in-memory search, every
    user-facing sort and search using `Intl.Collator('el', { sensitivity: 'base' })`.
11. Artwork rendered from a base64 data URL supplied by `getArtwork`, with a token-coloured
    placeholder when absent — never a broken image.
12. Bilingual UI (Greek + English), auto-detected from the device locale, switchable in
    Settings, persisted via `@capacitor/preferences`.
13. Graceful degradation: an unplayable track is skipped and reported via `trackUnavailable`
    rather than stalling the queue; a denied `READ_MEDIA_AUDIO` renders an actionable state,
    not an empty list.
14. `scripts/qa-playback.sh` — one script running every scripted assertion against a serial.

## Must NOT Have

- **No database.** No Room, no SQLite, no `@capacitor-community/sqlite`.
- **No playlists, favourites, play counts, recently-played, or smart playlists.** Named as
  the next plan.
- **No sleep timer, no playback-speed control, no home-screen widget.** Roadmap, not this plan.
- **No equalizer or audio effects** (`android.media.audiofx`) — explicitly declined.
- **No crossfade, no ReplayGain, no tag editing, no lyrics.**
- **No `MediaLibraryService` / `onPlaybackResumption()` / Android Auto.** Open upstream bugs;
  T12's own persistence delivers the useful part.
- **No streaming, remote sources, casting or scrobbling.** Local files only.
- **No Pinia.** State lives in module-level composables, matching the convention the user
  already runs in `ytmp3` (`src/composables/useDownloads.ts`). Two stores do not justify a
  dependency; add it later if state grows.
- **No SAF folder picker**, no `DocumentFile` enumeration, no custom library folder.
- **No `media3-ui`, `media3-datasource`, `media3-decoder-ffmpeg`, `media3-exoplayer-av1`.**
- **No hand-rolled `MediaStyle` notification.** Media3 owns the notification.
- **No TypeScript.** No `tsconfig*.json`, no `vue-tsc`, no `.ts` files. JSDoc comments only.
- **No modification of any file under `/mnt/X/myProjects/ytmp3`.**
- **No `abiFilters` / `useLegacyPackaging`** — those existed in `ytmp3` solely for the
  yt-dlp native payload, which this app does not have.
- **No polling loop for playback position** in the Vue layer; position comes from
  `playerState` events plus a snapshot on resume.

---

## TODOs

### Phase 0 — safety net and QA infrastructure

- [x] **T1: `git init`, first commit, and the QA emulator**
  - Files: `/mnt/X/myProjects/kefi-player/.git/` (new), no source changes.
  - Steps:
    1. `git init && git add -A && git commit -m "Scaffold: Vue 3 + Vite (JS) + Capacitor 8 Android"`.
       `.gitignore` already excludes `node_modules`, `dist`, `android/app/build`, keystores.
    2. Install command-line tools (absent on this machine):
       `curl -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip`
       `mkdir -p ~/Android/Sdk/cmdline-tools && unzip -q /tmp/cmdline-tools.zip -d /tmp/clt && mv /tmp/clt/cmdline-tools ~/Android/Sdk/cmdline-tools/latest`
    3. `yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses`
       `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "system-images;android-36;google_apis;x86_64"`
       (`google_apis_playstore` has **no** API 36 image — do not try it.)
    4. `~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n Kefi_API_36 -k "system-images;android-36;google_apis;x86_64" -d pixel_7 --force`
    5. Boot with the constant command above and `$EMU wait-for-device`.
  - Fallback: if step 2 or 3 fails, record the failure verbatim in a new
    "## Recorded observations" section of this plan file, set `AVD = Pixel_7_API_35`, and
    continue. Do **not** stop the plan for it.
  - QA Scenario: `git log --oneline | wc -l` prints `1`; `git status --porcelain` prints
    nothing; `$EMU shell getprop ro.build.version.sdk` prints `36` (or `35` with the fallback
    recorded).

- [x] **T2: Deterministic audio fixtures + a working MediaStore indexing method**
  - Files: NEW `scripts/qa-fixtures.sh`.
  - The app has no downloader, so library content must be manufactured. `ffmpeg 6.1.1` is
    installed; generate the three fixtures from the constants table:
    `ffmpeg -f lavfi -i "sine=frequency=440:duration=180" -metadata title="Άσπρη Μέρα" -metadata artist="Δήμητρα Παπαδοπούλου" -metadata album="Κέφι Vol 1" -metadata track="1" -id3v2_version 3 -codec:a libmp3lame -b:a 128k /tmp/kefi-qa/f1.mp3`
    (frequencies 440/523/659 Hz and durations 180/210/240s keep the three distinguishable). If
    `libmp3lame` is unavailable, fall back to `-c:a aac` with `.m4a` and record it.
    For `f1.mp3` attach cover art generated with
    `ffmpeg -f lavfi -i color=c=orange:s=600x600:d=1 -frames:v 1 /tmp/kefi-qa/cover.png`
    and map it with `-disposition:v attached_pic`.
  - Push and index, trying in order and **recording which worked**:
    1. `$EMU push /tmp/kefi-qa/. /sdcard/Music/kefi-qa/`
    2. `$EMU shell content call --uri content://media --method scan_file --arg /sdcard/Music/kefi-qa/f1.mp3` (repeat per file)
    3. `$EMU shell content call --uri content://media --method scan_volume --arg external_primary`
    4. `$EMU shell cmd jobscheduler run -f com.android.providers.media.module -201`
  - QA Scenario:
    `$EMU shell content query --uri content://media/external/audio/media --projection title:artist:album:duration --where "is_music=1 AND album LIKE '%efi%' OR album LIKE '%έφι%'"`
    returns exactly 3 rows whose titles are `Άσπρη Μέρα`, `Zeibekiko`, `Ωδή` with durations
    within 1500ms of 180000/210000/240000. The working method is written into "## Recorded
    observations" in this file.

### Phase A — native playback core

- [x] **T3: Media3 dependencies only**
  - Files: `android/variables.gradle` (add `media3Version = '1.11.0'` after the last entry,
    `cordovaAndroidVersion`), `android/app/build.gradle` (dependencies block, next to
    `implementation project(':capacitor-android')`).
  - Exactly two lines:
    `implementation "androidx.media3:media3-exoplayer:$media3Version"` and
    `implementation "androidx.media3:media3-session:$media3Version"`. No other artifact.
  - QA Scenario: `npm run apk` prints `BUILD SUCCESSFUL`; APK grows by **0.3MB–4MB** over the
    4.3MB baseline (`stat -c%s android/app/build/outputs/apk/debug/app-debug.apk`);
    `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c '^.*lib/'` prints
    `0` (Media3 ships no native libraries — a non-zero count means a forbidden artifact was
    added). Commit alone.

- [x] **T4: `App.kt` application-scoped singleton**
  - Files: NEW `android/app/src/main/java/com/kefi/player/App.kt`;
    `android/app/src/main/AndroidManifest.xml` (add `android:name=".App"` to `<application>`).
  - Pattern: `/mnt/X/myProjects/ytmp3/android/app/src/main/java/com/ytmp3/app/App.kt:13-29`
    — `lateinit var instance` set in `onCreate`, `val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
    Do **not** copy its `ensureNativeToolsReady()` (yt-dlp specific).
  - QA Scenario: `cd android && JAVA_HOME=... ANDROID_HOME=... ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`;
    install and launch, then `$EMU logcat -d | grep -c "KefiApp onCreate"` is `>= 1` (add that
    one `Log.i` line).

- [x] **T5: `PlaybackService.kt` + manifest, one hardcoded fixture track**
  - Files: NEW `android/app/src/main/java/com/kefi/player/PlaybackService.kt`;
    `android/app/src/main/AndroidManifest.xml`; `android/app/src/main/res/values/strings.xml`
    (add `playback_channel_name`).
  - Build the player exactly as:
    `ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()`,
    one `MediaSession.Builder(this, player).build()`, returned from `onGetSession`; release
    player then session in `onDestroy`.
    Set a `DefaultMediaNotificationProvider.Builder(this).setChannelId("playback").setChannelName(R.string.playback_channel_name).build()` via `setMediaNotificationProvider(...)`.
    **Do not override `onTaskRemoved()`** — see Findings.
  - Manifest additions: the five permissions from Must Have #4; the `<service>` with
    `android:name=".PlaybackService"`, `android:exported="true"`,
    `android:foregroundServiceType="mediaPlayback"` and the
    `androidx.media3.session.MediaSessionService` action; and
    `<receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true">`
    with the `android.intent.action.MEDIA_BUTTON` action.
  - Temporarily hardcode a `MediaItem` built from the `f1.mp3` content URI resolved with
    `$EMU shell content query --uri content://media/external/audio/media --projection _id:title --where "title='Άσπρη Μέρα'"`, started by a debug entry point removed in T7.
  - QA Scenario, all on `$EMU`:
    1. `shell dumpsys activity services com.kefi.player | grep -A5 PlaybackService` shows the
       service running with `foregroundServiceType` including `mediaPlayback`.
    2. `shell dumpsys media_session | grep -E "state=|package=com.kefi.player"` shows `state=3`.
    3. `shell dumpsys audio | grep "u/pid:.*/$($EMU shell pidof com.kefi.player)"` contains
       `state:started`, and `shell dumpsys media.audio_flinger` shows an output MIXER thread
       with `Standby: no`.
    4. `logcat -d | grep -icE "ForegroundServiceStartNotAllowed|MissingForegroundServiceType|SecurityException"` prints `0`.
    5. `shell dumpsys notification --noredact | grep -A25 "pkg=com.kefi.player"` shows
       `channel=playback` and `android.template=...MediaStyle`.

- [x] **T6: `PlayerController.kt` application-scoped `MediaController`**
  - Files: NEW `android/app/src/main/java/com/kefi/player/PlayerController.kt`.
  - `@Volatile` + `Mutex` double-checked async init mirroring the *shape* of
    `/mnt/X/myProjects/ytmp3/android/app/src/main/java/com/ytmp3/app/App.kt:31-52`; reuse
    `App.instance` / `App.scope`, never a new scope. Build with
    `MediaController.Builder(App.instance, SessionToken(App.instance, ComponentName(App.instance, PlaybackService::class.java))).buildAsync()`.
    Expose `snapshot()`, `play/pause/resume/seek/next/previous/setQueue/setShuffle/setRepeatMode`.
    One `Player.Listener` re-broadcasting to a settable callback. **All controller access on
    `Dispatchers.Main`.** Document in a header comment that it may only be first touched from
    a user-gesture-originated plugin call.
  - QA Scenario: compile gate — `./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.

- [x] **T7: `PlayerPlugin.kt` + JS bridge**
  - Files: NEW `android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`;
    `android/app/src/main/java/com/kefi/player/MainActivity.java` (add
    `registerPlugin(PlayerPlugin.class);` before `super.onCreate(...)`);
    NEW `src/plugins/player.js`, NEW `src/plugins/player.web.js`.
  - Patterns: `@CapacitorPlugin(name = "Player")` as
    `/mnt/X/myProjects/ytmp3/android/app/src/main/java/com/ytmp3/app/YtdlpPlugin.kt:28`;
    registration call site as
    `/mnt/X/myProjects/ytmp3/android/app/src/main/java/com/ytmp3/app/MainActivity.java:10-17`;
    resolve/reject idiom as `YtdlpPlugin.kt:69-93`; the `rejectWith` Throwable bridge at
    `YtdlpPlugin.kt:444-449`; `notifyListeners` push as `YtdlpPlugin.kt:161-167`;
    `load()`/`handleOnDestroy()` hooks as `YtdlpPlugin.kt:33-43`.
    JS side follows `/mnt/X/myProjects/ytmp3/src/plugins/ytdlp.ts:78-148` **converted to plain
    JS with JSDoc** — `registerPlugin('Player', { web: () => import('./player.web').then(m => new m.PlayerWeb()) })`;
    the web stub follows `/mnt/X/myProjects/ytmp3/src/plugins/ytdlp.web.ts:15-126` so
    `npm run dev` works in a desktop browser.
  - Methods in this task: `getPlayerState`, `playTrack({ uri })`, `pause`, `resume`,
    `seek({ positionMs })`. `getPlayerState` must read **live** off the controller
    (`currentMediaItem`, `currentPosition`, `duration`, `isPlaying`, `shuffle`, `repeatMode`)
    — never a cached value. Remove T5's debug entry point.
  - QA Scenario: `$EMU logcat -c`, launch, call `playTrack` with the `f1` URI from the web
    layer, then `$EMU logcat -d | grep -oE '"isPlaying":(true|false)'` shows `"isPlaying":true`
    and two snapshots >= 3s apart show `currentPosition` increasing by 2500-5000ms.
    (NOT `grep -oE '\{"isPlaying":[^}]*\}'` — `isPlaying` is the 7th key in T6's snapshot
    contract, not the first, so that anchored pattern can never match. Found in T7.)

- [x] **T8: GATE — state rehydration across WebView destruction**
  - Files: NEW `src/composables/usePlayer.js`; `src/App.vue` (temporary read-out).
  - Pattern: module-level refs and `readonly(...)` exposure as
    `/mnt/X/myProjects/ytmp3/src/composables/useDownloads.ts:30-45` and `:246-268`;
    attach-listeners-once guard as `:55` and `:76-115`; error normalisation helper as `:65-69`.
    Must implement **both** push (subscribe `playerState`) and pull
    (`getPlayerState()` on mount and on every `visibilitychange` → visible). Never render from
    a cached value after resume.
  - QA Scenario (all scripted, all must pass):
    1. **Renderer kill** (replaces the original `always_finish_activities` version, which is a
       NO-OP on API 36 — the ActivityRecord survives HOME with the setting on, verified twice).
       The WebView renderer runs in its own sandboxed process, so killing it destroys the entire
       JS heap while the service plays on — a strictly harsher and more realistic test:
       play; `RPID=$($EMU shell ps -A | grep sandboxed_process0 | awk '{print $2}')`;
       `$EMU shell kill -9 $RPID`; sleep 12. Assert: the app PID is UNCHANGED, `state=3` holds with
       the position still advancing, `logcat` contains `renderer gone, recovering` and ZERO
       `killing application`, and the rebuilt WebView repopulates from a fresh `getPlayerState()`
       within 3000ms of the `dumpsys` position.
    2. `input keyevent KEYCODE_APP_SWITCH`, dismiss the task; assert no crash in logcat and
       that playback state follows Media3's default `onTaskRemoved` behaviour (still playing
       while active).
    3. Capture P1, `input keyevent KEYCODE_POWER`, sleep 15, capture P2: `state=3` throughout
       and `P2 - P1 >= 12000`.
  - **This task gates everything after it.** If it fails, stop and redesign the state flow;
    do not continue to T9.

- [x] **T9: `TrackSource.kt`, `READ_MEDIA_AUDIO`, `listTracks`, `getArtwork`**
  - Files: NEW `android/app/src/main/java/com/kefi/player/TrackSource.kt`;
    `PlayerPlugin.kt`; `src/plugins/player.js`; `AndroidManifest.xml` (permissions added in T5
    are already present — verify only).
  - Query `MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)` (API 29+) /
    `EXTERNAL_CONTENT_URI` (24-28), selection `IS_MUSIC = 1 AND IS_RINGTONE = 0 AND IS_ALARM = 0 AND IS_NOTIFICATION = 0`,
    projecting `_ID, DISPLAY_NAME, TITLE, ARTIST, ALBUM, ALBUM_ARTIST, ALBUM_ID, TRACK, YEAR, DURATION, SIZE`.
    Build each playable URI with `ContentUris.withAppendedId(collection, id)`.
  - Permissions: `@CapacitorPlugin(name = "Player", permissions = [Permission(alias = "audio", strings = [Manifest.permission.READ_MEDIA_AUDIO])])`,
    `requestPermissionForAlias("audio", call, "audioPermissionCallback")`, and
    `@PermissionCallback private fun audioPermissionCallback(call: PluginCall)`. On API < 33
    request `READ_EXTERNAL_STORAGE` instead. `listTracks` must reject with a message
    containing `permission` when the permission is absent. **No pattern exists in `ytmp3` for
    this — follow `https://capacitorjs.com/docs/plugins/android`.**
  - `getArtwork({ trackId })` returns `{ dataUrl }` or `{ dataUrl: null }`: `ContentResolver.loadThumbnail`
    on API 29+, `MediaMetadataRetriever.getEmbeddedPicture()` (in a `use`-style block, on
    `Dispatchers.IO`) on 24-28; JPEG-compress to 256×256, base64-encode, and memoise in an
    in-memory LRU of at most 64 entries.
  - QA Scenario:
    1. `$EMU shell pm revoke com.kefi.player android.permission.READ_MEDIA_AUDIO`; `listTracks`
       rejects with a message matching `permission`.
    2. `$EMU shell pm grant com.kefi.player android.permission.READ_MEDIA_AUDIO`; `listTracks`
       resolves with the 3 fixtures present, each with non-empty `title` and `durationMs > 0`,
       and `Zeibekiko` carrying `artist = "Nikos Alexiou"`, `album = "Kefi Vol 1"`.
    3. `getArtwork` for `Άσπρη Μέρα` returns a `dataUrl` starting `data:image/jpeg;base64,`;
       for `Zeibekiko` it returns `null`.

- [x] **T10: Queue, next/previous, shuffle, repeat**
  - Files: `PlayerController.kt`, `PlayerPlugin.kt`, `src/plugins/player.js`.
  - `setQueue({ uris, startIndex })` → `controller.setMediaItems(items, startIndex, 0L)`;
    `next()`, `previous()`; `setShuffle({ enabled })` → `shuffleModeEnabled`;
    `setRepeatMode({ mode })` accepting `off|one|all` → `REPEAT_MODE_OFF|ONE|ALL`.
    `getPlayerState` reports both, plus `queueLength` and `queueIndex`.
  - QA Scenario: queue all three fixtures with `startIndex: 0`; `dumpsys media_session` shows
    `Άσπρη Μέρα`; `next()` → `Zeibekiko`; `previous()` → `Άσπρη Μέρα`; `setRepeatMode({mode:'one'})`
    then `getPlayerState` reports `repeatMode: "one"`; logcat has zero exceptions.

- [x] **T11: Unavailable-track degradation**
  - Files: `PlayerController.kt` (`Player.Listener.onPlayerError`), `PlayerPlugin.kt`,
    `src/composables/usePlayer.js`.
  - Catch `PlaybackException` whose cause is `FileNotFoundException`/`SecurityException`, skip
    to the next queue item, and emit `trackUnavailable { uri, reason }`. The queue must never
    stall silently.
  - QA Scenario: queue f1 then f2, start f1, `$EMU shell rm /sdcard/Music/kefi-qa/f1.mp3` and
    re-scan; restart playback of the queue; a `trackUnavailable` event arrives within 10s and
    `dumpsys media_session` shows `Zeibekiko` playing. Restore the fixture afterwards via
    `scripts/qa-fixtures.sh`.

- [x] **T12: Session persistence (queue, index, position, shuffle, repeat)**
  - Files: NEW `android/app/src/main/java/com/kefi/player/PlaybackStateStore.kt`;
    `PlaybackService.kt`; `PlayerController.kt`.
  - `context.getSharedPreferences("kefi.player", MODE_PRIVATE)` with a `player.` key prefix,
    mirroring the single-prefs-file convention of
    `/mnt/X/myProjects/ytmp3/android/app/src/main/java/com/ytmp3/app/StorageLocation.kt:40-83`.
    Keys: `player.queue` (JSON array via `org.json.JSONArray`, no new dependency),
    `player.index`, `player.positionMs`, `player.shuffle`, `player.repeat`, `player.savedAt`.
    Writes: immediately on `onMediaItemTransition` / `onIsPlayingChanged`; position at most
    every 5s while playing.
  - Restore at the end of `PlaybackService.onCreate()`: validate each URI, drop unresolvable
    ones, `player.setMediaItems(items, index, positionMs)` then `player.prepare()` —
    **never `play()`**. Audio without a user gesture is hostile and an API 34+ background
    start would be refused anyway. If every stored URI is unresolvable, clear the state.
  - QA Scenario: queue the three fixtures, play index 1 for 20s, capture IDX1/POS1;
    `$EMU shell am force-stop com.kefi.player`; relaunch; wait 10s; `getPlayerState` reports
    the same track, `positionMs` within 6000ms of POS1, and `isPlaying: false`; pressing play
    reaches `state=3` and the position advances from ~POS1, not from 0.

> **Execution checkpoint.** At the end of T12 the app is a working background music player.
> If execution has to stop, this is the clean place to stop: commit, and Phase B resumes from
> a green state.

### Phase B — the shell

- [x] **T13: Router, tab bar, back button**
  - Files: `package.json` (add `vue-router`, `@capacitor/app`, `@capacitor/preferences@^8.0.1`);
    NEW `src/router/index.js`, `src/views/LibraryView.vue`, `src/views/NowPlayingView.vue`,
    `src/views/SettingsView.vue`, `src/components/TabBar.vue`; rewrite `src/App.vue` as the
    shell (`<RouterView>` + `<MiniPlayer>` + `<TabBar>`).
  - `createWebHashHistory()` — Capacitor serves from a local origin where path history can 404
    on reload. Routes: `/library` (default), `/now-playing`, `/settings`. Wire
    `CapacitorApp.addListener('backButton', ...)` to `router.back()`, calling
    `CapacitorApp.exitApp()` only at `/library`.
  - Styling uses the tokens already defined in `src/style.css` (`--surface-1`, `--border`,
    `--accent`, `--text-muted`, `--safe-bottom`), in the style of
    `/mnt/X/myProjects/ytmp3/src/components/JobCard.vue:87-225`.
  - QA Scenario: `npm run build` succeeds; navigate `/library → /settings → /now-playing`,
    `$EMU exec-out screencap -p > /tmp/kefi-<route>.png` for each; from `/now-playing`,
    `input keyevent KEYCODE_BACK` returns to the previous route with
    `dumpsys activity activities | grep mResumedActivity` still naming `com.kefi.player`; at
    `/library`, `KEYCODE_BACK` backgrounds the app.

- [x] **T14: Bilingual UI (el/en)**
  - Files: NEW `src/i18n/index.js`, `src/i18n/el.js`, `src/i18n/en.js`;
    `src/views/SettingsView.vue`; `src/main.js`.
  - A hand-rolled composable (`useI18n()` exposing `t(key)` and `setLocale(code)`) over two
    flat dictionaries — **no i18n library**. Initial locale: stored preference, else
    `navigator.language.startsWith('el') ? 'el' : 'en'`. Persist via `@capacitor/preferences`
    under key `kefi.locale`. Every user-facing string in every view goes through `t()`.
  - QA Scenario: `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("`
    returns no hard-coded UI strings; switching language in Settings re-renders the Library tab
    label between `Library` and `Βιβλιοθήκη` without reload; relaunching the app keeps the
    chosen language.

- [x] **T15: Songs list with search and Greek collation**
  - Files: `src/views/LibraryView.vue`; NEW `src/components/TrackRow.vue`; NEW
    `src/utils/format.js` (port `formatDuration` from
    `/mnt/X/myProjects/ytmp3/src/utils/format.ts:1-8` to plain JS); NEW
    `src/composables/useLibrary.js`.
  - `useLibrary.js` calls `listTracks` once per mount-with-permission, holds the result at
    module level, and exposes sorted/filtered computeds. **Every sort and every search compare
    uses `Intl.Collator('el', { sensitivity: 'base' })`** — default JS sort mishandles tonos
    and dialytika, which a Greek library exposes immediately. Tapping a row calls `setQueue`
    with the full visible list and `startIndex` at the tapped row.
  - QA Scenario: the list shows exactly 3 rows; sorting Songs by title puts `Άσπρη Μέρα`
    before `Ωδή` before `Zeibekiko` under the Greek collator; typing `ωδη` (no tonos, lower
    case) narrows to exactly one row (`Ωδή`) — this is the assertion that proves the collator
    is actually wired; tapping row 2 makes `dumpsys media_session` report `Zeibekiko` with
    `state=3`.

- [x] **T16: Albums and Artists groupings with artwork**
  - Files: `src/views/LibraryView.vue` (segmented control Songs | Albums | Artists); NEW
    `src/components/AlbumGrid.vue`, `src/components/ArtistList.vue`.
  - Pure in-memory `groupBy` over the same `listTracks` result — **no second native call**.
    Album cells lazily call `getArtwork` for their first track; a null result renders a
    token-coloured placeholder built from `--surface-2` and `--accent-soft`, never a broken
    image.
  - QA Scenario: Albums shows exactly 2 cells (`Κέφι Vol 1`, `Κέφι Vol 2`); Artists shows
    exactly 2 rows, with `Δήμητρα Παπαδοπούλου` reporting 2 tracks; the `Κέφι Vol 1` cell
    renders the orange embedded cover while `Kefi Vol 1` renders the placeholder;
    `$EMU exec-out screencap -p > /tmp/kefi-albums.png` for the record.

- [ ] **T17: Persistent mini-player**
  - Files: NEW `src/components/MiniPlayer.vue`; `src/App.vue`.
  - Mounted **outside** `<RouterView>`, above the tab bar. Artwork, title, artist, play/pause,
    thin progress line. Hidden entirely when there is no current track. Tapping routes to
    `/now-playing`. Position comes from `playerState` events and the resume snapshot — no new
    polling loop.
  - QA Scenario: start playback, navigate `/library → /settings → /library`; `state=3` holds
    throughout and position increases monotonically (proving no remount); before any playback
    the mini-player is absent from the DOM.

- [ ] **T18: Now Playing screen**
  - Files: `src/views/NowPlayingView.vue`.
  - Large artwork (or placeholder), title/artist/album, seek bar bound to
    `positionMs`/`durationMs`, play/pause, next/previous, shuffle toggle, repeat cycling
    off → all → one. Every control renders from `getPlayerState`, never local component
    state, so it survives resume.
  - QA Scenario: drag the seek bar to the midpoint of `Ωδή` — position lands within 3000ms of
    120000ms and `state=3` resumes; `next`/`previous` change the `dumpsys media_session` title in
    both directions; toggling shuffle then re-reading `getPlayerState` reports
    `shuffle: true`; killing and relaunching the app (T12) leaves the repeat/shuffle toggles
    rendering the restored values.

- [ ] **T19: Empty, loading and permission states**
  - Files: `src/views/LibraryView.vue`.
  - Three distinct states: permission not yet requested (button calling
    `requestAudioPermission()`), permission denied (guidance plus a button opening system
    settings), granted-but-no-audio-found. All strings through `t()`.
  - QA Scenario: `$EMU shell pm revoke com.kefi.player android.permission.READ_MEDIA_AUDIO`
    and relaunch → Library renders the permission state, not an empty list; tapping the button
    and granting populates the list **without an app restart**; with permission granted and
    `$EMU shell rm -r /sdcard/Music/kefi-qa` plus a re-scan, the empty state renders.

- [ ] **T20: `scripts/qa-playback.sh`**
  - Files: NEW `scripts/qa-playback.sh` (executable); `package.json` (add `"qa": "bash scripts/qa-playback.sh"`).
  - Takes a serial (default `emulator-5554`), runs every scripted assertion from T5, T8, T10,
    T12 and prints one `PASS`/`FAIL` line each, exiting non-zero on any failure. Includes the
    media-button routing pre-check: `dumpsys media_session | grep "Media button session"` must
    name `com.kefi.player`, and the script fails loudly (naming the offending package) rather
    than silently testing the wrong app.
  - QA Scenario: `npm run qa` exits `0` with every line `PASS` on a booted emulator with the
    app installed and the fixtures present.

---

## Final Verification Wave

- [ ] **F1: Build integrity** — `npm run build` succeeds; `npm run apk` prints
  `BUILD SUCCESSFUL`; `cd android && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` prints `0`.
- [ ] **F2: Release integrity** — `npm run apk:release` prints `BUILD SUCCESSFUL`. No
  `android/keystore.properties` exists in this project today, so `hasKeystore` is false in
  `android/app/build.gradle` and the expected artifact is
  `android/app/build/outputs/apk/release/app-release-unsigned.apk` — assert that file exists.
  Only if a keystore has been created (`npm run keystore`) does the signed path apply, in
  which case `~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs -v <apk>` must
  report `Verified using v2 scheme (APK Signature Scheme v2): true`. Do **not** create a
  keystore as part of this plan — it is a one-time user decision with a password.
- [ ] **F3: Scripted QA suite** — `npm run qa` exits `0`; every T-task QA scenario re-run
  green on a fresh install (`$EMU install -r`), fixtures re-created by `scripts/qa-fixtures.sh`.
- [ ] **F4: Scope review of the complete diff** — `git diff --stat` reviewed against Must Have
  and Must NOT Have;
  `grep -rniE "room|sqlite|pinia|equalizer|audiofx|crossfade|sleeptimer|MediaLibraryService" src/ android/app/src/main/java/ package.json`
  returns no matches; `find . -name "*.ts" -not -path "./node_modules/*" -not -path "./android/*" | wc -l` prints `0`;
  `grep -c "media3-ui\|media3-datasource\|decoder-ffmpeg\|exoplayer-av1" android/app/build.gradle` prints `0`;
  `git status --porcelain` is empty (everything committed).
- [ ] **F5: Hands-on QA — BLOCKED-UNTIL-DEVICE.** The four items proven unscriptable:
  audibility; real Bluetooth A2DP/AVRCP routing plus play/pause/next from the headset;
  lock-screen control appearance; wired-headset unplug pausing rather than blasting the
  speaker. Run `adb -s <serial> shell dumpsys media_session | grep state=` before and after
  each. If no device is connected, F5 is reported as **not run**, never as passed.
- [ ] **F6: Documentation** — `README.md` gains a "What the app does" section, the
  `npm run qa` / `npm run fixtures` commands, the permission model, and the roadmap
  (playlists+favourites on Room, sleep timer + playback speed, widget) so the next plan starts
  from a written baseline.

## Success Criteria

Every line below is a command with an expected output and needs no human intervention
(except F5, which is explicitly device-gated).

1. `cd /mnt/X/myProjects/kefi-player && git status --porcelain | wc -l` → `0`
2. `npm run apk 2>&1 | grep -c "BUILD SUCCESSFUL"` → `1`
3. `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` → `0`
4. `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c 'lib/'` → `0`
5. `adb -s emulator-5554 shell dumpsys activity services com.kefi.player | grep -c mediaPlayback` → `>= 1`
6. Screen-off advance: `P2 - P1 >= 12000` ms across a 15s window with `state=3` throughout.
7. Rehydration: after `always_finish_activities 1` + HOME + 10s + relaunch, the WebView shows
   a non-null track whose position is within 3000ms of `dumpsys media_session`.
8. Persistence: after `am force-stop` + relaunch, `getPlayerState` reports the same track,
   position within 6000ms of the saved value, and `isPlaying: false`.
9. `listTracks` returns the 3 fixtures; Albums groups to 2; Artists groups to 2.
10. Greek collation: searching `ωδη` returns exactly 1 row (`Ωδή`).
11. Permission: `pm revoke READ_MEDIA_AUDIO` + relaunch renders the permission state and
    `listTracks` rejects with a message matching `permission`.
12. Degradation: deleting the playing file emits `trackUnavailable` within 10s and playback
    continues with the next queue item.
13. `npm run qa` exits `0`.
14. `grep -rniE "room|sqlite|pinia|equalizer|audiofx|MediaLibraryService" src/ android/app/src/main/java/ package.json | wc -l` → `0`
15. `find . -name "*.ts" -not -path "./node_modules/*" -not -path "./android/*" | wc -l` → `0`

## Recorded observations

### T2 — deterministic audio fixtures + MediaStore indexing (2026-08-19)
- **Codec used**: `libmp3lame` (available; no AAC/`.m4a` fallback needed). All three fixtures
  are `.mp3`, `-id3v2_version 3`, `-codec:a libmp3lame -b:a 128k`.
- **Scan method that worked**: the FIRST method in the ladder —
  `adb -s emulator-5554 shell content call --uri content://media --method scan_file --arg /sdcard/Music/kefi-qa/f1.mp3`
  (repeated once per file: f1.mp3, f2.mp3, f3.mp3). `scan_volume` and the `jobscheduler`
  fallback were never needed.
- **Observed durations** (from `content query`, ms): Άσπρη Μέρα = 5042, Zeibekiko = 7027,
  Ωδή = 9038 — all within 500ms of the nominal 5000/7000/9000.
- **Corrected (2026-08-19, post-T5 verification)**: the 5/7/9s durations above made T8's
  ">= 12000ms position advance across a 15s screen-off window" and T12's "play 20s then
  force-stop" assertions physically impossible — the track ended and the session dropped
  to `STOPPED` before the timed wait completed. Fixtures were regenerated at 180s/210s/240s
  (frequencies 440/523/659 Hz, all other metadata unchanged). New observed durations (ms):
  Άσπρη Μέρα = 180036, Zeibekiko = 210051, Ωδή = 240039 — all within 1500ms of the nominal
  180000/210000/240000. `scripts/qa-fixtures.sh` verified idempotent again at the new
  durations (two consecutive runs, still exactly 3 rows, no duplicates). MediaStore `_id`
  values were unchanged by the re-index (23/24/25, updated in place, not re-inserted); see
  `learnings.md` for full detail.
- **`content query` projection gotcha**: the plan's original example projection
  `--projection title,artist,album,duration` (comma-separated) throws
  `java.lang.IllegalArgumentException: Invalid column title,artist,album,duration` on this
  API 36 `content` CLI. The correct separator per `adb shell content query --help` is a
  **colon**: `--projection title:artist:album:duration`. `scripts/qa-fixtures.sh` and all
  verification in this task use the colon form.
- Greek tags (`Άσπρη Μέρα`, `Δήμητρα Παπαδοπούλου`, `Κέφι Vol 1`, `Ωδή`, `Κέφι Vol 2`) round-tripped
  correctly through ffmpeg → ID3v2.3 → MediaStore with no mojibake, confirmed via both
  `ffprobe -show_entries format_tags=...` on the local file and `content query` on the device.
