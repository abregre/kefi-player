#!/usr/bin/env bash
# Scripted QA for the playback layer: re-runs the T5, T8, T10 and T12 assertions
# against a connected device/emulator and prints one PASS/FAIL line each.
#
#   bash scripts/qa-playback.sh [serial]        # default serial: emulator-5554
#   npm run qa
#
# Requirements: the debug APK installed, READ_MEDIA_AUDIO granted, the three QA
# fixtures indexed (`npm run fixtures`), and `node` on PATH.
#
# HOW IT DRIVES THE APP. Every action goes through the app's own JS bridge, evaluated
# inside the running WebView over the Chrome DevTools protocol (the debug build's
# WebView is debuggable; Node 22 has a global WebSocket, so no dependency is needed).
# That is deliberate: driving playback with `input tap` needs hardcoded pixel
# coordinates, which silently stop hitting anything the moment the layout moves, and a
# tap that misses reads exactly like a passing test. Calling `Player.setQueue(...)`
# exercises the same bridge, plugin and Media3 code path the UI does.
#
# Not covered here, by design: audibility, real Bluetooth routing, lock-screen visuals
# and wired-headset unplug. Those are provably unscriptable — every scripted signal
# proves the pipeline reached the mixer, never that sound came out — and live in the
# plan's hands-on F5 wave instead.
# NO `pipefail` on purpose: almost every assertion here ends in `grep -q`, which exits
# as soon as it matches and SIGPIPEs whatever was feeding it. Under pipefail that turns a
# SUCCESSFUL match into a failed pipeline — a false FAIL that is very hard to see.
set -u

SERIAL="${1:-emulator-5554}"
PKG=com.kefi.player
ACTIVITY="$PKG/.MainActivity"
PORT="${QA_CDP_PORT:-9222}"
EMU=(adb -s "$SERIAL")

FAILURES=0
CHECKS=0

pass() { CHECKS=$((CHECKS + 1)); echo "PASS  $*"; }
fail() { CHECKS=$((CHECKS + 1)); FAILURES=$((FAILURES + 1)); echo "FAIL  $*"; }
step() { echo; echo "== $* =="; }
abort() { echo "ABORT $*" >&2; exit 2; }

# A missing prerequisite is not a test failure — it means the suite never ran, and
# saying so is the difference between "green" and "did not execute".
command -v adb >/dev/null || abort "adb not on PATH"
command -v node >/dev/null || abort "node not on PATH (needed to talk to the WebView)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/cdp.mjs" <<'NODE'
// Evaluates one expression inside the app's WebView. Prints the JSON-encoded result on
// stdout, or `__CDP_ERROR__<message>` and exit 1 on a JS exception.
const [port, expression] = process.argv.slice(2)
const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
const page = targets.find((t) => t.type === 'page') ?? targets[0]
if (!page) {
  console.log('__CDP_ERROR__no devtools page target')
  process.exit(1)
}
const socket = new WebSocket(page.webSocketDebuggerUrl)
const timer = setTimeout(() => {
  console.log('__CDP_ERROR__timed out')
  process.exit(1)
}, 20000)
socket.onerror = () => {
  console.log('__CDP_ERROR__websocket error')
  process.exit(1)
}
socket.onopen = () =>
  socket.send(
    JSON.stringify({
      id: 1,
      method: 'Runtime.evaluate',
      // userGesture matters: PlayerController only accepts its first touch from a call
      // that originated in a user gesture.
      params: { expression, returnByValue: true, awaitPromise: true, userGesture: true },
    }),
  )
socket.onmessage = (message) => {
  const data = JSON.parse(message.data)
  if (data.id !== 1) return
  clearTimeout(timer)
  const details = data.result?.exceptionDetails
  if (details) {
    console.log(`__CDP_ERROR__${details.exception?.description ?? details.text ?? 'exception'}`)
    process.exit(1)
  }
  console.log(JSON.stringify(data.result.result.value ?? null))
  process.exit(0)
}
NODE

# --- device plumbing -------------------------------------------------------------

