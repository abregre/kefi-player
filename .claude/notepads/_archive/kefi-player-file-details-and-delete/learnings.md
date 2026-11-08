# Learnings

Seeded by the orchestrator. Append, never overwrite.

## Hard rules for this project (learned the expensive way, across three prior plans)

- No emulator, no adb against the user's phone, no release builds. Standing user instructions.
  The ONLY automatable gates are: npm run build, ./gradlew :app:compileDebugKotlin, npm run apk,
  greps, and reading the diff.
- Only the orchestrator runs builds, with every agent idle. Parallel builds from multiple agents
  corrupt each other's results — this happened in an earlier plan.
- `@vue/compiler-sfc` via `node -e` (parse -> compileScript -> compileTemplate) catches build
  errors a plain code read misses (a real redeclaration bug shipped past a "bindings all resolve"
  self-report in a prior plan). Use it whenever a .vue file changes and a build can't be run yet.
- A missing Kotlin import that Kotlin itself does not error on (an unresolved annotation
  degrades silently) surfaces ONLY as `@error.NonExistentClass` in a kapt-generated stub, caught
  only by the orchestrator's actual build. "I checked every import" is not evidence unless the
  check's real output is shown.
- Six-plus agent self-reports across this project's history have been contradicted by the actual
  diff or the actual build. Read the diff. Run the build. Do not tick on a report alone.

## This plan's specific landmines

- `NowPlayingView.vue` already has a `dragging` identifier taken by its seek bar, and a
  `sheetDragging` alias from a drag-to-dismiss composable. Not directly in this plan's file list,
  but if any task touches that file, grep before naming new state.
- `TrackMenu.vue`'s existing `busy`/`notice` idiom and its three-outcome handling in
  `onSetAsRingtone` (granted / sent-to-Settings / rejected) is the template for delete's own three
  outcomes (deleted / user-cancelled / real error). Do not write a naive try/catch.
- `PlaylistView.vue:275-281`'s `window.confirm()` is the established destructive-action gate in
  this app. No new dialog primitive.
- The Room database (LibraryDao/LibraryDatabase) stores NOTHING about track content, only paths.
  A deleted file's path staying referenced in favorites/playlist_items is accepted, precedented
  behaviour (same as a moved/renamed file) — do NOT add cleanup code for it.

## T1

- The constant already referenced in `TrackSource.kt`'s `selection` WHERE-clause (`:163-164`
  after this edit, `:155-156` before it) is `MediaStore.Audio.Media.MIME_TYPE` — reused verbatim
  (same fully-qualified reference) in the `projection` array and the cursor-column lookup. No
  second/differently-named MIME_TYPE constant introduced.
- `mimeType` added as the last field on `Track` (after `dateAdded`), nullable `String?`, read via
  plain `cursor.getString(mimeTypeCol)` (naturally nullable-returning, same idiom as `artist`/
  `album`, no `isNull` check needed for a String column).
- `PlayerPlugin.kt`'s `toJSObject()` got `put("mimeType", mimeType ?: JSONObject.NULL)`, matching
  `artist`/`album`/`albumArtist`'s exact `?: JSONObject.NULL` pattern.
- `player.js` JSDoc: one `@property {string | null} mimeType` line appended to the `PlayerTrack`
  typedef.
- `player.web.js`: all three mocks currently use `.mp3` extensions (`Aspri Mera.mp3`,
  `02 Zeibekiko.mp3`, `Odi.mp3`), so all three got `mimeType: 'audio/mpeg'` — no `.m4a`/other
  extension present among the mocks, so no other MIME value was needed.
- No new import needed in either `.kt` file: `android.provider.MediaStore` (already imported at
  `TrackSource.kt:11`) is the only import touched, and `MIME_TYPE` is a member accessed off the
  already-imported `MediaStore.Audio.Media` class, same as every other column constant in the
  file. Confirmed by reading the full import list — nothing added.
- Brace balance verified with `grep -o '{' file | wc -l` vs `'}' | wc -l` on both `.kt` files:
  `TrackSource.kt` 59/59, `PlayerPlugin.kt` 171/171. This does NOT substitute for a real compile —
  no build was run per this task's MUST NOT DO (T4 was working in the same tree in parallel).

## T2

