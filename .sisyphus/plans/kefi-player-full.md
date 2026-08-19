# Plan: Kefi Player — standalone Android music player

## Context

### What exists

The project at `/mnt/X/myProjects/kefi-player` is a **clean shell**: Vue 3 + Vite web
layer wrapped by Capacitor 8, with **zero application logic**. Plain JavaScript (not
TypeScript) — deliberate.

**Web layer** (all placeholder):
- `src/App.vue` (66 lines) — shows platform/runtime info, nothing else
- `src/main.js` (5 lines) — `createApp(App).mount('#app')`
- `src/style.css` (117 lines) — full design token system: warm amber/coral palette,
  dark+light mode, Inter+Roboto bundled, safe-area insets
- `src/{components,composables,plugins,utils}/` — ALL EMPTY
- `vite.config.js` (7 lines) — bare vue plugin
- `jsconfig.json` (14 lines) — ESNext, `@/*` path alias
- `index.html` (14 lines) — viewport-fit=cover

**Android layer** (bare Capacitor scaffold):
- `MainActivity.java` (5 lines) — extends `BridgeActivity`, no plugin registration
- `AndroidManifest.xml` (41 lines) — one activity, `INTERNET` permission only
- `android/build.gradle` (31 lines) — AGP 8.13.0, Kotlin 2.2.10
- `android/app/build.gradle` (108 lines) — release signing with early-fail,
  `jvmTarget = '21'`, namespace `com.kefi.player`
- `android/variables.gradle` (16 lines) — compileSdk/targetSdk 36, minSdk 24
- `capacitor.config.json` — appId `com.kefi.player`
- **No Kotlin source files exist**

**Build scripts**: `scripts/build-apk.sh`, `make-keystore.sh`, `fetch-fonts.py`,
`make-icons.py`

**Dependencies**: `@capacitor/android ^8.5.0`, `@capacitor/core ^8.5.0`, `vue ^3.5.40`

### What this plan builds

A full-featured standalone music player that:
1. Scans device audio via `READ_MEDIA_AUDIO` + optional SAF folder
2. Plays in the background with Media3 ExoPlayer + MediaSessionService
3. Shows Library (Songs/Albums/Artists), Now Playing, and a persistent mini-player
4. Handles audio focus, media buttons, lock-screen controls, notification
5. Persists queue/position across process death via SharedPreferences
6. Degrades gracefully on unplayable tracks and SAF revocation

### Specs adapted from

- `ytmp3/.claude/plans/native-music-player-milestone-1.md` (playback core)
- `ytmp3/.claude/plans/music-player-shell-milestone-2.md` (UI shell)
- `ytmp3/.claude/plans/deferred-cuts-proposed-solutions.md` (WP1: queue persistence,
  WP5: scripted QA)

---

## Decisions

1. **Plain JavaScript** — all plugin wrappers are `.js`, no vue-tsc, no type annotations.
   The sibling project ytmp3 is TypeScript; this one is not.
2. **No downloader** — no YtdlpPlugin, no DownloadService, no SAF folder picker for
   downloads, no `/downloader` route.
3. **Package**: `com.kefi.player` everywhere.
4. **No existing code to protect** — everything built from scratch, no frozen-set checks.
5. **No jniLibs concerns** — no native payloads beyond Media3.
6. **SAF folder**: optional "pick a folder to scan" for additional audio sources, simpler
   than ytmp3's download-destination picker.
7. **git init as first task** — the project has `.gitignore` but no `.git`.
8. **App.kt singleton** needed for application scope (PlayerController pattern). Built from
   scratch following ytmp3's `App.kt`: Application subclass, `instance`/`scope`,
   `ensureReady`-style pattern (but without yt-dlp/FFmpeg init).
9. **JS plugin wrapper**: Capacitor `registerPlugin` pattern in plain JS — export the plugin
   object + `addListener` overloads + web fallback class.
