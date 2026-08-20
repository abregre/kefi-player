# Issues

(Empty at start. Append blocking or surprising issues with the task id.)

## T5 — MediaSessionService silently never promotes to foreground without addSession()
- Building `MediaSession.Builder(this, player).build()` and returning it from
  `onGetSession` looked complete per the task text and played real audio, but the
  service never called `Service.startForeground()`, and the process was killed
  ~30s later with `ForegroundServiceDidNotStartInTimeException` (triggered because
  the debug trigger used `am start-foreground-service`, which obligates *someone*
  to call `startForeground()` within a short window). Root cause: the base class
  requires an explicit call to its own public `addSession(session)` (and
  `removeSession(session)` on teardown) to wire a session into its internal
  notification/foreground manager — this is not mentioned in the plan's T5 body.
  Fixed in `PlaybackService.kt`; see `learnings.md` T5 for the decompile evidence.

## T1 — JAVA_HOME workaround
- The shell's ambient `JAVA_HOME` was `/usr/lib/jvm/17-openjdk-17`, which does not exist / is invalid on this machine. `sdkmanager`/`avdmanager` from the newly-installed cmdline-tools refused to run with it ("JAVA_HOME is set to an invalid directory"). Workaround: explicitly export `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto` before every sdkmanager/avdmanager invocation (matches the Gradle env noted in learnings.md).

## T12 — restore vs. T8's zombie-restart guard race, and the "no polling" instruction vs. position freshness
- `PlaybackService.onStartCommand`'s T8 zombie guard (`intent == null && mediaItemCount == 0
  → stopSelf()`) would race T12's async restore (which populates the queue moments after
  `onCreate()` returns) — a null-intent OS auto-restart could observe `mediaItemCount == 0`
  before restore finishes and stop the service out from under its own restore. Fixed with
  `restoreInProgress`/`sawNullIntentDuringRestore` flags deferring the stop decision to
  restore's completion. Reproduced the race scenario live with `kill -9 <pid>` (not
  `am force-stop`, which disables the OS's sticky auto-restart entirely) — see `learnings.md`
  `## T12` for the full repro and result (restore won the race, real paused notification, no
  zombie, no crash).
- The task's instruction "do NOT add a polling loop to observe position... drive writes from
  the existing Player.Listener callbacks plus a throttle timestamp" cannot, taken literally,
  satisfy the QA scenario's own numeric requirement (position within 6000ms of the true
  position after 20s of continuous, event-less playback) — decompiled
  `MediaControllerImplBase.class` to confirm Media3's `Player.Listener` has zero periodic
  position callback (the one internal periodic mechanism, `notifyPeriodicSessionPositionInfoChanged`,
  only silently refreshes the controller's own cached position field and never invokes any
  listener). Resolved by adding a `Handler`-driven heartbeat started/stopped by the existing
  `onIsPlayingChanged` callback, capped at exactly the task's own 5s interval — read as
  implementing the "throttle" the task asked for rather than violating the "no polling"
  intent (which in context, matching T8's notepad entry, is about bridge-crossing
  UI-observation polling, not an internal low-frequency disk-write scheduler). Full reasoning
  and live verification in `learnings.md` `## T12`.

## Device feedback round 1 (2026-08-20) — three bugs from a real Android 15/16 phone
The API 36 emulator could not show any of the three; all three needed either a different
WebView version, a real track transition in the background, or a real "close the app" gesture.

### 1. Layout broke on Settings/Library — Capacitor's insets strategy depends on the WebView version
`src/style.css` defined `--safe-top`/`--safe-bottom` as `env(safe-area-inset-*)`. On Android
that is only sometimes right, and which way it goes is decided by **Capacitor 8's own
`SystemBars` plugin** (`node_modules/@capacitor/android/.../plugin/SystemBars.java`):
- WebView **< 140**: it pads the WebView's PARENT by the system bars and zeroes the insets it
  passes down. The page is not edge-to-edge, and `env()` = 0 is correct. This is the emulator
  (WebView 133) — which is why everything looked perfect here.
