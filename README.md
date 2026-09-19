# Kefi Player

Android music player for the audio already on the device. Vue 3 + Vite web layer,
wrapped by Capacitor; the playback engine is native (Media3) behind a Capacitor
plugin.

The web layer is plain **JavaScript, not TypeScript** — deliberate: the sibling
project `../ytmp3` is TypeScript, this one is not. `src/potoken/index.ts` is the
one deliberate exception; see below.

## What the app does

- **Plays local audio in a real foreground service.** A Media3 `MediaSessionService`
  owns one `ExoPlayer` and one `MediaSession`, so playback continues with the screen
  off or the app backgrounded, with a MediaStyle notification and working
  lock-screen / media-button controls. Audio focus and becoming-noisy are handled by
  Media3, not by hand.
- **Reads the whole device library itself with no database.** One `MediaStore.Audio`
  query on demand for the track listing — no cache table, no `ContentObserver`.
  Playlists and favourites are the one exception: they live in a small Room database,
  described in "Playlists and favourites" below. Artwork is fetched per track, lazily,
  as a base64 JPEG.
- **Library in four groupings** — Songs, Albums, Artists, Folders — with in-memory
  search, switched by tapping a tab or swiping the page sideways.
  Every sort and every search compare goes through `Intl.Collator('el', { sensitivity:
  'base' })`, so `ωδη` finds `Ωδή` and Greek sorts the way a Greek speaker expects.
- **A mini-player that never remounts** (it lives outside `<RouterView>`) and a full
  Now Playing screen with seek, next/previous, shuffle and repeat.
- **A live playing queue** with a dedicated view from Now Playing. Upcoming tracks can
  be reordered or removed without restarting the current track; “Play next” and “Add to
  queue” in a track’s options menu change this live queue only.
- **Bilingual, Greek and English**, auto-detected from the device locale and
  switchable in Settings.
- **Survives what Android does to apps.** The WebView can be destroyed and rebuilt —
  or its renderer killed outright — and the UI repopulates from a live native
  snapshot while the audio never stops. Queue, index, position, shuffle and repeat
  are persisted and restored **paused** after process death, never auto-playing.
- **A home-screen widget** (4×1) with artwork, the current track and previous / play-pause
  / next. It follows the same state the app does, shows the last-played track — paused —
  when nothing is running, and can start playback from cold. Settings offers to add it.
- **Degrades instead of stalling.** A deleted or unreadable file is skipped, reported
  in the UI, and the queue moves on.

Deliberately **not** in this version: sleep timer, playback speed, an equalizer,
streaming, or — outside the hidden downloader tab described below — any network use
at all.

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
| `npm run apk:release` | Signed release APK from an exact `vMAJOR.MINOR.PATCH` Git tag (needs a keystore, see below) |
| `npm run install:apk` | `adb install -r` the debug APK on a connected device |
| `npm run dev:android` | Live-reload on device against the dev server |
| `npm run open` | Open the project in Android Studio (snap path) |
| `npm run qa` | Scripted playback QA against a device (see below) |
| `npm run fixtures` | (Re)create and index the QA audio fixtures on a device |

`install:apk` is *not* called `install`: `npm run install` collides with npm's own
`install` lifecycle script and runs at unexpected times.

## Permission model

The app asks for exactly one runtime permission: **`READ_MEDIA_AUDIO`** on Android 13+,
falling back to `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) below it. Nothing else
is read, and by default nothing leaves the device: every network-capable permission the
manifest declares, and every line of network code, lives behind the hidden downloader tab
(see "The hidden downloader tab" below) and stays unused unless that tab is unlocked and
opened. `POST_NOTIFICATIONS` is declared for the playback notification,
`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` for the playback service, and
`FOREGROUND_SERVICE_DATA_SYNC` for the download service. `INTERNET` (already present from
the Capacitor scaffold, before this feature existed) and `ACCESS_NETWORK_STATE` (added for
it) are both declared, along with a legacy `WRITE_EXTERNAL_STORAGE`
(`maxSdkVersion="28"`) needed only pre-Android-10, where the downloader writes straight
into `/Music` instead of through MediaStore.

The Library screen renders three distinct states rather than a blank list:

| State | What the user sees |
| --- | --- |
| `prompt` / `prompt-with-rationale` | An explanation and an **Allow access** button that raises the system dialog. Granting populates the list with no restart. |
| `denied` | Android will not show the dialog again, so the screen explains that and offers **Open app settings**. |
| granted, nothing found | A "no music found" state with a **Refresh** button. |

## QA

```bash
npm run fixtures          # push + index 3 audio fixtures (in 2 folders) + 2 unplayable baits
npm run qa                # 38 scripted checks; exits non-zero on any FAIL
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

