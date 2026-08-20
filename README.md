# Kefi Player

Android music player for the audio already on the device. Vue 3 + Vite web layer,
wrapped by Capacitor; the playback engine is native (Media3) behind a Capacitor
plugin.

The web layer is plain **JavaScript, not TypeScript** — deliberate: the sibling
project `../ytmp3` is TypeScript, this one is not.

## What the app does

- **Plays local audio in a real foreground service.** A Media3 `MediaSessionService`
  owns one `ExoPlayer` and one `MediaSession`, so playback continues with the screen
  off or the app backgrounded, with a MediaStyle notification and working
  lock-screen / media-button controls. Audio focus and becoming-noisy are handled by
  Media3, not by hand.
- **Reads the whole device library with no database.** One `MediaStore.Audio` query
  on demand — no Room, no SQLite, no cache table, no `ContentObserver`. Artwork is
  fetched per track, lazily, as a base64 JPEG.
- **Library in three groupings** — Songs, Albums, Artists — with in-memory search.
  Every sort and every search compare goes through `Intl.Collator('el', { sensitivity:
  'base' })`, so `ωδη` finds `Ωδή` and Greek sorts the way a Greek speaker expects.
- **A mini-player that never remounts** (it lives outside `<RouterView>`) and a full
  Now Playing screen with seek, next/previous, shuffle and repeat.
- **Bilingual, Greek and English**, auto-detected from the device locale and
  switchable in Settings.
- **Survives what Android does to apps.** The WebView can be destroyed and rebuilt —
  or its renderer killed outright — and the UI repopulates from a live native
  snapshot while the audio never stops. Queue, index, position, shuffle and repeat
  are persisted and restored **paused** after process death, never auto-playing.
- **Degrades instead of stalling.** A deleted or unreadable file is skipped, reported
  in the UI, and the queue moves on.

Deliberately **not** in this version: playlists and favourites, sleep timer, playback
speed, a home-screen widget, an equalizer, streaming or any network use at all.

## Requirements

- Node 22 (`node -v` → v22.x), npm 10+
- JDK 17+ — `scripts/build-apk.sh` resolves one itself (prefers
  `/usr/lib/jvm/java-21-amazon-corretto`), so `JAVA_HOME` need not be set
- Android SDK at `~/Android/Sdk` (or `ANDROID_HOME`), platform 36
- Optional: Pillow (`pip install --user Pillow`) for `npm run icons`

## Everyday commands

| Command | What it does |
| --- | --- |
| `npm run dev` | Vite dev server in a desktop browser |
| `npm run build` | Web bundle → `dist/` |
| `npm run sync` | `build` + `npx cap sync android` (copies `dist/` into the app) |
| `npm run apk` | Debug APK, end to end (build → sync → Gradle) |
| `npm run apk:release` | Signed release APK (needs a keystore, see below) |
| `npm run install:apk` | `adb install -r` the debug APK on a connected device |
| `npm run dev:android` | Live-reload on device against the dev server |
| `npm run open` | Open the project in Android Studio (snap path) |
| `npm run qa` | Scripted playback QA against a device (see below) |
| `npm run fixtures` | (Re)create and index the three QA audio fixtures on a device |

`install:apk` is *not* called `install`: `npm run install` collides with npm's own
`install` lifecycle script and runs at unexpected times.

## Permission model

