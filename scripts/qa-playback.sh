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
skip() { echo "SKIP  $*"; }
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
# A physical device can have several active media sessions. Scope state/title/
# position assertions to Kefi instead of accidentally reading another player.
session_for_app() {
  session_dump | awk -v pkg="$PKG" '
    /^    [^[:space:]]/ {
      if (keep) { printf "%s", block; exit }
      block=$0 ORS
      next
    }
    { block=block $0 ORS }
    $0 ~ "package=" pkg { keep=1 }
    END { if (keep) printf "%s", block }
  '
}
session_state() { session_for_app | grep -m1 -oE "state=[A-Z]+\([0-9]\)" | grep -oE "\([0-9]\)" | tr -d '()'; }
session_position() { session_for_app | grep -m1 -oE "position=[0-9]+" | head -1 | cut -d= -f2; }
session_title() { session_for_app | grep -m1 -oE "description=[^,]*" | cut -d= -f2-; }

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
FIXTURE_LISTED=$(node -e '
  const wanted = new Set(["Άσπρη Μέρα", "Zeibekiko", "Ωδή"])
  const { tracks } = JSON.parse(process.argv[1])
  console.log(tracks.filter((t) => wanted.has(t.title)).length)
' "$TRACKS")
if [[ "$MEDIASTORE_ROWS" -lt 2 ]]; then
  fail "the unplayable baits are missing from MediaStore — run npm run fixtures (found $MEDIASTORE_ROWS)"
else
  [[ "$LISTED_JUNK" == 0 && "$FIXTURE_LISTED" == 3 ]] &&
    pass "listTracks filtered out $MEDIASTORE_ROWS unplayable MediaStore rows and returned all 3 fixtures (total $LISTED)" ||
    fail "listTracks returned $LISTED tracks, including $FIXTURE_LISTED fixtures and $LISTED_JUNK unplayable rows (expected 3 fixtures and 0 unplayable rows)"
fi

# The fixtures live in two directories (f3 sits in vol2/), which is what makes the
# library's Folders grouping testable at all — and it exercises the `folder` field the
# native TrackSource added for it.
FIXTURE_FOLDERS=$(node -e '
  const { tracks } = JSON.parse(process.argv[1])
  const wanted = new Set(["Άσπρη Μέρα", "Zeibekiko", "Ωδή"])
  const unique = [...new Set(tracks.filter((t) => wanted.has(t.title)).map((t) => t.folder))].sort()
  console.log(JSON.stringify(unique))
' "$TRACKS")
FOLDER_COUNT=$(node -e 'console.log(JSON.parse(process.argv[1]).length)' "$FIXTURE_FOLDERS")
if [[ "$FOLDER_COUNT" == 2 && "$FIXTURE_FOLDERS" == *"kefi-qa/vol2"* ]]; then
  pass "listTracks reports the fixtures in 2 folders ($FIXTURE_FOLDERS)"
else
  fail "listTracks reports fixture tracks in $FOLDER_COUNT folder(s): $FIXTURE_FOLDERS (expected 2, one of them kefi-qa/vol2)"
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
SESSION_META=$(session_for_app | grep -m1 "metadata: size")
if [[ "$SESSION_META" == *"Άσπρη Μέρα, Δήμητρα Παπαδοπούλου, Κέφι Vol 1"* ]]; then
  pass "T10 session metadata carries title, artist and album"
else
  fail "T10 session metadata is incomplete: ${SESSION_META:-none}"
fi

EXCEPTIONS=$("${EMU[@]}" logcat -d | grep -icE "AndroidRuntime|FATAL EXCEPTION")
[[ "$EXCEPTIONS" == 0 ]] &&
  pass "T10 logcat has zero fatal exceptions" ||
  fail "T10 logcat has $EXCEPTIONS fatal exception lines"

# --- Playing queue ---------------------------------------------------------------

step "Playing queue — native edits and queue screen"

# Start from a known order. These direct bridge calls intentionally exercise the same native
# Media3 mutations QueueView uses after a drag/menu action; CDP cannot synthesize its long-press
# drag gesture reliably enough to make a pass meaningful.
player "setQueue({ uris: $URIS, startIndex: 0 })" >/dev/null
QUEUE=$(player "getQueue()")
QUEUE_ORDER=$(node -e 'const q=JSON.parse(process.argv[1]); console.log(JSON.stringify(q.items.map(x=>x.uri)))' "$QUEUE")
[[ "$QUEUE_ORDER" == "$URIS" && "$(jsonval "$QUEUE" currentIndex)" == 0 ]] &&
  pass "queue getQueue returns the native order and current index" ||
  fail "queue getQueue returned order=$QUEUE_ORDER currentIndex=$(jsonval "$QUEUE" currentIndex), expected $URIS / 0"

player "moveQueueItem({ from: 2, to: 1 })" >/dev/null
MOVED_QUEUE=$(player "getQueue()")
MOVED_ORDER=$(node -e 'const q=JSON.parse(process.argv[1]); console.log(JSON.stringify(q.items.map(x=>x.uri)))' "$MOVED_QUEUE")
EXPECTED_MOVED=$(node -e 'const u=JSON.parse(process.argv[1]); console.log(JSON.stringify([u[0],u[2],u[1]]))' "$URIS")
[[ "$MOVED_ORDER" == "$EXPECTED_MOVED" ]] &&
  pass "queue moveQueueItem changes order without replacing the queue" ||
  fail "queue moveQueueItem returned $MOVED_ORDER, expected $EXPECTED_MOVED"

CURRENT_REMOVE=$(player "removeQueueItem({ index: 0 })" 2>&1 || true)
[[ "$CURRENT_REMOVE" == *"Only upcoming queue items may be removed"* ]] &&
  pass "queue rejects removal of the current item" ||
  fail "queue current-item removal did not reject as expected: $CURRENT_REMOVE"

# History is protected too, not merely the row currently playing. The UI hides these edits,
# but exercise the bridge directly so that a future caller cannot bypass the native invariant.
player "jumpToQueueIndex({ index: 1 })" >/dev/null
HISTORY_MOVE=$(player "moveQueueItem({ from: 0, to: 2 })" 2>&1 || true)
[[ "$HISTORY_MOVE" == *"Only upcoming queue items may be moved"* ]] &&
  pass "queue rejects moving an already-played item" ||
  fail "queue history move did not reject as expected: $HISTORY_MOVE"

HISTORY_REMOVE=$(player "removeQueueItem({ index: 0 })" 2>&1 || true)
[[ "$HISTORY_REMOVE" == *"Only upcoming queue items may be removed"* ]] &&
  pass "queue rejects removal of an already-played item" ||
  fail "queue history removal did not reject as expected: $HISTORY_REMOVE"

# Return to a stable current index before the upcoming-item and insertion cases below.
player "setQueue({ uris: $URIS, startIndex: 0 })" >/dev/null
player "removeQueueItem({ index: 2 })" >/dev/null
REMOVED_QUEUE=$(player "getQueue()")
[[ "$(jsonval "$REMOVED_QUEUE" items.length)" == 2 ]] &&
  pass "queue removes an upcoming item" ||
  fail "queue removeQueueItem left $(jsonval "$REMOVED_QUEUE" items.length) item(s), expected 2"

player "playNext({ uri: JSON.parse('$URIS')[1] })" >/dev/null
player "addToQueue({ uri: JSON.parse('$URIS')[2] })" >/dev/null
INSERTED_QUEUE=$(player "getQueue()")
INSERTED_ORDER=$(node -e 'const q=JSON.parse(process.argv[1]); console.log(JSON.stringify(q.items.map(x=>x.uri)))' "$INSERTED_QUEUE")
EXPECTED_INSERTED=$(node -e 'const u=JSON.parse(process.argv[1]); console.log(JSON.stringify([u[0],u[1],u[1],u[2]]))' "$URIS")
[[ "$INSERTED_ORDER" == "$EXPECTED_INSERTED" ]] &&
  pass "queue playNext inserts after current and addToQueue appends" ||
  fail "queue insertion order was $INSERTED_ORDER, expected $EXPECTED_INSERTED"

js "location.hash = '#/queue'" > /dev/null
sleep 1
QUEUE_ROWS=$(js "document.querySelectorAll('.track-row').length")
[[ "$QUEUE_ROWS" == 4 ]] &&
  pass "queue route renders all live queue rows" ||
  fail "queue route rendered $QUEUE_ROWS rows, expected 4"

js "document.querySelectorAll('.track-row .row-main')[1]?.click()" > /dev/null
sleep 1
[[ "$(jsonval "$(player 'getPlayerState()')" currentIndex)" == 1 ]] &&
  pass "queue row tap jumps to the selected item" ||
  fail "queue row tap did not update currentIndex to 1"

# Restore the fixture queue expected by the service/persistence checks below.
player "setQueue({ uris: $URIS, startIndex: 0 })" >/dev/null
js "location.hash = '#/now-playing'" > /dev/null

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

NOTIF=$("${EMU[@]}" shell dumpsys notification --noredact | grep -A80 "pkg=$PKG")
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
  skip "T8 renderer-kill branch requires root/userdebug; production device cannot expose it"
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

# --- home-screen widget ----------------------------------------------------------

step "Widget — provider registration"

# Placing a widget needs a launcher and cannot be scripted on this build (there is no
# `cmd appwidget`), so what is asserted here is the part that silently breaks on a manifest
# or resource mistake: the system knows about the provider at all, with its declared size.
WIDGET=$("${EMU[@]}" shell dumpsys appwidget | grep -A2 "com.kefi.player/com.kefi.player.PlayerWidget" | head -3)
if [[ -n "$WIDGET" ]]; then
  pass "AppWidget provider PlayerWidget is registered with the system"
else
  fail "AppWidget provider PlayerWidget is not registered (manifest receiver or provider xml)"
fi

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

# ── Downloader feature — easter egg, router guard, share alias (T17) ──────────
#
# All checks here are OFFLINE: no real YouTube calls, no `initialize()`, no
# network. The web mock (ytdlp.web.js) drives the UI smoke test; the tab count,
# route guard and share alias are plain DOM/adb assertions.
#
# HOW THE GESTURE IS DRIVEN: `registerTap()` is bound via `@click="registerTap()"`
# on `.card.support` in SettingsView.vue — an ordinary DOM click handler, not
# something private to the ES module. `.click()` on that real element over CDP
# fires it exactly like a finger tap would (confirmed live in T16: see
# learnings.md's "T16 — CDP verification results" table, where 10 real
# `.card.support.click()` calls flipped the tab count from 2 to 3). So every
# gesture case below performs REAL clicks through the app's own handler —
# there is no shortcut through Preferences/setShareTargetEnabled for the
# unlock itself; that native path is only used afterwards to confirm the
# gesture's side effects actually persisted (checks 7-8) and survive a cold
# start (check 10), mirroring what App.onCreate() re-asserts on every launch.

# Resets to a known-LOCKED state without `pm clear`/`pm grant`: on a "user"-build
# retail device the shell UID holds neither CLEAR_APP_USER_DATA nor
# GRANT_RUNTIME_PERMISSIONS (both throw a SecurityException here — confirmed live,
# not assumed), so a full data wipe + re-grant is simply not available from adb on
# this class of device. Instead this deletes only the one preference key that
# matters, via `run-as` (ordinary file ownership under the app's own uid, not a
# privileged AMS call, so it works everywhere `pm clear` doesn't), then force-stops
# and relaunches: with the key gone, `initializeEasterEgg()` hydrates
# `unlocked.value = false` and — the same production path check 10 already
# exercises — `App.onCreate()` reads the (now-absent) preference and disables the
# `.ShareTarget` alias itself. No permission is ever revoked by this reset, so
# there is nothing to re-grant and no prompt that could sit on top of the Settings
# card.
reset_locked() {
  "${EMU[@]}" shell run-as "$PKG" rm -f /data/data/"$PKG"/shared_prefs/CapacitorStorage.xml \
    2>/dev/null || true
  "${EMU[@]}" shell am force-stop "$PKG"
  sleep 1
  launch_app || abort "could not reach the WebView after resetting to locked state (downloader section)"
}

# Navigates to #/settings and gives the route a moment to settle before any
# click is attempted.
goto_settings() {
  js "location.hash = '#/settings'" > /dev/null
  sleep 1
}

# Clicks `.card.support` $1 times inside a single CDP eval — a tight
# synchronous loop, i.e. genuinely "rapid succession" taps landing within the
# same event-loop turn, all through the real @click="registerTap()" handler.
# Returns the JSON string "OK", or "NO_ELEMENT" if the card isn't on screen
# (callers must check for that before trusting a tab-count assertion).
tap_support() {
  local n="$1"
  js "(() => {
    const el = document.querySelector('.card.support');
    if (!el) return 'NO_ELEMENT';
    for (let i = 0; i < $n; i++) el.click();
    return 'OK';
  })()"
}