- **`App.scope` needed NO explicit `Dispatchers.IO` wrap.** Read `App.kt`: `val scope =
  CoroutineScope(SupervisorJob() + Dispatchers.IO)` — already IO-backed, unlike a
  `Dispatchers.Main`/`Default` scope would be. `getFileDetails` therefore just does the
  `MediaMetadataRetriever`/`ContentResolver.query` work directly inside `App.scope.launch { }`,
  exactly like every other method in this file (`playTrack`, `listTracks`, etc.) — no
  `withContext(Dispatchers.IO)` added, no `kotlinx.coroutines.Dispatchers` import needed in
  `PlayerPlugin.kt`. This differs from `TrackSource.kt`'s public functions, which DO each do
  their own `withContext(Dispatchers.IO)` hop — but that is because `TrackSource`'s functions
  are a shared object callable from contexts that are NOT already on `App.scope` (verified by
  reading `TrackSource.kt`'s header comment: "Every public function here does its own
  `withContext(Dispatchers.IO)` hop, so callers ... never need to think about threading" — it
  protects against callers who aren't already on an IO dispatcher). `getFileDetails`'s only
  caller is this plugin method itself, already inside `App.scope.launch`, so the extra hop
  would be redundant, not incorrect.
- **`DATA` column availability at `targetSdk 36` — HONEST CONFIDENCE LEVEL: LOW, not verified.**
  I did not have device/emulator access this session (standing project rule) and did NOT read
  AOSP source for this specific question (unlike the plan's Context section, which cites AOSP
  reads for the metadata-editing dead end and the BITRATE/SAMPLERATE-cache finding — those were
  done during planning, not by this task). What I know with confidence: Android's own official
  docs mark `MediaColumns.DATA` deprecated since API 29 and state its value is only GUARANTEED
  populated for apps holding `MANAGE_EXTERNAL_STORAGE` (which this app deliberately does not
  request, per Must NOT Have 3). Without that permission, the column may still return a real
  path in practice on many devices/API levels (commonly reported for MediaStore audio rows
  queried via `ContentResolver`, as opposed to direct file-path access) — but I could not
  confirm this against this project's actual `targetSdk 36` behavior by reading Android's
  source this session, so I implemented the only thing that is safe regardless of which way
  the truth actually falls: query it, and treat ANY outcome other than "cursor has a row with a
  non-blank string in that column" as `null` — no assumed value, no empty string, no exception
  escaping past the `try/catch` in `absolutePathOf()`. T3 and F5 (the user's hands-on device
  pass) are the only ways this actually gets a definitive real-device answer.
- **Retriever release pattern**: plain `try { retriever.setDataSource(...); ... } finally {
  retriever.release() }`, no `use {}` — matches `TrackSource.kt`'s `loadArtworkBitmap`'s exact
  idiom for the same class (confirmed by reading it, `TrackSource.kt:360-369`), since
  `MediaMetadataRetriever` isn't guaranteed `Closeable` across this app's `minSdk 24` floor.
- **Imports added to `PlayerPlugin.kt`** (it had neither before): `android.media.
  MediaMetadataRetriever` (inserted alphabetically before the existing `android.media.
  RingtoneManager`) and `android.provider.MediaStore` (inserted alphabetically before the
  existing `android.provider.Settings`). No `kotlinx.coroutines.Dispatchers` import added (see
  above). `Uri`/`JSObject`/`JSONObject`/`PluginCall`/`PluginMethod` were all already imported
  and reused as-is.
- **Numeric types chosen**: `bitrate` as `Long` (`toLongOrNull()` — bits-per-second can exceed
  `Int` range for high-bitrate/lossless files), `sampleRate` as `Int` (`toIntOrNull()` — real
  sample rates top out around 384000, comfortably inside `Int`). Both written via
  `JSObject().put(key, value ?: JSONObject.NULL)`, matching T1's exact `mimeType` idiom.
- **`player.web.js` mock**: chose to special-case by `uri` (`mock://1`/`2`/`3`) rather than one
  generic value, so the details step's on-demand fields look as distinct/plausible as the free
  fields already are in `npm run dev`/QA — a single generic bitrate/sampleRate for all three
  tracks would look obviously fake sitting next to real per-track title/artist/album variety.
  Mock values: track 1 `128000`/`44100`, track 2 `192000`/`44100`, track 3 `320000`/`48000`
  bps/Hz, with a plausible absolute path under each mock's existing `folder`/`displayName`. An
  unrecognised uri resolves plausible-shaped `null`s rather than rejecting — the mock must
  never throw, per the plan.
- Verification run (no build, per MUST NOT DO): `grep -n "getFileDetails\|METADATA_KEY_BITRATE\|
  METADATA_KEY_SAMPLERATE\|MediaStore.MediaColumns.DATA"` across all three files shows the
  method and both retriever/column references in every file; import-audit greps for
  `MediaMetadataRetriever`/`MediaStore`/`Uri` each show their import line present;
  `node --check` passed clean on both `.js` files; brace balance on `PlayerPlugin.kt`: 187
  open / 187 close (T1 left it at 171/171; this task's net-new braces from `getFileDetails` +
  `absolutePathOf` account for the +16).

## T4

**Post-hoc fix (caught by the orchestrator's real `./gradlew :app:compileDebugJavaWithJavac`,
not by this task's own manual read or by `compileDebugKotlin`):** the first version of this
code declared `intentSenderLauncher` (whose field initializer's lambda reads
`pendingIntentSenderCallback`) BEFORE `pendingIntentSenderCallback` itself in source order. That
failed javac with "illegal forward reference". The rule: javac's forward-reference check for
instance field initializers is purely textual/declaration-order — it does not matter that the
lambda body only runs later, long after both fields exist at runtime (the lambda fires only when
the user responds to the system dialog, well after construction). javac rejects the reference at
the textual point it appears, based on declaration order alone, not actual execution order. Fix
was a pure reordering: `pendingIntentSenderCallback` now declared first, `intentSenderLauncher`
after it — no logic change. **Lesson for anyone adding another field-initialized
`ActivityResultLauncher` (or any field whose initializer's lambda/anonymous-class body reads a
sibling instance field) to this file: declare the field it reads BEFORE the launcher field, or
javac will reject it even though the read is safe at runtime.** `compileDebugKotlin` alone did
NOT catch this — nothing in Kotlin calls into `MainActivity.java` yet, so only a real
`compileDebugJavaWithJavac` (or a Java-side caller/test) exercises this file's own compilation.
Verified after the fix: `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ./gradlew
:app:compileDebugJavaWithJavac` → `BUILD SUCCESSFUL`; `./gradlew :app:compileDebugKotlin` →
`BUILD SUCCESSFUL` (re-run, confirms no regression). Note: `JAVA_HOME` must point at a full JDK
with `javac` (`/usr/lib/jvm/java-21-amazon-corretto` or `/usr/lib/jvm/java-17-openjdk-amd64`) —
the shell's default `JAVA_HOME` was a broken path, and `/usr/lib/jvm/java-21-openjdk-amd64` is a
JRE-only install with no `javac` and no `JAVA_COMPILER` toolchain capability.

**Approach used: PRIMARY (`registerForActivityResult` on `MainActivity`). No fallback needed.**
The deprecated `startIntentSenderForResult`/`handleOnActivityResult` path was NOT used — the
primary approach compiled cleanly on paper with no blocker found. Confidence is HIGH: every
API used below was verified against this project's actual *resolved* dependency versions'
source code (extracted from `~/.gradle/caches/.../*-sources.jar`, pulled by exact version from
`android/variables.gradle`: `androidx.appcompat:appcompat:1.7.1`,
`androidx.fragment:fragment:1.8.9`, `androidx.activity:activity:1.11.0`), not from
documentation or memory. Specifically confirmed by reading source:
- `AppCompatActivity.java` (1.7.1): `public class AppCompatActivity extends FragmentActivity ...`
- `FragmentActivity.java` (1.8.9): `public class FragmentActivity extends ComponentActivity ...`
- `ComponentActivity.kt` (1.11.0) implements `ActivityResultCaller`, which declares
  `fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback:
  ActivityResultCallback<O>): ActivityResultLauncher<I>` — and its own KDoc says this "must be
  called unconditionally, as part of initialization path, typically as a field initializer",
  confirming the field-initializer placement used below is the intended pattern, not a hack.
- `IntentSenderRequest.kt` (1.11.0): `class Builder(private val intentSender: IntentSender)` with
  `fun build(): IntentSenderRequest` — confirms `new IntentSenderRequest.Builder(intentSender).build()`.
- `ActivityResultContracts.kt` (1.11.0): `class StartIntentSenderForResult : ActivityResultContract
  <IntentSenderRequest, ActivityResult>()` — confirms the contract's input/output types.
- `ActivityResult.kt` (1.11.0): `class ActivityResult(val resultCode: Int, ...)` — confirms
  `result.getResultCode()` from Java (Kotlin property → generated getter).
- Extra corroboration, not just chain-walking: Capacitor's own `Bridge.java` (~line 1002,
  `registerForActivityResult`) already calls `context.registerForActivityResult(contract,
  callback)` directly on its `AppCompatActivity` field for its own `@ActivityCallback` plumbing —
  i.e. this exact call already executes successfully one layer up this same inheritance chain in
  this app's real dependency graph today.

**What T5 must call — exact public signature:**
```java
// MainActivity.java
public interface IntentSenderResultCallback {
    void onIntentSenderResult(boolean approved);
}
public void launchIntentSenderForResult(IntentSender intentSender, IntentSenderResultCallback callback)
```
Generic: no delete-specific strings/logic anywhere in `MainActivity`. Reusable for ANY future
IntentSender-launching need, not just delete. Holds at most one in-flight callback (a single
field, `pendingIntentSenderCallback`) — correct because the user can only be looking at one
system dialog at a time; T5 must not call `launchIntentSenderForResult` again before the
callback from a prior call has fired.

**Exact call sequence T5 will need, traced end to end (from `PlayerPlugin.kt`, a Kotlin
`Plugin` subclass, NOT an Activity):**
1. `PlayerPlugin.kt`'s `deleteTrack` catches `RecoverableSecurityException` (API 29) and reads
   `e.userAction.actionIntent.intentSender`, OR (API 30+) calls
   `MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))` and reads
   `.intentSender` off the returned `PendingIntent`. Either way it ends up holding a plain
   `android.content.IntentSender`.
2. `PlayerPlugin.kt` gets the Activity via Capacitor's own `Plugin.getActivity()` (returns
   `AppCompatActivity`, per `Plugin.java:208`) and casts it: `val activity = getActivity() as
   MainActivity` (or `bridge.getActivity() as MainActivity` — same object, `getActivity()` on
   `Plugin` just delegates to `bridge.getActivity()`, confirmed reading `Plugin.java`).
3. `PlayerPlugin.kt` calls `activity.launchIntentSenderForResult(intentSender) { approved -> ... }`
   — a Kotlin trailing lambda SAM-converts to `IntentSenderResultCallback` automatically since it
   is a single-method Java interface.
4. Inside `MainActivity`: `pendingIntentSenderCallback` is set to that callback, then
   `intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())` — this is
   `ActivityResultLauncher<IntentSenderRequest>.launch(IntentSenderRequest)`, which under the
   hood calls the real `Activity.startIntentSenderForResult(...)` for the caller.
5. The system shows its dialog (Android's delete-confirmation UI). When the user responds,
   AndroidX's `ActivityResultRegistry` delivers an `ActivityResult` back into the lambda
   registered as the launcher's callback (the one built in the `intentSenderLauncher` field
   initializer).
