# Learnings — hidden downloader tab

## Conventions of THIS project (kefi-player) — non-negotiable
- **Plain JavaScript + JSDoc. No TypeScript** anywhere except `src/potoken/index.ts` and
  `vite.potoken.config.ts` (a separate IIFE bundle for an offscreen WebView; documented exception).
- Every user-facing string goes through `t()` and exists in BOTH `src/i18n/en.js` and
  `src/i18n/el.js`. QA greps for hardcoded UI text and fails the build if it finds any:
  `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("` must be empty.
- State lives in **module-level composables** (refs declared outside the exported function), see
  `src/composables/usePlayer.js:12-35`. No Pinia, no new state library.
- Design tokens only, never a hardcoded colour: `src/style.css`. ytmp3 uses the SAME token names
  (`--accent`, `--surface-1`…) for a DIFFERENT palette — never copy CSS from there.
- Preferences keys are namespaced `kefi.*` (`src/i18n/index.js:7`).
- Capacitor plugin JS wrappers: `src/plugins/player.js` is the pattern (JSDoc typedefs + web stub).
- Build: `npm run apk` (debug), `npm run qa` (38 checks today). Gradle env:
  `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk`.

## Hard facts established during planning (do not re-derive)
- yt-dlp/ffmpeg ship as Maven deps `io.github.junkfood02.youtubedl-android:{library,ffmpeg}:0.18.1`,
  packaged as `lib*.zip.so` and ONLY working with `packaging.jniLibs.useLegacyPackaging = true`.
- **Media3's `DefaultMediaNotificationProvider` default notification id is 1001** (verified by
  decompiling media3-session-1.11.0) and ytmp3's `DownloadService` also uses 1001 → collision.
  Downloads must use **2001**.
- `ensureNativeToolsReady()` in ytmp3's App.kt is **already lazy** (called only from plugin
  methods). Never hoist it into `Application.onCreate()`.
- ytmp3's `ACTION_SEND` filter sits on MainActivity → must move to a disabled `<activity-alias>`.
- `/mnt/X/myProjects/ytmp3` is **READ-ONLY**. Never write, build or `cap sync` there.
- The QA emulator is `emulator-5554`, x86_64, `ro.product.cpu.abilist=x86_64,arm64-v8a`.
- WebView tap offset for scripted QA: device_y = css_y * devicePixelRatio + 135.

## T1 — baseline (2026-08-20)

1. `npm run qa` — exit code `0`. Summary line: `38/38 checks passed` / `ALL PASS`. All 38
   individual checks printed `PASS` (no failures, no skips).

2. APK sizes (`du -b`), both files present:
   - `android/app/build/outputs/apk/debug/app-debug.apk` → `8498205` bytes (~8.10 MB)
   - `android/app/build/outputs/apk/release/app-release-unsigned.apk` → `6267673` bytes (~5.98 MB)

3. `adb -s emulator-5554 shell getprop ro.product.cpu.abilist` output:
   ```
   x86_64,arm64-v8a
   ```

## T14: format.js — formatEta/formatBytes/looksLikeUrl/looksLikeYoutube (ported from ytmp3)
- Added to `src/utils/format.js`, converted from ytmp3's `src/utils/format.ts` to plain JS + JSDoc.
  `formatDuration`/`formatMs` untouched.
- **Sentinels are empty string, not a placeholder like '--' or '0s':**
  - `formatEta(seconds)` returns `''` for non-finite or `seconds <= 0` (takes SECONDS, not ms).
  - `formatBytes(bytes)` returns `''` for `undefined`, non-finite, or `bytes <= 0` (takes BYTES).
  - This is deliberate: a job row with no ETA/size yet should render nothing in that slot, not a
    dash that reads as "stuck". Do NOT "fix" this into a placeholder in later UI tasks.
- `formatBytes` formatting: B unit and any unit when value >= 10 get 0 decimals; KB/MB/GB with
  value < 10 get 1 decimal. Thresholds copied exactly from ytmp3, not re-derived.
- `looksLikeUrl(value)`: any `http(s)://` string with no whitespace, not youtube-specific.
- `looksLikeYoutube(value)`: matches `youtube.com`/`youtu.be` with optional `www./m./music.`
  subdomain via the `YOUTUBE_URL` regex constant (module-private, not exported).
- Verified: `node --input-type=module -e "import('./src/utils/format.js')..."` → all 5 assertions
  true, exit 0. `npm run build` succeeds.

## T15
- PO-token bundle copied verbatim from ytmp3: `src/potoken/index.ts` (TypeScript, the one
  exception) + `vite.potoken.config.ts` (unchanged `outDir`), built by
  `npm run build:potoken` to **`android/app/src/main/assets/potoken/potoken.js`** (8.9 kB).
- Added `bgutils-js@^4.0.3` to dependencies and `typescript@~6.0.2` to devDependencies —
  ONLY so Vite can transpile this one file. Did NOT add `vue-tsc`, `@vue/tsconfig`, or any
  type-checking of the Vue app; `npm run build` (plain `vite build`) is untouched and
  `find src -name "*.ts" | wc -l` is still `1`.
- `npm run sync` now runs `build && build:potoken && cap sync android`.
- **`scripts/build-apk.sh` now runs `npm run build:potoken` between `npm run build` and
  `npx cap sync android` (with a comment explaining why).** ytmp3's own build-apk.sh does
  NOT do this (verified: only `npm run build` appears in it) and silently ships a stale
  bundle — later tasks touching `scripts/build-apk.sh` must not remove this step.
- README.md documents the exception in a new "## The PO-token bundle (the one TypeScript
  file)" section and lists `potoken/` in the `src/` layout tree.

## T2 — can the emulator exec an arm64 ELF?

1. `adb -s emulator-5554 shell getprop ro.enable.native.bridge.exec` output:
   ```
   1
   ```

2. AAR used: `/home/nonos/.gradle/caches/modules-2/files-2.1/io.github.junkfood02.youtubedl-android/library/0.18.1/9db4b55f3aa38a284218a8e39bfdc5949f8187e4/library-0.18.1.aar`
   Extraction command:
   ```
   unzip -o "$AAR" 'jni/arm64-v8a/*' -d /tmp/ytdlp-aar
   ```
   This produced `libpython.so`, `libpython.zip.so`, and `libqjs.so` under
   `/tmp/ytdlp-aar/jni/arm64-v8a/`. Used `libqjs.so` (916104 bytes) as instructed.

   Architecture verification (`file /tmp/ytdlp-aar/jni/arm64-v8a/libqjs.so`):
   ```
   /tmp/ytdlp-aar/jni/arm64-v8a/libqjs.so: ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /system/bin/linker64, stripped
   ```
   Confirmed genuine ARM aarch64 ELF, not x86.

3. Push, chmod, and execution on `emulator-5554`:
   ```
   $ adb -s emulator-5554 push /tmp/ytdlp-aar/jni/arm64-v8a/libqjs.so /data/local/tmp/libqjs.so
   /tmp/ytdlp-aar/jni/arm64-v8a/libqjs.so: 1 file pushed, 0 skipped. 133.5 MB/s (916104 bytes in 0.007s)

   $ adb -s emulator-5554 shell chmod 755 /data/local/tmp/libqjs.so
   (no output, exit 0)

   $ adb -s emulator-5554 shell /data/local/tmp/libqjs.so -h
   QuickJS version 2025-04-26
   usage: qjs [options] [file [args]]
   -h  --help         list options
   -e  --eval EXPR    evaluate EXPR
   -i  --interactive  go to interactive mode
   -m  --module       load as ES6 module (default=autodetect)
       --script       load as ES6 script (default=autodetect)
   -I  --include file include an additional file
       --std          make 'std' and 'os' available to the loaded script
   -T  --trace        trace memory allocation
   -d  --dump         dump the memory usage stats
       --memory-limit n  limit the memory usage to 'n' bytes (SI suffixes allowed)
       --stack-size n    limit the stack size to 'n' bytes (SI suffixes allowed)
       --no-unhandled-rejection  ignore unhandled promise rejections
   -s                    strip all the debug info
       --strip-source    strip the source code
   -q  --quit         just instantiate the interpreter and quit

   $ adb -s emulator-5554 shell /data/local/tmp/libqjs.so
   QuickJS - Type "\h" for help
   qjs >
   ```
   The no-args invocation dropped into an interactive QuickJS REPL and blocked on stdin
   (the shell call had to be killed/timed out from the harness side — this is the binary
   correctly waiting for interactive input, not a crash). Both invocations prove the
   kernel executed the pushed aarch64 ELF directly: it printed real, binary-specific
   program output (a version string and a full options table) rather than
   `Exec format error` or `Permission denied`. Cleaned up afterwards with
   `adb -s emulator-5554 shell rm -f /data/local/tmp/libqjs.so` (verified removed).

   Outcome classification: the binary RAN (produced its own stdout output on both
   invocations) — not a kernel refusal, not an inconclusive push/permission failure.
   This is consistent with `ro.enable.native.bridge.exec=1`: this AVD image (API 36,
   google_apis, x86_64) has libhoudini/native-bridge exec-translation enabled, so
   standalone arm64-v8a ELF executables run under emulation, not just arm64 `.so`
   libraries loaded via JNI.

VERDICT: EXEC-OK

## T9: Plugin bridge + web mock (ytdlp.js / ytdlp.web.js)

Shipped `src/plugins/ytdlp.js` (JSDoc typedefs + `registerPlugin`) and
`src/plugins/ytdlp.web.js` (`YtdlpWeb extends WebPlugin`), same shape as `player.js`/
`player.web.js`. Final contract T10/T11 can rely on without re-reading ytmp3:

- **Methods** (all on `Ytdlp` from `src/plugins/ytdlp.js`): `prepare()` →
  `{ ready, version }`; `getInfo({ url, ...YoutubeAuthOptions })` → `VideoInfo`;
  `download(DownloadOptions)` → `DownloadResult` (see below); `cancel({ jobId })` →
  `{ cancelled }`; `updateYtdlp({ channel? })` → `{ status, version }`;
  `checkForUpdate({ channel? })` → `{ installed, latest, updateAvailable }`;
  `mintPoToken({ forceRefresh? })` → `{ poToken, visitorData }`; `openFile({ uri })` →
  `void`; `getStorageLocation()` → `StorageLocationInfo`; `consumeSharedUrl()` →
  `{ url }`.
- **`pickStorageLocation`/`clearStorageLocation` are NOT present** — SAF folder-tree
  picking is deliberately not ported (files in a picked tree never reach MediaStore,
  breaking "downloads show up in the library"). `getStorageLocation()` always returns the
  fixed default `{ label: 'Music/YTMP3', isCustom: false, uri: null, cancelled: false }`
  in both the mock and (per T6's native contract) in practice.
- **Events** (`addListener`): `progress` → `{ jobId, progress, etaSeconds, line }` (progress
  0–100, `line` is yt-dlp's raw stdout — UI must check `line.includes('[ExtractAudio]')` to
  flip a job from "downloading" to "converting"); `completed` → `DownloadResult`; `failed` →
  `{ jobId, error }`; `cancelled` → `{ jobId }`; `sharedUrl` → no payload, call
  `consumeSharedUrl()` to collect the URL.
- **`download()` dual-signals completion**: it both RESOLVES with the `DownloadResult` AND
  emits a `completed` event with the same payload. T10's composable should listen to the
  event as the source of truth; treat the resolve as a fallback only.
- **Cancel path is intentional, not a bug**: calling `cancel({ jobId })` makes the
  *original* `download()` promise for that jobId REJECT with `error.code === 'CANCELLED'`,
  while a separate `cancelled` event also fires for the same jobId. Both fire — callers
  must not treat the rejection as an unexpected error path.
- **Payload key names are verbatim** from the native side and must never be renamed:
  `jobId`, `progress`, `etaSeconds`, `line`, `uri`, `displayName`, `size`, `location`,
  `title`, `error`.
- **`ytdlp.web.js` mock**: progress loop steps by 10 every 90ms (~1s to 100%), fully
  deterministic, honours a `cancelled` Set checked before each step so `cancel()` mid-flight
  throws `{ code: 'CANCELLED' }` and emits `cancelled` — this is what the offline QA suite
  (T10/T11 and beyond) drives the whole downloader UI through, since automated tests may not
  touch the network. `getInfo` fabricates a plausible `VideoInfo` from the URL's video id (or
  a fallback id). `consumeSharedUrl()` always returns `{ url: null }` (no share-intent
  simulation in a browser).