step "Downloader: locked state (clean slate)"

reset_locked

# 1. Locked: exactly 2 tabs
TAB_COUNT=$(js "document.querySelectorAll('.tab').length")
[[ "$TAB_COUNT" == 2 ]] &&
  pass "downloader locked: exactly 2 tabs in the tab bar" ||
  fail "downloader locked: expected 2 tabs, got $TAB_COUNT"

# 2. Locked: router guard redirects /downloader → /library
goto_settings
js "location.hash = '#/downloader'" > /dev/null
sleep 1
HASH_AFTER=$(js "location.hash")
[[ "$HASH_AFTER" == '"#/library"' ]] &&
  pass "downloader locked: navigating #/downloader redirects to #/library" ||
  fail "downloader locked: expected #/library after guard, got $HASH_AFTER"

# 3. Locked: share alias absent from system share sheet
SHARE_COUNT=$("${EMU[@]}" shell cmd package query-activities \
  -a android.intent.action.SEND -t text/plain 2>/dev/null |
  grep -c "^[[:space:]]*name=com.kefi.player.ShareTarget" || true)
[[ "$SHARE_COUNT" == 0 ]] &&
  pass "downloader locked: ShareTarget not visible in system share sheet" ||
  fail "downloader locked: ShareTarget unexpectedly visible ($SHARE_COUNT matches)"