10. **Pinia from the start** — greenfield, no migration needed.
11. **vue-router with `createWebHashHistory`** — hash history avoids 404 on WebView reload.
12. **SharedPreferences for queue persistence** — not Room, not a database. Four keys with
    `player.` prefix in a `kefi.settings` prefs file.
13. **Media3 >= 1.11.0** — pinned via `media3Version` in `variables.gradle`. 1.11.0
    specifically patched `ForegroundServiceStartNotAllowedException` in
    `MediaNotificationManager`.
14. **No database** — no Room, no SQLite, no SharedPreferences-backed library index.
    Playlists, favourites, Room are future work explicitly excluded.
15. **Test infrastructure**: none exists. No test runner. QA is agent-executed via
    `$EMU` adb commands, `dumpsys`, `logcat`, and Vite build verification.

---

## Fixed QA constants

Used by every scenario — no placeholders anywhere in the plan.

- `EMU` = `adb -s emulator-5554` (Pixel_7_API_35 AVD, API 35).
  Boot with:
  `cd /home/nonos/Android/Sdk/emulator && ANDROID_HOME=/home/nonos/Android/Sdk ./emulator -avd Pixel_7_API_35 -no-window -no-boot-anim -gpu swiftshader_indirect &`
- Build env for every Gradle command:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`
- `npm run apk` is the debug build command (runs `scripts/build-apk.sh debug`).
- No physical device QA — emulator only (no connected devices in the QA environment).
- No cmdline-tools installed.
- Audio files for testing: push known MP3s via
  `$EMU push /path/to/test.mp3 /storage/emulated/0/Music/` then trigger media scan with
  `$EMU shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///storage/emulated/0/Music/test.mp3`.
  Alternatively, use `$EMU shell content insert --uri content://media/external/audio/media` — but `adb push` + broadcast is simpler for test seeding.

---

## Must Have

1. `androidx.media3:media3-exoplayer` and `androidx.media3:media3-session` pinned at
   `1.11.0` via a `media3Version` constant in `android/variables.gradle`.
2. `App.kt` — Application subclass with `instance`, `scope`
   (`CoroutineScope(SupervisorJob() + Dispatchers.IO)`), registered in
   `AndroidManifest.xml` via `android:name=".App"`.
3. `PlaybackService.kt` — a `MediaSessionService` owning one `ExoPlayer` + one
   `MediaSession`, with `setAudioAttributes(..., handleAudioFocus = true)`,
   `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_NETWORK)`, and its own
   notification channel `playback`.
   `onTaskRemoved()` must keep playing if playback is active and call `stopSelf()` only
   when it is not.