The app asks for exactly one runtime permission: **`READ_MEDIA_AUDIO`** on Android 13+,
falling back to `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) below it. Nothing else
is read, and nothing ever leaves the device — there is no network code in the app.
`POST_NOTIFICATIONS` is declared for the playback notification, and
`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` for the service itself.

The Library screen renders three distinct states rather than a blank list:

| State | What the user sees |
| --- | --- |
| `prompt` / `prompt-with-rationale` | An explanation and an **Allow access** button that raises the system dialog. Granting populates the list with no restart. |
| `denied` | Android will not show the dialog again, so the screen explains that and offers **Open app settings**. |
| granted, nothing found | A "no music found" state with a **Refresh** button. |

## QA

```bash
npm run fixtures          # push + index 3 deterministic audio fixtures (needs ffmpeg)
npm run qa                # 35 scripted checks; exits non-zero on any FAIL
bash scripts/qa-playback.sh 1a2b3c4d   # ...against a specific adb serial
```

`scripts/qa-playback.sh` re-runs the playback assertions end to end on a connected
device: foreground-service type, media-session state, notification channel and style,
queue and transport, metadata completeness, WebView renderer death, screen-off playback,
persistence across `am force-stop`, and that closing the app while paused leaves neither a
service nor a notification behind, and that a 140-character track name cannot widen the
layout. It drives the app through its own JS bridge (evaluated inside
the running WebView over the DevTools protocol) rather than through pixel coordinates,
because a tap that misses reads exactly like a passing test.

It requires the **debug** APK installed, `READ_MEDIA_AUDIO` granted, the fixtures
indexed, and root on the device for the renderer-kill scenario (`adb root` on an
emulator) — it fails loudly rather than skipping when it cannot kill the renderer.

Four things are **not** scriptable and stay manual: audibility, real Bluetooth
A2DP/AVRCP routing, lock-screen control appearance, and wired-headset unplug. Every
scripted signal proves the pipeline reached the mixer, never that sound came out.

## Roadmap

Named here so the next piece of work starts from a written baseline:

1. **Playlists and favourites** — the first feature that genuinely needs persistence
   beyond `SharedPreferences`; the intended home is Room.
2. **Sleep timer and playback speed.**
3. **Home-screen widget.**

An equalizer was considered and explicitly declined.

## Release signing

```bash
npm run keystore     # once, ever — creates ~/kefi-player-release.jks
npm run apk:release
```

`scripts/make-keystore.sh` writes `android/keystore.properties`, which
`android/app/build.gradle` reads. Both the keystore and that file are git-ignored.
**Back up the .jks and its password outside this project** — losing either means
nobody who already installed the app can ever update it.

Without a keystore, `npm run apk` (debug) still works; only release builds need one.

## Android facts worth knowing before editing

- `applicationId` / `appId` is **`com.kefi.player`** and must not change: Android
  identifies an app by it, so a rename is a different app — existing installs
  cannot update and lose their data. The launcher label (`app_name` in
  `android/app/src/main/res/values/strings.xml`) and the icon are free to change.
- `android/app/build.gradle` and `AndroidManifest.xml` are **ours to edit and are
  not regenerated** by `cap sync`. `android/app/capacitor.build.gradle` and
  `android/capacitor.settings.gradle` **are** generated — never hand-edit them.
- Java target is 21 (set by the generated `capacitor.build.gradle`), so Kotlin's
  `jvmTarget` is pinned to 21 to match. A mismatch fails the build.
- The native playback layer is Kotlin: `App.kt` (application singleton),
  `PlaybackService.kt` (the `MediaSessionService`), `PlayerController.kt` (the
  application-scoped `MediaController`), `PlayerPlugin.kt` (the Capacitor bridge),
  `TrackSource.kt` (MediaStore) and `PlaybackStateStore.kt` (persistence).

## Assets

- `npm run fonts` re-downloads Inter + Roboto (Latin, Latin Ext, Greek) into
  `src/assets/fonts/`. Bundled, not linked, because the app runs in an offline
  WebView.
- `npm run icons ~/Pictures/kefi-player.png` regenerates every launcher density
  from one square source image and samples the adaptive-icon background from it.
  Keep `BRAND_BG` in `scripts/make-icons.py` equal to `--bg` in `src/style.css`,
  or the launch screen flashes a different colour before the WebView paints.

## Layout

```
src/
  App.vue            app shell: RouterView + MiniPlayer + TabBar
  main.js            mount (after the stored locale is read)
  style.css          design tokens (colours, fonts, safe-area insets)
  assets/fonts/      bundled woff2 + generated fonts.css
  components/        TabBar, TrackRow, AlbumGrid, ArtistList, MiniPlayer
  composables/       state (module-level, per the ytmp3 convention):
                     usePlayer (native snapshot), useLibrary (MediaStore + search)
  i18n/              hand-rolled t()/setLocale over two flat dictionaries
  plugins/           JS wrappers over Capacitor plugins
  router/            hash-history routes + the Android back button
  utils/             pure helpers
  views/             LibraryView, NowPlayingView, SettingsView
android/             Capacitor Android project (Kotlin playback layer under
                     app/src/main/java/com/kefi/player/)
scripts/             build-apk.sh, qa-playback.sh, qa-fixtures.sh,
                     make-keystore.sh, fetch-fonts.py, make-icons.py
```