step "Downloader: gesture — 9 taps stay locked"

# 4. GESTURE, 9 taps: nine real clicks on .card.support, one short of the
# 10-tap threshold — must remain locked, and the guard must still redirect.
goto_settings
TAP9=$(tap_support 9)
if [[ "$TAP9" == '"NO_ELEMENT"' ]]; then
  fail "downloader gesture (9 taps): .card.support was not found on #/settings"
else
  sleep 1
  TAB_AFTER_9=$(js "document.querySelectorAll('.tab').length")
  [[ "$TAB_AFTER_9" == 2 ]] &&
    pass "downloader gesture: 9 real taps on .card.support — still 2 tabs (below the 10-tap threshold)" ||
    fail "downloader gesture: 9 real taps unexpectedly changed the tab count to $TAB_AFTER_9"

  js "location.hash = '#/downloader'" > /dev/null
  sleep 1
  HASH9=$(js "location.hash")
  [[ "$HASH9" == '"#/library"' ]] &&
    pass "downloader gesture: after 9 taps, #/downloader still redirects to #/library" ||
    fail "downloader gesture: after 9 taps, expected #/library, got $HASH9"
fi

step "Downloader: gesture — 10 taps spread past the 15s window stay locked"

# 5. GESTURE, taps spread past the window: 5 real taps, a genuine 16s wall-clock
# wait (longer than registerTap()'s 15000ms sliding window), then 5 more real
# taps. 10 taps happened, but never 10 inside any 15s window — must stay
# locked. This is the sliding-window logic's own reason to exist.
reset_locked
goto_settings
FIRST5=$(tap_support 5)
if [[ "$FIRST5" == '"NO_ELEMENT"' ]]; then
  fail "downloader gesture (spread taps): .card.support was not found on #/settings"
else
  sleep 16
  tap_support 5 > /dev/null
  sleep 1
  TAB_SPREAD=$(js "document.querySelectorAll('.tab').length")
  [[ "$TAB_SPREAD" == 2 ]] &&
    pass "downloader gesture: 10 real taps spread across >15s (5, wait 16s, 5) — still 2 tabs, the sliding window expired the first batch" ||
    fail "downloader gesture: 10 taps spread past the window unexpectedly unlocked (tab count $TAB_SPREAD)"
fi

step "Downloader: gesture — 10 taps inside 15s unlocks"

# 6. GESTURE, 10 taps inside 15s: ten real, rapid clicks on .card.support —
# crosses the threshold inside the window, must unlock (3 tabs appear).
reset_locked
goto_settings
TAP10=$(tap_support 10)
if [[ "$TAP10" == '"NO_ELEMENT"' ]]; then
  fail "downloader gesture (10 taps): .card.support was not found on #/settings"
else
  sleep 2
  TAB_UNLOCKED=$(js "document.querySelectorAll('.tab').length")
  [[ "$TAB_UNLOCKED" == 3 ]] &&
    pass "downloader gesture: 10 real rapid taps on .card.support unlocked — 3 tabs now shown" ||
    fail "downloader gesture: 10 real rapid taps did not unlock (tab count $TAB_UNLOCKED, expected 3)"
fi

step "Downloader: unlock side effects — persistence, share alias, guard"

# 7. The unlock preference genuinely persisted (registerTap()'s own
# Preferences.set call, not anything written by this script).
PREF_XML=$("${EMU[@]}" shell run-as "$PKG" cat \
  /data/data/"$PKG"/shared_prefs/CapacitorStorage.xml 2>/dev/null)
echo "$PREF_XML" | grep -q 'name="kefi.downloader.unlocked">1<' &&
  pass "downloader gesture: kefi.downloader.unlocked=1 persisted in CapacitorStorage.xml" ||
  fail "downloader gesture: kefi.downloader.unlocked=1 not found in CapacitorStorage.xml (${PREF_XML:-empty})"

# 8. The share alias registerTap() enabled is now visible to the system.
SHARE_AFTER=$("${EMU[@]}" shell cmd package query-activities \
  -a android.intent.action.SEND -t text/plain 2>/dev/null |
  grep -c "^[[:space:]]*name=com.kefi.player.ShareTarget" || true)
[[ "$SHARE_AFTER" -ge 1 ]] &&
  pass "downloader gesture: ShareTarget now visible in system share sheet ($SHARE_AFTER match)" ||
  fail "downloader gesture: ShareTarget not visible in share sheet after unlock (matches=$SHARE_AFTER)"

# 9. Router guard lifted: /downloader no longer redirects.
js "location.hash = '#/library'" > /dev/null
sleep 1
js "location.hash = '#/downloader'" > /dev/null
sleep 1
HASH_UNLOCKED=$(js "location.hash")
[[ "$HASH_UNLOCKED" == '"#/downloader"' ]] &&
  pass "downloader unlocked: navigating #/downloader stays on /downloader" ||
  fail "downloader unlocked: expected #/downloader to stick, got $HASH_UNLOCKED"

step "Downloader: persistence across process death"