4. Manifest: the service declared with `android:foregroundServiceType="mediaPlayback"`,
   `android:exported="true"`, and the
   `androidx.media3.session.MediaSessionService` intent-filter; plus
   `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, and
   `READ_MEDIA_AUDIO` permissions; `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`.
5. `PlayerController.kt` — application-scoped singleton owning the `MediaController`,
   binding asynchronously, surviving Activity/WebView recreation. Init ONLY from a
   user-gesture-originated plugin call.
6. `PlayerPlugin.kt` — `@CapacitorPlugin(name="Player")` exposing: `getPlayerState`,
   `setQueue`, `playTrack`, `pause`, `resume`, `next`, `previous`, `seek`, `listTracks`,
   `requestAudioPermission`, `setShuffle`, `setRepeatMode`; emitting `playerState` and
   `trackUnavailable` events.
7. `TrackSource.kt` — `MediaStore.Audio` query (`IS_MUSIC!=0`, projecting `_ID`,
   `DISPLAY_NAME`, `TITLE`, `ARTIST`, `ALBUM`, `ALBUM_ID`, `DURATION`, `SIZE`) plus
   optional SAF `DocumentFile` enumeration, de-dup on `(displayName, size, durationMs)`.
   Track carries `album`/`artist`/`albumArtist`/`artworkUri`.
8. `src/plugins/player.js` — Capacitor `registerPlugin('Player', ...)` with web fallback
   in `player.web.js`.
9. `src/composables/usePlayer.js` — push (`playerState` listener) + pull
   (`getPlayerState` on mount + `visibilitychange` resume). BOTH required.
10. vue-router with `createWebHashHistory`: `/library` (default), `/now-playing`.
11. `@capacitor/app` for Android back button: `router.back()`, `exitApp()` only at root.
12. Persistent `MiniPlayer` outside `<RouterView>`, hidden when no current track.
13. Library with Songs + Albums + Artists groupings (in-memory `groupBy`).
14. In-memory search over title/artist/album.
15. Now Playing: large artwork, title/artist/album, seek bar, play/pause, next/prev,
    shuffle, repeat (off -> all -> one).
16. Empty/permission-denied states with action buttons.
17. Album artwork from `Track.artworkUri`; placeholder for missing.
18. Pinia stores: `usePlayerStore`, `useLibraryStore`.
19. Graceful degradation: skip unplayable tracks via `onPlayerError`, emit
    `trackUnavailable`.
20. `READ_MEDIA_AUDIO` with `@PermissionCallback`, fallback `READ_EXTERNAL_STORAGE`
    `maxSdkVersion=32`.
21. `PlaybackStateStore.kt` — SharedPreferences (`kefi.settings`), `player.` key prefix.
    Persist queue (JSON array of URI strings), index, positionMs, savedAt. Debounced.
    Restore on `PlaybackService.onCreate`, validate URIs, `prepare()`, never `play()`.
22. `DefaultMediaNotificationProvider` — do NOT hand-roll notification.
23. Utility: `src/utils/format.js` — `formatDuration(ms)`.

## Must NOT Have

- **No database.** No Room, no SQLite, no `@capacitor-community/sqlite`.
- **No playlists, favourites, play counts, or queue reordering UI.**
- **No downloader.** No YtdlpPlugin, no DownloadService, no download UI.
- **No `media3-ui`, `media3-datasource`, `media3-decoder-ffmpeg`, or
  `media3-exoplayer-av1`.**
- **No hand-rolled `MediaStyle` notification.** Media3 owns the notification.
- **No `WorkManager`.** No background jobs.
- **No `MediaLibraryService` / `onPlaybackResumption()`.**
- **No streaming or remote sources.**
- **No infinite scroll or virtualised list** unless QA reveals a real perf problem.
- **No new UI framework, component library, or CSS framework.** Keep the existing
  hand-rolled CSS-token system in `src/style.css`.
- **No TypeScript.** All `.js` and `.vue` files only.
- **No sleep timer, playback speed, i18n.**
- **No rename of the app label, icon, or `applicationId`.**

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: NO (no test runner, no `test` script in `package.json`)
- **Automated tests**: NONE — no test framework
- **QA method**: Agent-executed via `$EMU` adb commands, `dumpsys`, `logcat`, Vite build

### QA Policy
Every task MUST include agent-executed QA scenarios using `$EMU` commands.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Native/Service**: `dumpsys media_session`, `dumpsys audio`, `dumpsys activity services`,
  `dumpsys notification --noredact`, `logcat`
- **UI**: `$EMU exec-out screencap -p > /tmp/kefi-{name}.png`
- **Build**: `npm run build` exit code, `npm run apk` exit code, Gradle
  `:app:compileDebugKotlin`
- **Focus/Buttons**: `adb emu gsm call`, `cmd media_session dispatch`

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation — start immediately):
├── T0: git init                                    [quick]
├── T1: Media3 dependencies only                    [quick]
├── T2: App.kt application singleton                [quick]
└── T3: Utility helpers (format.js)                 [quick]

Wave 2 (Native playback core — after Wave 1):
├── T4: PlaybackService + manifest                  [deep]
├── T5: PlayerController singleton                  [deep]
├── T6: TrackSource (device audio enumeration)      [unspecified-high]
└── T7: READ_MEDIA_AUDIO permission handling        [quick]

Wave 3 (Bridge + JS layer — after Wave 2):
├── T8: PlayerPlugin.kt + JS bridge                 [deep]
├── T9: usePlayer composable (push+pull)            [unspecified-high]
└── T10: Pinia stores skeleton                      [quick]

Wave 4 (Prove playback — after Wave 3):
├── T11: Prove playback with adb-pushed track       [deep]
├── T12: Prove state rehydration across WebView kill [deep]
└── T13: Queue, next/previous, shuffle, repeat      [unspecified-high]

Wave 5 (UI shell — after Wave 4):
├── T14: Router + shell layout + back button        [visual-engineering]
├── T15: Library view (Songs list + search)         [visual-engineering]
├── T16: Albums + Artists groupings + artwork       [visual-engineering]
└── T17: Now Playing screen                         [visual-engineering]

Wave 6 (Integration + polish — after Wave 5):
├── T18: MiniPlayer persistent bar                  [visual-engineering]
├── T19: Empty/permission-denied states             [visual-engineering]
├── T20: Unavailable-track degradation              [unspecified-high]
└── T21: Queue persistence (SharedPreferences)      [deep]

Wave 7 (Verification-only — after Wave 6):
├── T22: Audio focus + media buttons                [unspecified-high]
├── T23: Queue persistence survival (process kill)  [unspecified-high]
└── T24: Full integration smoke test                [unspecified-high]

Wave FINAL (4 parallel reviews, then user okay):
├── F1: Plan compliance audit                       [oracle]
├── F2: Code quality review                         [unspecified-high]
├── F3: Real manual QA execution                    [unspecified-high]
└── F4: Scope fidelity check                        [deep]
-> Present results -> Get explicit user okay
```