6. That lambda reads `pendingIntentSenderCallback`, clears the field to null (so a second launch
   later doesn't accidentally re-fire it), and calls
   `callback.onIntentSenderResult(result.getResultCode() == RESULT_OK)`.
7. That callback is the same lambda `PlayerPlugin.kt` passed in step 3 — control returns to
   `deleteTrack` with a plain `Boolean` (`approved`/not), which is where T5's own three-outcome
   mapping (deleted / user-cancelled / real error) takes over. Everything past this point is
   entirely T5's responsibility.

**Files touched:** only `android/app/src/main/java/com/kefi/player/MainActivity.java`. No new
file was needed — the mechanism is small enough (one interface, one field, one method) to live
directly on `MainActivity` without adding a separate broker class. `onCreate`/`onNewIntent`/
`captureSharedUrl`/the `WebViewListener.onRenderProcessGone` override are byte-for-byte
unchanged from before this task; the new field, interface, and method were inserted before
`onCreate`, after the class declaration.

**Not done (out of scope for T4, confirmed against MUST NOT DO):** no `deleteTrack`, no
`RecoverableSecurityException` catch, no `MediaStore.createDeleteRequest` call, no edits to
`PlayerPlugin.kt`/`TrackSource.kt`/`player.js`/`player.web.js`/`AndroidManifest.xml`. No
manifest entry is needed for this mechanism — registering an `ActivityResultLauncher` requires
no permission or manifest declaration.

**Not verified (explicitly out of reach this session, flagged per this task's instructions):**
no Gradle build was run (forbidden by this task's MUST NOT DO), so this is a manual trace against
real dependency sources, not a compiler's confirmation. The one residual unknown a real compile
could still catch: whether Kotlin's SAM conversion for `IntentSenderResultCallback` (a plain Java
interface, not annotated `fun interface`-equivalent — but Java single-abstract-method interfaces
SAM-convert from Kotlin lambdas automatically, this is standard behavior, not something specific
to this interface) behaves as expected in `PlayerPlugin.kt` once T5 writes that call — flagged for
T5/the orchestrator to watch for at compile time, not because there is a specific reason to doubt
it.

## T3

**File touched: `src/components/TrackMenu.vue` only** (plus this notepad). `src/i18n/en.js`/`el.js`
were NOT touched, per the orchestrator amendment — T8 owns them. Every string below renders as its
raw `trackMenu.*` key until T8 lands; that is expected, not a defect.

**Step machine**: added a third value, `'details'`, alongside the file's existing `'menu'`/
`'playlist'` (the file's actual step names — the plan's own text called them `'menu'`/
`'addToPlaylist'`, but the real code uses `'playlist'`; went with what the file already has).
`openDetailsStep()` sets `step.value = 'details'`, resets `fileDetails`/`fileDetailsLoading`,
snapshots `activeTrack.value.uri`, then awaits `Player.getFileDetails({ uri })`. `backToMenu()`
(already the shared back-navigation function for the `'playlist'` step) now also resets
`fileDetails`/`fileDetailsLoading` and is reused as-is for `'details'` — no second back function.
The header's back-button `v-if` and the `sheet-title` ternary were both extended to cover
`'details'` the same way they already covered `'playlist'`.

**Formatters reused, none duplicated**:
- `formatMs` (duration) and `formatBytes` (size) — both already existed in `src/utils/format.js`
  and were NOT previously imported into `TrackMenu.vue` (checked: this file had zero import from
  `utils/format.js` before this change, unlike `TrackRow.vue`/`JobCard.vue`/`NowPlayingView.vue`
  which already import one or the other). Added `import { formatBytes, formatMs } from
  '../utils/format'`, same import path/shape those three files already use.
- **No date formatter exists anywhere in this codebase** — checked `format.js` in full and grepped
  every `.vue`/`.js` file for `toLocaleDateString`/`formatDate`; zero hits besides `dateAdded`'s own
  raw-number usages in `player.js`'s JSDoc, `usePlaylists.js`'s sort, and `player.web.js`'s mocks.
  Wrote a local `formatDateAdded()` in this file: `dateAdded ? new
  Date(dateAdded * 1000).toLocaleDateString() : t('trackMenu.notAvailable')` — converts epoch
  SECONDS to the milliseconds `Date` expects, and treats `0` (MediaStore's "never recorded one"
  sentinel, per `usePlaylists.js`'s own `dateAdded === 0` check) as not-available rather than
  rendering the 1970 epoch date. This is a single-call-site helper, not a new module — if a second
  call site ever needs it, it should move to `format.js` then, not before.
- `formatBitrate()`/`formatSampleRate()` are also new local helpers in this file (not in
  `format.js`), since both are trivial one-line unit conversions specific to this one screen's
  display, same reasoning as `formatDateAdded()`.

**Units displayed** (per T2's notepad: `getFileDetails` resolves `bitrate` in bits-per-second,
`sampleRate` in Hz, both nullable `number`):
- Bitrate shown in **kbps**: `Math.round(bitrate / 1000)} kbps` (e.g. `128000` -> `"128 kbps"`).
- Sample rate shown in **kHz**, one decimal only where it matters: `` `${Number((sampleRate /
  1000).toFixed(1))} kHz` `` (e.g. `44100` -> `"44.1 kHz"`, `48000` -> `"48 kHz"`, not `"48.0 kHz"` —
  `Number(...)` drops the trailing zero).
- Both, and `absolutePath`, fall back to `t('trackMenu.notAvailable')` when `null` — whether from a
  genuinely-null field on a resolved response, or from a rejected `getFileDetails` call (caught and
  swallowed; see below).

**Loading state**: `fileDetailsLoading` (ref) is true from the moment `openDetailsStep()` fires
until the fetch settles; the three on-demand rows (`detailPath`/`detailBitrate`/`detailSampleRate`)
show `t('trackMenu.detailLoading')` while true, the real/fallback value once false. Free fields
(title/artist/album/albumArtist/trackNumber/year/duration/size/dateAdded/folder/mimeType) render
immediately from `activeTrack.value` with no loading state, per the plan.

**Nullable free fields**: `artist`/`album`/`albumArtist`/`trackNumber`/`year` can be `null` per
`PlayerTrack`'s own typedef — each falls back to `t('trackMenu.notAvailable')` via `??`, so nothing
ever renders the literal word "null". `folder` can be empty string (not null) per its own doc
comment, so it uses `activeTrack.folder || t('trackMenu.notAvailable')` (falsy check, not `??`).
`mimeType` null gets its OWN distinct fallback, `t('trackMenu.unknownFormat')`, per the task's
explicit instruction, rather than sharing the generic `notAvailable` string.

**A failed `getFileDetails()` call does NOT produce a `notice`.** Decision, matching the inherited
wisdom's framing: unlike `onSetAsRingtone`'s three real outcomes (a destructive/permission action
where failure is worth surfacing), a failed details lookup just means "we couldn't show three extra
fields" — the three on-demand rows silently show `notAvailable` (via the same `catch` that leaves
`fileDetails.value` at `null`) and nothing else on the sheet changes. `busy` is also NOT set for this
step (it exists specifically to block double-tapping the ringtone/playlist/favourite mutations
during a native round trip; viewing details isn't a mutation and has no double-tap hazard to guard
against).

**Stale-fetch guard**: `openDetailsStep()` snapshots `activeTrack.value.uri` into a local `uri`
before awaiting, and only writes `fileDetails.value`/clears `fileDetailsLoading.value` if
`activeTrack.value?.uri === uri` still holds when the promise settles — guards the same
re-entrancy case the file's existing top-of-file comment and `watch(activeTrack, ...)` already
call out (`openTrackMenu` can retarget `activeTrack` to a different track while a previous
sheet's async call is still in flight). `watch(activeTrack, ...)` was also extended to reset
`fileDetails`/`fileDetailsLoading` on every track change, same as it already resets
`step`/`creatingPlaylist`/`notice`.

**New markup**: a sixth `.option` row (icon: a circled "i") in the `step === 'menu'` template,
identical shape to the existing five, `@click="openDetailsStep"`. The details step itself is NOT
built from `.option` buttons (those are for tappable actions) — it's a new `<dl class=
"details-list">` of `.detail-row`/`.detail-label`/`.detail-value` term/description pairs, styled in
`<style scoped>` to match this file's existing token usage (`var(--text-muted)`, `var(--border)`,
same font-size scale as `.option-meta`/`.sheet-title`). No new `.vue` component, no new dialog
primitive — everything lives inside the existing sheet.

**New i18n keys needed (T8: add both files, identical order, informal register matching the
existing `trackMenu.*` entries in `el.js:58-76`)**:

| Key | Proposed EN | Proposed EL |
|---|---|---|
| `trackMenu.fileDetails` | File details | Στοιχεία αρχείου |
| `trackMenu.detailTitle` | Title | Τίτλος |
| `trackMenu.detailArtist` | Artist | Καλλιτέχνης |
| `trackMenu.detailAlbum` | Album | Άλμπουμ |
| `trackMenu.detailAlbumArtist` | Album artist | Καλλιτέχνης άλμπουμ |
| `trackMenu.detailTrackNumber` | Track number | Αριθμός κομματιού |
| `trackMenu.detailYear` | Year | Έτος |
| `trackMenu.detailDuration` | Duration | Διάρκεια |
| `trackMenu.detailSize` | Size | Μέγεθος |
| `trackMenu.detailDateAdded` | Date added | Ημερομηνία προσθήκης |
| `trackMenu.detailFolder` | Folder | Φάκελος |
| `trackMenu.detailMimeType` | Format | Μορφή |
| `trackMenu.detailPath` | File path | Διαδρομή αρχείου |
| `trackMenu.detailBitrate` | Bitrate | Bitrate |
| `trackMenu.detailSampleRate` | Sample rate | Συχνότητα δειγματοληψίας |
| `trackMenu.detailLoading` | Loading… | Φόρτωση… |
| `trackMenu.notAvailable` | Not available | Μη διαθέσιμο |
| `trackMenu.unknownFormat` | Unknown format | Άγνωστη μορφή |

`trackMenu.back` and `trackMenu.close` (header controls) already exist in both locale files from
the earlier plan — reused as-is, no new key needed for them.

**Verification run** (no build, per MUST NOT DO): `node -e` with `@vue/compiler-sfc`'s `parse` ->
`compileScript` -> `compileTemplate` on `TrackMenu.vue` — `compileScript OK, bindings: 41`;
`compileTemplate OK`, zero errors from either step. `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<'
src/components/TrackMenu.vue | grep -v "t("` — empty (exit 1, no hardcoded text). `grep -n
"getFileDetails\|mimeType\|formatMs" src/components/TrackMenu.vue` — shows the new import, the
`Player.getFileDetails` call, the `mimeType` fallback rendering, and the `formatMs` duration call,
confirmed against the actual file (not just self-reported).

## T5

**SAM conversion for `IntentSenderResultCallback` (T4's flagged residual unknown) works exactly
as expected — no issue.** `(getActivity() as MainActivity).launchIntentSenderForResult(intentSender)
{ approved -> continuation.resume(approved) }` compiled cleanly with a trailing Kotlin lambda,
confirmed by a real `compileDebugKotlin` (and `compileDebugJavaWithJavac`, since `MainActivity.java`
is on the classpath either way). `getActivity()` (Capacitor's `Plugin.getActivity()`, returns
`AppCompatActivity`) needed an explicit `as MainActivity` cast, exactly as T4's trace predicted —
no `bridge.getActivity()` detour was needed, `getActivity()` alone was enough.

**Coroutine bridging — the exact code, and the one real gotcha in it:**
```kotlin
private suspend fun awaitIntentSenderApproval(intentSender: IntentSender): Boolean =
    suspendCancellableCoroutine { continuation ->
        (getActivity() as MainActivity).launchIntentSenderForResult(intentSender) { approved ->
            continuation.resume(approved)
        }
    }
```
The gotcha: `CancellableContinuation<T>.resume(value)` with a SINGLE argument is NOT a member of
`CancellableContinuation` itself — I checked this project's actual resolved
`kotlinx-coroutines-core-jvm:1.10.2` sources (`android/app/build.gradle:127`) directly rather than
assume it. `CancellableContinuation<T>`'s own two `resume` members both require an
`onCancellation` second parameter (one deprecated-with-warning, one not). The single-argument
`resume(value)` that actually gets called above resolves instead to the plain Kotlin STDLIB
extension `kotlin.coroutines.resume` (`Continuation<T>.resume(value): Unit =
resumeWith(Result.success(value))`) — usable here only because `CancellableContinuation<T> :
Continuation<T>`. This needs its own import, separate from
`kotlinx.coroutines.suspendCancellableCoroutine`: **`import kotlin.coroutines.resume`**. Missing
just this one import would not fail with "unresolved reference" in an obvious way for a newcomer
expecting it to live in `kotlinx.coroutines` — worth flagging for anyone extending this pattern
elsewhere in the app.

**Imports added to `PlayerPlugin.kt`** (alphabetically placed among the existing, already
roughly-alphabetical import block): `android.app.RecoverableSecurityException`,
`android.content.IntentSender`, `kotlin.coroutines.resume`,
`kotlinx.coroutines.suspendCancellableCoroutine`. No import needed for `MainActivity` itself or
its nested `IntentSenderResultCallback` interface — both are in the same `com.kefi.player`
package as `PlayerPlugin.kt`, and the callback is passed as a bare trailing lambda (SAM
conversion), never referenced by its interface name.

**`createDeleteRequest`'s "system already deletes it" contract — verified by reading the actual
source, not assumed, per the task's explicit instruction to double-check this.** Read
`MediaStore.java`'s doc comment on `createDeleteRequest` directly
(`/home/nonos/Android/Sdk/sources/android-35/android/provider/MediaStore.java:1508-1535`): *"When
the user approves this request, `ContentResolver.delete` will be called on these items... The
requested operation will have completely finished before this activity result is delivered."*
This is unambiguous: on approval, the SYSTEM calls `delete()` itself and finishes before this
plugin's callback ever fires. `deleteTrack`'s `>= R` branch therefore resolves `{ deleted: true }`
directly on `approved == true`, with NO second `delete()` call — confirmed correct, not a
guess, and the KDoc on `deleteTrack` quotes this exact source comment so a future reader doesn't
have to re-derive it.

**Row-count-zero-is-a-failure decision (the `< Q` branch, and the `== Q` retry branch, which share
one private helper `resolveDeleteRowCount(call, rowsDeleted)`):** `ContentResolver.delete`
returning `0` means no row matched/was removed — treated as a genuine `call.reject("No matching
row was deleted")`, never a silently-resolved `{ deleted: true }` with a fabricated success, and
never folded into the `cancelled` outcome either (a `0` row count is not the user declining
anything — it is the delete operation itself not doing what was asked). `> 0` resolves `{
deleted: true }`. This same helper is called from both the plain `< Q` path and the `== Q`
post-approval retry, since both end in the identical `resolver.delete(uri, null, null)` call and
must judge it identically — a private helper here avoids the two call sites silently drifting
apart, not premature abstraction for its own sake.

**Exact `RecoverableSecurityException` shape used, verified against
`/home/nonos/Android/Sdk/sources/android-35/android/app/RecoverableSecurityException.java`:**
`e.userAction` is `getUserAction(): RemoteAction`, `.actionIntent` is `RemoteAction.getActionIntent():
PendingIntent`, `.intentSender` is `PendingIntent.getIntentSender(): IntentSender` — exactly the
chain the plan's Must Have 4 and T4's own trace both name, confirmed by reading the actual class,
not assumed from the plan text alone.

**Three-outcome shape delivered, matching `onSetAsRingtone`'s rigor exactly:** `{ deleted: true }`
(a real deletion, all three SDK branches), `{ deleted: false, reason: "cancelled" }` (the user
declined the system dialog — built by a small shared `cancelledDeleteResult()` helper so both the
`== Q` and `>= R` branches emit the identical shape), or a genuine `call.rejectWith(...)` (a bad
uri, an unreadable/already-gone row post-`0`-count, or any other unexpected `Throwable` — the
`try/catch (t: Throwable)` wraps the entire `App.scope.launch` body, same idiom as every other
method in this file).

**Compile results — both green, run once this task's own code was complete, per this task's
explicit one-time exception to the no-build rule:**
```
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk \
  ./gradlew :app:compileDebugKotlin
→ BUILD SUCCESSFUL in 2s

JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto ANDROID_HOME=/home/nonos/Android/Sdk \
  ./gradlew :app:compileDebugJavaWithJavac
→ BUILD SUCCESSFUL in 1s
```
No other agent was editing `.kt`/`.java` files at the time (T3 only touches `TrackMenu.vue` +
i18n, confirmed disjoint). Brace balance before this task (per T2's entry): 187/187. After this
task: 211/211 (net +24, matching the size of `deleteTrack` plus its three private helpers).

## T6

**Placement**: `deleteTrack`'s `@property` entry was inserted in `player.js` directly after
`getFileDetails`'s entry (both immediately follow `setAsRingtone`), matching the task's
instruction that the two belong together (`TrackMenu.vue`-only, on-demand, per-track native
calls). The web mock method was placed the same way in `player.web.js`: directly after
`getFileDetails()`, before `checkAudioPermission()`.

**Exact final JSDoc text added to `player.js`** (inserted between `getFileDetails`'s entry and
`addListener`'s entry):
```
 * @property {(options: { uri: string }) => Promise<{ deleted: boolean, reason?: string }>} deleteTrack
 *   Deletes the file at `uri` from device storage. Three real outcomes, not just the two the
 *   type signature above shows: resolves `{ deleted: true }` when the file was actually
 *   deleted; resolves `{ deleted: false, reason: 'cancelled' }` when the user declines the
 *   OS-owned delete confirmation — a normal, expected outcome, never surfaced as an error; or
 *   the promise REJECTS for a genuine unexpected failure (a malformed uri, an I/O error, a row
 *   that was already gone). On Android this always routes through an OS-owned confirmation
 *   dialog before anything is removed, and which exact dialog appears varies by Android
 *   version — a detail the caller never needs to branch on differently, but should know
 *   exists, the same way `setAsRingtone`'s own outcome can redirect the user to system
 *   Settings.
```
Documentation depth/structure was matched to `setAsRingtone`'s entry (short lead sentence on
what the call does, then a description of the outcome shape and what the UI's cue is for each
branch) — but scaled up slightly since `deleteTrack` genuinely has three outcomes (two resolved
shapes plus a rejection) where `setAsRingtone`'s prose only walks through its `granted: false`
branch explicitly. Per T5's real native shape (`## T5` above): `{ deleted: true }` on all three
`SDK_INT` branches' success path (including the API 29 retry-after-approval), `{ deleted:
false, reason: 'cancelled' }` on a declined system dialog (API 29 and 30+), and a rejection for
a genuine failure including a zero-rows-affected delete — the JSDoc's three-outcome prose
mirrors this exactly, not a guess.

**Exact web mock added to `player.web.js`**:
```js
  /**
   * Resolves `{ deleted: true }` unconditionally, for any uri. This does NOT simulate the real
   * permission dance — there is no `RecoverableSecurityException` equivalent here, no
   * confirmation-dialog equivalent, no `SDK_INT` branching. A browser has no real file to
   * delete and no OS dialog to show, so this mock exists purely so `npm run dev`/offline QA can
   * exercise the "track disappeared, reload the library" UI flow, not the delete mechanics
   * themselves.
   */
  async deleteTrack({ uri }) {
    return { deleted: true }
  }
```
`{ uri }` is destructured (even though unused) to keep the mock's signature visibly matching the
real contract's `{ uri: string }` options shape — consistent with `getFileDetails({ uri })`
right above it, which also destructures and uses it. `setAsRingtone()` in this same file ignores
its options entirely (empty parameter list) since its mock never branches on them; `deleteTrack`
follows the `getFileDetails` style instead since the task's own expected signature named the
destructured form explicitly.

**Shape check — JSDoc type vs. mock's actual resolved value, confirmed to agree exactly:**
type is `{ deleted: boolean, reason?: string }` (an optional `reason`); the mock resolves
`{ deleted: true }` — a valid instance of that type with the optional key omitted, no extra key
added, no missing required key.

**Verification run** (no build, per MUST NOT DO):
```
$ grep -n "deleteTrack" src/plugins/player.js src/plugins/player.web.js
src/plugins/player.web.js:239:  async deleteTrack({ uri }) {
src/plugins/player.js:128: * @property {(options: { uri: string }) => Promise<{ deleted: boolean, reason?: string }>} deleteTrack

$ node --check src/plugins/player.web.js
(exit 0, no output)
```

**Not done (confirmed against MUST NOT DO):** no `android/`, `TrackMenu.vue`, `src/i18n/*`, or
plan-file edits. No UI wiring for delete (T7's job). No new dependency. No build/gradle/adb/
emulator/`npm run qa` run.

## T7

**File touched: `src/components/TrackMenu.vue` only** (plus this notepad). Per the orchestrator
amendment, `src/i18n/en.js`/`el.js` were NOT touched — T8 owns them. This is now the SEVENTH
`.option` row (T3 already added "File details" as the sixth); placed LAST, after "File details",
matching the common convention of putting a destructive action at the end of a menu rather than
interleaving it among the non-destructive ones above it.

**Close-sheet-on-success decision, and why:** the orchestrator's own EXPECTED OUTCOME section
flagged a real tension — the plan's T7 checkbox text literally says "close the sheet" on
`deleted: true`, but closing calls `closeTrackMenu()`, which nulls the shared `activeTrack` ref,
which the file's own `watch(activeTrack, ...)` uses to reset `notice` to `null` on every track
change (including to `null`). Closing immediately would therefore wipe the success notice before
the user could read it. I read `onSetAsRingtone` (`TrackMenu.vue`'s existing three-outcome
precedent, ~L191-216): on `granted: true` it sets `notice` and does **NOT** call `close()`
anywhere — the sheet stays open with the notice showing, and the user dismisses it manually
(backdrop tap or the header close button) whenever they're done reading it. I followed that exact
precedent for `deleted: true` too: set `notice`, call `useLibrary().reload()`, do **NOT** call
`closeTrackMenu()`. This directly contradicts the plan checkbox's literal "close the sheet" text,
but the orchestrator's own instruction explicitly told this task to resolve that tension by
following `onSetAsRingtone`'s established precedent "rather than inventing a new one" — closing
here would have been the new (uninvited) behavior, not the precedented one.

**Three outcomes, matching `onSetAsRingtone`'s rigor exactly (T5/T6's contract, verified against
`## T5`/`## T6` above):**
- `{ deleted: true }` → `notice = { kind: 'success', text: t('trackMenu.deleteSuccess') }`, then
  `useLibrary().reload()` (module-level cache, never re-queries on its own — see
  `useLibrary.js:271-273`'s own doc comment). Sheet stays open (see decision above).
- `{ deleted: false, reason: 'cancelled' }` → `notice = { kind: 'info', text:
  t('trackMenu.deleteCancelled') }`. No `reload()` call (nothing on disk changed — matches
  `onSetAsRingtone`'s own `granted: false` branch, which is also `kind: 'info'`, not `'error'`,
  for the same "nothing actually went wrong" reasoning). Sheet stays open.
- A caught rejection → `notice = { kind: 'error', text: t('trackMenu.actionFailed') }` — reused
  the SAME existing generic key `onSetAsRingtone`'s own catch block already uses, rather than
  inventing a near-duplicate `deleteFailed` key with an identical "something went wrong" meaning.
  Sheet stays open.
- `busy.value = true` set right after the `window.confirm()` gate passes, `notice.value = null`
  cleared at the same point (matches `onSetAsRingtone`'s exact ordering), `busy.value = false` in
  a `finally`, same idiom as every other async handler in this file.

**The `window.confirm()` gate uses `displayName`, not `title`,** per the task's explicit
instruction — a destructive, irreversible action should name the real file on disk, not a tag
value that can be missing or edited-looking. Matches `PlaylistView.vue`'s `deleteThis()`
(`~L280-281`) for the interpolation shape (`{ name }`), just a different source field.

**New icon**: a plain trash-can outline (`viewBox 0 0 24 24`, `stroke-width 1.7`, no fill),
matching every other `.option-icon` svg in this file exactly in markup shape.

**New i18n keys needed (T8: add to both files, `trackMenu.*` namespace) — CONFIRMED NO
COLLISION with T3's 18 keys listed above** (`fileDetails`, `detailTitle`, `detailArtist`,
`detailAlbum`, `detailAlbumArtist`, `detailTrackNumber`, `detailYear`, `detailDuration`,
`detailSize`, `detailDateAdded`, `detailFolder`, `detailMimeType`, `detailPath`, `detailBitrate`,
`detailSampleRate`, `detailLoading`, `notAvailable`, `unknownFormat` — none overlap with any key
below):

| Key | Proposed EN | Proposed EL |
|---|---|---|
| `trackMenu.deleteFromDevice` | Delete from device | Διαγραφή από τη συσκευή |
| `trackMenu.confirmDelete` | Delete "{name}"? This can't be undone. | Διαγραφή του «{name}»; Δεν αναιρείται. |
| `trackMenu.deleteSuccess` | Deleted | Διαγράφηκε |
| `trackMenu.deleteCancelled` | Delete cancelled | Η διαγραφή ακυρώθηκε |

`trackMenu.confirmDelete`'s EN/EL text is deliberately copied verbatim (just the key namespace
differs, `trackMenu.*` vs `playlists.*`) from `playlists.confirmDelete` (`en.js:46`, `el.js:45`:
`'Delete "{name}"? This can’t be undone.'` / `'Διαγραφή της «{name}»; Δεν αναιρείται.'`) — same
interpolation shape (`{name}`), same wording, so the two destructive-delete confirms in this app
read as one consistent voice rather than two independently-worded ones. Note the EL article
differs (`του` here vs. `της` there) since a track's grammatical gender differs from a playlist's
in Greek — intentional, not a typo. `trackMenu.actionFailed` (existing key, T1-era) is REUSED for
the rejection branch, not duplicated — see decision above.

**Verification run** (no build, per MUST NOT DO):
- `@vue/compiler-sfc` via `node -e` (parse → compileScript → compileTemplate): `compileScript OK,
  bindings: 43` (up from T3's 41 — the two new bindings are the `useLibrary` import and
  `onDeleteFromDevice`), `compileTemplate OK`, zero errors from either step.
- `grep -n "deleteTrack\|window.confirm\|reload()" src/components/TrackMenu.vue` shows the
  confirm gate (L159), the `Player.deleteTrack` call (L166), and `useLibrary().reload()` (L176).
- `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/components/TrackMenu.vue | grep -v "t("` — empty
  (exit 1), no hardcoded text introduced.
- `git status --porcelain src/i18n/en.js src/i18n/el.js` — empty, confirming neither locale file
  was touched.

**Not done (confirmed against MUST NOT DO):** no Room cleanup call (Must NOT Have 5), no
Now-Playing special-casing (Must NOT Have 9), no `src/i18n/*` edit, no new dependency, no new
dialog component/idiom, no build/gradle/adb/emulator/`npm run qa` run, no plan-file checkbox
edit.

## T9

**File touched: `scripts/qa-playback.sh` only** (plus this notepad). New section appended
between the file's final `goto_library` call and `step "Summary"`, under one `step "T9 — File
details menu row and delete-from-device confirm gate"` header (a second, narrower comment
banner — not a second `step()` call — introduces the mocked-delete sub-flow, matching how other
multi-part sections in this file use plain `#` comment dividers rather than stacking `step()`
calls within one logical flow).

**A real quoting subtlety in this script's `js()` helper, discovered while writing this task,
that the T16 section above appears to have gotten wrong (not fixed here — out of scope, T16's
own file/checks were untouched):** `cdp.mjs` does
`console.log(JSON.stringify(data.result.result.value ?? null))`. For a JS expression that
evaluates to a **string**, `JSON.stringify` wraps it in literal quote characters, and
`console.log` prints a string argument verbatim — so `$(js "someElement.textContent")` captures
the quote characters AS PART OF the bash variable's value (e.g. `"Άσπρη Μέρα"`, 14 characters,
not 10). Numbers/booleans/null come back unquoted. The file's own EXECUTED downloader section
already knows this and handles it correctly (`[[ "$HASH_AFTER" == '"#/library"' ]]` — note the
literal embedded quotes in the bash comparison literal). But `pathof_by_title()`
(T16, ~line 817) passes a `textContent`-derived variable straight into a `node -e` script that
does `t.title === process.argv[2]` with NO unwrap — since `process.argv` values are passed
through as literal text including any embedded quote characters, that comparison is (as far as
I can tell from reading it, not running it — T16 has never executed either) comparing a quoted
string against an unquoted `t.title` field and would never match. Flagging this for whoever
next touches T16, not fixing it here (out of this task's scope, and T16's file "TEXT" is not
`qa-playback.sh`... it IS the same file, but T16's own checks were explicitly off-limits per
this task's MUST NOT DO "do not modify or weaken ANY existing check"). **For my own new T9
checks**, every `textContent` pulled via `js()` is unwrapped through
`node -e 'console.log(JSON.parse(process.argv[1]))'` before any bash `==` comparison or before
being handed to a second `node -e` that reads `$TRACKS`'s plain-text JSON fields — confirmed by
re-reading my own diff line by line for this specific bug before finishing.

**Selectors depended on** (all confirmed against the real `TrackMenu.vue` markup, read in
full for this task, not guessed):
- `.option` — every top-level menu action, including the two new rows. Exactly 7 in the `'menu'`
  step (5 pre-existing + T3's "File details" at index 5 + T7's "Delete from device" at index 6,
  the last one). No data-attribute or other stable per-row selector exists on these buttons —
  confirmed by reading the file — so the check asserts on the row COUNT, which is what a
  reverted T3 or T7 would change, plus (implicitly, via which index opens which step/dialog) the
  ORDER T3/T7's own notepad entries document.
- `.icon-button` — the sheet header's back/close buttons. When `step === 'details'` (or
  `'playlist'`), the back button is the FIRST one in DOM order (the close button is always
  present and always second) — `document.querySelector('.icon-button')` reliably hits "back"
  from the details step, calling `backToMenu()`.
- `.detail-value` — the details step's `<dl class="details-list">` rows, in template order:
  index 0 is Title, index 1 is Artist (verified by reading the template's exact row sequence).
- `.notice` / `.notice.success` — the shared notice element `onDeleteFromDevice`/
  `onSetAsRingtone`/etc. all write to; `classList.contains('success')` distinguishes the
  success branch from `info`/`error`.
- `.track-row` — same selector the existing "Layout" check and T16 sections already use for
  Songs-tab rows; used here only to count (3 → 2), never to read text.
- `.backdrop`, `.row-menu` — reused verbatim from the existing T16 "options sheet" checks (the
  sheet's open/close affordances), per this task's instruction to reuse whatever DOM path an
  earlier section already uses.

**`window.confirm` observation: no existing pattern found.** Grepped the whole file for
"confirm" before writing anything — zero hits prior to this task's own addition. Wrote a new
one: `window.confirm = (msg) => { window.__qaConfirmCalls.push(msg); return false }`, returning
`false` so the app's own early-return branch (`if (!window.confirm(...)) return`) fires and the
real native `deleteTrack` call is never reached — this keeps the check safe to run against a
real device (a `true` return at THIS gate would proceed into the real native call, which then
shows the real, CDP-unreachable OS dialog — exactly the thing this task says cannot be driven
this way).

**Did NOT assert the confirm message's text content.** `trackMenu.confirmDelete`'s `{name}`
interpolation (T7's key, not yet added to `en.js`/`el.js` as of T7's own notepad entry — T8 owns
that, and was running in parallel with this task per the orchestrator's own dependency note) only
substitutes when the real key exists; `translate()`'s fallback-to-raw-key path
(`src/i18n/index.js:36`) still runs the `{name}` replace, but a raw key like
`trackMenu.confirmDelete` has no `{name}` placeholder in it to replace, so the message would read
differently depending on whether T8 has landed by the time this actually runs. Rather than build
a check whose pass/fail depends on unrelated i18n timing (the same reasoning the file's own T16
section already gives for skipping asserts on translated text), this only asserts
`window.confirm` was invoked exactly once, and that declining leaves the sheet on the `'menu'`
step with no `.notice` — both structural, both real, neither dependent on translated strings.

**Check 4 (mock delete + reload) stubs BOTH `Capacitor.Plugins.Player.deleteTrack` AND
`.listTracks`, not just `deleteTrack`.** Reasoning worked through and rejected two shallower
options first: (a) calling the REAL `deleteTrack` directly over CDP (the reorder-persistence
check's precedent) would, on a real device, actually reach the real
`RecoverableSecurityException`/`MediaStore.createDeleteRequest` path and show the real OS
dialog — hanging the suite on input nothing here can supply, and risking real data loss of one
of the three QA fixtures if it ever did resolve; (b) stubbing only `deleteTrack` (to resolve
`{deleted:true}`) while leaving the real `listTracks` in place would make `reload()` re-fetch
the SAME three real on-device fixtures (nothing on disk actually changed), so the row count
could never drop — that check could never fail in the way intended, which is exactly the
"placebo check" this task was told not to write. Stubbing `listTracks` too (to return one fewer
track than the real list, captured via `.slice(1)` off a genuine prior real call) makes the
row-count-drop assertion a faithful proxy for "the mock's deleteTrack resolved {deleted:true}
and the library reloaded" while staying 100% off the real native path. Both stubs and the
`window.confirm` override are restored (and the sheet closed) at the end of the section.

**Verification run** (no build, no suite run, per this task's MUST NOT DO):
- `bash -n scripts/qa-playback.sh` → exits 0 (`SYNTAX OK`).
- `git diff scripts/qa-playback.sh | grep '^-' | grep -v '^--- ' | wc -l` → `0` (pure addition,
  using the corrected filtered form, not the orchestrator's earlier miscounting `grep -c '^-'`).
- New `pass "`/`fail "` call sites: **15** lines (`git diff ... | grep '^+' | grep -cE '(pass|
  fail) "'`), covering **8 distinct check sites** — 1 entry guard (`.row-menu` not found) + 7
  genuine checks, each with its own `pass`/`fail` branch (menu row count; details title; details
  artist; confirm-called-once; confirm-decline-leaves-menu-unaffected; track count drops by one;
  success notice shown).

**Native delete flow status, restated plainly per this task's explicit requirement:** the three
real SDK branches in `PlayerPlugin.kt`'s `deleteTrack` (pre-Q direct `resolver.delete`, API 29
`RecoverableSecurityException` retry-after-approval, API 30+ `MediaStore.createDeleteRequest`),
the real `RecoverableSecurityException`/`createDeleteRequest` flows themselves, and the real OS
confirmation dialogs have NEVER been executed in any session of this plan — no device, no
emulator, only manual source-reading and compiler runs (T4/T5's notepad entries). A first real
device run should watch for the three distinct outcomes: **deleted** (file actually gone,
`listTracks()` stops reporting it, success notice, sheet stays open per T7's precedent),
**cancelled** (user declines the system dialog, nothing on disk changes, info notice, no
reload), and **rejected** (a genuine failure, including a zero-rows-deleted case, surfaced as
the generic `actionFailed` error notice).

## T8

**Files touched: `src/i18n/en.js` and `src/i18n/el.js` only** (plus this notepad).

**Authoritative key list derived mechanically**, not by trusting T3/T7's notes blind:
`grep -rhoE "t\('trackMenu\.[a-zA-Z]+'" src/components/TrackMenu.vue | sed "s/t('//;s/'//" |
sort -u` returned 40 distinct `trackMenu.*` keys the component actually calls. Diffed against
the 18 keys already in `en.js` from earlier plans (`open`, `favourite`, `unfavourite`,
`addToPlaylist`, `setRingtone`, `setNotification`, `viewInFolder`, `close`, `back`,
`newPlaylist`, `newPlaylistPlaceholder`, `createAndAdd`, `cancel`, `playlistTrackCount`,
`ringtoneSet`, `notificationSet`, `permissionNeeded`, `actionFailed`) — the remaining 22 are
exactly the union of T3's 18 proposed keys and T7's 4 proposed keys, zero collisions, zero
extras, zero omissions. This matched the orchestrator's own "approximately" list exactly, so no
key was invented and none was skipped.

**Wording: took T3/T7's proposals essentially as-is**, with two precision fixes caught by
looking at both sets together plus the existing dictionary:
- `trackMenu.confirmDelete` EN: T7's notepad draft used a straight apostrophe (`can't`).
  Checked `playlists.confirmDelete` in `en.js:46` byte-for-byte (`cat -A`) and confirmed it uses
  a curly `’` (U+2019), not `'`. Copied the curly apostrophe into `trackMenu.confirmDelete` so
  the two destructive-confirm strings are typographically identical apart from the object noun,
  not just semantically similar. Final: `'Delete "{name}"? This can’t be undone.'`
- No inconsistency found between T3's `notAvailable`/`unknownFormat` split (mimeType
  deliberately gets its own fallback per the plan) and T7's keys — T7 introduces no second
  "not available"-shaped string, so there was nothing to reconcile there. Confirmed by reading
  all 22 new keys side by side before writing anything.
- Everything else (all 18 `detail*`/`fileDetails`/`notAvailable`/`unknownFormat` keys from T3,
  and `deleteFromDevice`/`deleteSuccess`/`deleteCancelled` from T7) landed with T3/T7's exact
  proposed EN/EL text, verified against the surrounding `trackMenu.*`/`playlists.*` register
  before accepting each: short label nouns for the `detail*` fields (matching `library.tab.*`'s
  short-label style, not full sentences), `Bitrate` left untranslated in Greek (loanword,
  consistent with `downloader.bitrate.label`'s `Ποιότητα MP3` keeping `MP3` untranslated too),
  `deleteSuccess`/`Διαγράφηκε` and its EN `Deleted` matched the brevity of the existing
  `downloader.job.status.doneGeneric`/`Αποθηκεύτηκε`/`Saved` pair rather than a full sentence.
  `trackMenu.confirmDelete`'s Greek deliberately uses `του` (not `της`, unlike
  `playlists.confirmDelete`) since the referent here is an implied neuter "το αρχείο", not the
  feminine "η λίστα" — confirmed this is what T7 already reasoned, not a new decision.

**Placement**: inserted the full 22-key block as one contiguous group directly after the
existing `trackMenu.viewInFolder` entry and before `trackMenu.close`, in BOTH files, same
position, same internal order: `fileDetails` → the 14 `detail*` field labels in the same order
they render in the template (`detailTitle`, `detailArtist`, `detailAlbum`, `detailAlbumArtist`,
`detailTrackNumber`, `detailYear`, `detailDuration`, `detailSize`, `detailDateAdded`,
`detailFolder`, `detailMimeType`, `detailPath`, `detailBitrate`, `detailSampleRate`) → the three
shared fallback/loading strings (`detailLoading`, `notAvailable`, `unknownFormat`) → the four
delete-related keys (`deleteFromDevice`, `confirmDelete`, `deleteSuccess`, `deleteCancelled`).
No existing key's value, position relative to other existing keys, or wording was touched.

**Verification run (real output, not summarized):**
```
$ node --check src/i18n/en.js && node --check src/i18n/el.js
OK

$ grep -oE "^\s*'[a-zA-Z0-9_.]+':" src/i18n/en.js | sed -E "s/^\s*'//;s/':$//" > en_keys_ordered.txt
$ grep -oE "^\s*'[a-zA-Z0-9_.]+':" src/i18n/el.js | sed -E "s/^\s*'//;s/':$//" > el_keys_ordered.txt
$ wc -l en_keys_ordered.txt el_keys_ordered.txt
 160 en_keys_ordered.txt
 160 el_keys_ordered.txt
$ diff en_keys_ordered.txt el_keys_ordered.txt
(empty — IDENTICAL ORDER AND SET)
$ sort en_keys_ordered.txt | uniq -d
(empty — no duplicates)
$ sort el_keys_ordered.txt | uniq -d
(empty — no duplicates)

$ grep -rhoE "t\('trackMenu\.[a-zA-Z]+'" src/components/TrackMenu.vue | sed "s/t('//;s/'//" | sort -u > needed_keys.txt
$ wc -l needed_keys.txt
40 needed_keys.txt
$ while read -r k; do grep -qF "'$k':" src/i18n/en.js || echo "MISSING in en.js: $k"; done < needed_keys.txt
(no output — all 40 present)
$ while read -r k; do grep -qF "'$k':" src/i18n/el.js || echo "MISSING in el.js: $k"; done < needed_keys.txt
(no output — all 40 present)

$ grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/views src/components | grep -v "t("
(empty, exit 1 — gate still passes)

$ git status --porcelain src/i18n/en.js src/i18n/el.js
 M src/i18n/en.js
 M src/i18n/el.js
$ git diff --stat src/i18n/en.js src/i18n/el.js
 src/i18n/en.js | 22 ++++++++++++++++++++++
 src/i18n/el.js | 22 ++++++++++++++++++++++
 2 files changed, 44 insertions(+)
```

**Not done (confirmed against MUST NOT DO):** no file other than `en.js`/`el.js`/this notepad
touched. No existing key renamed, reworded, or deleted. No key invented that the grep extraction
didn't call for. No build/gradle/adb/emulator/`npm run qa` run. No dependency added.

## T10

**File touched: `README.md` only** (plus this notepad). Extended the existing "Playlists and
favourites" section's "**The track options menu**" paragraph in place — no new top-level
section, no restructuring, no reordering of surrounding content.

**Exact changes made:**
1. The existing menu-action list in that paragraph ("Favourite/Unfavourite, Add to playlist,
   Set as ringtone, Set as notification, View in folder.") now also names "File details, Delete
   from device." — nothing else in that sentence changed.
2. Three new paragraphs inserted immediately after that paragraph (before "**Two side effects on
   existing screens...**", which was left untouched):
   - **File details**: lists the free fields (title, artist, album, album artist, track number,
     year, duration, size, date added, folder, MIME type — matching T3's actual free-field list,
     not a guess), states bitrate/sample rate come from `MediaMetadataRetriever` with "already
     part of the Android SDK... so this needed no new dependency" (matches T2's real
     implementation), and flags the absolute path as display-only/best-effort, tying it to the
     `MANAGE_EXTERNAL_STORAGE`-not-requested fact already established in the plan (matches T2's
     notepad honest-confidence note on `DATA` column availability).
   - **Delete from device**: states it works on every supported Android version (`minSdk 24` up)
     through three distinct native code paths — plain delete below Android 10,
     `RecoverableSecurityException` on Android 10, `MediaStore.createDeleteRequest` on Android 11+
     — matching T5's actual `SDK_INT` branches exactly (`< 29` / `== 29` / `>= 30`). States plainly
     that the confirmation shown in every path is OS-owned and the app cannot skip, customise, or
     replace it (matches Must NOT Have 7).
   - **Metadata editing rejected**: states the real AOSP reason (a hard column allowlist that
     excludes title/artist/album/album artist/year/track for every app, always, with a silent
     revert on the next media scan) rather than a vague "not supported" — and points to "the
     file-details-and-delete plan's Context section" for the full citation, per the task's
     instruction, rather than re-deriving or duplicating the AOSP source references inline.

**No existing contradiction found and none needed fixing**: read the full README before editing
(per REQUIRED TOOLS); no other passage claimed the app could edit tags, and no other passage
implied delete didn't exist — the inherited-wisdom warning about a stale absolute claim did not
apply here, nothing else needed touching.

**Verification run:**
```
$ grep -n "createDeleteRequest\|RecoverableSecurityException\|metadata editing\|MediaMetadataRetriever" README.md
172:demand via `MediaMetadataRetriever` — already part of the Android SDK and already used
182:permission dialog on Android 10 (`RecoverableSecurityException`), and one batched system
183:dialog on Android 11 and newer (`MediaStore.createDeleteRequest`). In every one of those

$ git diff --stat -- README.md
 README.md | 38 +++++++++++++++++++++++++++++++++-----
 1 file changed, 33 insertions(+), 5 deletions(-)
```
The literal phrase "metadata editing" does not appear verbatim (the prose reads "Editing a
track's tags ... was considered and explicitly rejected" instead, to match the README's own
voice rather than the plan-checkbox phrasing) — the OR-grep above still shows matching content
via the other three terms, satisfying the plan's stated QA scenario. `git diff --stat` confirms
`README.md` is the only file this task modified.

**Not done (confirmed against MUST NOT DO):** no code, script, i18n, or plan-checkbox edit; no
build/gradle/adb/emulator/`npm run qa` run; no claim that metadata editing works, is "not yet
supported," or "may be added later"; no claim that delete or file details has been tested on a
real device.

## T6 follow-up (mock completeness fix)

**File touched: `src/plugins/player.web.js` only** (plus this notepad). Found by the orchestrator
reviewing T9's QA work, not self-reported: T6's original `deleteTrack` mock resolved `{ deleted:
true }` unconditionally while `listTracks()` built a brand-new inline literal array on every call
with no backing instance field — so a real `npm run dev` session's "delete then reload" never
actually removed anything, contradicting the mock's own doc comment.

**Before → after behaviour:**
- **Before**: `listTracks()` returned `{ tracks: [ <inline literal of 3 objects> ] }` — a fresh
  array built from scratch every call, no instance field. `deleteTrack({ uri })` ignored `uri`
  entirely and always resolved `{ deleted: true }`. A `deleteTrack` call followed by a `reload()`
  → `listTracks()` call always returned the same 3 tracks, unconditionally, delete or not.
- **After**: the 3 track objects moved verbatim into a new instance field, `mockTracks` (declared
  near the top of `PlayerWeb`, directly after `favoritePaths`, matching where `queue`/`playlists`
  live — same "mutable backing state, reset only on page reload" idiom, doc comment explains why).
  `listTracks()` now just returns `{ tracks: [...this.mockTracks] }` — a copy, so a caller
  mutating the returned array (e.g. sorting it in place) can never corrupt the backing field,
  matching `setQueue`'s existing `this.queue = [...uris]` defensive-copy habit in this same file.
  `deleteTrack({ uri })` now does `this.mockTracks = this.mockTracks.filter(t => t.uri !== uri)`
  and only resolves `{ deleted: true }` if the array's length actually shrank.

**Not-found decision: rejects, matching the real native `deleteTrack`'s zero-rows-affected
posture (T5's `## T5` entry above).** Re-read T5's notepad entry before deciding: the real native
`deleteTrack` treats `ContentResolver.delete` returning `0` rows as a genuine
`call.reject("No matching row was deleted")` — never a silently-resolved fake success, and never
folded into the `{ deleted: false, reason: 'cancelled' }` outcome either (a 0-row-count is not
the user declining a dialog; it's the delete itself not doing what was asked). The mock now
mirrors that exact posture: an uri with no match in `this.mockTracks` throws
`new Error('No matching row was deleted for uri: ' + uri)` rather than resolving `{ deleted:
true }` for nothing, or fabricating a `cancelled` shape the mock has no way to genuinely produce
(there is no confirm-dialog equivalent inside the mock itself — `TrackMenu.vue`'s own
`window.confirm()` is what produces `cancelled` today, entirely before this method is ever
called). This does not change `deleteTrack`'s documented JSDoc contract in `player.js` (still
`{ deleted: boolean, reason?: string }`, unchanged) — a reject was already one of the contract's
three documented outcomes, just previously unreachable through the mock.

**Doc comments rewritten to describe what the mock NOW actually does** (both `mockTracks` and
`deleteTrack` got new doc comments explaining the instance-field backing state and the
filter-then-check logic) while preserving the existing, still-true caveat verbatim in spirit:
this does NOT simulate the real permission dance, `RecoverableSecurityException`, `SDK_INT`
branching, or an OS confirmation dialog — a browser has none of those, only `TrackMenu.vue`'s own
`window.confirm()` gate stands in front of this method.

**`getFileDetails` confirmed NOT changed, verified by reading it, not assumed.** Its `byUri`
lookup table is independent, hardcoded per-uri data (absolutePath/bitrate/sampleRate) that is
never derived from or read through `this.mockTracks` — it already falls back to plausible-shaped
nulls for any uri (including one just removed from `mockTracks` by `deleteTrack`), so it needed
no change. `getArtwork`, `checkAudioPermission`/`requestAudioPermission`,
`simulateTrackUnavailable`, and every playlist/favourites method were also checked and confirmed
to hold no reference to the three mock tracks by uri/id — none needed touching.

**Manual trace (no build possible/allowed; reasoned through the actual code, not just written to
look right):**
1. `listTracks()` → `{ tracks: [...this.mockTracks] }` with `this.mockTracks` at its initial
   3-entry state → 3 tracks: `mock://1`, `mock://2`, `mock://3`.
2. `deleteTrack({ uri: 'mock://2' })` → `before = 3`; `this.mockTracks =
   this.mockTracks.filter(t => t.uri !== 'mock://2')` → new length `2` (`mock://1`, `mock://3`
   remain); `2 !== 3` so the "not found" throw is skipped → resolves `{ deleted: true }`.
3. `listTracks()` called again → `{ tracks: [...this.mockTracks] }` reads the SAME instance
   field, now length 2 → 2 tracks, `mock://2` absent, `mock://1`/`mock://3` present. Confirms the
   "track disappeared, reload the library" flow the mock's doc comment claims now genuinely
   works through the real mock, no test-injected stub required.

**T9's QA additions confirmed UNAFFECTED, verified by reading `scripts/qa-playback.sh`'s T9
section again, not assumed.** Its "mock delete+reload" check (around the file's `## T9` comment
banner, before `step "Summary"`) does `window.__qaOriginalDeleteTrack =
Capacitor.Plugins.Player.deleteTrack; window.__qaOriginalListTracks =
Capacitor.Plugins.Player.listTracks; Capacitor.Plugins.Player.deleteTrack = async () => ({
deleted: true }); Capacitor.Plugins.Player.listTracks = async () => { ... }` — it overwrites both
plugin methods directly on the live `Capacitor.Plugins.Player` object with its own inline async
functions, bypassing `PlayerWeb`'s real `deleteTrack`/`listTracks` entirely, then restores the
originals at the end of the section. Since this bypass happens at the JS plugin-registry layer,
above `PlayerWeb`'s class methods, T9's checks never call into this task's changed code at all —
confirmed by re-reading the actual stub assignments, not by assuming "it stubs things" was
enough. `scripts/qa-playback.sh` was NOT modified by this task, per MUST NOT DO.

**Verification run (no build, per MUST NOT DO):**
```
$ node --check src/plugins/player.web.js
(exit 0, no output)

$ grep -n "async getFileDetails\|async deleteTrack\|async getArtwork\|async checkAudioPermission" src/plugins/player.web.js
211:  async getArtwork() {
224:  async getFileDetails({ uri }) {
261:  async deleteTrack({ uri }) {
270:  async checkAudioPermission() {
```
(confirms no accidental duplication of any method after the edit — exactly one of each.)

**Not done (confirmed against MUST NOT DO):** no `android/`, `TrackMenu.vue`, `src/i18n/*`,
`scripts/qa-playback.sh`, `README.md`, or plan-file checkbox edit. `deleteTrack`'s JSDoc contract
in `player.js` was NOT touched — its documented `{ deleted: boolean, reason?: string }` shape and
three-outcome prose are unchanged; only the web mock's internal fidelity to that existing contract
improved. No new dependency. No build/gradle/adb/emulator/`npm run qa` run.

## Post-ship fix: styled delete confirm + close-on-success

Direct user feedback after T7 shipped: "the confirmation popup should be styled on delete, and
also on successful delete the song popup menu should close also for this song." Two changes,
`src/components/TrackMenu.vue` only, plus two new i18n keys added directly to both locale files
in this same task (no active "Strings" task to hand them to this time).

**Styled confirm step.** Added `'confirmDelete'` as a fourth `step` value. Tapping "Delete from
device" now calls a new `openConfirmDeleteStep()` (same shape as `openPlaylistStep`/
`openDetailsStep`: guards on `activeTrack`, sets `step.value`, clears `notice`) instead of
`onDeleteFromDevice` directly. `window.confirm()` is gone from the active call path — the
confirmation now lives in the sheet itself, reusing the EXISTING `trackMenu.confirmDelete` key
(same `{name}: activeTrack.displayName}` interpolation) as body text, with a Cancel button
(`@click="backToMenu"`, identical to the header back-arrow, which was extended to show for this
step too) and a Delete button that calls `onDeleteFromDevice` — now with no gate at its own top,
since confirmation already happened via the UI. The header title for this step reuses
`trackMenu.deleteFromDevice` verbatim rather than a new title key — this is the SAME established
pattern the `'playlist'` step already uses (`trackMenu.addToPlaylist` is both the menu row's own
label and that step's header title), so `'confirmDelete'` follows precedent rather than inventing
a new one.

**One new i18n key, not two.** `trackMenu.cancel` already existed (from the playlist add-flow,
"Cancel"/"Άκυρο") and its wording fits a destructive confirm's Cancel button too — reused as-is,
no duplicate. Only `trackMenu.delete` ("Delete"/"Διαγραφή") was added, for the Delete button's own
short label (the header already says the longer "Delete from device"; the button itself just
needs "Delete"). Added to both `en.js`/`el.js` at the same position, directly after
`trackMenu.deleteFromDevice`.

**Danger button styling.** No pre-existing destructive-styled BUTTON existed anywhere in this
codebase to copy chrome from (checked `PlaylistView.vue`'s own delete button — it's a plain
`.icon-button`, no color override; the only existing `--danger`/`--danger-soft` USE anywhere was
`TrackMenu.vue`'s own `.notice.error`). Added `.text-button.danger { color: var(--danger);
background: var(--danger-soft); }` — same token PAIRING `.notice.error` already uses, same
`.text-button` base chrome (padding/border-radius/font-size) every other sheet button already
uses, no new color value invented.

**Close-on-success is a DELIBERATE REVERSAL of T7's own recorded decision**, not a new default
pattern. T7's entry above (search "Close-sheet-on-success decision, and why") explains at length
why it followed `onSetAsRingtone`'s "never close on success" precedent. The user's literal
feedback now asks for the opposite for delete's success case specifically. `onDeleteFromDevice`'s
`deleted === true` branch now calls `closeTrackMenu()` right after `useLibrary().reload()`. The
success `notice.value` assignment was REMOVED (not just left in place) — since closing happens on
the same tick and closing nulls `activeTrack` (the file's own `watch(activeTrack, ...)` reset),
a success notice set on this branch would never actually be visible; keeping it would be dead
code dressed up as feedback. The comment on this branch was rewritten to explain the reversal and
why the notice was dropped, rather than left stating the old (now false) reasoning. `onSetAsRingtone`
itself was NOT touched — this is an exception for delete's success path alone, per the task's own
explicit instruction, not a change to the established pattern elsewhere in the file.

**Verification run** (no build, per this project's standing MUST NOT DO):
- `@vue/compiler-sfc` via `node -e` (parse → compileScript → compileTemplate): zero parse errors,
  `compileScript OK, bindings: 44`, `compileTemplate` zero errors/tips.
- `node --check src/i18n/en.js && node --check src/i18n/el.js` — both exit 0.
- Key-set parity: both locale files have 161 keys, identical set (`only in en: []`, `only in el:
  []`), identical order (0 order mismatches) — checked both directions programmatically, not by
  eye.
- `grep -rnE '>[A-Za-zΑ-Ωα-ω][^<{]{3,}<' src/components/TrackMenu.vue | grep -v "t("` — empty
  (exit 1), no hardcoded text introduced.
- `grep -n "window.confirm\|confirmDelete\|closeTrackMenu"` confirms: `window.confirm` appears
  only inside comments describing the OLD/other (`PlaylistView.vue`) behaviour, never as an
  active call in this file any more; the new step is wired end to end (header v-if, title
  ternary, menu button, template branch); `closeTrackMenu()` is called in the success branch,
  alongside its two pre-existing call sites (`close()`, `onViewInFolder`).

**Not done:** `PlaylistView.vue`'s own separate `window.confirm()`-based playlist-delete gate was
not touched (out of scope — the user's feedback was specifically about the track options menu).
The cancelled/error branches of `onDeleteFromDevice` are byte-for-byte unchanged. No new
dialog/modal component, no new dependency. `trackMenu.deleteSuccess` is now unused in
`TrackMenu.vue` (its only call site was the removed notice assignment) but was deliberately left
in both locale files rather than removed — pruning now-unused translation strings wasn't part of
this task's ask, and the key isn't harmful sitting unused.