## Playlists and favourites

A fifth library tab, alongside Songs / Albums / Artists / Folders: a list of lists —
**Favorites**, **Recently added**, and any user-created playlists, each row showing its
track count. Tapping one opens its own screen.

**What "Recently added" means**: the newest 15 tracks by MediaStore's `DATE_ADDED`, which
is when the media scanner indexed the file on the device — not when the user actually
acquired it. A library copied onto the device in one pass will have most files sharing
that same timestamp, so ties are broken by title, through the same Greek-aware collator
used everywhere else in the app. A file MediaStore reports no `DATE_ADDED` for is treated
as `0` and sorts last, not first.

**Where the data lives**: a Room database, `kefi-library.db` (schema version 1 — nothing
has shipped yet, so there are no migrations), reached only from the native side through
the app's own Capacitor plugin; the web layer never sees SQL. Three tables, exactly as
Room built them: `playlists` (`id` autoincrement, `name`, `createdAt`), `playlist_items`
(`id` autoincrement, `playlistId` → `playlists.id` with `ON DELETE CASCADE`, `path`,
`position`, and a unique index on `(playlistId, path)` so a track can't land in the same
playlist twice), and `favorites` (`path` as primary key, `addedAt`). It stores nothing
beyond what's already on the device and makes no network call. Room is wired through
**kapt, not KSP**: no KSP release targets this project's Kotlin version, `2.2.10` — KSP's
versioning jumped straight from `2.2.0` to a decoupled `2.3.x` scheme, skipping
`2.2.10`/`.20`/`.21` entirely. kapt ships inside the `kotlin-gradle-plugin` already on
this build's classpath, so unlike KSP it has no separately-versioned artifact to
reconcile against Kotlin — the full diagnosis is recorded in
`.claude/notepads/kefi-player-playlists-tab/learnings.md` under `## T2`, so the next
person to touch this build doesn't go try KSP again.

**The identity choice, and its cost**: playlist and favourite membership is keyed by a
track's relative path (`folder/filename`), not MediaStore's numeric id — a rescan
reassigns ids, but not paths. This is a deliberate choice, not a limitation apologised
for: the accepted cost is that moving or renaming a file drops it out of every playlist
and out of favourites, silently, with nothing trying to chase it back. There is no
content hashing.

**What you can do**: create, rename, and delete a playlist; add and remove tracks; drag
to reorder tracks inside a user-created playlist. Favorites and Recently added have a
fixed order and cannot be renamed, reordered, or deleted — they are smart lists over the
same data, not rows in the `playlists` table.

**The track options menu**: reached from a kebab button on every track row, and from Now
Playing — the same menu either way: Favourite/Unfavourite, Add to playlist, Set as
ringtone, Set as notification, View in folder, File details, Delete from device. There is
deliberately no one-tap heart anywhere in the UI; favouriting only happens through this
menu. Setting a ringtone needs Android's `WRITE_SETTINGS` special permission, which the OS
grants only through a trip to its own Settings app, never through the normal
runtime-permission dialog — so the first attempt sends the user there instead of simply
failing.

**File details**, also from that menu, is a read-only screen. The fields already free on
every track (title, artist, album, album artist, track number, year, duration, size, date
added, folder, MIME type) render immediately; bitrate and sample rate are then fetched on
demand via `MediaMetadataRetriever` — already part of the Android SDK and already used
elsewhere in this codebase for embedded artwork, so this needed no new dependency — along
with the file's real on-disk path. That path is display-only and best-effort: it comes from
a MediaStore column Android has deprecated since API 29 and does not guarantee outside the
broad `MANAGE_EXTERNAL_STORAGE` permission, which this app deliberately does not request, so
it degrades to "not available" rather than guessing.