# 10. Persistence: force-stop + relaunch — still 3 tabs. Covers both
# initializeEasterEgg()'s Preferences hydration and App.onCreate()'s
# re-assertion of the share alias, from a cold start.
"${EMU[@]}" shell am force-stop "$PKG"
sleep 2
launch_app || fail "downloader persistence: app did not come back after force-stop"
sleep 2

TAB_PERSIST=$(js "document.querySelectorAll('.tab').length")
[[ "$TAB_PERSIST" == 3 ]] &&
  pass "downloader persistence: still 3 tabs after force-stop + relaunch" ||
  fail "downloader persistence: expected 3 tabs after relaunch, got $TAB_PERSIST"

step "Downloader: UI smoke test (web mock, offline)"

# 11. Downloader UI smoke test — offline, no initialize()/network involved in
# the assertions themselves (DownloaderView's own onMounted calls initialize(),
# which only touches the web mock in this build).
js "location.hash = '#/downloader'" > /dev/null
sleep 3

DL_UI=$(js "JSON.stringify({
  page: document.querySelector('.downloader-page') !== null,
  chips: document.querySelectorAll('.chip').length,
  btnDisabled: document.querySelector('.primary-btn') && document.querySelector('.primary-btn').disabled,
  urlEmpty: document.querySelector('.url-input') && document.querySelector('.url-input').value === ''
})")

if [[ "$DL_UI" == __CDP_ERROR__* ]]; then
  fail "downloader UI smoke: CDP error — ${DL_UI#__CDP_ERROR__}"
else
  PAGE=$(node -e "const d=JSON.parse(process.argv[1]); console.log(d.page)" "$DL_UI")
  CHIPS=$(node -e "const d=JSON.parse(process.argv[1]); console.log(d.chips)" "$DL_UI")
  BTN=$(node -e "const d=JSON.parse(process.argv[1]); console.log(d.btnDisabled)" "$DL_UI")

  [[ "$PAGE" == true ]] &&
    pass "downloader UI: .downloader-page is present" ||
    fail "downloader UI: .downloader-page not found in DOM"

  [[ "$CHIPS" == 3 ]] &&
    pass "downloader UI: exactly 3 bitrate chips" ||
    fail "downloader UI: expected 3 .chip elements, got $CHIPS"

  [[ "$BTN" == true ]] &&
    pass "downloader UI: primary-btn is disabled while URL field is empty" ||
    fail "downloader UI: primary-btn is not disabled with empty URL field (btn.disabled=$BTN)"
fi

# Navigate back to library so the existing Summary step is clean.
js "location.hash = '#/library'" > /dev/null
sleep 1

# ── Playlists tab, favourites, and the track options menu (T16) ──────────────
#
# THIS SECTION HAS NEVER BEEN EXECUTED. The user withdrew device access ("stop testing on
# my mobile device") and the emulator was retired ("stamata na anoigeis ton emulator")
# during this plan's execution — every check below was authored against the real markup
# (PlaylistList.vue, PlaylistView.vue, TrackMenu.vue, TrackRow.vue, LibraryView.vue) and the
# real plugin contract (player.js / PlayerPlugin.kt), but no run has ever confirmed a single
# line of it actually passes. A first real run should watch for:
#   - CDP/UI timing: every `sleep` below is a guess at how long a route change, a Vue
#     re-render, or a Room round trip takes on a real device. A FAIL immediately after a
#     `.click()` or a force-stop is more likely a race than a real regression — lengthen the
#     adjacent sleep before concluding the feature is broken.
#   - T15 (the i18n strings) had NOT landed when this was written: most `t()` labels still
#     render as their raw key (e.g. `library.tab.playlists`, `playlists.favorites`). Nothing
#     below asserts on that visible text for exactly that reason — only on structure
#     (`.segment`, `.playlist-row`, `.row-menu`, `.backdrop`, `.option`), counts, and data the
#     player itself supplies (titles, paths). `library.count.song(s)` is the one exception —
#     it predates this plan and already exists in both `en.js`/`el.js`, which is why counts
#     are asserted on directly.
#   - The reorder check does NOT exercise the drag gesture — CDP cannot synthesise the
#     long-press-then-drag `useDragReorder.js` requires. It calls `reorderPlaylist` directly,
#     the same call the gesture's own `onDragCommit` makes on drop, and only proves the
#     persistence half. See that check's own comment for the full reasoning.
#   - Nothing in this section calls `pm clear`, so nothing needs a `pm grant` re-grant —
#     every "survives force-stop + relaunch" check below uses `am force-stop` only, same as
#     the existing T12 section above.

goto_library() {
  js "location.hash = '#/library'" > /dev/null
  sleep 1
}

# TABS = ['songs', 'albums', 'artists', 'folders', 'playlists'] (LibraryView.vue) — index-
# based, same left-to-right order the segmented control itself renders.
select_tab() {
  js "document.querySelectorAll('.segment')[$1]?.click()" > /dev/null
  sleep 1
}

# Mirrors usePlaylists.js's own pathOf() exactly (folder === '' ? displayName : folder + '/'
# + displayName), reading from the $TRACKS snapshot the preflight section already captured,
# so a path built here always matches what the app itself would derive for the same track.
pathof_by_title() {
  node -e '
    const { tracks } = JSON.parse(process.argv[1])
    const track = tracks.find((t) => t.title === process.argv[2])
    if (!track) { console.log(""); process.exit(0) }
    console.log(track.folder === "" ? track.displayName : `${track.folder}/${track.displayName}`)
  ' "$TRACKS" "$1"
}

step "T16 — Playlists tab: five tabs, fifth reachable"

goto_library
SEGMENT_COUNT=$(js "document.querySelectorAll('.segment').length")
[[ "$SEGMENT_COUNT" == 5 ]] &&
  pass "T16 the segmented control reports 5 segments" ||
  fail "T16 the segmented control reports $SEGMENT_COUNT segments, expected 5"

select_tab 4
SELECTED_INDEX=$(js "[...document.querySelectorAll('.segment')].findIndex((el) => el.classList.contains('selected'))")
PLAYLIST_ROW_COUNT=$(js "document.querySelectorAll('.playlist-row').length")

[[ "$PLAYLIST_ROW_COUNT" -ge 3 ]] &&
  pass "T16 selecting the 5th segment renders $PLAYLIST_ROW_COUNT .playlist-row rows (PlaylistList actually mounted, not just a class toggle)" ||
  fail "T16 selecting the 5th segment rendered only $PLAYLIST_ROW_COUNT .playlist-row rows (expected >= 3: Favorites, Recently added, the create affordance)"