- WebView **140+ with `viewport-fit=cover`** (our `index.html` has it): it switches to
  passthrough — no parent padding, the page IS edge-to-edge, and it injects the real insets as
  **`--safe-area-inset-top/right/bottom/left`** CSS variables. A layout reading only `env()`
  then paints its header under the clock and its tab bar under the gesture pill.
Fix: `--safe-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px))` (and the other
three). Capacitor's variable first — it is correct in BOTH modes — `env()` as the fallback for
iOS/other WebViews, `0px` last. Verified by injecting 48px/24px into `--safe-area-inset-*` on
the emulator: page padding-top went 17.6px → 65.6px, tab bar padding-bottom → 24px.
**Do not "simplify" this back to env() alone.** Also note `insetsHandling: 'disable'` in
`capacitor.config.json` would turn the injection off entirely.

### 2. The media controls showed the PREVIOUS track after an automatic transition
`setQueue` (and `PlaybackStateStore.restore`) built `MediaItem`s from bare uris, so they
carried NO metadata. The notification, lock screen and Bluetooth head unit all render what the
**session** reports, and with bare items that is empty until ExoPlayer's extractor has parsed
the file — which happens after the item became current. Fix: NEW
`TrackSource.mediaItemsFor(context, uris)` does ONE MediaStore query for the whole queue and
attaches title/artist/album/albumArtist/trackNumber plus `artworkUri`
(`content://media/external/audio/albumart/<albumId>` — verified to resolve to a real JPEG on
API 36). Used by `setQueue`, `playTrack` (explicit title/artist still win) and `restore`.
Two traps found while fixing it, both caught by reading `dumpsys media_session`:
- **Do NOT `setDisplayTitle`.** The legacy `MediaMetadataCompat` bridge renders the DISPLAY_*
  triplet when DISPLAY_TITLE exists and only falls back to title/artist/album otherwise, so
  setting just the display title turned `description=Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι
  Vol 1` into `description=Άσπρη Μέρα, null, null` — i.e. artist and album vanish on AVRCP.
- **`PlayerController.toSnapshot()` precedence had to be INVERTED** from what T15 set up. T15
  preferred the player's combined metadata because bare items had none; now that items carry
  their own, the combined value is the stale one — it updates a beat after the current item
  changes, so a snapshot taken right after `setQueue` reported the previously played title
  next to the new `mediaId`. Item metadata leads, combined is the fallback for uris MediaStore
  does not know.

### 3. The notification outlived the app and could not be dismissed
Three separate causes, all of which had to be fixed:
- **A bound service cannot be destroyed.** `PlayerController`'s app-scoped `MediaController`
  stayed bound for the process lifetime, so `stopSelf()` — including inside Media3's own
  `pauseAllPlayersAndStopSelf()` — never took effect. Upstream documents this in
  `MediaSessionService.onTaskRemoved`'s javadoc. Fix: `PlayerController.release()`, called from
  `PlayerPlugin.handleOnDestroy()` and from the service's own `onTaskRemoved`.
- **Media3 keeps the service in the foreground for 10 minutes after playback stops**
  (`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000`), and a foreground notification carries
  `FLAG_NO_CLEAR`. Fix: `setForegroundServiceTimeoutMs(30_000)`.
- **`onTaskRemoved` had to be overridden** (against the plan's original Must Have — the plan is
  amended): keep upstream's "only stay alive while actually playing" decision, but release our
  own binding first so the stop can happen.
- Plus: backing out at `/library` calls `App.exitApp()`, which destroys the Activity WITHOUT
  removing the task, so `onTaskRemoved` never fires on that path. `handleOnDestroy` therefore
  also `stopService`s when nothing is playing.
Verified: task swiped away while paused → 0 service records, 0 notifications, log line
`task removed while not playing, stopping`; task swiped away while PLAYING → service alive,
`state=PLAYING(3)`, position still advancing; back-out while paused → service and notification
both gone, and the next launch still restored track/index/position paused (T12 intact).
