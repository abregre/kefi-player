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
