# Kefi Player

Android music player. Vue 3 + Vite web layer, wrapped by Capacitor; the playback
engine will be native (Media3) behind a Capacitor plugin.

The web layer is plain **JavaScript, not TypeScript** — deliberate: the sibling
project `../ytmp3` is TypeScript, this one is not.

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

`install:apk` is *not* called `install`: `npm run install` collides with npm's own
`install` lifecycle script and runs at unexpected times.

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
- The Kotlin Gradle plugin is already applied even though no `.kt` file exists
  yet: the native playback layer is Kotlin.

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
  App.vue            app shell
  main.js            mount
  style.css          design tokens (colours, fonts, safe-area insets)
  assets/fonts/      bundled woff2 + generated fonts.css
  components/        UI pieces
  composables/       state (module-level, per the ytmp3 convention)
  plugins/           JS wrappers over Capacitor plugins
  utils/             pure helpers
android/             Capacitor Android project
scripts/             build-apk.sh, make-keystore.sh, fetch-fonts.py, make-icons.py
```
