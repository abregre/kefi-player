# Problems

(Append failures and how they were resolved.)

## Orchestration flaw (mine, found via T7): parallel agents cannot share the emulator
I dispatched T5, T7, T8 in parallel and told each to finish by running `npm run qa`. That suite
drives ONE emulator: it installs the APK, force-stops the app, starts playback, kills the WebView
renderer and reads `dumpsys`. Three of them at once produced `SocketError: other side closed` and
non-reproducible 30/38 and 29/38 results — contention, not regression.

**Rule from here on:** subagents verify with `npm run build`, `compileDebugKotlin`, greps and
targeted `adb` reads. **Only the orchestrator runs `npm run qa`, serialized, between batches.**
Any 38/38 reported by an agent that ran concurrently with another agent is not evidence.