[[ "$SELECTED_INDEX" == 4 ]] &&
  pass "T16 the 5th segment carries .selected after being clicked" ||
  fail "T16 expected segment index 4 to carry .selected, got index $SELECTED_INDEX"

step "T16 — Playlists tab content: Favorites and Recently added show real counts"

goto_library
select_tab 4

FAV_META=$(js "document.querySelectorAll('.playlist-row')[0]?.querySelector('.meta')?.textContent ?? ''")
[[ "$FAV_META" =~ [0-9]+ ]] &&
  pass "T16 Favorites row meta line shows a count ('$FAV_META')" ||
  fail "T16 Favorites row meta line has no digit in it ('$FAV_META') — favoriteTracks may not be wired to the row"

RECENT_META=$(js "document.querySelectorAll('.playlist-row')[1]?.querySelector('.meta')?.textContent ?? ''")
RECENT_META_NUM=$(node -e 'console.log((process.argv[1].match(/\d+/) || ["?"])[0])' "$RECENT_META")
EXPECTED_RECENT=$(node -e 'const { tracks } = JSON.parse(process.argv[1]); console.log(Math.min(tracks.length, 15))' "$TRACKS")
[[ "$RECENT_META_NUM" == "$EXPECTED_RECENT" ]] &&
  pass "T16 Recently added row meta shows $RECENT_META_NUM, matching min(library size, 15) = $EXPECTED_RECENT" ||
  fail "T16 Recently added row meta is '$RECENT_META' (parsed '$RECENT_META_NUM'), expected $EXPECTED_RECENT"

step "T16 — Create a playlist through the DOM, then confirm it survives a relaunch"

NEW_PLAYLIST_ID=""
PLAYLIST_NAME="QA Playlist $(date +%s)"
PLAYLIST_NAME_JSON=$(node -e 'console.log(JSON.stringify(process.argv[1]))' "$PLAYLIST_NAME")

goto_library
select_tab 4

CREATE_CLICK=$(js "(() => {
  const el = document.querySelector('.create-row')
  if (!el) return 'NO_ELEMENT'
  el.click()
  return 'OK'
})()")

if [[ "$CREATE_CLICK" != '"OK"' ]]; then
  fail "T16 create playlist: .create-row was not found on the Playlists tab"
else
  sleep 1
  TYPE_RESULT=$(js "(() => {
    const el = document.querySelector('.create-input')
    if (!el) return 'NO_INPUT'
    el.value = $PLAYLIST_NAME_JSON
    el.dispatchEvent(new Event('input', { bubbles: true }))
    return el.value
  })()")

  if [[ "$TYPE_RESULT" != *"$PLAYLIST_NAME"* ]]; then
    fail "T16 create playlist: typing into .create-input did not stick (got $TYPE_RESULT)"
  else
    CONFIRM_CLICK=$(js "(() => {
      const el = document.querySelector('.create-confirm')
      if (!el) return 'NO_ELEMENT'
      el.click()
      return 'OK'
    })()")

    if [[ "$CONFIRM_CLICK" != '"OK"' ]]; then
      fail "T16 create playlist: .create-confirm was not found after typing a name"
    else
      sleep 2
      # submitCreate() navigates to the new playlist's own detail screen on success
      # (PlaylistList.vue's `emit('select', id)` -> LibraryView's goToPlaylist), so the new
      # row is not visible again until coming back to the Playlists tab.
      HASH_AFTER_CREATE=$(js "location.hash")
      NEW_PLAYLIST_ID=$(node -e '
        const hash = JSON.parse(process.argv[1])
        const m = hash.match(/\/playlist\/(\d+)/)
        console.log(m ? m[1] : "")
      ' "$HASH_AFTER_CREATE")

      goto_library
      select_tab 4
      CREATED_META=$(js "(() => {
        const row = [...document.querySelectorAll('.playlist-row')].find(
          (r) => r.querySelector('.name')?.textContent === $PLAYLIST_NAME_JSON,
        )
        return row ? row.querySelector('.meta').textContent : null
      })()")

      if [[ "$CREATED_META" == null ]]; then
        fail "T16 create playlist: no .playlist-row named '$PLAYLIST_NAME' appeared after submitting (hash was $HASH_AFTER_CREATE)"
      else
        CREATED_META_NUM=$(node -e 'console.log((JSON.parse(process.argv[1]).match(/\d+/) || ["?"])[0])' "$CREATED_META")
        [[ "$CREATED_META_NUM" == 0 ]] &&
          pass "T16 create playlist: '$PLAYLIST_NAME' appeared with a 0-track count" ||
          fail "T16 create playlist: '$PLAYLIST_NAME' appeared with meta $CREATED_META (parsed $CREATED_META_NUM), expected 0"

        "${EMU[@]}" shell am force-stop "$PKG"
        sleep 2
        launch_app || fail "T16 create playlist: app did not come back after force-stop"
        sleep 2
        goto_library
        select_tab 4

        SURVIVED=$(js "[...document.querySelectorAll('.playlist-row .name')].some((el) => el.textContent === $PLAYLIST_NAME_JSON)")
        [[ "$SURVIVED" == true ]] &&
          pass "T16 create playlist: '$PLAYLIST_NAME' survived force-stop + relaunch (Room persisted it, not just a ref)" ||
          fail "T16 create playlist: '$PLAYLIST_NAME' did NOT survive force-stop + relaunch"
      fi
    fi
  fi
fi

step "T16 — Favourite round trip: toggle via the options sheet, confirm it persists"

goto_library
select_tab 0

FIRST_TITLE=$(js "document.querySelectorAll('.track-row .title')[0]?.textContent ?? ''")
FIRST_PATH=$(pathof_by_title "$FIRST_TITLE")

if [[ -z "$FIRST_PATH" ]]; then
  fail "T16 favourite round trip: could not resolve a path for the first Songs row ('$FIRST_TITLE')"
