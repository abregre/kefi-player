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

Verification actually run: `grep -n "setNotificationId" PlaybackService.kt` → `.setNotificationId(1001)`;
`grep -n "NOTIFICATION_ID" DownloadService.kt` → `private const val NOTIFICATION_ID = 2001`;
`compileDebugKotlin 2>&1 | grep -c '^e:'` → `0`; `npm run apk` → `BUILD SUCCESSFUL`, 61M debug
APK; `adb -s emulator-5554 install -r ...` → `Success`; live `dumpsys notification` → `id=1001`
on channel `playback` for `pkg=com.kefi.player`, as shown above.