### Dependency Matrix

| Task | Depends on | Blocks |
|------|-----------|--------|
| T0   | —         | T1-T3  |
| T1   | T0        | T4-T8  |
| T2   | T0        | T4,T5  |
| T3   | T0        | T15-T17|
| T4   | T1,T2     | T5,T8,T11 |
| T5   | T2,T4     | T8,T11 |
| T6   | T1        | T8     |
| T7   | T1        | T8     |
| T8   | T4-T7     | T9-T13 |
| T9   | T8        | T11-T13,T14 |
| T10  | T9        | T14-T19 |
| T11  | T8,T9     | T12    |
| T12  | T11       | T13    |
| T13  | T12       | T14    |
| T14  | T10,T13   | T15-T19 |
| T15  | T3,T14    | T16    |
| T16  | T15       | T18    |
| T17  | T14       | T18    |
| T18  | T16,T17   | T19    |
| T19  | T14       | T22    |
| T20  | T13       | T22    |
| T21  | T13       | T23    |
| T22  | T20       | T24   |
| T23  | T21       | T24   |
| T24  | T22,T23   | F1-F4  |

### Agent Dispatch Summary

| Wave | Count | Dispatch |
|------|-------|----------|
| 1    | 4     | T0-T3: all `quick` |
| 2    | 4     | T4,T5: `deep`; T6: `unspecified-high`; T7: `quick` |
| 3    | 3     | T8: `deep`; T9: `unspecified-high`; T10: `quick` |
| 4    | 3     | T11,T12: `deep`; T13: `unspecified-high` |
| 5    | 4     | T14-T17: all `visual-engineering` |
| 6    | 4     | T18,T19: `visual-engineering`; T20: `unspecified-high`; T21: `deep` |
| 7    | 3     | T22-T24: all `unspecified-high` |
| FINAL| 4     | F1: `oracle`; F2,F3: `unspecified-high`; F4: `deep` |

---

## TODOs

---

## Final Verification Wave

_Content appended below._

## Commit Strategy

_Content appended below._

## Success Criteria

_Content appended below._