else
  FAV_BEFORE=$(js "Capacitor.Plugins.Player.listFavorites()")
  WAS_FAVOURITE=$(node -e 'const { paths } = JSON.parse(process.argv[1]); console.log(paths.includes(process.argv[2]))' "$FAV_BEFORE" "$FIRST_PATH")

  SHEET_CLICK=$(js "(() => {
    const el = document.querySelectorAll('.row-menu')[0]
    if (!el) return 'NO_ELEMENT'
    el.click()
    return 'OK'
  })()")

  if [[ "$SHEET_CLICK" != '"OK"' ]]; then
    fail "T16 favourite round trip: .row-menu was not found on the first Songs row"
  else
    sleep 1
    SHEET_OPEN=$(js "document.querySelector('.backdrop') !== null")
    if [[ "$SHEET_OPEN" != true ]]; then
      fail "T16 favourite round trip: .backdrop did not appear after clicking .row-menu"
    else
      # The favourite/unfavourite toggle is the FIRST .option in the sheet's 'menu' step
      # (TrackMenu.vue), which is exactly the step it opens on.
      js "document.querySelectorAll('.option')[0]?.click()" > /dev/null
      sleep 1
      js "document.querySelector('.backdrop')?.click()" > /dev/null
      sleep 1

      FAV_AFTER=$(js "Capacitor.Plugins.Player.listFavorites()")
      IS_FAVOURITE_AFTER=$(node -e 'const { paths } = JSON.parse(process.argv[1]); console.log(paths.includes(process.argv[2]))' "$FAV_AFTER" "$FIRST_PATH")
      FAV_COUNT_AFTER=$(node -e 'console.log(JSON.parse(process.argv[1]).paths.length)' "$FAV_AFTER")
      EXPECTED_AFTER=$([[ "$WAS_FAVOURITE" == true ]] && echo false || echo true)

      [[ "$IS_FAVOURITE_AFTER" == "$EXPECTED_AFTER" ]] &&
        pass "T16 favourite round trip: '$FIRST_TITLE' flipped from $WAS_FAVOURITE to $IS_FAVOURITE_AFTER via the sheet" ||
        fail "T16 favourite round trip: '$FIRST_TITLE' is $IS_FAVOURITE_AFTER after the toggle, expected $EXPECTED_AFTER (was $WAS_FAVOURITE)"

      goto_library
      select_tab 4
      FAV_META_NOW=$(js "document.querySelectorAll('.playlist-row')[0]?.querySelector('.meta')?.textContent ?? ''")
      FAV_META_NOW_NUM=$(node -e 'console.log((process.argv[1].match(/\d+/) || ["?"])[0])' "$FAV_META_NOW")
      [[ "$FAV_META_NOW_NUM" == "$FAV_COUNT_AFTER" ]] &&
        pass "T16 favourite round trip: the Favorites row meta now shows $FAV_META_NOW_NUM, matching listFavorites()'s $FAV_COUNT_AFTER paths" ||
        fail "T16 favourite round trip: Favorites row meta is '$FAV_META_NOW', expected to reflect $FAV_COUNT_AFTER favourites"

      "${EMU[@]}" shell am force-stop "$PKG"
      sleep 2
      launch_app || fail "T16 favourite round trip: app did not come back after force-stop"
      sleep 2
      goto_library
      select_tab 4

      FAV_AFTER_RESTART=$(js "Capacitor.Plugins.Player.listFavorites()")
      IS_FAVOURITE_RESTART=$(node -e 'const { paths } = JSON.parse(process.argv[1]); console.log(paths.includes(process.argv[2]))' "$FAV_AFTER_RESTART" "$FIRST_PATH")
      [[ "$IS_FAVOURITE_RESTART" == "$EXPECTED_AFTER" ]] &&
        pass "T16 favourite round trip: the favourite survived force-stop + relaunch (Room, not a ref)" ||
        fail "T16 favourite round trip: after relaunch '$FIRST_TITLE' favourite state is $IS_FAVOURITE_RESTART, expected $EXPECTED_AFTER"
    fi
  fi
fi

step "T16 — Reorder persistence (persistence contract only — see comment)"

# HONESTY NOTE, per the plan's explicit requirement: a real reorder is a long-press-then-
# drag driven by touch events (useDragReorder.js: a 400ms hold within 10px, THEN
# touchmove/touchend). CDP's Runtime.evaluate can synthesise ONE call at a time with
# userGesture:true — it cannot fabricate a timed touchstart/touchmove/touchend sequence that
# the composable's own listeners would treat as a genuine drag, and dispatching synthetic
# TouchEvents this composable was never built to trust would be exactly the placebo check
# this task was told not to write. So this check drives the SAME call the gesture commits
# through on drop — Capacitor.Plugins.Player.reorderPlaylist({ id, paths }), the exact call
# PlaylistView.vue's onDragCommit makes — directly, and verifies the detail screen re-renders
# in the new order after a force-stop + relaunch. That proves Room's reorderPlaylist
# persistence contract end-to-end; it does NOT exercise the drag gesture itself, which stays
# a hands-on-only item (plan's F5) until a device is available again.

if [[ -z "$NEW_PLAYLIST_ID" ]]; then
  fail "T16 reorder: no playlist id available (the create-playlist check above did not produce one)"
else
  FIRST_TWO=$(node -e '
    const { tracks } = JSON.parse(process.argv[1])
    const pathOf = (t) => (t.folder === "" ? t.displayName : `${t.folder}/${t.displayName}`)
    console.log(JSON.stringify(tracks.slice(0, 2).map((t) => ({ title: t.title, path: pathOf(t) }))))
  ' "$TRACKS")
  PATH1=$(node -e 'console.log(JSON.parse(process.argv[1])[0].path)' "$FIRST_TWO")
  PATH2=$(node -e 'console.log(JSON.parse(process.argv[1])[1].path)' "$FIRST_TWO")
  TITLE2=$(node -e 'console.log(JSON.parse(process.argv[1])[1].title)' "$FIRST_TWO")

  # SETUP, not the check itself: seed the playlist with these 2 tracks in a known order.
  SEED_PATHS_JSON=$(node -e 'console.log(JSON.stringify([process.argv[1], process.argv[2]]))' "$PATH1" "$PATH2")
  js "Capacitor.Plugins.Player.addToPlaylist({ id: $NEW_PLAYLIST_ID, paths: $SEED_PATHS_JSON })" > /dev/null

  # THE CHECK'S OWN ACTION: the exact call onDragCommit makes, with the two paths swapped.
  SWAPPED_PATHS_JSON=$(node -e 'console.log(JSON.stringify([process.argv[2], process.argv[1]]))' "$PATH1" "$PATH2")
  js "Capacitor.Plugins.Player.reorderPlaylist({ id: $NEW_PLAYLIST_ID, paths: $SWAPPED_PATHS_JSON })" > /dev/null

  "${EMU[@]}" shell am force-stop "$PKG"
  sleep 2
  launch_app || fail "T16 reorder: app did not come back after force-stop"
  sleep 2
  js "location.hash = '#/playlist/$NEW_PLAYLIST_ID'" > /dev/null
  sleep 2

  FIRST_RENDERED=$(js "document.querySelectorAll('.row-wrap .title')[0]?.textContent ?? ''")
  [[ "$FIRST_RENDERED" == "$TITLE2" ]] &&
    pass "T16 reorder: after force-stop + relaunch the detail screen renders '$FIRST_RENDERED' first, matching the order persisted via reorderPlaylist" ||
    fail "T16 reorder: after relaunch the detail screen's first row is '$FIRST_RENDERED', expected '$TITLE2' (reorderPlaylist did not persist the swap)"
fi

step "T16 — The options sheet opens and closes"

goto_library
select_tab 0

SHEET_CLICK2=$(js "(() => {
  const el = document.querySelectorAll('.row-menu')[0]
  if (!el) return 'NO_ELEMENT'
  el.click()
  return 'OK'
})()")

