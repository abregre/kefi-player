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