**Delete from device**, from the same menu, is gated behind a confirmation naming the file.
It works on every Android version this app supports (`minSdk 24` up), through three
different native code paths, not one: a plain delete below Android 10, a per-file system
permission dialog on Android 10 (`RecoverableSecurityException`), and one batched system
dialog on Android 11 and newer (`MediaStore.createDeleteRequest`). In every one of those
three paths, the confirmation the user actually sees is owned and shown by Android itself,
not by this app — the app cannot skip, customise, or replace it.

**Editing a track's tags (title/artist/album and similar) was considered and explicitly
rejected**, not deferred: Android's own MediaStore code maintains a hard allowlist of
columns any app may write, and title/artist/album/album artist/year/track are not on it, for
any app, ever — a write is silently accepted and then reverted back to the real file's tags
on the next media scan. See the file-details-and-delete plan's Context section for the full
AOSP citation; the short version is that only rewriting the file's actual embedded tag bytes
(ID3v2, MP4 atoms, Vorbis comments) changes what other apps see, and this app does not do
that.

**Two side effects on existing screens, worth calling out because they're visible**: a
folder expanded in the Folders tab now stays expanded after leaving and returning to the
Library — a long-standing code comment already claimed this, but the state lived
per-screen-instance and reset every time; that's now actually true. And the Library's
selected tab (Songs / Albums / Artists / Folders / Playlists) now survives navigating
away and back too, where it used to reset to Songs.

## Playing queue

The queue is the live Media3 playback list, separate from the Room-backed playlists above.
Open it from Now Playing to see the ordered list and jump to any track. The current track and
already-played entries are protected from queue edits; upcoming entries can be long-pressed and
dragged to reorder, or removed from their options menu. “Play next” inserts directly after the
current item, while “Add to queue” appends to the end.

## Roadmap

Named here so the next piece of work starts from a written baseline:

1. **Sleep timer and playback speed.**

An equalizer was considered and explicitly declined.

## Release signing

```bash
npm run keystore     # once, ever — creates ~/kefi-player-release.jks
npm run apk:release
```

## Release versioning

Android versioning is derived automatically from Git. Use one of the release
commands to bump `package.json` and `package-lock.json`, create a release commit,
and create its matching Git tag. The tag becomes Android's user-visible version
name; the Git commit count becomes its monotonically increasing Android version
code.

```bash
npm run release:patch  # 0.0.0 -> 0.0.1, commits, and tags v0.0.1
# or: npm run release:minor / npm run release:major
npm run apk:release
```

The bump command requires a clean Git working tree, so commit or stash work
first. Push the resulting commit and tag when ready: `git push --follow-tags`.
Non-release builds receive a development version such as
`1.0.0-dev.4+gabcdef0` (or `0.0.0-dev...` before the first release tag).

`scripts/make-keystore.sh` writes `android/keystore.properties`, which
`android/app/build.gradle` reads. Both the keystore and that file are git-ignored.
**Back up the .jks and its password outside this project** — losing either means
nobody who already installed the app can ever update it.

Without a keystore, `npm run apk` (debug) still works; only release builds need one.

## The hidden downloader tab

A third tab, hidden by default: a YouTube-to-MP3 downloader — the one network-facing
feature in the app, named as the exception in "What the app does" and "Permission model"
above. A user who never unlocks the tab causes it to make zero network calls: the engine
is only ever prepared, and only ever checked for updates, from `DownloaderView.vue`'s own
`onMounted`, never from `main.js` or app start.

Unlocked, downloads land in MediaStore under `Music/YTMP3`, so a finished file shows up in
Songs/Albums/Artists and under Folders → YTMP3 exactly like any track already on the
device — there is no separate "downloads" library and no folder picker: files always go to
that one fixed location.

**Unlocking it**: tap the support card at the bottom of Settings 10 times inside a
15-second sliding window (`src/composables/useEasterEgg.js`). The card looks and behaves
identically before and after the 10th tap — nothing about it hints that it is a target.
The unlock:

- sets a permanent preference, `kefi.downloader.unlocked` — it persists across restarts and
  `am force-stop`, and **there is no re-lock gesture**;
- adds the third bottom tab (Library | Downloads | Settings);
- enables the `.ShareTarget` activity-alias at runtime, so the system Share sheet offers
  Kefi Player as a destination for YouTube links. It ships in the manifest as
  `android:enabled="false"` and stays invisible to the OS until this happens.

**Why the engine has to run yt-dlp's nightly channel**: the yt-dlp build bundled in the APK
is a stable release, and as of 2026-08 yt-dlp's stable channel cannot download YouTube at
all. So the first time the downloader screen opens, it installs the `nightly` channel
outright instead of waiting for the user to hit a broken download; every later open only
checks for a newer nightly and offers an Update button. If that daily check fails — offline,
or GitHub unreachable — the existing binary keeps working for whatever it already supports,
and Update stays there to retry; nothing else in the app is affected.

**The size cost**: bundling yt-dlp (CPython) and ffmpeg as native libraries grows the APK a
lot. Measured in this project: the debug APK went from `8498205` bytes (~8.1MB) before this
feature to `62921685` bytes (~60MB) after, for the single `arm64-v8a` ABI this app ships
(both debug numbers; later full builds of the finished feature measured 61MB with `du -h`,
consistent with that figure). A release APK with the downloader has not been built in this
session — ~55-60MB is this project's own target for it, not yet a measurement.

**The one TypeScript exception** this feature needs is the PO-token bundle, documented in
its own section right below.

**`ytmp3` stays a separate, separately-shipped app.** This downloader's code was copied
from `../ytmp3`, not linked to it or kept in sync with it — divergence between the two is
accepted and expected going forward; this repo is not a fork that ever merges back into it.

## The RoadSync flavor

Honda RoadSync (`com.honda.ms.dm.sab`, built on the Drivemode SDK) does not pick
music apps by enumerating `MediaBrowserService`s the way a normal Android Auto
host does. It works from a hardcoded allowlist of package names instead: its
dex carries a `com.drivemode.sdk.music.apps.*` class per app it knows how to
drive. This was established on a test phone, not assumed — RoadSync's Music
Source list showed exactly two apps, SoundCloud and VLC, the only two
allowlisted packages installed at the time. Shazam, Stremio and ReVanced
YouTube were also installed and all publish a `MediaBrowserService`, and none
of them appeared. If RoadSync enumerated, they would have.

Commit `ee71f09` (`PlaybackService` as a `MediaLibraryService` with a
`MediaBrowseTree`) is still correct and still needed: it is what makes Kefi
browsable over real Android Auto and AVRCP, both of which do enumerate. It
just does nothing for RoadSync, which never asks.

So the `roadsync` product flavor exists to get onto the allowlist by
impersonating a package that is already on it: it builds under applicationId
`com.shaiban.audioplayer.mplayer` — Muzio Player's real id — betting that
Drivemode drives Muzio through its generic `MediaBrowserAdapter` rather than
Muzio-specific commands. That is not verified; it is the bet this flavor
makes. If it loses, `another.music.player` is the fallback id to try next.

Build and install it with `npm run apk:roadsync && npm run install:apk:roadsync`.
It is sideload-only: no store will ever accept an app under someone else's
package id, so this flavor never ships anywhere but a device you control by
hand. It also conflicts with the real Muzio Player — Android treats a shared
applicationId as the same app — so a phone can have one or the other
installed, not both. Installed on its own it keeps entirely separate app
data from both Muzio and the `standard` Kefi flavor. RoadSync's UI may also
show it labeled "Muzio Player" or "MP3 Player" rather than "Kefi (RoadSync)",
if Drivemode hardcodes the display name alongside the package id instead of
reading it from the APK.

This has not been tested on the motorcycle; that verification is the user's
to do on hardware.

## The PO-token bundle (the one TypeScript file)