if [[ "$SHEET_CLICK2" != '"OK"' ]]; then
  fail "T16 sheet open/close: .row-menu was not found on the first Songs row"
else
  sleep 1
  SHEET_PRESENT=$(js "document.querySelector('.backdrop') !== null")
  [[ "$SHEET_PRESENT" == true ]] &&
    pass "T16 sheet: clicking .row-menu renders .backdrop" ||
    fail "T16 sheet: .backdrop did not appear after clicking .row-menu"

  js "document.querySelector('.backdrop')?.click()" > /dev/null
  sleep 1
  SHEET_GONE=$(js "document.querySelector('.backdrop') === null")
  [[ "$SHEET_GONE" == true ]] &&
    pass "T16 sheet: clicking the backdrop removes .backdrop from the DOM" ||
    fail "T16 sheet: .backdrop is still present after a backdrop click"
fi

# Navigate back to library so the existing Summary step is clean.
goto_library

# ── T9: File details menu row, delete-from-device confirm gate, and a mocked
#    delete+reload round trip ───────────────────────────────────────────────
#
# HONESTY NOTE, mandatory per this task: none of the three real native SDK branches in
# PlayerPlugin.kt's deleteTrack (the plain pre-Q resolver.delete path, the API 29
# RecoverableSecurityException retry-after-approval path, or the API 30+
# MediaStore.createDeleteRequest path) has ever executed, on any device or emulator, in any
# session of this plan — and neither has the real OS-owned delete-confirmation dialog itself
# ever been shown or answered. Both are unreachable from this script by construction:
# MediaStore.createDeleteRequest's own documented contract requires that confirmation through a
# SYSTEM dialog, not a WebView one — it never appears in the DOM, so nothing Runtime.evaluate
# over CDP can see or click, and driving it with `input tap` would need a hardcoded pixel
# coordinate that silently stops working the moment the dialog's layout moves (exactly the
# failure mode this whole script's CDP-only approach exists to avoid; see the file's own header
# comment). A first real device run of this feature should watch for the three distinct
# outcomes `onDeleteFromDevice` maps in TrackMenu.vue: DELETED (the file is actually gone,
# listTracks() no longer reports it, a success notice shown, the sheet stays open), CANCELLED
# (the user backs out of the system dialog — no reload, an info notice, the file still present),
# and REJECTED (a genuine native failure, including PlayerPlugin.kt's own zero-rows-deleted
# rejection — surfaced as the app's generic actionFailed error notice). Every deleteTrack/
# listTracks call below runs through a JS-layer stub assigned directly onto
# Capacitor.Plugins.Player, never through the real native implementation: calling the real one
# here would hang the suite on a system dialog nothing in this script can answer.

step "T9 — File details menu row and delete-from-device confirm gate"

goto_library
select_tab 0

TITLE_T9_RAW=$(js "document.querySelectorAll('.track-row .title')[0]?.textContent ?? ''")
TITLE_T9=$(node -e 'console.log(JSON.parse(process.argv[1]))' "$TITLE_T9_RAW")

SHEET_CLICK_T9=$(js "(() => {
  const el = document.querySelectorAll('.row-menu')[0]
  if (!el) return 'NO_ELEMENT'
  el.click()
  return 'OK'
})()")

if [[ "$SHEET_CLICK_T9" != '"OK"' ]]; then
  fail "T9: .row-menu was not found on the first Songs row"