forward_devtools() {
  local socket
  socket=$("${EMU[@]}" shell cat /proc/net/unix 2>/dev/null |
    grep -o "webview_devtools_remote_[0-9]*" | sort -u | head -1)
  [[ -z "$socket" ]] && return 1
  # The abstract socket name carries the app's pid, so this must be redone after every
  # app restart. Cheap enough to just always redo it.
  "${EMU[@]}" forward --remove "tcp:$PORT" >/dev/null 2>&1
  "${EMU[@]}" forward "tcp:$PORT" "localabstract:$socket" >/dev/null
}

js() { node "$TMP/cdp.mjs" "$PORT" "$1"; }

# Reads one dotted path out of a JSON string: jsonval "$snapshot" title
jsonval() {
  node -e '
    const value = JSON.parse(process.argv[1])
    let cursor = value
    for (const key of process.argv[2].split(".")) cursor = cursor?.[key]
    console.log(cursor === undefined || cursor === null ? "" : typeof cursor === "object" ? JSON.stringify(cursor) : String(cursor))
  ' "$1" "$2"
}

player() { js "Capacitor.Plugins.Player.$1"; }

launch_app() {
  "${EMU[@]}" shell am start -n "$ACTIVITY" >/dev/null 2>&1
  for _ in $(seq 1 20); do
    sleep 1
    forward_devtools || continue
    [[ "$(js '2+2' 2>/dev/null)" == 4 ]] && return 0
  done
  return 1
}

app_pid() { "${EMU[@]}" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}'; }
session_dump() { "${EMU[@]}" shell dumpsys media_session; }
session_state() { session_dump | grep -m1 -oE "state=[A-Z]+\([0-9]\)" | grep -oE "\([0-9]\)" | tr -d '()'; }
session_position() { session_dump | grep -m1 -oE "position=[0-9]+" | head -1 | cut -d= -f2; }
session_title() { session_dump | grep -m1 -oE "description=[^,]*" | cut -d= -f2-; }

# --- preflight -------------------------------------------------------------------

step "Preflight"

[[ "$("${EMU[@]}" get-state 2>/dev/null)" == device ]] || abort "$SERIAL is not connected"
"${EMU[@]}" shell pm list packages | tr -d '\r' | grep -qx "package:$PKG" ||
  abort "$PKG is not installed on $SERIAL (npm run apk && adb install -r ...)"
"${EMU[@]}" shell dumpsys package "$PKG" | grep -q "READ_MEDIA_AUDIO: granted=true" ||
  abort "READ_MEDIA_AUDIO is not granted (adb -s $SERIAL shell pm grant $PKG android.permission.READ_MEDIA_AUDIO)"

FIXTURE_ROWS=$("${EMU[@]}" shell content query --uri content://media/external/audio/media \
  --projection _id:title 2>/dev/null | grep -cE "Άσπρη Μέρα|Zeibekiko|Ωδή")
[[ "$FIXTURE_ROWS" == 3 ]] || abort "expected 3 indexed fixtures, found $FIXTURE_ROWS (npm run fixtures)"

launch_app || abort "could not reach the app's WebView over devtools on port $PORT"
pass "preflight: app installed, permission granted, 3 fixtures indexed, WebView reachable"

# Resolve fixture uris by title. MediaStore ids renumber on every delete+rescan, so
# nothing here may hardcode them.
TRACKS=$(player "listTracks()")
if [[ "$TRACKS" == __CDP_ERROR__* ]]; then
  abort "listTracks failed: ${TRACKS#__CDP_ERROR__}"
fi
# The fixture set deliberately includes two files with an audio extension over content
# that is not audio (see scripts/qa-fixtures.sh). MediaStore indexes them by extension with
# a NULL duration, and the library must not show them: on a real device they appeared as
# unplayable "autobackup…"/"autoplaylist…" rows with no length (2026-08-20).
MEDIASTORE_ROWS=$("${EMU[@]}" shell content query --uri content://media/external/audio/media \
  --projection title --where "is_music=1" 2>/dev/null | grep -c "title=auto")