`src/potoken/index.ts`, built by `vite.potoken.config.ts` into
`android/app/src/main/assets/potoken/potoken.js`, is the single exception to the
no-TypeScript rule above. It mints YouTube PO tokens by running Google's BotGuard
VM — an obfuscated JS blob that has to execute inside a real browser engine —
inside an offscreen WebView with a `https://www.youtube.com` origin, something
that cannot be done from Kotlin or Python. It is copied verbatim from `../ytmp3`
and built as a standalone IIFE with no imports, because the native
`PoTokenGenerator` inlines it as text into an HTML page; a cross-origin
`<script src>` would be blocked. It is **opt-in and off by default** (forcing the
`web` client currently lands on YouTube's SABR-only delivery, which the download
engine can't handle yet). If you change `src/potoken/index.ts`, rebuild it with
`npm run build:potoken` — `npm run sync` and `npm run apk`/`apk:release` already
do this for you, so a plain `npm run build` is the only path that skips it.

## Android facts worth knowing before editing

- The `standard` flavor's `applicationId` (**`com.kefi.player`**) must not change:
  Android identifies an app by it, so a rename is a different app — existing
  installs cannot update and lose their data. The `roadsync` flavor
  deliberately builds under a different `applicationId`, so it is a separate
  app with separate data, by design. The launcher label (`app_name` in
  `android/app/src/main/res/values/strings.xml`) and the icon are free to
  change.
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
- `npm run icons ~/Pictures/kefi-4.png` regenerates **every icon and both splash
  screens** from one square source image (the current app icon is
  `~/Pictures/kefi-4.png`):
  - `mipmap-*/ic_launcher{,_round,_foreground}.png` for every density. The adaptive
    foreground is inset to 72/108 of its layer — the part every launcher mask is
    guaranteed to show. The adaptive background colour is sampled from the artwork
    only when the artwork actually has a background; a source with transparent
    corners (any pre-cut round logo) gets `BRAND_BG` instead.
  - `drawable-nodpi/splash_icon.png` (288dp canvas, artwork in the inner 192dp) plus
    the `splash_background` colour, which is what **Android 12+** composes its splash
    from. The `windowSplashScreen*` attributes in `res/values/styles.xml` point at them.
  - `drawable*/splash.png` for Android 11 and below, which still take the whole window
    background as the splash.
  - A final `pngquant` pass, if that tool is installed, which cuts these assets by
    roughly 70% (~2.0MB → ~0.8MB) with no visible difference. Optional: a missing
    pngquant only means larger files.
  Keep `BRAND_BG` in `scripts/make-icons.py` equal to `--bg` in `src/style.css`, or the
  launch screen flashes a different colour before the WebView paints.

## Colours

The app runs on a six-value palette, matching the icon:

| | | |
| --- | --- | --- |
| `#00C853` green | `#006B3C` deep green | `#FFEA00` yellow |
| `#FFD600` deep yellow | `#F5222D` red | `#080A0A` near-black |

They live in `src/style.css` as design tokens, and **no component hardcodes a colour** —
retheming is that one file. The pairs exist so each theme can take the version that
actually reads on its own ground: dark uses `#00C853`/`#FFEA00`, light uses
`#006B3C`/`#FFD600` (the bright green is 2.2:1 on white, the bright yellow 1.2:1). Every
foreground/background pairing in the file clears 4.5:1 and the ratio is written next to
it. `--accent-strong` and `--accent-deep` are fill-only; only `--accent`, `--danger` and
`--success` are safe as coloured text.

The same palette is mirrored in three places that CSS cannot reach, and all three have to
move together: `BRAND_BG` in `scripts/make-icons.py` (the splash and adaptive-icon
background), `res/values/colors.xml` (the native chrome Capacitor still shows, e.g.
text-selection handles in the WebView), and the generated
`res/values/ic_launcher_background.xml`.

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
  potoken/           index.ts — the one TypeScript file, see above
  router/            hash-history routes + the Android back button
  utils/             pure helpers
  views/             LibraryView, NowPlayingView, SettingsView
android/             Capacitor Android project (Kotlin playback layer under
                     app/src/main/java/com/kefi/player/)
scripts/             build-apk.sh, qa-playback.sh, qa-fixtures.sh,
                     make-keystore.sh, fetch-fonts.py, make-icons.py
```