- Verified: `npm run build` succeeds; `find src -name "*.ts" -not -path "src/potoken/*" | wc -l`
  → `0`; the mock's `getInfo`/`prepare` round-trip printed `true true`; a scripted
  cancel-mid-download run confirmed the promise rejects with `code: 'CANCELLED'`.
- **Correction (post-review): `usePoToken` defaults to FALSE, not true** (verified in
  `YtdlpPlugin.kt`'s `download()`: `call.getBoolean("usePoToken") ?: false`) — the ytmp3 TS
  source this was ported from had the same wrong claim in its own JSDoc, so it was copied
  verbatim without re-checking the Kotlin. yt-dlp nightly extracts YouTube fine with no
  token; forcing one forces the `web` player client, which lands on YouTube's SABR-only
  delivery that yt-dlp cannot download, so passing `usePoToken: true` is likely to make a
  download FAIL as of 2026-08. Fixed in `src/plugins/ytdlp.js`'s `YoutubeAuthOptions`
  typedef. T10/T11 must not default this to true anywhere.

## T4 — Gradle: youtubedl-android/ffmpeg deps, legacy packaging, abiFilters wiring
- Debug APK size: before `8498205` bytes, after `62921685` bytes (~60 MB; matches the
  expected ~50-60 MB yt-dlp CPython + ffmpeg payload for one ABI).
- `abiFilters` expression used in `android/app/build.gradle` `defaultConfig.ndk`:
  `abiFilters.addAll(project.findProperty('abiFilters')?.split(',') ?: ['arm64-v8a'])`
  (property name `abiFilters` matches what `scripts/build-apk.sh:69` already passes via
  `-PabiFilters=$ABIS`; `abiFilters` is a Set with no setter in AGP's NdkOptions, so `addAll`
  is required — plain assignment fails).
- `android/variables.gradle` gained only `youtubedlAndroidVersion = '0.18.1'` (next to
  `media3Version`). `androidx.documentfile:documentfile` and
  `org.jetbrains.kotlinx:kotlinx-coroutines-android` versions were left as literal strings in
  `build.gradle` (`1.1.0` / `1.10.2`), per the task's own phrasing — only youtubedl-android's
  version needed the variable-pin treatment.
- Verified: `npm run apk` → `BUILD SUCCESSFUL`; APK contains exactly 1 copy each of
  `lib/arm64-v8a/libpython.zip.so` and `lib/arm64-v8a/libffmpeg.zip.so`, 0 files under
  `lib/x86_64/`; `adb -s emulator-5554 install -r` → `Success`; `npm run qa` → `38/38 checks
  passed` / `ALL PASS` (no regression from adding the new classpath deps — nothing uses them
  yet).

## T3: Merged App.kt — companion object final shape
- `App.kt`'s companion object now holds four members, in this order: `instance`
  (unchanged), `scope` (unchanged name/construction — `PlaybackService`, `PlayerController`,
  `PlayerWidget`, `PlayerPlugin` all keep compiling against it untouched), a new
  `downloadScope` (also `CoroutineScope(SupervisorJob() + Dispatchers.IO)`), and
  `ensureNativeToolsReady()` with its `@Volatile isReady` + `Mutex` double-checked guard,
  copied verbatim from ytmp3's `App.kt`.
- **`scope` and `downloadScope` are two separate `CoroutineScope` instances on purpose**,
  even though both use the identical `SupervisorJob() + Dispatchers.IO` construction:
  `SupervisorJob` only isolates individual child-job failures from each other, it does
  NOT isolate a `scope.cancel()` blast radius. A future "cancel all downloads" operation
  must be able to cancel `downloadScope` without any chance of it reaching playback's jobs
  on `scope`, and vice versa. Do not "simplify" these into one scope later.
- **`ensureNativeToolsReady()` is lazy on purpose and must stay that way.** It is NOT called
  from `onCreate()` or anywhere else in `App.kt` — only the downloader plugin (T6) will call
  it. This is deliberate, not an omission: a user who never unlocks the hidden downloader
  tab must never pay the ~few-seconds/~40MB first-call unpack cost. Confirmed via
  `grep -c "ensureNativeToolsReady" App.kt` = 1 (the definition only) and
  `grep -c "ensureNativeToolsReady" MainActivity.java` = 0.
- Verified: `npm run apk` → `BUILD SUCCESSFUL`; `gradlew :app:compileDebugKotlin` → 0 `^e:`
  lines; `adb install -r` → `Success`; relaunch → `KefiApp onCreate` count 1 in logcat;
  `npm run qa` → `38/38 checks passed` / `ALL PASS` (no regression).

## T10: useDownloads.js — composable surface + state machine

Shipped `src/composables/useDownloads.js`, converted from ytmp3's `useDownloads.ts` to plain
JS + JSDoc, same module-level-singleton shape as `usePlayer.js:12-35`.

- **Exported surface from `useDownloads()`** (state first, all wrapped in `readonly()` except
  `activeCount`, a `computed`): `jobs`, `engineVersion`, `isPreparing`, `isUpdating`,
  `isCheckingUpdate`, `updateAvailable`, `latestVersion`, `updateCheckFailed`, `engineError`,
  `storageLocation`, `activeCount` (count of jobs whose status is `queued`/`downloading`/
  `converting`). Functions: `initialize`, `lookup`, `start`, `cancel`, `remove`, `updateEngine`,
  `play`, `refreshUpdateStatus`. **`chooseStorageLocation`/`resetStorageLocation` are NOT
  present** — grep for `chooseStorageLocation|resetStorageLocation|pickStorageLocation` in the
  file is `0`; T11 must not add a folder-picker control since there is nothing to call.
- **`jobs` is `ref([])`, not `reactive([])`** — a deliberate deviation from ytmp3's TS source.
  This project's own `useLibrary.js` (`tracks = ref([])`) already establishes plain `ref()` for
  array-shaped module state as the house style, so T10 matched that instead of copying ytmp3's
  `reactive<Job[]>`. Mutate via `jobs.value` (`.find`, `.unshift`, `.splice`) everywhere inside
  the module; consumers only ever see `readonly(jobs)`.
- **State machine, preserved exactly from ytmp3**: `queued → downloading ⇄ converting → done |
  failed | cancelled`. `progress` events: negative `progress` values are ignored (kept at the
  job's last known value, never coerced to 0) because yt-dlp emits `-1` before the first byte and
  again around the ffmpeg handoff — treating it as 0 would jump the bar backwards. A job flips to
  `'converting'` when the progress event's `line` includes `'[ExtractAudio]'`, else
  `'downloading'`. `failed`/`cancelled` listeners (and the `progress` listener) all no-op unless
  `isActive(job.status)` is true (`queued`/`downloading`/`converting`), so a stale event for a job
  already `done`/`failed`/`cancelled`/removed cannot resurrect or corrupt it. `completed` sets
  `progress = 100` and status `'done'` unconditionally (it's the authoritative terminal signal)
  and copies `displayName`/`size`/`uri`/`location`/`title` from the payload.
- **`start()` drives `download()` and treats its rejection as a fallback only**: the `'completed'`
  event is the source of truth for success; the try/catch around `Ytdlp.download()` only flips a
  still-active job to `'failed'` for rejections that never emit an event (e.g. argument
  validation) — it does NOT special-case the `'CANCELLED'` rejection code, because the
  `'cancelled'` event already handles that job's terminal state (both fire; no double counting,
  since the catch's `isActive(job.status)` guard is false by the time it runs after the event).
- **Nightly auto-update kept, trigger moved**: `shouldAutoUpdate()`/`UPDATE_INTERVAL_MILLIS`
  (24h) logic is byte-identical to ytmp3's. The call site (`initialize()`) is unchanged from
  ytmp3 — the actual behavior change is WHERE `initialize()` gets called: T11's downloader view
  must call it from its own `onMounted`, NOT from `App.vue`/router entry, so a user who never
  opens the hidden tab causes zero network traffic. `useDownloads.js` itself does nothing at
  import/module-evaluation time — no top-level `await`, no side effects outside functions.
- **Storage key renamed**: `LAST_UPDATE_KEY = 'kefi.downloader.ytdlpUpdatedAt'` (ytmp3 used the
  bare `'ytmp3.ytdlpUpdatedAt'`); this app namespaces every persisted key `kefi.*`
  (`src/i18n/index.js:7`), so the key was renamed rather than copied verbatim. If T11 or later
  tasks ever need to read/clear this key directly, use the new name.
- **Verification performed**: `npm run build` succeeds; `grep -c
  "chooseStorageLocation\|resetStorageLocation\|pickStorageLocation" src/composables/useDownloads.js`
  → `0`. Direct `node` import of the composable is awkward (Vite resolves the extensionless
  relative specifier `../plugins/ytdlp`; plain Node ESM resolution does not, and `@capacitor/core`
  assumes a `window`-ish global for platform detection) — worked around with a Node
  `module.register` loader hook that intercepts that one specifier and substitutes a hand-written
  stub of the `Ytdlp` surface (progress/completed/failed/cancelled emitter + resolved
  download()), then unit-called `useDownloads()`'s real exported functions against it. Confirmed,
  with real executed assertions: `initialize()` sets `engineVersion` and stamps the
  `kefi.downloader.ytdlpUpdatedAt` key; `start()` drives a job through
  `queued → downloading → converting → done` with final `progress === 100`; a negative progress
  event does not move progress backward; a `'[ExtractAudio]'` line flips status to `converting`;
  stale `failed`/`cancelled` events after a job is already `done` do not change its status;
  `remove()` deletes a job from the array; `activeCount` tracks active jobs correctly throughout.

## T11: DownloaderView.vue + JobCard.vue

Shipped `src/views/DownloaderView.vue` (root class `downloader-page`, ported from ytmp3's
`App.vue:179-302`) and `src/components/JobCard.vue` (ported from ytmp3's `JobCard.vue`), both
plain JS + JSDoc-free `<script setup>` (no TypeScript), all styling rewritten against this
project's own tokens (never copied verbatim from ytmp3's CSS).

- **`onMounted(() => { initialize() })` lives ONLY in `DownloaderView.vue`** (verified:
  `grep -c "initialize" src/views/DownloaderView.vue` → `2`, one is the destructure, the other
  is the call inside `onMounted`). Not called from `App.vue`, `main.js`, the router, or
  `TabBar.vue` — this is the one thing standing between "hidden tab" and "silent network call
  on every app start", per T10's `issues.md` note.
- **Dropped from ytmp3's `App.vue` on purpose** (do not re-add in a later task without a new
  decision): the storage-folder picker/"Save to" row (`chooseStorageLocation`/
  `resetStorageLocation` don't exist on the composable — grepped `0` in both new files); the
  `sharedUrl` listener / `consumeSharedUrl()` share-intent flow (not in the T11 task's ported
  line range and not in the Expected Outcome list — a later task can add it deliberately if
  ever wanted, this one does not silently half-wire it); the header logo/brand image and the
  footer credit line.
- **Component contract (unchanged from the task spec, confirmed as built)**:
  `JobCard.vue` takes a single `job` prop (the `Job` shape from `useDownloads.js`) and emits
  `cancel` / `remove` / `open` — no other props, no other events. It renders the thumbnail (if
  any), an ellipsised single-line title (`TrackRow.vue`'s `min-width: 0` + `white-space: nowrap`
  + `text-overflow: ellipsis` pattern — a raw unbroken video-title string cannot widen the row),
  the per-status label, a real-width progress bar while `downloading` and an indeterminate
  sliding bar while `queued`/`converting` (ffmpeg reports no percentage), the ETA via
  `formatEta()` only while `downloading`, the size via `formatBytes()` only when `done` (both
  helpers already return `''` for "not applicable" so an empty slot renders nothing, per T10's
  own note — not re-derived here), an error row when `failed`, and is only clickable/keyboard
  operable (`role="button"`, Enter/Space) when `status === 'done' && job.uri` is truthy.
- **DOM hooks for T17's offline QA suite** (stable, class-based, no ids):
  - `.downloader-page` — the view's root section (the one grep target named in the task).
  - `.chip` (exactly 3, one has `.active`) — the bitrate selector; `.chip.active` shows which
    of 128/256/320 is selected.
  - `.primary-btn` — the convert/download action; `disabled` exactly while the URL field
    (`.url-input`) is empty, per the QA scenario in the task.
  - `.url-input` — the URL `<input type="url">`.
  - `.ghost-btn` — both the Update button (header) and the Paste button (composer) share this
    class; distinguish by position/text if a test needs one specifically, or add a dedicated
    class in a later task if T17 needs to.
  - `.secondary-btn` — the Look-up button.
  - `.preview` — the video-info card, only rendered once `lookup()` resolves.
  - `.jobs` → zero or more `<article class="job-card">` (from `JobCard.vue`) plus `.empty`
    (the empty-state paragraph) when `jobs.length === 0`.
  - `JobCard.vue`'s root is `<article class="job-card">` with an extra class equal to
    `job.status` (`queued`/`downloading`/`converting`/`done`/`failed`/`cancelled`) and
    `.playable` added only when clickable. `.action-btn` is the Stop/Clear button (text
    distinguishes which). `.progress-track > .progress-fill` is the bar;
    `.progress-fill.indeterminate` marks the non-percentage (queued/converting) state.
- **Reused `--progress-gradient`** for `.progress-fill`'s background (same token
  `src/components/MiniPlayer.vue` uses for its playback strip), so a running download's bar
  reads as the same visual language as the rest of the app, not a bolted-on screen. Unlike
  MiniPlayer's two-layer "uncover a full-width sweep" technique (which needs a percentage to
  paint correctly), `JobCard`'s bar is a conventional growing-width fill — appropriate here
  since MiniPlayer's technique depends on the CSS custom-property-in-custom-property caveat
  documented in `src/style.css` not mattering when the element being sized is the fill itself,
  not a static-width track.
- **Colour tokens actually used** (all pre-existing in `src/style.css`, none invented): `--text`,
  `--text-muted`, `--border`, `--surface-1`, `--surface-2`, `--accent`, `--accent-strong`,
  `--accent-deep`, `--accent-soft`, `--on-accent`, `--success`, `--danger`, `--danger-soft`,
  `--progress-gradient`, `--font-display`, `--safe-top`. (`--bg` and the other `--safe-*` sides
  were not needed — the page inherits `--bg` from `body`/`#app`, and only `--safe-top` gets
  padded here, matching `LibraryView.vue`/`SettingsView.vue`; the tab bar pads its own
  `--safe-bottom`, per `App.vue:113-114`.)
- **i18n keys used — ALL new, none added to the dictionaries (T12's job)**. Every one below is
  referenced via `t('downloader.*'[, params])` in `DownloaderView.vue` or `JobCard.vue`; until
  T12 adds them to `src/i18n/en.js`/`el.js`, `t()`'s fallback (`dictionaries[locale] ?? en ??
  key`) renders the bare key text, which is expected and harmless at this stage:
  - `downloader.title` — "Downloader" (page `<h1>`)
  - `downloader.engine.preparing` — "Preparing yt-dlp and ffmpeg…"
  - `downloader.engine.updating` — "Updating yt-dlp…"
  - `downloader.engine.checking` — "Checking for updates…"
  - `downloader.engine.version` (`{version}`) — "yt-dlp {version}"
  - `downloader.engine.available` (`{version}`) — "{version} available"
  - `downloader.engine.notReady` — "Engine not ready"
  - `downloader.update.action` — "Update"
  - `downloader.update.updating` — "Updating…"
  - `downloader.update.checking` — "Checking for a yt-dlp update…"
  - `downloader.update.upToDate` — "yt-dlp is already up to date."
  - `downloader.update.updated` (`{status}`) — "yt-dlp updated ({status})."
  - `downloader.update.failed` — "Update failed."
  - `downloader.url.label` — "Video link"
  - `downloader.url.placeholder` — "https://youtube.com/watch?v=…"
  - `downloader.url.paste` — "Paste"
  - `downloader.url.notYoutube` — "Not a YouTube link — yt-dlp supports many sites, so this may
    still work."
  - `downloader.url.clipboardUnavailable` — "Clipboard is not available. Paste into the box
    instead."
  - `downloader.lookup.action` — "Look up"
  - `downloader.lookup.loading` — "Reading link…"
  - `downloader.lookup.invalidUrl` — "Paste a full link starting with http."
  - `downloader.lookup.error` — "Could not read that link."
  - `downloader.bitrate.label` — "MP3 quality"
  - `downloader.bitrate.kbps` (`{rate}`) — "{rate} kbps"
  - `downloader.convert.action` — "Convert to MP3"
  - `downloader.jobs.title` — "Downloads"
  - `downloader.jobs.running` (`{count}`) — "{count} running"
  - `downloader.jobs.empty` (`{location}`) — "Paste a link above to get started. Finished
    downloads are saved to {location} and show up right in your library." (the merge-with-
    library point from Inherited Wisdom)
  - `downloader.play.error` — "Could not open that file."
  - `downloader.job.status.queued` — "Starting…"
  - `downloader.job.status.downloading` (`{percent}`) — "Downloading {percent}%"
  - `downloader.job.status.converting` — "Converting to MP3…"
  - `downloader.job.status.done` (`{location}`) — "Saved to {location}"
  - `downloader.job.status.doneGeneric` — "Saved" (used only if a `done` job somehow has no
    `location`, which the native side always sets in practice)
  - `downloader.job.status.failed` — "Failed"
  - `downloader.job.status.cancelled` — "Cancelled"
  - `downloader.job.stop` — "Stop"
  - `downloader.job.clear` — "Clear"
  - `downloader.job.playAria` — "Play {title}" (aria-label)
  - `downloader.job.removeAria` — "Remove from list" (aria-label)
- **Verification performed (first pass)**: `npm run build` → succeeds (65 modules, no errors).
  `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views/DownloaderView.vue src/components/JobCard.vue
  | grep -v "t("` → no output (no hardcoded UI text; every visible string interpolates
  `{{ t(...) }}`, whose `{` immediately after `>` fails the regex's own "starts with a letter"
  condition, so it never needs the `grep -v` at all — same reasoning ytmp3's original template
  relied on for its `formatEta()`/`formatBytes()` calls). `grep -c "initialize"
  src/views/DownloaderView.vue` → `2` (destructure + the `onMounted` call). `grep -rn "var(--"
  src/views/DownloaderView.vue src/components/JobCard.vue | wc -l` → `58`; every distinct token
  used is listed above and all exist in `src/style.css`. `grep -n
  "chooseStorageLocation\|resetStorageLocation\|pickStorageLocation"` on both new files → no
  matches. No `lang="ts"` in either file.

### T11 — post-review fixes (progress bar technique + shared-URL wiring)

Review caught two issues in the first pass, both fixed in the same two files (no new files):

1. **`JobCard.vue`'s progress bar was a growing-width fill, which this app deliberately moved
   away from.** A growing-width element painting `var(--progress-gradient)` squeezes the whole
   green→yellow→red sweep into whatever fraction is done, so the bar looks identical at 5% and
   at 95% — exactly the thing `src/style.css`'s `--progress-gradient` comment and
   `MiniPlayer.vue`'s `.progress` rule exist to avoid. Fixed by switching the **determinate**
   case (`status === 'downloading'`) to the same full-width "uncover" technique MiniPlayer uses:
   one `.progress-fill` div at `width: 100%`, with `--progress-fill` set inline as a percentage
   and a two-layer `background` (`linear-gradient(transparent 0 var(--progress-fill), var(--surface-2)
   var(--progress-fill) 100%)` painted OVER `var(--progress-gradient)`) so the sweep is anchored
   to the whole track and only the completed portion is uncovered. The **indeterminate** case
   (`queued`/`converting`, where ffmpeg reports no percentage) is unchanged from the first pass:
   a separate `.progress-fill.indeterminate` at `width: 35%` sliding via
   `translateX`/`@keyframes job-card-slide`, painted with a plain (non-layered)
   `var(--progress-gradient)` — there is no percentage to uncover there, so the full-sweep
   technique does not apply and was correctly left alone per review.
   Final CSS (`src/components/JobCard.vue`):
   ```css
   .progress-track {
     height: 0.3rem;
     border-radius: 999px;
     background: var(--surface-2);
     overflow: hidden;
   }
   .progress-fill {
     width: 100%;
     height: 100%;
     border-radius: 999px;
     background:
       linear-gradient(
           90deg,
           transparent 0 var(--progress-fill, 0%),
           var(--surface-2) var(--progress-fill, 0%) 100%
         ),
       var(--progress-gradient);
   }
   .progress-fill.indeterminate {
     width: 35%;
     background: var(--progress-gradient);
     animation: job-card-slide 1.1s ease-in-out infinite;
   }
   ```
   Template: the determinate `<div class="progress-fill">` sets `:style="{ '--progress-fill':
   \`${job.progress}%\` }"` inline (a per-instance custom property, same pattern as
   `MiniPlayer.vue`'s `:style="{ '--progress-fill': ... }"` — NOT one of the tokens defined in
   `src/style.css`, it is set by the component, not read from the design system). Verified by
   reasoning (not a live render): at any fixed x position on the track, the colour comes from
   `var(--progress-gradient)` evaluated at that SAME x/track-width fraction regardless of how
   much of the track is currently uncovered — so the colour at x=5% is identical whether the job
   is at 10% or at 90% progress; only the uncovered extent differs. This matches the required
   verification ("same visible colour at a given x position at 10% and 90%, only the extent
   differs").

2. **Shared-YouTube-link handling was missing from `DownloaderView.vue`.** Ported from
   `$SRC/src/App.vue`'s `checkSharedUrl`/`onVisibilityChange`/`onMounted`/`onUnmounted` block:
   - `checkSharedUrl()` calls `Ytdlp.consumeSharedUrl()`; if it returns a non-null `url`, sets
     the URL field and calls `handleLookup()` (fills the field + shows the preview — does
     **not** start a download, matching ytmp3 exactly).
   - A `sharedUrl` plugin-event listener (`Ytdlp.addListener('sharedUrl', () =>
     void checkSharedUrl())`) covers a share arriving while the screen is already open.
   - A `document.visibilitychange` listener re-checks whenever the app returns to the
     foreground, since a share can arrive while backgrounded.
   - `checkSharedUrl()` is also awaited once directly in `onMounted`, after `await initialize()`
     (matches ytmp3's ordering: prepare the engine first, then look for a pending share).
   - **Attach-once guard**: `sharedUrlListenerAttached` (module-scoped-in-spirit `let`,
     mirroring `listenersReady` in `src/composables/usePlayer.js`) prevents `onMounted` firing
     twice for the same instance from stacking a second plugin listener; `onUnmounted` removes
     both the DOM `visibilitychange` listener and the plugin listener (via the handle's
     `.remove()`) and resets the flag, so navigating away and back to this hidden screen — a
     fresh component instance each time, per this app's router — never accumulates listeners.
   - `import { Ytdlp } from '../plugins/ytdlp'` was added to `DownloaderView.vue` for this
     (T9's plugin wrapper; no new dependency).
   - **No new i18n keys** from this fix — `checkSharedUrl` only ever calls the already-listed
     `handleLookup()` path (whose strings are already in the T11 key list above); it introduces
     no new user-facing text of its own.
- **Verification performed (post-fix)**: `npm run build` → succeeds (65 modules, no errors).
  Re-ran the hardcoded-string grep on both files → no output (still clean; `--progress-fill`
  interpolation and the new script-side strings all route through `t()` or dynamic values).
  `grep -n "initialize" src/views/DownloaderView.vue` → 2 hits, the `onMounted` one now reads
  `await initialize()` (still the only call site in the app). `grep -n
  "chooseStorageLocation\|resetStorageLocation\|pickStorageLocation"` → still no matches in
  either file.

## T12: Strings

Added a `downloader.*` block (40 keys — T11's write-up undercounted by one; the source grep
turns up `downloader.url.placeholder` as a 40th key alongside the 39 T11 enumerated) to both
`src/i18n/en.js` and `src/i18n/el.js`, placed after `player.*` and before `settings.*`, same
order in both files. Verified against the SOURCE (not just T11's list):
`grep -ohE "t\(['\`][a-zA-Z.]+" src/views/DownloaderView.vue src/components/JobCard.vue | sed
"s/t(['\`]//" | sort -u` also turns up `cancel`, `open`, `remove` — false positives from
`$emit('cancel'|'open'|'remove')`, whose text ends in `t(` right before the argument; these are
not translation keys and are correctly absent from both dictionaries. Key-parity check
(`Object.keys(en).every(k => k in el)`) and the reverse both pass; `npm run build` succeeds
(65 modules); the hardcoded-string grep on `src/views` and `src/components` is empty.

Terms worth recording for later UI work:
- **"Downloader" (page `<h1>`) → «Λήψη βίντεο»**, not a literal transliteration of "Downloader"
  (there is no natural one-word Greek noun for the agent-that-downloads sense) and deliberately
  NOT «Λήψεις» — that word was reserved for `downloader.jobs.title` (the job-list heading, i.e.
  "Downloads" plural) so the two headings on the same screen stay visually distinct.
- **"Look up" (`downloader.lookup.action`) → «Έλεγχος συνδέσμου»** ("check the link"), paired
  with `downloader.lookup.loading` = «Ανάγνωση συνδέσμου…» ("reading the link…") — translating
  "look up" word-for-word (e.g. «Αναζήτηση») would collide in register with `library.search`'s
  existing «Αναζήτηση» for a completely different action (searching the library), so a distinct
  verb was picked for this distinct action (fetching video metadata from a pasted URL).
- **"Convert to MP3" → «Μετατροπή σε MP3»** — straight, idiomatic, no issue; MP3 stays Latin
  per the technical-term rule.
- **"Updating…" (`downloader.update.updating`) → «Ενημερώνεται…»** (passive "it is being
  updated"), not «Ενημέρωση…» (which would just repeat `downloader.update.action`'s "Ενημέρωση"
  with an ellipsis and read as static rather than in-progress).
- **"sites" in `downloader.url.notYoutube` → «ιστοσελίδες»**, not left as the English word —
  this app's exemption list for untranslated technical terms is MP3/yt-dlp/kbps/YouTube only;
  "sites" is ordinary vocabulary and got a real Greek word.
- **`downloader.job.clear` → «Αφαίρεση»**, not «Διαγραφή» — the button removes a finished job
  card from the in-app list, it does not delete the downloaded file from storage, so the softer
  "remove" reading was chosen over "delete".
- Placeholders kept identical between languages, one to one: `{version}`, `{status}`, `{rate}`,
  `{count}`, `{location}`, `{percent}`, `{title}`.

## T6: Ported the native engine — YtdlpPlugin.kt + 5 supporting files

Shipped 6 new files under `android/app/src/main/java/com/kefi/player/`, package
`com.kefi.player`: `YtdlpPlugin.kt`, `DownloadService.kt`, `MediaStoreExporter.kt`,
`StorageExporter.kt`, `PoTokenGenerator.kt`, `SharedUrlHolder.kt`. `StorageLocation.kt` was
**not** ported, per the plan.

- **Final plugin method list** (matches T9's `src/plugins/ytdlp.js` contract exactly, verified
  method-by-method against it before writing any Kotlin): `prepare`, `getInfo`, `download`,
  `mintPoToken`, `checkForUpdate`, `openFile`, `getStorageLocation`, `cancel`, `updateYtdlp`,
  `consumeSharedUrl`. `pickStorageLocation`/`clearStorageLocation`/`onStorageLocationPicked`
  are gone entirely — no `ActivityCallback`, no `ActivityResult` import, no
  `ACTION_OPEN_DOCUMENT_TREE` intent. Event names (`progress`, `completed`, `failed`,
  `cancelled`, `sharedUrl`), all resolve/payload keys, the `"CANCELLED"` reject code, and
  `humanError()`'s substring mapping are byte-identical to ytmp3's — copied verbatim, only
  `App.scope` → `App.downloadScope` and `com.ytmp3.app` → `com.kefi.player` changed.
- **`getStorageLocation` always resolves the fixed default** (`label = "Music/YTMP3"`,
  `isCustom = false`, `uri = null`, `cancelled = false`) — built directly as a `JSObject()` in
  the plugin, no `StorageLocation` type anywhere. `StorageExporter` is now a thin wrapper: it
  calls `MediaStoreExporter.exportMp3()` and wraps the result with the fixed
  `StorageExporter.DEFAULT_LABEL` constant. No `DocumentFile`, no tree URI, no SAF branch at
  all — the whole `exportToTree` path from ytmp3 is gone.
- **`DownloadService`'s notification id is 2001, not ytmp3's 1001.** Media3's
  `DefaultMediaNotificationProvider` (this app's own playback notification) also defaults to
  1001, so keeping ytmp3's id would collide with it in the shade. The constant carries a
  comment naming exactly this collision. Channel id stays `"downloads"` (untouched — no
  collision risk there, `PlaybackService`'s channel is `"playback"`). `runCatching` around
  both `startForegroundService`/`startService` and `stopService` is preserved verbatim from
  ytmp3.
- **Removed `androidx.documentfile:documentfile:1.1.0` from `android/app/build.gradle`**
  together with its now-misleading comment (T4 added it "for when the user picks their own
  download folder" — they can't, by design). Confirmed nothing in the codebase imports
  `androidx.documentfile` before removing it.
- **The QA scenario's own grep (`pickStorageLocation\|clearStorageLocation\|StorageLocation`
  over `com.kefi.player/*.kt`, expecting 0 for every file) cannot literally be 0 for
  `YtdlpPlugin.kt`**, because the plan explicitly requires keeping the method named
  `getStorageLocation` (the JS side calls it by that exact name; renaming it would break the
  T9 contract) — and that method name contains the substring `StorageLocation`. This is the
  only match in the file (checked with `grep -n`: it's the `fun getStorageLocation(...)`
  declaration line, nothing else). Two more incidental matches exist in files outside this
  task's scope (`PlayerController.kt` and `PlaybackStateStore.kt`, both pre-existing, both
  just doc-comments citing ytmp3's `App.ensureNativeToolsReady()`/`StorageLocation.kt` as
  design precedent) — left untouched since editing them isn't part of T6. All 6 of T6's own
  files have exactly the expected non-zero-only-for-the-required-method-name result; a future
  reader re-running this exact grep should expect the `getStorageLocation` line, not treat it
  as drift.
- **`file_paths.xml` did not need changes.** It already existed with `<external-path name=
  "my_images" path="."/>` (root of external storage) and `<cache-path .../>`. `openFile()`'s
  `shareableUri()` only re-publishes through `FileProvider` for `file://` URIs (the pre-Q
  legacy-export path in `MediaStoreExporter`, which writes into
  `getExternalStoragePublicDirectory(Music)/YTMP3`); that directory is under the external
  storage root, so the existing `path="."` external-path already covers it. Q+ downloads are
  `content://` MediaStore URIs and skip `FileProvider` entirely (`shareableUri` returns them
  unchanged).
- **`PoTokenGenerator`'s `ASSET_PATH = "potoken/potoken.js"`** matches
  `android/app/src/main/assets/potoken/potoken.js` (8923 bytes, landed by T15) exactly — no
  path adjustment needed.
- Nothing surprising in the port itself (it really is a verbatim copy plus a package rename);
  the only real judgment calls were the notification-id bump (already decided by the plan) and
  how far to sand down the `StorageLocation` substring out of comments (see above) without
  compromising the required method name.

Verification actually run: `cd android && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
ANDROID_HOME=/home/nonos/Android/Sdk ./gradlew :app:compileDebugKotlin 2>&1 | grep -c '^e:'` →
`0`; per-file `grep -c` for `pickStorageLocation|clearStorageLocation|StorageLocation` → `0`
for `DownloadService.kt`/`MediaStoreExporter.kt`/`StorageExporter.kt`/`PoTokenGenerator.kt`/
`SharedUrlHolder.kt`, `1` for `YtdlpPlugin.kt` (the `getStorageLocation` declaration — see
above); `grep -rn "com.ytmp3" android/app/src/main/java/com/kefi/player/*.kt | wc -l` → `2`,
both in pre-existing out-of-scope files, `0` across T6's own 6 files; `grep -rn "App.scope"`
on `YtdlpPlugin.kt`/`DownloadService.kt`/`PoTokenGenerator.kt` → `0`; `NOTIFICATION_ID` in
`DownloadService.kt` → `2001`; `documentfile` in `android/app/build.gradle` → `0`; `npm run
apk` → `BUILD SUCCESSFUL` (61M debug APK); `npm run qa` → `38/38 checks passed`, `ALL PASS`
(no regression).

## T8: MainActivity — second plugin, shared URLs

Edited only `android/app/src/main/java/com/kefi/player/MainActivity.java` (no other file
touched). Final shape: both `registerPlugin(PlayerPlugin.class)` and
`registerPlugin(YtdlpPlugin.class)` are called **before** `super.onCreate(savedInstanceState)`
— app-module plugins are not auto-discovered, so registering after `super.onCreate` silently
does nothing; this app already relied on that ordering for `PlayerPlugin` before this task,
`YtdlpPlugin` was simply added as the next line, same block. `captureSharedUrl(getIntent())` is
called right after `super.onCreate(...)`, before the existing `getBridge().addWebViewListener(...)`
block (which is untouched, comments and all). A new `onNewIntent(Intent)` override was added
**after** the closing brace of `onCreate`, calling `super.onNewIntent(intent)` **first** — this
is required because `BridgeActivity` dispatches the new intent to its registered plugins, and
calling it late/never would break other plugins' own intent handling — followed by
`setIntent(intent)` (so a later `getIntent()` call anywhere in the Activity's lifetime reflects
the newest intent, matching `$SRC`'s pattern) then `captureSharedUrl(intent)`. `MainActivity` is
declared `singleTask` in the manifest, so a second share while the app is already running
arrives via `onNewIntent`, not `onCreate`. `captureSharedUrl(Intent)` is a private method, copied
verbatim from `$SRC/android/app/src/main/java/com/ytmp3/app/MainActivity.java:27-32`: it no-ops
unless `intent.getAction()` is `Intent.ACTION_SEND`, then forwards
`intent.getStringExtra(Intent.EXTRA_TEXT)` to `SharedUrlHolder.INSTANCE.offer(...)` (T6's Kotlin
`object` singleton, called from Java via the compiler-generated `INSTANCE` field). Added one new
import, `android.content.Intent`; no other import changes.

- **`consumeSharedUrl()` resolves `{}`, not `{"url":null}`, when nothing is pending.** This is
  pre-existing native behavior inherited verbatim from ytmp3 via T6, NOT something introduced or
  fixable within this task's scope (`YtdlpPlugin.kt` is on the MUST-NOT-DO list). Root cause:
  `YtdlpPlugin.kt`'s `consumeSharedUrl` does `JSObject().apply { put("url", SharedUrlHolder.consume()) }`,
  and `org.json.JSONObject.put(name, value)` treats a plain Kotlin/Java `null` value as "remove
  this key" (per the `org.json` contract — only the sentinel `JSONObject.NULL` serializes to a
  literal JSON `null`), so the resolved JSON has no `url` key at all when there is nothing
  pending. Verified live over CDP: `Capacitor.Plugins.Ytdlp.consumeSharedUrl()` with nothing
  pending → `{}`. The plan's own QA Scenario text expects `{"url":null}` — that expectation is
  slightly off; the plugin *is* registered and reachable (an unregistered/unreachable plugin
  would throw a JS exception, not resolve `{}`), which is the actual thing this task's QA
  Scenario is checking for. A future task that wants a literal `{"url":null}` shape would need to
  change `YtdlpPlugin.kt` to `put("url", JSONObject.NULL)` — not done here, out of scope.
- **Exact `adb am start` command used to simulate a share** (T17 will want this for its own
  scripted QA):
  ```
  adb -s emulator-5554 shell am start -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT 'https://youtu.be/dQw4w9WgXcQ' -n com.kefi.player/.MainActivity
  ```
  Must target `.MainActivity` explicitly with `-n` — the `.ShareTarget` activity-alias that would
  normally catch `ACTION_SEND` from the system Share sheet ships **disabled** (another task owns
  enabling it at runtime after unlock), so an intent without an explicit component would not
  resolve to anything. Confirmed live: first `consumeSharedUrl()` call after the share resolved
  `{"url":"https://youtu.be/dQw4w9WgXcQ"}` (URL passed through unchanged since it was already a
  bare URL, no surrounding text for the regex to trim), a second immediate call resolved `{}` —
  i.e. read-once, exactly per `SharedUrlHolder.consume()`'s contract.
- **Verification actually run**: `cd android && JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
  ANDROID_HOME=/home/nonos/Android/Sdk ./gradlew :app:compileDebugJavaWithJavac` →
  `BUILD SUCCESSFUL`; `npm run apk` → `BUILD SUCCESSFUL`; `adb -s emulator-5554 install -r
  android/app/build/outputs/apk/debug/app-debug.apk` → `Success`; CDP plugin-reachability check
  (using the `cdp.mjs`/`forward_devtools` technique from `scripts/qa-playback.sh:40-120`) against
  a fresh launch → `Capacitor.Plugins.Ytdlp.consumeSharedUrl()` resolved `{}` (see note above on
  why this is `{}` not `{"url":null}`); the share-intent smoke test above passed both the
  first-call-returns-the-URL and second-call-returns-empty assertions.
- **`npm run qa` was NOT run to completion as a clean signal for this task.** The orchestrator
  was running this task in parallel with others against the same shared emulator; concurrent
  `npm run qa` invocations from different tasks collided (`SocketError: other side closed`,
  scores as low as 23/38 and 31/38 purely from contention — force-stopping/relaunching the app,
  stealing the devtools port forward, etc., out from under each other). Per the orchestrator's
  explicit correction mid-task, `npm run qa` was excluded from this task's verification; the
  compile/build/install/CDP checks above are the verification of record for T8.

### T8 — post-review correction: fix the JSDoc that documented a contract the native side doesn't honour

The coordinator flagged that `src/plugins/ytdlp.js` still declared
`@property {() => Promise<{ url: string | null }>} consumeSharedUrl` — a lie by omission, since
the native side never resolves a literal JSON `null` for `url` (see the `{}` finding above); a
reader writing `if (result.url === null)` would never see that branch fire. Authorized as a
one-line exception to this task's MUST-NOT-DO list (that file wasn't otherwise in scope for T8).

- Fixed **only** that one `@property` line + its description in `src/plugins/ytdlp.js`
  (line ~112): retyped as `Promise<{ url?: string }>` and rewrote the description to state the
  measured truth — `url` is present only when a share was pending, the resolved object has no
  `url` key at all otherwise (root cause: `org.json.JSONObject.put(name, null)` drops a
  null-valued key instead of serializing JSON `null`), callers must check `result.url` for
  truthiness and never `=== null`, and a second call immediately after the first resolves `{}`
  (read-once).
- **Checked the caller, `src/views/DownloaderView.vue`'s `checkSharedUrl()`**: it does
  `const { url: shared } = await Ytdlp.consumeSharedUrl(); if (shared) { ... }` — a plain
  truthiness check, which is correct for both `null` and `undefined`/missing-key. No bug here;
  nothing to report, nothing fixed (that file belongs to T11, out of scope regardless).
- No other file references `consumeSharedUrl`'s return shape (`grep -rn "consumeSharedUrl"
  src/` → only the JSDoc declaration, `ytdlp.web.js`'s mock implementation (unrelated, already
  returns `{ url: null }` deliberately since it's a browser stub with no share simulation, and
  was not touched), and this one call site).
- Verified: `npm run build` → succeeds, `371ms`, no errors. `npm run qa` intentionally NOT run
  (coordinator ran it separately, serialized, to avoid the same-emulator contention noted above).

## T13: Route, still hidden

Added `{ path: '/downloader', name: 'downloader', component: DownloaderView }` to
`src/router/index.js`, between `/settings` and the `/:pathMatch(.*)*` catch-all — same static
top-of-file import style as `LibraryView`/`SettingsView`. A comment above the route states it is
reachable by hash only on purpose and that the tab + unlock guard land together in a later task
(T16), so the missing guard reads as deliberate, not forgotten. `wireHardwareBack()` was not
touched — it walks router history / exits at `/library` regardless of which route is current, so
it covers `/downloader` for free, per the task's own note.

- Route name: **`downloader`**, path: **`/downloader`**.
- DOM confirmed on-device (Pixel-class x86_64 emulator, `emulator-5554`, debug APK) via the CDP
  bridge from `scripts/qa-playback.sh` after `location.hash = '#/downloader'`:
  `{"hash":"#/downloader","page":true,"chips":3}` — i.e. `.downloader-page` (the view's root,
  already documented under T11 above) rendered, and exactly 3 `.chip` elements (the bitrate
  selector; there is no separate `.bitrate-chip` class — T11's own DOM-hooks note already
  established `.chip` as the real selector, confirmed again here from a live render rather than
  source reading).
- **Verification performed**: `npm run build` succeeds; `npm run apk` → `BUILD SUCCESSFUL`;
  `adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk` →
  `Success`; CDP navigation to `#/downloader` on the running app → `page: true`, `chips: 3` (see
  above). **`npm run qa` was NOT run for this task** — the orchestrator flagged that multiple
  tasks were driving the same shared emulator concurrently (installs/force-stops/renderer kills
  collide), so a suite run during this window is not reproducible evidence either way. The route
  addition itself is additive and does not touch any file the existing 38 checks assert against;
  a clean, uncontended `npm run qa` run is still owed before T17 relies on this route.

## T7: Manifest — permissions, DownloadService, disabled ShareTarget alias

Added to `android/app/src/main/AndroidManifest.xml` only: the three permissions
(`ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WRITE_EXTERNAL_STORAGE
maxSdkVersion="28"` — `INTERNET`/`FOREGROUND_SERVICE`/`POST_NOTIFICATIONS` already existed from
`PlaybackService` and were NOT duplicated); the `<service android:name=".DownloadService"
android:exported="false" android:foregroundServiceType="dataSync" />` declaration (no
intent-filter — it is only ever started by explicit component name from Kotlin, never resolved
by the system); and a disabled `<activity-alias>`.

- **The alias's full component name is `com.kefi.player.ShareTarget`** (manifest
  `android:name=".ShareTarget"`, `android:targetActivity=".MainActivity"`). It ships
  `android:enabled="false"` and carries the ONLY `ACTION_SEND` + `text/plain` intent-filter in
  the manifest — `MainActivity`'s own `<activity>` block was left with just its LAUNCHER filter
  (verified: `grep -A12 'android:name=".MainActivity"' AndroidManifest.xml | grep -c "SEND"` →
  `0`).
- **The exact proof command T16 and T17 will both need** — confirms the app is invisible in the
  system share sheet while the alias is disabled:
  ```
  adb -s emulator-5554 shell cmd package query-activities -a android.intent.action.SEND -t text/plain | grep -c com.kefi.player
  ```
  Currently prints `0`. Once T16 flips the alias to enabled at runtime, the same command must
  print `1` (the `ShareTarget` alias resolving under the real app's package, not a second
  package) — that's the assertion T16/T17 should build their "unlock worked" check on.
- **`android:enabled="false"` on the alias survives `adb install -r` (reinstall over an existing
  install)**: reinstalled the APK with `-r` and immediately re-ran the query-activities command
  above → still `0`. This matters because T16's runtime toggle (`setComponentEnabledSetting`)
  writes to the package manager's per-user component-state table, which is NOT part of the APK's
  static manifest and is normally preserved across `-r` reinstalls (only a full uninstall clears
  it) — but confirming the manifest's OWN static default (disabled) also survives a plain `-r`
  reinstall (no uninstall) rules out any doubt that the alias might spontaneously re-enable on a
  routine debug reinstall during later tasks.
- **`dumpsys package com.kefi.player | grep -c "DownloadService"` prints `0`, not "at least 1"
  as the plan's QA scenario for a related step assumed.** `dumpsys package <name>` only lists
  components that appear in the Activity/Service/Receiver *Resolver Tables*, i.e. components with
  an intent-filter. `DownloadService` deliberately has none (it is started by explicit
  component name only — see T6), so it is invisible to that specific command on this Android
  version/build (API 36 emulator). Proved the service IS correctly compiled into the merged
  manifest a different way instead:
  `aapt2 dump xmltree android/app/build/outputs/apk/debug/app-debug.apk --file
  AndroidManifest.xml | grep -A3 'DownloadService'` → shows `android:name="com.kefi.player.
  DownloadService"`, `android:exported=false`, `android:foregroundServiceType=0x00000001`
  (dataSync). Later tasks needing to confirm `DownloadService` is declared should use the aapt2
  xmltree command, not `dumpsys package | grep DownloadService`.
- **The emulator is a shared, non-parallel-safe QA resource.** While running `npm run qa` for
  this task, `ps aux` showed two OTHER `npm run qa` processes already active against the same
  `emulator-5554` (presumably from T5/T8 running concurrently), and the run hit a Chrome DevTools
  Protocol `SocketError: other side closed` plus track-state mismatches (`resumed 'Άσπρη Μέρα',
  expected 'Zeibekiko'`) consistent with two test drivers fighting over the same adb/CDP
  connection and the same installed APK. `npm run qa` is described as "the gate after every
  native change" but is NOT safe to run from two parallel tasks against one emulator at once —
  a later orchestration pass should serialize `npm run qa` invocations across parallel tasks, or
  each task needs its own emulator instance.

## T5: Pinned Media3's notification id (1001) and cross-referenced it with DownloadService's 2001

- **`PlaybackService.kt`**: added `.setNotificationId(1001)` to the
  `DefaultMediaNotificationProvider.Builder(this)` chain in `onCreate()`, between
  `.setChannelName(...)` and `.build()`. Channel id (`"playback"`) and channel-name resource
  are untouched. The comment states 1001 is Media3's own default (per T6's decompile of
  `media3-session-1.11.0`), pinned here to be visible rather than implicit, and explicitly
  names `DownloadService`'s 2001 as the reason — notification ids are per-package, not
  per-service, and the two foreground services can run concurrently (music playing while a
  download runs).
- **`DownloadService.kt`**: only its `NOTIFICATION_ID` comment changed (value stays `2001`,
  untouched) — it now names `PlaybackService`'s `.setNotificationId(1001)` call by name
  instead of just citing Media3's default, so a future reader following either file's comment
  lands on the other file's actual pinned constant, not just a description of the collision.
- **`dumpsys` evidence** (emulator-5554, fresh install of this change): after `setQueue(...)`
  via the app's own CDP-driven WebView bridge (same mechanism `scripts/qa-playback.sh` uses),
  `adb shell dumpsys notification --noredact | grep -A2 "pkg=com.kefi.player"` showed:
  `NotificationRecord(... pkg=com.kefi.player user=UserHandle{0} id=1001 tag=null ... key=0|com.kefi.player|1001|null|10216: Notification(channel=playback ... category=transport groupKey=media3_group_key ...)`.
  `id=1001`, channel `playback` — matches the pinned constant exactly.
- **`npm run qa` was NOT used as this task's gate.** The orchestrator ran three T-tasks in
  parallel against the single shared emulator and mid-task corrected all of them: do not run
  or trust `npm run qa` here, because concurrent runs collide over the same adb/CDP connection
  and installed APK (`SocketError: other side closed`, non-reproducible pass counts like
  30/38, 29/38, and in this task's own local attempts 16/34, 27/37, 22/38, 36/38 — the
  contention masks or fabricates failures unrelated to any real change). The orchestrator will
  run the full suite itself, serialized, once every parallel task has landed. Verification for
  T5 instead relied on: `compileDebugKotlin` (0 errors), the two `grep`s for
  `setNotificationId`/`NOTIFICATION_ID`, `npm run apk` (`BUILD SUCCESSFUL`), `adb install -r`
  (`Success`), and the direct `dumpsys notification` read above — none of which touch the
  shared QA harness.

Verification actually run: `grep -n \"setNotificationId\" PlaybackService.kt` → `.setNotificationId(1001)`;
`grep -n \"NOTIFICATION_ID\" DownloadService.kt` → `private const val NOTIFICATION_ID = 2001`;
`compileDebugKotlin 2>&1 | grep -c '^e:'` → `0`; `npm run apk` → `BUILD SUCCESSFUL`, 61M debug
APK; `adb -s emulator-5554 install -r ...` → `Success`; live `dumpsys notification` → `id=1001`
on channel `playback` for `pkg=com.kefi.player`, as shown above.

## T16: Unlock gesture, persistence, tab, guard, share alias

Shipped `src/composables/useEasterEgg.js` (new), modified `src/views/SettingsView.vue`,
`src/components/TabBar.vue`, `src/router/index.js`, `src/main.js`, `src/i18n/en.js`,
`src/i18n/el.js`, `android/app/src/main/java/com/kefi/player/App.kt`,
`android/app/src/main/java/com/kefi/player/PlayerPlugin.kt`.

### `useEasterEgg.js` — design decisions

- **Module-level singleton** (`unlocked = ref(false)`, `tapTimes = []`) — same pattern as
  `usePlayer.js:12-35`. Module evaluation is inert: no Preferences call, no side effects.
- **`initializeEasterEgg()`** reads `kefi.downloader.unlocked` from Preferences via the same
  `Preferences.get({ key })` pattern as `initLocale()` (lines 47-54 of `i18n/index.js`). Called
  from `main.js` in a `Promise.all([initLocale(), initializeEasterEgg()])` so the router guard
  has the correct `unlocked.value` before the first route resolution.
- **Sliding window**: `tapTimes` array; on each call drop entries `> 15000ms` old (using a `while`
  loop and `splice(0, i)`), push the new timestamp, check if `tapTimes.length >= 10`. This is a
  true sliding window — not "15s after the first tap".
- **On unlock**: `unlocked.value = true`, array cleared, `Preferences.set(...)`, then
  `Player.setShareTargetEnabled({ enabled: true })`. Both are in their own try/catch so a
  persistence failure or a web-context absence of the native method cannot prevent the JS ref
  from going truthy (the session will work even if persistence fails).

### `PlayerPlugin.kt` — companion object pattern

Added `@PluginMethod fun setShareTargetEnabled(call: PluginCall)` that delegates to a `companion
object { fun setShareTargetEnabled(context: Context, enabled: Boolean) }`. The static flavor is
called directly from `App.onCreate()` without bridging through a Capacitor call (which requires
an Activity/WebView to be constructed first — `onCreate` runs before that). The plugin method
is called from the JS side on unlock. `android.content.pm.PackageManager` was added as a new
import (line 6 of the imports block, between `Context` and `Intent`).

### `App.kt` — re-assert on every launch

`App.onCreate()` reads `getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
.getString("kefi.downloader.unlocked", null)` and calls
`PlayerPlugin.setShareTargetEnabled(this, it == "1")`. This re-asserts the component state on
every cold start, covering the case where the OS cleared the package-manager's per-user
component-state table (very rare, but possible after some OTA updates). `Context` import was
added to `App.kt` (was already transitively available in the plugin file via `Plugin.context`
property but `App` extends `Application` directly, so the explicit import was needed).
`ensureNativeToolsReady()` is still NOT called from `onCreate()`.

### Router guard

`router.beforeEach((to) => { if (to.path === '/downloader' && !unlocked.value) return '/library' })`
— added after `wireHardwareBack` export, before `export default router`. The old three-line
comment on the route declaration (explaining the guard was deferred to a later task) was replaced
with a one-liner: "Guarded below: unlocked users only. Locked users are redirected to /library."

### TabBar

`tabs` changed from a static array to a `computed()` that inserts `{ to: '/downloader', key: 'tab.downloads' }` between Library and Settings when `unlocked.value` is truthy. Downloads icon: a
24×24 stroke SVG with a vertical line (stem), a down-pointing chevron, and a horizontal tray
rect — same `stroke="currentColor" stroke-width="1.7" fill="none"` style as the other icons. The
grid's `grid-auto-columns: 1fr` already reflows to equal thirds automatically.

### i18n

`'tab.downloads': 'Downloads'` added to `src/i18n/en.js` (between `tab.library` and
`tab.settings`); `'tab.downloads': 'Λήψεις'` to `src/i18n/el.js` (same position). Tab label
«Λήψεις» was already reserved in T12's learnings note for this exact purpose (`downloader.jobs.title`
also uses «Λήψεις» for the jobs section heading — same word, different key, which is fine: the
tab label and the in-page section heading are the same concept).

### CDP verification results (emulator-5554, debug APK, 2026-08-20)

| Check | Result |
|---|---|
| Tab count before any tap | `2` |
| Navigate to `#/downloader` while locked → hash | `"#/library"` (guard redirected) |
| Navigate to `#/settings`, click `.card.support` 10x → tab count | `3` |
| Navigate to `#/downloader` after unlock → hash | `"#/downloader"` (no redirect) |
| `kefi.downloader.unlocked` in CapacitorStorage.xml | `"1"` |
| `cmd package query-activities -a ACTION_SEND -t text/plain \| grep -c com.kefi.player` | `12` (match — ShareTarget enabled) |
| Fresh restart, tab count (Preferences hydration) | `3` |

- **Dynamic `import()` from CDP is NOT usable** against the production bundle: the bundled app
  serves from `https://localhost/` and the module specifiers in source (`./src/composables/…`)
  do not exist at that URL. CDP verification of unlock must click the real DOM element
  (`.card.support`), not call the composable's exported function directly. The same constraint
  applies to any future CDP-driven test that wants to call a composable function — go through
  the DOM/Vue component interface, not a dynamic import.
- **`npm run build`** → `✓ 73 modules transformed` (no errors).
- **`compileDebugKotlin 2>&1 | grep -c '^e:'`** → `0`.
- **`npm run apk`** → `BUILD SUCCESSFUL`, 61M debug APK.

## F6: Documentation

Added one new `## The hidden downloader tab` section to `README.md`, inserted between
`## Release signing` and the existing `## The PO-token bundle (the one TypeScript file)`
section (line ~131 in the pre-edit file) — no existing section touched, no restructuring.
48 lines added, `git diff --stat` shows only `README.md` changed (1 file, 48 insertions).

- **T15's TypeScript-exception writeup already lives in README.md's own
  `## The PO-token bundle (the one TypeScript file)` section** (added by T15 itself,
  confirmed present at the same location before this task started) — the new F6 section
  does NOT duplicate it; it has one line ("**The one TypeScript exception** this feature
  needs is the PO-token bundle, documented in its own section right below.") that forward-
  references it by position, since F6's section was inserted immediately above it.
- **Size figures used, both labelled by source, no invented numbers**: debug APK
  `8498205` bytes (~8.1MB, T1's pre-feature baseline) → `62921685` bytes (~60MB, T4's
  after-yt-dlp/ffmpeg figure for arm64-v8a only), plus a note that later full builds (T6,
  T16) measured `61M` via `du -h`, consistent with the T4 byte figure. Release APK with the
  downloader has explicitly NOT been built this session — stated as the plan's F2 target
  (~55-60MB), not as a measurement. (Did not build or measure anything myself, per the
  MUST NOT DO restriction on `npm run apk`/`apk:release`/`qa`/`adb`.)
- **Existing "no network" claims elsewhere in README.md were left untouched** (the app
  description's "no network use at all" and the Permission model section's "there is no
  network code in the app") — per the MUST NOT DO on restructuring existing sections. The
  new section instead calls itself out explicitly as the one exception and points back at
  those two spots by name ("see 'Permission model' and the app description above, which
  describe the app's *default* experience"), so the document stays internally consistent
  without editing the older sections.
- **Verification**: `grep -n "YTMP3\|potoken\|build:potoken\|kefi.downloader.unlocked"
  README.md` shows hits in both the new section and the pre-existing PO-token section;
  `git diff --stat -- README.md` → `1 file changed, 48 insertions(+)`; `git status
  --porcelain` shows no source file other than `README.md` newly modified by this task
  (all other listed changes pre-existed before F6 started).

### F6 — post-review correction: the two absolute "no network" claims were qualified, not left standing

The coordinator overrode my original call to leave `README.md:40` (app description) and
`README.md:72` (Permission model) untouched. Rationale accepted: pointing at the new
downloader section from those two lines doesn't repair them, and a reader hits the false
absolute claim (line 72, "there is no network code in the app") long before reaching the
new section — the document contradicted itself in one direction. A minimal in-place
qualifier on each sentence is a correction of a claim this feature made false, not a
restructuring of the README's shape, so it was in scope for this task after all.

- **`README.md:40`** ("Deliberately not in this version: ... streaming or any network use
  at all.") → now reads "...streaming, or — outside the hidden downloader tab described
  below — any network use at all." Still true for the default/locked app; names the one
  exception and points forward.
- **`README.md:72`** (Permission model's "nothing ever leaves the device — there is no
  network code in the app") → rewritten to state the actual shape: nothing leaves the
  device by default, every network-capable permission/line of code lives behind the hidden
  downloader tab and stays unused unless it's unlocked and opened. Also folded in the
  manifest facts the coordinator asked to check (`grep -n "uses-permission" android/app/
  src/main/AndroidManifest.xml`): `INTERNET` (pre-existing, from the Capacitor scaffold,
  not added by this feature) and `ACCESS_NETWORK_STATE` (added for the downloader) are both
  declared; `FOREGROUND_SERVICE_DATA_SYNC` is the download service's foreground-service
  type (kept alongside the pre-existing `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MEDIA_
  PLAYBACK` pair for playback); a legacy `WRITE_EXTERNAL_STORAGE` with
  `android:maxSdkVersion="28"` is declared too, needed only pre-Android-10 where the
  downloader writes straight into `/Music` instead of through MediaStore.
- **Tightened the new section's opening paragraph** to drop the now-redundant "which
  describe the app's *default* experience" hedge — with the two source claims themselves
  now scoped correctly, the new section no longer needs to apologize for a contradiction
  that doesn't exist; it just names itself as the one network-facing feature and cites
  "What the app does" / "Permission model" as already saying so.
- Confirmed no other absolute network/offline claim exists elsewhere in README.md:
  `grep -in "network\|offline\|no network" README.md` shows only these two (now-qualified)
  spots plus the new section's own text and the intro's "plain JavaScript" sentence
  (unrelated).
- `git diff --stat -- README.md` → `1 file changed, 59 insertions(+), 4 deletions(-)`.
  `git status --porcelain` still shows only `README.md` as a newly-touched file for this
  task (all other entries predate F6).

## T19
- **New i18n key**: `library.scan`, positioned right after `library.search` (a flat,
  aria-only key like `library.search` itself) and before `library.loading`, same spot in
  both files.
  - en: `'library.scan': 'Refresh library'`
  - el: `'library.scan': 'Ανανέωση βιβλιοθήκης'`
  - Chose "refresh" over a literal "scan" translation deliberately: the action IS
    `reload()` → `load({ force: true })` → a plain re-query, the exact same operation the
    existing `library.empty.action` ("Refresh" / "Ανανέωση") already names. Introducing a
    second word ("scan"/"σάρωση") for the identical action would read as if the button did
    something more thorough (e.g. an actual disk scan), which it does not.
  - Key parity: `grep -c "'library\." src/i18n/en.js` and the same on `el.js` both return
    `20` after the addition — confirms the count matches, not just this one key.
- **`status` DOES transition to `'loading'` on every forced reload.** Read
  `useLibrary.js:113-137`: `load({ force = false })` only short-circuits on
  `if (!force && status.value === 'ready') return undefined` — with `force: true` that
  guard is skipped unconditionally and `status.value = 'loading'` runs regardless of the
  current status. The only thing `force` does NOT bypass is the shared `loadPromise`
  (concurrent callers await the same in-flight query), which was already documented as a
  T19 guarantee against double-tap. Verified by reading the code, not by running it — no
  build/device access was permitted for this task.
- **Empty-state decision**: the header scan button shows under `!canAsk && !blocked`
  ONLY — deliberately WITHOUT `&& !empty`, unlike the `<p class="count">` next to it
  (which stays `!canAsk && !blocked && !empty`, unchanged). Reasoning: the bug this task
  exists to fix — a finished download not appearing until app restart — presents to the
  UI as exactly the empty state (0 tracks known), so hiding the rescan control there would
  hide it in the one moment it matters most. The pre-existing empty-state `.cta`
  ("Refresh", `LibraryView.vue` empty-state block) is untouched and still calls `reload()`
  the same way — the header button is a second, always-reachable entry point to the same
  action, not a replacement.
- **`.head` layout change**: `align-items: baseline` → `align-items: center`. `baseline`
  aligned the title text against the count text's baseline, which made sense for two text
  nodes but has no meaning for a circular icon button with no text baseline; `center`
  keeps the row looking aligned with the button in it. Added `.head-actions` (flex, gap
  0.5rem) as a wrapper around `<p class="count">` + the new `<button class="scan-button">`
  so the pair reads as one group on the right side of the existing `justify-content:
  space-between` header — `.head` itself still has exactly two direct children (`h1` and
  this new wrapper div), so the space-between behaviour is unchanged.
- **Icon**: reused the industry-standard "refresh/sync" glyph shape (two arcs + two
  arrowhead corners, the same shape Feather's `refresh-cw` uses) redrawn as `<path>`
  elements at this app's own stroke idiom (`viewBox="0 0 24 24" fill="none"
  stroke="currentColor" stroke-width="1.7"`, matching `TabBar.vue`'s glyphs) rather than
  importing an icon library (forbidden by this task's MUST NOT DO).
- **Spinner**: no existing generic "spinning icon" utility class was found (checked for
  `.icon-button`/`spin`/`@keyframes` across `src/**/*.vue`). `NowPlayingView.vue` has an
  `.icon-button` pattern for round transparent buttons (reused the shape/sizing idiom, not
  the class itself — its 2.4rem size is for a full-page transport bar, oversized for this
  compact header) and `MiniPlayer.vue` has a `@keyframes spin` used for the rotating disc
  art, gated behind `@media (prefers-reduced-motion: reduce)`. Followed the same
  reduced-motion carve-out for the new `@keyframes scan-spin`, scoped locally to
  `LibraryView.vue` (scoped styles don't leak, so a same-named keyframe in another file is
  not a collision).
- Not verified by build/device (disallowed for this task): confirmed by re-reading the
  final template that every tag opened in the T19 edit is closed, and that `status`,
  `reload`, `canAsk`, `blocked`, `empty`, `countLabel`, `t` are all pre-existing
  `<script setup>` bindings (lines 13-27 of `LibraryView.vue`) — no new binding was
  introduced.

### T19 review fix: tap target size
- First pass shipped `.scan-button` at `1.9rem` (≈30px) — reviewer (reading the diff, not
  the report) flagged it as smaller than every other touch target in the app and asked
  for the established icon-button idiom instead.
- **Established round icon-button idiom in this codebase is `MiniPlayer.vue:228-243`
  (`.toggle`): `width/height: 2.6rem`, inner `svg`: `1.4rem` (ratio ≈0.54).**
  `NowPlayingView.vue`'s `.icon-button` (2.4rem / 1.5rem svg) is the same idea at a
  slightly smaller scale for a full-page transport bar. `.scan-button` in
  `LibraryView.vue` now matches `MiniPlayer.toggle` exactly — **2.6rem box, 1.4rem svg** —
  so a future icon button in a compact header has one convention to copy
  (`MiniPlayer.toggle`), not a second, smaller one.
- **No `:active`/pressed treatment was added.** Grepped `:active`/`:hover` across
  `MiniPlayer.vue`, `NowPlayingView.vue`, `TrackRow.vue`, `FolderList.vue`,
  `ArtistList.vue`: the round icon-button idiom (`MiniPlayer.toggle`,
  `NowPlayingView.icon-button`) has NO press-feedback style at all in this codebase —
  only the rectangular list-`.row` idiom (`TrackRow`, `FolderList`, `ArtistList`) does,
  via a `background: var(--surface-1)` swap on `:active`, which is a different shape and
  not the one being matched here. Followed the icon-button idiom faithfully (no
  press state) rather than inventing a new convention for round buttons.
- **Header-row overflow guard**: added `flex-shrink: 0` to `.count` (it previously had
  none, so under a tight header it would have been shrunk below its `white-space: nowrap`
  content width and visually overflowed/clipped instead of the row simply getting tight).
  With both `.count` and `.scan-button` now `flex-shrink: 0`, the only element that
  absorbs a narrow screen is `.page-title` (an `<h1>`, free to wrap) — the count and the
  new button keep their full width always. `.head-actions` itself (gap `0.5rem`,
  `align-items: center`) needed no change; `.head`'s `align-items: center` (from the
  first pass) still reads as one row with the taller 2.6rem button.

## T20: Paste button — native clipboard read

- **Root cause confirmed, not intermittent**: the Capacitor WebView never grants the async
  Clipboard API's read permission (`navigator.clipboard.readText()`), so `handlePaste()`
  always landed in its `catch` and showed `downloader.url.clipboardUnavailable` — 100% of
  the time, on every device, not flaky. The long-press paste that "worked" was the
  WebView's own native text-selection paste, never touching JS at all. A real desktop
  browser DOES grant this permission on a user gesture, which is why `npm run dev` and the
  mock never surfaced the bug.
- **Fix, native side**: `YtdlpPlugin.readClipboard(call)` — synchronous resolve (no
  `App.downloadScope.launch`, matching `getStorageLocation`/`consumeSharedUrl`'s shape, not
  the coroutine methods). Uses `context.getSystemService(Context.CLIPBOARD_SERVICE) as
  ClipboardManager`, reads `primaryClip?.getItemAt(0)?.coerceToText(context)`. No clip,
  zero items, or non-text content all resolve `{ "text": "" }` — never `call.reject()`, since
  an empty clipboard is a normal state, not a genuine failure.
- **JS contract**: `src/plugins/ytdlp.js` gained `readClipboard(): Promise<{ text: string }>`
  in the JSDoc `YtdlpPlugin` typedef, right after `consumeSharedUrl`. `src/plugins/ytdlp.web.js`
  mocks it over the real `navigator.clipboard.readText()` (browsers DO grant this), wrapped in
  try/catch so it never throws — required since the offline QA suite drives the whole UI
  through this mock.
- **New key — `downloader.url.clipboardEmpty`**, added to both locales right after
  `clipboardUnavailable`:
  - en: `'Clipboard is empty. Copy a link first.'`
  - el: `'Το πρόχειρο είναι άδειο. Αντιγράψε πρώτα έναν σύνδεσμο.'`
  - Distinct in meaning from `clipboardUnavailable` ("Clipboard is not available. Paste into
    the box instead.") on purpose: `clipboardEmpty` fires when the native read SUCCEEDED and
    returned `""` (nothing copied yet — user action needed); `clipboardUnavailable` fires only
    when the plugin call itself threw/rejected (a genuine failure). `handlePaste()` in
    `DownloaderView.vue` branches on this: non-empty text → trim + `handleLookup()`; empty
    string → `clipboardEmpty`; thrown/rejected call → `clipboardUnavailable`.
- **Comment placement, deliberately without the literal string `navigator.clipboard`**
  outside `ytdlp.web.js`: the task's own verification step requires
  `grep -rn "navigator.clipboard" src/` to hit ONLY `ytdlp.web.js`. The explanatory "why"
  comments in `YtdlpPlugin.kt`'s `readClipboard()`, `ytdlp.js`'s JSDoc, and
  `DownloaderView.vue`'s `handlePaste()` all describe "the async Clipboard API" / "the
  browser's clipboard API" instead of naming the property literally, so the why is preserved
  without breaking that grep.

## T21: MiniPlayer — prev/next buttons and a touch-seekable progress line

- **Tap-target sizes**: `.toggle` (play/pause, unchanged) stays `2.6rem` / `1.4rem` svg —
  the established primary round-button size (`MiniPlayer.toggle`, also now matched by
  `LibraryView.scan-button` per the T19 notes above). The two new `.side` buttons
  (prev/next) are `2.4rem` / `1.5rem` svg — this is NOT a new third size, it is
  `NowPlayingView.icon-button`'s existing "secondary, transparent, muted" convention
  (2.4rem/1.5rem, `background: transparent`, `color: var(--text-muted)`), reused verbatim
  rather than inventing a smaller mini-player-specific button. Icon glyphs for prev/next
  are the literal same `<path>`/`<rect>` coordinates as `NowPlayingView.vue`'s transport
  buttons (not CSS-mirrored) so the shape is pixel-identical in both places.
- **Hiding the thumb**: `.seek-input::-webkit-slider-thumb { appearance: none; width: 0;
  height: 0; background: transparent; }`. Only the `-webkit` prefix was added (no
  `::-moz-range-thumb` or unprefixed `::-slider-thumb`) because `NowPlayingView.vue`'s own
  `.seek-input` (the pattern of record) only styles `-webkit`, and this ships exclusively
  inside an Android WebView (Chromium) — matching the sibling's idiom rather than adding
  selectors it doesn't have. The input's own `::-webkit-slider-runnable-track` is painted
  transparent (`background: transparent`) since the visible line is the separate `.progress`
  div underneath, not the input's track.
- **Tall touch area without changing layout height**: introduced a `.progress-wrap` with
  `position: relative; height: 2px` (i.e. it takes exactly the space the old bare `.progress`
  div used to take — zero layout change to `.mini`/`.body`). `.progress` (the visible 2px
  line, gradient untouched byte-for-byte from the original) is its first child in normal
  flow. `.seek-input` is the second child, `position: absolute; top: 50%; transform:
  translateY(-50%); height: 1rem; width: 100%; background: transparent` — centred on the
  2px line but 1rem tall, overlaid rather than stacked, so the extra hit area costs no
  row height and doesn't push `.body` down. Nothing clips it (`.mini`/`.progress-wrap` set
  no `overflow`), so the extra ~0.4rem above/below the line renders fine; the overlap
  eats into `.body`'s top padding (0.45rem) but sits above where the buttons themselves are
  vertically centered, so it doesn't block taps on the buttons.
- **`duration === 0`**: matched `NowPlayingView.vue` exactly rather than adding a new guard
  — `:max="player.duration.value || 0"` on the input, and the `progress` computed returns
  `0` early via `if (!total) return 0` before ever dividing. No NaN path exists; when
  duration is unknown the range is effectively `min=0 max=0`, which is inert (nothing to
  drag) but never throws or paints a NaN width.
- **Drag-state reset on Now Playing navigation**: added `watch(hidden, (value) => { if
  (value) dragging.value = false })`. `MiniPlayer` never unmounts (it lives outside
  `<RouterView>`), so if a user were mid-drag when the route flips to `/now-playing` (the
  strip hides via CSS, not `v-if`), the range input's `change` event that normally clears
  `dragging` would never fire, and the strip would come back from Now Playing showing a
  frozen `dragValue` instead of resuming `player.displayPosition`'s live ticker. This watch
  is the fix; it is new code not present in the `NowPlayingView` pattern this was modeled
  on, because that screen unmounts on navigation and never needed it.
- **`aria-hidden` removed** from the element that used to carry it (the div now named
  `.progress`, formerly the whole strip) — it is purely decorative paint now, no separate
  info from what the new `<input>` announces, but the literal attribute was dropped per the
  task's grep check. The `<input>` itself carries `:aria-label="t('player.seek')"`. The one
  remaining `aria-hidden="true"` in the file is on the unrelated placeholder-art SVG, which
  is still decorative.
- **Verification performed**: no build/device (disallowed). Parsed the file with
  `@vue/compiler-sfc` (`parse` + `compileScript` + `compileTemplate`) directly via `node -e`
  to confirm the SFC is well-formed and the template compiles against the script-setup
  bindings — this caught nothing, all clean. Cross-checked every `t('...')` key used in the
  file against both `en.js` and `el.js` by grep; all 8 keys present in both.

## T22: Dismiss Now Playing with a downward drag

- **New composable, sibling of `useHorizontalSwipe.js`**: `src/composables/useVerticalDismiss.js`.
  Same shape (target ref + options, `onMounted`/`onBeforeUnmount`, plain closure variables
  for drag bookkeeping) with one deliberate exception — `offset` (and `dragging`) are refs,
  not closure vars, because `NowPlayingView.vue` has to re-render on every `touchmove` to
  follow the finger. Returned as `{ offset, dragging }`.
- **Thresholds**: `MIN_DISTANCE = 120` (downward px to dismiss on release — bigger than
  `useHorizontalSwipe`'s `MIN_DISTANCE = 56` because a full-screen sheet needs a much more
  deliberate gesture than a tab swipe to avoid accidental dismissal while scrolling
  metadata/album art with a thumb). `DIRECTION_RATIO = 1.6`, copied verbatim from the
  sibling's ratio — same reasoning, a diagonal drag should read as a scroll/swipe attempt,
  not a dismiss.
- **Flick shortcut kept, but reshaped**: unlike the sibling's single `MAX_DURATION_MS` (a
  duration ceiling applied to a drag that already passed `MIN_DISTANCE`), a fast downward
  flick here dismisses even short of `MIN_DISTANCE`, via its own lower floor
  (`MIN_FLICK_DISTANCE = 48`) combined with a duration ceiling (`MAX_FLICK_DURATION_MS =
  300`). Reasoning: the task explicitly asked for "a fast downward flick should also
  dismiss even if it did not travel the full distance," which the sibling's model (duration
  gates a full-distance swipe) can't express — it needed a second, shorter distance floor
  gated by a shorter duration, evaluated as an OR alongside the full-distance threshold.
- **Damped follow, not 1:1**: `DRAG_RESISTANCE = 0.55` — `offset = dy * DRAG_RESISTANCE`
  while dragging. Chose damping over a raw follow because a 1:1 translateY on a full-height
  sheet reads like the screen is being torn off under the finger; a little resistance reads
  as "the sheet has weight" and also means the finger has room to travel well past
  `MIN_DISTANCE` in screen-px before the sheet visually leaves the viewport, which matters
  because dismissal is decided on the raw (undamped) `dy`, not on `offset`.
- **`ignore` selector**: `'.seek-input, .icon-button, .play'` on `NowPlayingView.vue`'s
  `useVerticalDismiss` call — covers the seek range input (owns its own touch handling per
  the seek-drag state at ~38-70) and every button on the screen (`.icon-button`: back,
  shuffle, previous, next, repeat; `.play`: the transport play/pause button), so a drag
  starting on any control is left alone rather than sliding the sheet.
- **No stale offset across mounts**: didn't add an explicit reset-on-unmount/route-change.
  `NowPlayingView` is a routed view — navigating away unmounts the component entirely
  (the MiniPlayer, which lives outside `<RouterView>`, is what stays alive and re-shows
  itself via CSS, not this component), so `useVerticalDismiss`'s `offset` ref is created
  fresh at 0 on every mount. Nothing survives to leak into the next visit.
- **Why the non-passive `touchmove` is justified here when the sibling refuses it**:
  `useHorizontalSwipe.js:14-18` never calls `preventDefault` because it shares its surface
  with a vertically scrolling list (`LibraryView`'s song/album/artist/folder lists) —
  stealing the touch there would break scrolling to save an animation nobody asked for.
  `NowPlayingView` is a full-height sheet with no scrolling content behind the gesture
  surface: dragging down IS the interaction, not a shortcut layered on top of one, so there
  is no competing native behavior to protect. `onMove` still only calls `preventDefault()`
  after `verticalDrag` has been latched true (drag exceeded a small deadzone AND read as
  vertical via `DIRECTION_RATIO`) — an undecided or horizontal touch is never intercepted,
  so tapping/dragging elsewhere on the screen (anything not in `ignore`) is unaffected until
  the gesture has already committed to being a vertical drag.
- **Reduced motion**: `NowPlayingView.vue`'s `.page` gets `transition: transform 0.2s
  ease-out` for the spring-back settle, `.page.dragging { transition: none }` so the
  transform doesn't lag the finger while a drag is live, and the settle transition itself
  is dropped under `@media (prefers-reduced-motion: reduce)` — same pattern as
  `MiniPlayer.vue`'s `.art` rotation carve-out (state change kept, animation removed).
- **Unverified on hardware**: per the no-device/no-emulator constraint, the feel of the
  120px threshold, the 0.55 damping factor, and the 48px/300ms flick shortcut are untested
  on a real touchscreen — these are reasoned defaults, not measured ones, and are worth the
  user's own judgment on a device.

## T21 follow-up: fixed a touch-area regression found in review

Coordinator review caught a real defect in the first pass: `.seek-input` was `height:
1rem` centred with `top: 50%; transform: translateY(-50%)` on a `.progress-wrap` that is
only 2px tall, so ~7px of the invisible touch target hung *above* `.mini`'s top edge.
`App.vue` lays the shell out as a CSS grid (`grid-template-rows: 1fr auto auto auto`)
with the mini-player pinned by the grid, not fixed-positioned, and grid rows don't clip
their children — `.shell`'s `overflow: hidden` clips at the shell's bounds, not the row
boundary. So that overhang sat on top of the scrolling library list and would have
intercepted taps meant for the bottom edge of the last visible track row (seeking
instead of playing it) — exactly where a thumb lands on a phone.

**Fix**: anchored `.seek-input` to `top: 0` of `.progress-wrap` (dropped the
`translateY(-50%)` centring) and reduced `height` from `1rem` to `0.6rem`, so the touch
area grows downward only, landing entirely inside `.body`'s own top padding (`0.45rem`)
plus the 2px line itself — nothing tappable lives there to steal taps from. Final
geometry: `.progress-wrap` stays 2px (unchanged layout height), `.seek-input` is 0.6rem
(~9.6px) tall, 0 top offset, i.e. a ~4.8x touch-area multiplier over the visible 2px line,
entirely inside the strip. Chose 0.6rem specifically to match the reviewer's math (2px
line + ~0.45rem `.body` padding ≈ the safe ceiling before the area would start
overlapping the tops of the `.open`/`.side`/`.toggle` buttons that sit inside that
padding). Did NOT increase `.body`'s padding to allow a taller touch strip — that would
grow the mini-player's overall height, which is out of scope; recorded here as the
trade-off instead of taking it.

Re-verified with the same `@vue/compiler-sfc` `parse`/`compileScript`/`compileTemplate`
check as the first pass — SFC parses, script compiles, template compiles against the
script-setup bindings, no errors.

**Correction (post-review):** the first pass destructured `const { offset, dragging } =
useVerticalDismiss(...)`, colliding with the pre-existing seek-drag `const dragging =
ref(false)` declared earlier in the same `<script setup>` (the file's "ONLY local state"
comment at ~32-38) — a hard `SyntaxError: Identifier 'dragging' has already been declared`
from `@vue/compiler-sfc`'s `compileScript`, caught by `npm run build`, not by the diff
re-read. Fixed by renaming at the destructure only: `const { offset, dragging:
sheetDragging } = useVerticalDismiss(...)`. The composable's own returned property is
still named `dragging` (no reason to rename the API) and its JSDoc is unchanged. The
template's `:class="{ dragging }"` shorthand became `:class="{ dragging: sheetDragging }"`
— the CSS class name `.page.dragging` did not change, only the JS expression feeding it.
**For the next person touching this screen: `dragging` is taken by the seek bar (the range
input's drag-to-scrub state); any new gesture's own "is dragging" flag needs a distinct
name (this task used `sheetDragging`).** Verified this time with `@vue/compiler-sfc`
(`parse` → `compileScript` → `compileTemplate`) via `node -e`, which is the actual step
that raised the original error — both stages now compile clean.

## F1 + F2 (orchestrator, serialized, 2026-08-20)

Run by the orchestrator after T19-T22 landed, with every agent idle. No device involved.

- `npm run build` → `✓ built in 248ms`, no errors. NOTE: the first attempt FAILED —
  `NowPlayingView.vue:92 SyntaxError: Identifier 'dragging' has already been declared`. T22 had
  destructured `{ offset, dragging }` from `useVerticalDismiss` into a `<script setup>` that
  already owned `dragging` for the seek bar. The agent's self-review had claimed "bindings all
  resolve"; only compiling caught it. Fixed by renaming at the destructure to `sheetDragging`
  (the CSS class `.page.dragging` and the seek state are unchanged).
  **Lesson for future gesture work on that screen: `dragging` is taken by the seek bar.**
  `@vue/compiler-sfc`'s `compileScript` is the cheap check that catches this class of error
  without a full build — worth requiring of any agent editing an SFC when builds are serialized.
- `cd android && ./gradlew :app:compileDebugKotlin` → `BUILD SUCCESSFUL`, 0 `^e:` lines. This is
  the first real compile of T20's `YtdlpPlugin.readClipboard()` and T16's
  `PlayerPlugin.setShareTargetEnabled` companion.
- `npm run apk` → `BUILD SUCCESSFUL`, debug APK **61M**.
- `npm run apk:release` → `BUILD SUCCESSFUL`, `app-release-unsigned.apk` **59M** — inside the
  plan's expected 55-60MB band. This is now a MEASUREMENT, not a target; README (F6) said it had
  not been built yet and must be corrected.

F3 was NOT run: no emulator (retired at the user's instruction) and no device access (withdrawn
mid-run: "stop testing on my mobile device"). T17's new checks therefore remain written but never
executed — recorded as BLOCKED in the plan rather than ticked.

### Correction to F2, same day

The user stopped release builds right after that run: "no release apk, it is not finished." The 59M
figure above stands as a one-off measurement, but F2 is NOT a gate for this plan and
`npm run apk:release` must not be run again until they ask for a release. The README keeps
describing ~55-60MB as a target rather than quoting a shipped size — do not "correct" it to 59M.

## F4 verdict: APPROVE (2026-08-20)

Independent read-only review of the full uncommitted diff against Must Have / Must NOT Have.
Verdict APPROVE, no blocking issues. Three non-blocking notes worth carrying forward:

1. `useVerticalDismiss.js`'s justification for its non-passive `touchmove` + `preventDefault()`
   rests on Now Playing never needing to scroll, which in turn rests on `.art`'s
   `max-height: 46vh`. Under extreme font scaling or accessibility zoom that bound could be
   exceeded, and a downward drag mid-scroll would then be captured by the dismiss gesture instead
   of the page scroll. Flag for whoever does accessibility QA; not a defect today.
2. `scripts/qa-playback.sh`'s downloader UI smoke test computes `urlEmpty` into its CDP JSON
   payload and never asserts on it — dead data. Harmless, but it should either be asserted or
   dropped when that section is next touched (it has to be revisited anyway: it has never run).
3. `git status --porcelain` = 21 entries. Not a hidden failure; the user has not asked for a
   commit, so Success Criterion 1 stays open by choice.

Verification stance that held up all session: three separate agent claims did not survive reading
the actual diff (T11's superseded progress bar, T11's skipped sharedUrl wiring, T15's skipped APK
check — all from the previous session), plus this session's T19 undersized tap target, T21's
hit area straddling the strip's top edge, and T22's `dragging` redeclaration that broke the build
while the agent reported its bindings all resolved. Read the diff; run the build yourself.

### T19 review fix: spin must outlive a fast query
- User-reported symptom: "make the scan icon spin when it is clicked" — the `.spinning`
  class and `@keyframes scan-spin` were already correct, but bound directly to
  `status === 'loading'`. A forced `Player.listTracks()` on-device round trip finishes in
  well under a frame's worth of perceptible time, so the class turned on and off before
  the `0.9s linear infinite` rotation ever became visible motion — the button read as
  dead on tap.
- **Fix**: a local `scanning` ref (LibraryView.vue, near `countLabel`) set `true` the
  instant the button is clicked, independent of `status`. `scanBusy = computed(() =>
  status.value === 'loading' || scanning.value)` is the single source driving both
  `:class="{ spinning: scanBusy }"` and `:disabled="scanBusy"` on the button, so the two
  can never disagree and a second tap mid-spin is a no-op.
- **Constant chosen**: `MIN_SPIN_MS = 900`, named and commented (not a bare `setTimeout`
  magic number) — one full rotation at the *existing* `scan-spin` keyframe timing
  (`0.9s linear infinite`, unchanged). Picked the full-rotation floor over a shorter one
  because a partial turn on a spinner people have never seen before reads ambiguously (did
  it even move?), where a full 360° is unambiguous motion. Not verified on-device per this
  round's constraints (no adb/phone/emulator) — this is a reasoned default, not a measured
  one; if it still feels laggy or twitchy on the user's phone, that is the number to tune.
- **Guarantee against being stranded**: `scan()` does
  `await Promise.all([reload(), wait(MIN_SPIN_MS)])` inside `try {} finally { scanning.value
  = false }`. Verified (again, by reading `useLibrary.js:113-137`) that `load()` — and
  therefore `reload = () => load({ force: true })` — never rejects; it catches its own
  errors internally and routes them into `status` (`'error'` | `'denied'`). The `finally`
  is there anyway per the coordinator's instruction, so the spin cannot be stranded open
  even if that internal contract ever changes.
- **Teardown**: `spinTimer` (the id from the `wait()` `setTimeout`) is cleared in
  `onUnmounted`. The `wait()` promise itself is not force-resolved on unmount — it is left
  pending forever if the component is torn down mid-spin, which only leaks one small
  closure in the rare case of navigating away during an in-flight rescan, not a live timer
  (the actual ask: "must not leave a timer running").
- **Reduced-motion users**: the existing `@media (prefers-reduced-motion: reduce) {
  .scan-button.spinning svg { animation: none } }` carve-out is untouched. What they get
  INSTEAD of the rotation is the pre-existing `.scan-button:disabled { opacity: 0.6 }` —
  the button visibly dims for the same `scanBusy` window (now stretched to the same
  `MIN_SPIN_MS` floor by this fix, so the dim is long enough to notice too). Not nothing,
  but a much quieter signal than the spin; flagging this rather than presenting it as
  equivalent feedback.
- **Unrelated drift found, not touched**: between this fix and the previous tap-target
  round, `.scan-button` in the working tree had already changed to `width/height: 2.3rem`
  / `svg: 1rem` (not the `2.6rem` / `1.4rem` set in the prior round), the button and the
  count `<p>` had been reordered (button first, then count), and `.head-actions` had lost
  its `gap`. The CSS comment above `.scan-button` still says "Sized to MiniPlayer's
  `.toggle` (2.6rem / 1.4rem svg)", which is now inaccurate — the code no longer matches
  what the comment claims. This happened outside this task's edits (evidence: the
  in-flight template edit for this fix had to be redone against reformatted, differently-
  indented markup that did not match what an earlier round had written) — likely a second,
  concurrent T19 pass from another session, per this file's own note a few sections up
  about "this session's T19 undersized tap target" being flagged separately by F4's
  reviewer. Left as-is because this round's task was scoped to the spin timing only ("Do
  not touch anything else"); the coordinator should reconcile the size/comment mismatch
  and the reordering explicitly, since neither matches what either T19 round actually
  specified.