else
  sleep 1

  # T3 added "File details" as the sheet's 6th .option (index 5); T7 added "Delete from device"
  # as the 7th and last (index 6) — exact placement confirmed in this notepad's own T3/T7
  # entries. TrackMenu.vue's real markup gives every .option row the same plain
  # `<button class="option">` shape with no data-attribute or other stable per-row selector
  # (confirmed by reading the file), so — the same index-based convention this script's own
  # select_tab()/TABS comment already relies on for .segment — a row count is what is asserted
  # here, which is exactly what a reverted T3 or T7 would change.
  OPTION_COUNT_T9=$(js "document.querySelectorAll('.option').length")
  [[ "$OPTION_COUNT_T9" == 7 ]] &&
    pass "T9 menu: the options sheet shows 7 .option rows (5 pre-existing + File details + Delete from device)" ||
    fail "T9 menu: the options sheet shows $OPTION_COUNT_T9 .option rows, expected 7"

  # listTracks()'s own real artist for the opened track, read from the preflight's $TRACKS
  # snapshot — a concrete expected value from real fixture data, not merely "is non-empty".
  ARTIST_T9=$(node -e '
    const { tracks } = JSON.parse(process.argv[1])
    const track = tracks.find((t) => t.title === process.argv[2])
    console.log(track ? (track.artist ?? "") : "")
  ' "$TRACKS" "$TITLE_T9")

  js "document.querySelectorAll('.option')[5]?.click()" > /dev/null
  sleep 1

  DETAIL_TITLE_T9_RAW=$(js "document.querySelectorAll('.detail-value')[0]?.textContent ?? ''")
  DETAIL_TITLE_T9=$(node -e 'console.log(JSON.parse(process.argv[1]))' "$DETAIL_TITLE_T9_RAW")
  DETAIL_ARTIST_T9_RAW=$(js "document.querySelectorAll('.detail-value')[1]?.textContent ?? ''")
  DETAIL_ARTIST_T9=$(node -e 'console.log(JSON.parse(process.argv[1]))' "$DETAIL_ARTIST_T9_RAW")

  [[ -n "$DETAIL_TITLE_T9" && "$DETAIL_TITLE_T9" == "$TITLE_T9" ]] &&
    pass "T9 details: the details step's title value is '$DETAIL_TITLE_T9', matching the opened track" ||
    fail "T9 details: details step title is '$DETAIL_TITLE_T9', expected '$TITLE_T9'"

  [[ -n "$DETAIL_ARTIST_T9" && "$DETAIL_ARTIST_T9" == "$ARTIST_T9" ]] &&
    pass "T9 details: the details step's artist value is '$DETAIL_ARTIST_T9', matching listTracks()'s real artist for '$TITLE_T9'" ||
    fail "T9 details: details step artist is '$DETAIL_ARTIST_T9', expected '$ARTIST_T9' (from listTracks())"

  # Back to the menu step (the sheet's own back button, present whenever step is 'details' or
  # 'playlist' — it is always the FIRST .icon-button in DOM order when present) before touching
  # the delete row.
  js "document.querySelector('.icon-button')?.click()" > /dev/null
  sleep 1

  # window.confirm on a delete tap: grepped this file for "confirm" before writing this — no
  # existing pattern was found for stubbing/observing it. Stubbed here rather than left
  # untested, since this is a plain page-global JS function and overriding it from CDP is no
  # different from any other DOM/JS manipulation this script already does elsewhere (e.g. the
  # layout check's DOM mutation, or tap_support()'s real .click() calls) — this is NOT the same
  # thing as the real OS-owned dialog Player.deleteTrack shows afterwards, which is the part the
  # top-of-section HONESTY NOTE says cannot be driven this way. Returns false here so the code
  # takes its documented early-return branch (`if (!window.confirm(...)) return`) and never
  # reaches the real native call — genuinely safe to run against a real device.
  js "window.__qaConfirmCalls = []; window.confirm = (msg) => { window.__qaConfirmCalls.push(msg); return false }" > /dev/null
  js "document.querySelectorAll('.option')[6]?.click()" > /dev/null
  sleep 1

  CONFIRM_CALLS_T9=$(js "window.__qaConfirmCalls.length")
  MENU_AFTER_DECLINE_T9=$(js "document.querySelectorAll('.option').length")
  NOTICE_ABSENT_T9=$(js "document.querySelector('.notice') === null")

  [[ "$CONFIRM_CALLS_T9" == 1 ]] &&
    pass "T9 confirm gate: tapping Delete from device invoked window.confirm exactly once" ||
    fail "T9 confirm gate: window.confirm was called $CONFIRM_CALLS_T9 times, expected 1"

  [[ "$MENU_AFTER_DECLINE_T9" == 7 && "$NOTICE_ABSENT_T9" == true ]] &&
    pass "T9 confirm gate: declining leaves the sheet on the menu step with no notice (the real native deleteTrack call never ran)" ||
    fail "T9 confirm gate: after declining, .option count is $MENU_AFTER_DECLINE_T9 (expected 7) and notice-absent is $NOTICE_ABSENT_T9 (expected true)"

  # Neither this checkbox's "mock deleteTrack resolving {deleted:true}" nor the row-count-drops-
  # by-one assertion can come from the REAL native deleteTrack on an actual device: both the API
  # 30+ MediaStore.createDeleteRequest path and the API 29 RecoverableSecurityException
  # approval-retry path route through the real OS dialog the HONESTY NOTE above already covers,
  # and even approving it would permanently remove one of the three real QA fixture files every
  # other section in this suite depends on. So this stubs Capacitor.Plugins.Player.deleteTrack
  # and .listTracks directly on the live plugin object — the same "call the plugin surface
  # directly instead of the untestable gesture" precedent this notepad's T16 reorder-persistence
  # check already used for a drag it could not synthesise. This proves ONLY the reload-wiring
  # contract: that TrackMenu's real onDeleteFromDevice handler, on a resolved `{ deleted: true }`,
  # really calls useLibrary().reload(), which really re-invokes listTracks() and really
  # re-renders one fewer .track-row. It does NOT exercise the real native delete mechanics or
  # the real OS confirmation dialog.
  BEFORE_COUNT_T9=$(js "document.querySelectorAll('.track-row').length")

  js "(() => {
    window.__qaOriginalDeleteTrack = Capacitor.Plugins.Player.deleteTrack
    window.__qaOriginalListTracks = Capacitor.Plugins.Player.listTracks
    window.confirm = () => true
    Capacitor.Plugins.Player.deleteTrack = async () => ({ deleted: true })
    Capacitor.Plugins.Player.listTracks = async () => {
      const real = await window.__qaOriginalListTracks.call(Capacitor.Plugins.Player)
      return { tracks: real.tracks.slice(1) }
    }
    return 'OK'
  })()" > /dev/null

  js "document.querySelectorAll('.option')[6]?.click()" > /dev/null
  sleep 2

  AFTER_COUNT_T9=$(js "document.querySelectorAll('.track-row').length")
  NOTICE_SUCCESS_T9=$(js "document.querySelector('.notice')?.classList.contains('success') ?? false")

  [[ "$AFTER_COUNT_T9" == "$((BEFORE_COUNT_T9 - 1))" ]] &&
    pass "T9 mock delete+reload: .track-row count dropped from $BEFORE_COUNT_T9 to $AFTER_COUNT_T9 after the stubbed deleteTrack resolved {deleted:true} and reload() re-ran listTracks" ||
    fail "T9 mock delete+reload: .track-row count is $AFTER_COUNT_T9, expected $((BEFORE_COUNT_T9 - 1)) (was $BEFORE_COUNT_T9)"

  [[ "$NOTICE_SUCCESS_T9" == true ]] &&
    pass "T9 mock delete+reload: a success notice (.notice.success) is shown after the stubbed delete resolved" ||
    fail "T9 mock delete+reload: no .notice.success shown after the stubbed delete (got $NOTICE_SUCCESS_T9)"

  # Restore the real plugin methods and close the sheet, so nothing stubbed here leaks into
  # whatever runs after this section.
  js "(() => {
    Capacitor.Plugins.Player.deleteTrack = window.__qaOriginalDeleteTrack
    Capacitor.Plugins.Player.listTracks = window.__qaOriginalListTracks
    delete window.confirm
  })()" > /dev/null
  js "document.querySelector('.backdrop')?.click()" > /dev/null
  sleep 1
fi

goto_library

step "Summary"
echo "$((CHECKS - FAILURES))/$CHECKS checks passed"
if [[ "$FAILURES" -gt 0 ]]; then
  echo "$FAILURES FAILED"
  exit 1
fi
echo "ALL PASS"
