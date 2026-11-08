# RESUME — kefi-player, paused at the end of Phase A (2026-08-20)

Execution of `.claude/plans/kefi-player-full-android-player.md` was paused **deliberately**
at the plan's own "Execution checkpoint", which sits between T12 and T13. The plan says of
this point: *"the app is a working background music player. If execution has to stop, this is
the clean place to stop."*

## To resume

```
/start-work .claude/plans/kefi-player-full-android-player.md
```

It picks up at **T13** (the router shell). Ground truth is the plan's checkboxes: **12 of 26
done** (T1-T12 ✓; T13-T20 + F1-F6 remaining). Do NOT redo T1-T12.

## What actually works right now (all verified on device, not just reported)

A real Android music player, minus the UI:
- Plays local audio through a Media3 `MediaSessionService` as a proper `mediaPlayback`
  foreground service, with a MediaStyle notification and lock-screen/media-button controls.
- Driven end to end from Vue over a Capacitor plugin (`Player`): `getPlayerState`, `playTrack`,
  `pause`, `resume`, `seek`, `setQueue`, `next`, `previous`, `setShuffle`, `setRepeatMode`,
  `listTracks`, `getArtwork`, `checkAudioPermission`, `requestAudioPermission`; events
  `playerState` and `trackUnavailable`.
- Device-wide library via `READ_MEDIA_AUDIO` with the API<33 fallback; artwork as base64 JPEG.
- Survives WebView renderer death: audio keeps playing, the UI rebuilds and re-pulls state.
- Skips and reports unavailable tracks instead of stalling, with a bounded skip loop.
- Queue/index/position/shuffle/repeat survive process death and restore **paused**; the
  notification's play button resumes from the restored position.

## Phase B is UI only

T13 router+tabs+back button → T14 bilingual el/en → T15 Songs+search (Greek collator) →
T16 Albums/Artists+artwork → T17 MiniPlayer outside `<RouterView>` → T18 Now Playing →
T19 empty/permission states → T20 `scripts/qa-playback.sh`. Then F1-F6.

**First thing T13 must do:** `src/App.vue` is currently a dev QA panel, entirely marked
`TODO(T13)`. T13 rewrites `App.vue` as the shell and deletes that panel. Everything it drives
is already covered by plugin methods, so nothing is lost — but re-screenshot for tap
coordinates afterwards, since every later QA scenario drives the real UI.

## Environment quirks that cost time — do not rediscover them

- Ambient `JAVA_HOME=/usr/lib/jvm/17-openjdk-17` **does not exist**. `npm run apk` resolves its
  own JDK, but any direct `./gradlew` / `sdkmanager` / `avdmanager` call needs
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`.
- Emulator: `Kefi_API_36` (API 36, `google_apis`, x86_64), serial `emulator-5554`, `adb root`
  enabled. Boot with:
  `cd ~/Android/Sdk/emulator && ANDROID_HOME=/home/nonos/Android/Sdk ./emulator -avd Kefi_API_36 -no-window -no-boot-anim -gpu swiftshader_indirect &`
  There is **no** API 36 playstore image — `google_apis` is correct, and it is a bonus (no
  YouTube Music to steal media-button routing).
- `content query --projection` columns are **colon**-separated. The comma form throws
  `IllegalArgumentException: Invalid column` on API 36.
- `settings put global always_finish_activities 1` is a **no-op** on API 36 — the ActivityRecord
  survives HOME. Use the renderer kill instead (see the plan's T8 scenario 1).
- Fixtures: `npm run fixtures` regenerates and re-indexes all three (180/210/240s, Greek+Latin
  tags, art on f1 only). **MediaStore ids renumber on every delete+rescan** — currently 29/30/31
  — so never hardcode them; resolve by title via `listTracks()` (the dev panel now does).
- Grant before QA: `adb -s emulator-5554 shell pm grant com.kefi.player android.permission.READ_MEDIA_AUDIO`
  (and `...POST_NOTIFICATIONS`).

## Contracts later tasks must not break

- Snapshot keys (frozen, 11): `mediaId, uri, title, artist, currentPosition, duration,
  isPlaying, shuffleModeEnabled, repeatMode, queueLength, currentIndex`. Push (`playerState`)
  and pull (`getPlayerState`) are deliberately symmetric.
- `repeatMode` crosses the bridge as the STRING `off|one|all`, not Media3's raw Int.
- Track keys from `listTracks`: `id, uri, title, artist, album, albumArtist, trackNumber, year,
  durationMs, size` (`albumArtist`/`year` are null for the fixtures — ffmpeg wrote no such tags).
- `PluginCall.getLong` returns null when `org.json` boxes a small number as `Integer`. Any new
  numeric arg needs `getLong(...) ?: getInt(...)?.toLong()`. This bit twice already.
- **T17/T18 must interpolate playback position locally** (see the coordinator note in
  `learnings.md`): `playerState` fires only on state CHANGES, so a bar bound straight to
  `currentPosition` sits frozen. Derive it from last-known-position + elapsed time; the plan
  forbids polling the bridge.
- Don't disturb: `onRenderProcessGone` → `recreate()` in `MainActivity`; the `addSession` call
  and null-intent restart guard in `PlaybackService`; the first-touch rule on `PlayerController`
  (only from a user-gesture-originated plugin call).

## Open items carried into Phase B / the Final Wave

- **F5 is BLOCKED-UNTIL-DEVICE.** Audibility, real Bluetooth A2DP/AVRCP, lock-screen visuals and
  wired-headset unplug are provably unscriptable; no physical device has been connected. Report
  as not-run, never as passed.
- `PlaybackService.onStartCommand` is now a passthrough override that only holds the restart
  guard — worth a glance in F4 for whether it still earns its place.
- No launcher icon yet: still the stock Capacitor icon. `npm run icons <square.png>` generates
  every density and samples the adaptive background; it needs a source image from the user.
- The plan's roadmap (explicitly NOT this plan): playlists+favourites on Room, sleep timer +
  playback speed, home-screen widget. Equalizer was explicitly declined.