LISTED=$(node -e 'const { tracks } = JSON.parse(process.argv[1]); console.log(tracks.length)' "$TRACKS")
LISTED_JUNK=$(node -e '
  const { tracks } = JSON.parse(process.argv[1])
  console.log(tracks.filter((t) => /^auto(backup|playlist)/.test(t.title) || !t.durationMs).length)
' "$TRACKS")
if [[ "$MEDIASTORE_ROWS" -lt 2 ]]; then
  fail "the unplayable baits are missing from MediaStore — run npm run fixtures (found $MEDIASTORE_ROWS)"
else
  [[ "$LISTED_JUNK" == 0 && "$LISTED" == 3 ]] &&
    pass "listTracks filtered out $MEDIASTORE_ROWS unplayable MediaStore rows and returned exactly 3" ||
    fail "listTracks returned $LISTED tracks of which $LISTED_JUNK are unplayable (expected 3 and 0)"
fi

# The fixtures live in two directories (f3 sits in vol2/), which is what makes the
# library's Folders grouping testable at all — and it exercises the `folder` field the
# native TrackSource added for it.
FOLDERS=$(node -e '
  const { tracks } = JSON.parse(process.argv[1])
  const unique = [...new Set(tracks.map((t) => t.folder))].sort()
  console.log(JSON.stringify(unique))
' "$TRACKS")
FOLDER_COUNT=$(node -e 'console.log(JSON.parse(process.argv[1]).length)' "$FOLDERS")
if [[ "$FOLDER_COUNT" == 2 && "$FOLDERS" == *"kefi-qa/vol2"* ]]; then
  pass "listTracks reports the fixtures in 2 folders ($FOLDERS)"
else
  fail "listTracks reports $FOLDER_COUNT folder(s): $FOLDERS (expected 2, one of them kefi-qa/vol2)"
fi

URIS=$(node -e '
  const wanted = ["Άσπρη Μέρα", "Zeibekiko", "Ωδή"]
  const { tracks } = JSON.parse(process.argv[1])
  const found = wanted.map((title) => tracks.find((t) => t.title === title))
  if (found.some((t) => !t)) { console.log("MISSING"); process.exit(0) }
  console.log(JSON.stringify(found.map((t) => t.uri)))
' "$TRACKS")
[[ "$URIS" == MISSING ]] && abort "listTracks did not return all three fixtures"

# --- T10: queue, next/previous, repeat -------------------------------------------

step "T10 — queue, next/previous, shuffle, repeat"

"${EMU[@]}" logcat -c
SNAP=$(player "setQueue({ uris: $URIS, startIndex: 0 })")
sleep 3
[[ "$(jsonval "$SNAP" queueLength)" == 3 ]] &&
  pass "T10 setQueue reports queueLength 3" ||
  fail "T10 setQueue reported queueLength $(jsonval "$SNAP" queueLength)"

TITLE=$(session_title)
[[ "$TITLE" == "Άσπρη Μέρα" ]] &&
  pass "T10 media_session shows Άσπρη Μέρα at startIndex 0" ||
  fail "T10 media_session shows '$TITLE', expected Άσπρη Μέρα"

player "next()" >/dev/null
sleep 2
TITLE=$(session_title)
[[ "$TITLE" == Zeibekiko ]] &&
  pass "T10 next() advances to Zeibekiko" ||
  fail "T10 next() left media_session on '$TITLE'"

player "previous()" >/dev/null
sleep 2
TITLE=$(session_title)
[[ "$TITLE" == "Άσπρη Μέρα" ]] &&
  pass "T10 previous() returns to Άσπρη Μέρα" ||
  fail "T10 previous() left media_session on '$TITLE'"

player "setRepeatMode({ mode: 'one' })" >/dev/null
REPEAT=$(jsonval "$(player 'getPlayerState()')" repeatMode)
[[ "$REPEAT" == one ]] &&
  pass "T10 getPlayerState reports repeatMode \"one\"" ||
  fail "T10 getPlayerState reports repeatMode \"$REPEAT\", expected \"one\""
player "setRepeatMode({ mode: 'off' })" >/dev/null

SHUFFLE=$(jsonval "$(player 'setShuffle({ enabled: true })')" shuffleModeEnabled)
[[ "$SHUFFLE" == true ]] &&
  pass "T10 setShuffle({enabled:true}) reports shuffleModeEnabled true" ||
  fail "T10 setShuffle reported shuffleModeEnabled $SHUFFLE"
player "setShuffle({ enabled: false })" >/dev/null

# Regression guards for the metadata bugs a real device found on 2026-08-20.
SNAP_TITLE=$(jsonval "$(player 'getPlayerState()')" title)
[[ "$SNAP_TITLE" == "Άσπρη Μέρα" ]] &&
  pass "T10 the snapshot's title matches the current item, not the previously played one" ||
  fail "T10 snapshot title is '$SNAP_TITLE', expected Άσπρη Μέρα (stale metadata regression)"

# `metadata:` is the session's own line. It must carry the full title/artist/album triplet:
# setting a display title instead silently turns it into "<title>, null, null", which is what
# Bluetooth head units and older lock screens render.
SESSION_META=$(session_dump | grep -m1 "metadata: size")
if [[ "$SESSION_META" == *"Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι Vol 1"* ]]; then
  pass "T10 session metadata carries title, artist and album"
else
  fail "T10 session metadata is incomplete: ${SESSION_META:-none}"
fi

EXCEPTIONS=$("${EMU[@]}" logcat -d | grep -icE "AndroidRuntime|FATAL EXCEPTION")
[[ "$EXCEPTIONS" == 0 ]] &&
  pass "T10 logcat has zero fatal exceptions" ||
  fail "T10 logcat has $EXCEPTIONS fatal exception lines"

# --- media-button routing pre-check ---------------------------------------------

step "Media-button routing pre-check"

# Fails LOUDLY and names the offending package: if another media app owns the media
# button, every media-key assertion below would be testing that app instead of this one.
BUTTON_LINE=$(session_dump | grep -m1 "Media button session")
BUTTON_OWNER=$(echo "$BUTTON_LINE" | grep -oE "[a-zA-Z0-9_.]+/" | head -1 | tr -d '/')
if [[ "$BUTTON_OWNER" == "$PKG" ]]; then
  pass "media button session is owned by $PKG"
else
  fail "media button session is owned by '${BUTTON_OWNER:-nobody}', not $PKG — every media-key assertion below would test the wrong app ($BUTTON_LINE)"
fi

# --- T5: foreground service, session, notification ------------------------------

step "T5 — foreground service, session, notification"

SERVICES=$("${EMU[@]}" shell dumpsys activity services "$PKG")
echo "$SERVICES" | grep -q "PlaybackService" &&
  pass "T5 PlaybackService is running" ||
  fail "T5 PlaybackService is not in dumpsys activity services"

# `dumpsys` prints the foreground type as a BITMASK, not the manifest's word:
# `isForeground=true foregroundId=1001 types=0x00000002`, where 0x2 is
# FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK. Grepping for the string "mediaPlayback" finds
# nothing and looks like a real failure.
FG_LINE=$(echo "$SERVICES" | grep -m1 "isForeground=")
FG_TYPES=$(echo "$FG_LINE" | grep -oE "types=0x[0-9a-fA-F]+" | cut -d= -f2)
if [[ "$FG_LINE" == *"isForeground=true"* && -n "$FG_TYPES" ]] && (( FG_TYPES & 0x2 )); then
  pass "T5 service is foreground with type mediaPlayback ($FG_TYPES)"
else
  fail "T5 service is not foreground with mediaPlayback (line: ${FG_LINE:-none})"
fi

STATE=$(session_state)
[[ "$STATE" == 3 ]] &&
  pass "T5 media_session reports state=3 (playing)" ||
  fail "T5 media_session reports state=$STATE, expected 3"

# The AudioPlaybackConfiguration line reads `u/pid:<uid>/<pid> state:started`; a bare
# `pid:$PID` also matches unrelated log lines in the same dump, so match the whole shape.
PID=$(app_pid)
AUDIO_LINE=$("${EMU[@]}" shell dumpsys audio | grep -m1 -E "u/pid:[0-9]+/$PID .*state:started")
[[ -n "$AUDIO_LINE" ]] &&
  pass "T5 dumpsys audio shows an AudioTrack in state:started for pid $PID" ||
  fail "T5 dumpsys audio has no started AudioTrack for pid $PID"

"${EMU[@]}" shell dumpsys media.audio_flinger | grep -A40 "Output thread" |
  grep -qE "Standby: no|standby: no" &&
  pass "T5 audio_flinger has an output mixer thread out of standby" ||
  fail "T5 audio_flinger reports every output thread in standby"

FGS_ERRORS=$("${EMU[@]}" logcat -d |
  grep -icE "ForegroundServiceStartNotAllowed|MissingForegroundServiceType|ForegroundServiceDidNotStartInTime")
[[ "$FGS_ERRORS" == 0 ]] &&
  pass "T5 logcat has zero foreground-service violations" ||
  fail "T5 logcat has $FGS_ERRORS foreground-service violation lines"

NOTIF=$("${EMU[@]}" shell dumpsys notification --noredact | grep -A30 "pkg=$PKG")
echo "$NOTIF" | grep -q "channel=playback" &&
  pass "T5 notification posted on channel \"playback\"" ||
  fail "T5 no notification on channel \"playback\""

echo "$NOTIF" | grep -qi "MediaStyle" &&
  pass "T5 notification uses a MediaStyle template" ||
  fail "T5 notification is not MediaStyle"

# --- T8: renderer death and screen-off playback ---------------------------------

step "T8 — WebView renderer death, screen-off playback"

"${EMU[@]}" logcat -c
PID_BEFORE=$(app_pid)
RENDERER=$("${EMU[@]}" shell ps -A | grep -m1 "sandboxed_process0" | awk '{print $2}' | tr -d '\r')
if [[ -z "$RENDERER" ]]; then
  fail "T8 could not find the WebView renderer process (sandboxed_process0)"
elif ! "${EMU[@]}" shell su 0 kill -9 "$RENDERER" 2>/dev/null; then
  # The shell user cannot signal another app's isolated process. Without root this
  # scenario simply cannot run, and saying so is the only honest outcome — the
  # assertions below would otherwise all "pass" against a renderer that never died.
  fail "T8 could not kill the renderer (needs root: \`adb -s $SERIAL root\`, or a userdebug build)"
else
  sleep 12
  PID_AFTER=$(app_pid)
  [[ -n "$PID_AFTER" && "$PID_AFTER" == "$PID_BEFORE" ]] &&
    pass "T8 app process survived the renderer kill (pid $PID_BEFORE)" ||
    fail "T8 app pid changed across the renderer kill ($PID_BEFORE -> ${PID_AFTER:-gone})"

  [[ "$(session_state)" == 3 ]] &&
    pass "T8 playback continued through the renderer kill (state=3)" ||
    fail "T8 playback stopped across the renderer kill (state=$(session_state))"

  RECOVERED=$("${EMU[@]}" logcat -d -s KefiApp | grep -c "renderer gone, recovering")
  [[ "$RECOVERED" -ge 1 ]] &&
    pass "T8 MainActivity logged \"renderer gone, recovering\"" ||
    fail "T8 no \"renderer gone, recovering\" line in logcat"

  KILLED=$("${EMU[@]}" logcat -d | grep -c "killing application")
  [[ "$KILLED" == 0 ]] &&
    pass "T8 zero \"killing application\" lines" ||
    fail "T8 logcat has $KILLED \"killing application\" lines"

  # The rebuilt WebView has no push history at all, so this is the pull half proving
  # itself: whatever it reports must match the native side, not a remembered value.
  forward_devtools
  sleep 1
  WEB_POS=$(jsonval "$(player 'getPlayerState()')" currentPosition)
  NATIVE_POS=$(session_position)
  if [[ -n "$WEB_POS" && -n "$NATIVE_POS" ]]; then
    DELTA=$(( WEB_POS > NATIVE_POS ? WEB_POS - NATIVE_POS : NATIVE_POS - WEB_POS ))
    [[ "$DELTA" -le 3000 ]] &&
      pass "T8 rebuilt WebView repopulated within 3000ms of the session (${DELTA}ms)" ||
      fail "T8 rebuilt WebView is ${DELTA}ms off the session position (limit 3000)"
  else
    fail "T8 could not read both positions (web='$WEB_POS' native='$NATIVE_POS')"
  fi
fi

POS1=$(session_position)
"${EMU[@]}" shell input keyevent KEYCODE_POWER
sleep 15
STATE_OFF=$(session_state)
POS2=$(session_position)
"${EMU[@]}" shell input keyevent KEYCODE_POWER
sleep 1
"${EMU[@]}" shell wm dismiss-keyguard >/dev/null 2>&1

if [[ -n "$POS1" && -n "$POS2" ]]; then
  ADVANCE=$((POS2 - POS1))
  [[ "$STATE_OFF" == 3 && "$ADVANCE" -ge 12000 ]] &&
    pass "T8 position advanced ${ADVANCE}ms across a 15s screen-off window (state=3 throughout)" ||
    fail "T8 screen-off advance was ${ADVANCE}ms with state=$STATE_OFF (need >=12000 and state=3)"
else
  fail "T8 could not read the screen-off positions (P1='$POS1' P2='$POS2')"
fi

# --- T12: persistence across process death --------------------------------------

step "T12 — persistence across process death"

player "setQueue({ uris: $URIS, startIndex: 1 })" >/dev/null
sleep 20
IDX1=$(jsonval "$(player 'getPlayerState()')" currentIndex)
POS_BEFORE=$(session_position)
TITLE_BEFORE=$(session_title)

"${EMU[@]}" shell am force-stop "$PKG"
sleep 2
launch_app || fail "T12 app did not come back up after force-stop"
sleep 8

RESTORED=$(player "getPlayerState()")
if [[ "$RESTORED" == __CDP_ERROR__* ]]; then
  fail "T12 getPlayerState failed after relaunch: ${RESTORED#__CDP_ERROR__}"
else
  [[ "$(jsonval "$RESTORED" currentIndex)" == "$IDX1" ]] &&
    pass "T12 restored the same queue index ($IDX1)" ||
    fail "T12 restored index $(jsonval "$RESTORED" currentIndex), expected $IDX1"

  [[ "$(jsonval "$RESTORED" isPlaying)" == false ]] &&
    pass "T12 restored paused, never auto-playing" ||
    fail "T12 restored with isPlaying=$(jsonval "$RESTORED" isPlaying)"

  POS_AFTER=$(jsonval "$RESTORED" currentPosition)
  DELTA=$(( POS_AFTER > POS_BEFORE ? POS_AFTER - POS_BEFORE : POS_BEFORE - POS_AFTER ))
  [[ "$DELTA" -le 6000 ]] &&
    pass "T12 restored position within 6000ms of the saved value (${DELTA}ms)" ||
    fail "T12 restored position is ${DELTA}ms off the saved value (limit 6000)"

  # Media-key play, not a plugin call: this is also the notification's play button, and
  # the whole point of the restore is that it resumes from the restored position.
  "${EMU[@]}" shell input keyevent KEYCODE_MEDIA_PLAY
  sleep 4
  [[ "$(session_state)" == 3 ]] &&
    pass "T12 KEYCODE_MEDIA_PLAY resumed the restored session (state=3)" ||
    fail "T12 KEYCODE_MEDIA_PLAY did not resume (state=$(session_state))"

  RESUMED_TITLE=$(session_title)
  [[ "$RESUMED_TITLE" == "$TITLE_BEFORE" ]] &&
    pass "T12 resumed the same track ($RESUMED_TITLE)" ||
    fail "T12 resumed '$RESUMED_TITLE', expected '$TITLE_BEFORE'"

  RESUMED_POS=$(session_position)
  [[ -n "$RESUMED_POS" && "$RESUMED_POS" -ge $((POS_AFTER - 2000)) ]] &&
    pass "T12 resumed from the restored position (${RESUMED_POS}ms), not from 0" ||
    fail "T12 resumed from ${RESUMED_POS}ms, expected >= $((POS_AFTER - 2000))ms"
fi

# --- service and notification lifetime -----------------------------------------

step "Service lifetime — closing the app while paused"

# A media notification whose session is still active cannot be swiped away by hand, so a
# service that outlives the UI leaves the user stuck with it (reported from a real device on
# 2026-08-20). Paused + no UI means there is nothing for either to be for.
player "pause()" >/dev/null
sleep 2
"${EMU[@]}" shell input keyevent KEYCODE_BACK   # at /library this calls App.exitApp()
sleep 5

SERVICE_RECORDS=$("${EMU[@]}" shell dumpsys activity services "$PKG" |
  grep -c "ServiceRecord.*PlaybackService")
[[ "$SERVICE_RECORDS" == 0 ]] &&
  pass "PlaybackService stopped after the app closed while paused" ||
  fail "PlaybackService is still running after the app closed while paused"

NOTIFICATIONS=$("${EMU[@]}" shell dumpsys notification --noredact | grep -c "pkg=$PKG")
[[ "$NOTIFICATIONS" == 0 ]] &&
  pass "no leftover notification after the app closed while paused" ||
  fail "$NOTIFICATIONS notification record(s) survived the app closing while paused"

# Everything above depends on the app being up; put it back so a re-run starts clean.
launch_app >/dev/null 2>&1
sleep 2

# --- layout: long names must not widen the app ----------------------------------

step "Layout — a long track name cannot widen the app"

# A grid item's automatic minimum size is its MIN-CONTENT size, so one long unbroken title
# used to size the shell's single column past the viewport: the app was laid out ~2.3x too
# wide and clipped, leaving the right-hand side of every row — and every control there —
# off screen and untappable. This injects a worse title than any real file has, straight into
# the DOM, and asserts the layout does not budge. No fixture needed, and it is the last thing
# the suite does, so the mutated DOM matters to nobody.
LAYOUT=$(js "(() => {
  const long = 'Χ'.repeat(140);
  for (const el of document.querySelectorAll('.track-row .title, .track-row .sub, .mini .title, .mini .artist')) {
    el.textContent = long;
  }
  const content = document.querySelector('.content');
  const overflowing = [...document.querySelectorAll('*')].filter(
    (el) => el.getBoundingClientRect().right > window.innerWidth + 1,
  ).length;
  return JSON.stringify({
    contentWidth: Math.round(content ? content.getBoundingClientRect().width : -1),
    innerWidth: window.innerWidth,
    overflowing,
    injectedInto: document.querySelectorAll('.track-row .title').length,
  });
})()")

if [[ "$LAYOUT" == __CDP_ERROR__* ]]; then
  fail "layout check could not run: ${LAYOUT#__CDP_ERROR__}"
else
  LAYOUT=$(node -e 'console.log(JSON.parse(process.argv[1]))' "$LAYOUT")
  INJECTED=$(jsonval "$LAYOUT" injectedInto)
  CONTENT_W=$(jsonval "$LAYOUT" contentWidth)
  INNER_W=$(jsonval "$LAYOUT" innerWidth)
  OVERFLOWING=$(jsonval "$LAYOUT" overflowing)
  if [[ "$INJECTED" == 0 ]]; then
    fail "layout check found no track rows to lengthen (library empty?)"
  else
    [[ "$CONTENT_W" == "$INNER_W" ]] &&
      pass "a 140-character title leaves the route area at the viewport width (${CONTENT_W}px)" ||
      fail "a 140-character title widened the route area to ${CONTENT_W}px on a ${INNER_W}px viewport"

    [[ "$OVERFLOWING" == 0 ]] &&
      pass "no element extends past the viewport with a 140-character title" ||
      fail "$OVERFLOWING element(s) extend past the viewport with a 140-character title"
  fi
fi

step "Summary"
echo "$((CHECKS - FAILURES))/$CHECKS checks passed"
if [[ "$FAILURES" -gt 0 ]]; then
  echo "$FAILURES FAILED"
  exit 1
fi
echo "ALL PASS"
