#!/usr/bin/env bash
# Generates 3 deterministic audio fixtures with ffmpeg, pushes them to the
# emulator's /sdcard/Music/kefi-qa/, and indexes them into MediaStore.
#
# Idempotent: safe to re-run any number of times. Regenerates /tmp/kefi-qa/*,
# re-pushes, re-scans, and re-verifies. Never leaves duplicate MediaStore rows
# because the pushed files keep identical paths/content and Android's media
# scanner updates existing rows in place rather than inserting new ones.
#
# Usage:
#   scripts/qa-fixtures.sh              # uses emulator-5554
#   scripts/qa-fixtures.sh emulator-5556 # explicit serial
set -euo pipefail

SERIAL="${1:-emulator-5554}"
ADB=(adb -s "$SERIAL")
LOCAL_DIR="/tmp/kefi-qa"
REMOTE_DIR="/sdcard/Music/kefi-qa"

echo "Serial:    $SERIAL"
echo "Local dir: $LOCAL_DIR"

rm -rf "$LOCAL_DIR"
mkdir -p "$LOCAL_DIR"

# Detect an mp3 encoder; fall back to AAC/m4a if libmp3lame is unavailable.
# (Captured into a variable first, not piped straight into `grep -q`: with
# pipefail, grep's early exit on match would SIGPIPE ffmpeg and the pipeline's
# exit status would reflect ffmpeg's non-zero SIGPIPE status, not grep's.)
FFMPEG_ENCODERS="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
if grep -q libmp3lame <<<"$FFMPEG_ENCODERS"; then
  AUDIO_CODEC="libmp3lame"
  EXT="mp3"
  echo "Codec:     libmp3lame -> .mp3"
else
  AUDIO_CODEC="aac"
  EXT="m4a"
  echo "Codec:     libmp3lame UNAVAILABLE, falling back to aac -> .m4a (RECORDED)"
fi

# Cover art for f1 only.
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "color=c=orange:s=600x600:d=1" -frames:v 1 \
  "$LOCAL_DIR/cover.png"

# f1.mp3 — Άσπρη Μέρα / Δήμητρα Παπαδοπούλου / Κέφι Vol 1 / 5s / embedded art
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "sine=frequency=440:duration=5" \
  -i "$LOCAL_DIR/cover.png" \
  -map 0:a -map 1:v \
  -metadata title="Άσπρη Μέρα" \
  -metadata artist="Δήμητρα Παπαδοπούλου" \
  -metadata album="Κέφι Vol 1" \
  -metadata track="1" \
  -id3v2_version 3 \
  -codec:a "$AUDIO_CODEC" -b:a 128k \
  -codec:v copy -disposition:v attached_pic \
  "$LOCAL_DIR/f1.$EXT"

# f2 — Zeibekiko / Nikos Alexiou / Kefi Vol 1 / 7s / no art
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "sine=frequency=523:duration=7" \
  -metadata title="Zeibekiko" \
  -metadata artist="Nikos Alexiou" \
  -metadata album="Kefi Vol 1" \
  -metadata track="2" \
  -id3v2_version 3 \
  -codec:a "$AUDIO_CODEC" -b:a 128k \
  "$LOCAL_DIR/f2.$EXT"

# f3 — Ωδή / Δήμητρα Παπαδοπούλου / Κέφι Vol 2 / 9s / no art
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "sine=frequency=659:duration=9" \
  -metadata title="Ωδή" \
  -metadata artist="Δήμητρα Παπαδοπούλου" \
  -metadata album="Κέφι Vol 2" \
  -metadata track="1" \
  -id3v2_version 3 \
  -codec:a "$AUDIO_CODEC" -b:a 128k \
  "$LOCAL_DIR/f3.$EXT"

rm -f "$LOCAL_DIR/cover.png"

echo
echo "Generated fixtures:"
ls -la "$LOCAL_DIR"

echo
echo "Verifying Greek tags survived the encode (ffprobe):"
for f in f1 f3; do
  ffprobe -v quiet -show_entries format_tags=title,artist,album \
    -of default=noprint_wrappers=1 "$LOCAL_DIR/$f.$EXT"
done

# Push to the device.
"${ADB[@]}" shell mkdir -p "$REMOTE_DIR"
"${ADB[@]}" push "$LOCAL_DIR/." "$REMOTE_DIR/"

# Verification helper: query MediaStore and check for exactly 3 correctly
# tagged rows with the expected durations (within 500ms).
verify() {
  local out
  out=$("${ADB[@]}" shell content query --uri content://media/external/audio/media \
    --projection title:artist:album:duration --where "is_music=1" 2>/dev/null || true)
  VERIFY_OUTPUT="$out"

  local rows
  rows=$(printf '%s\n' "$out" | grep -c "^Row: " || true)
  [[ "$rows" -eq 3 ]] || return 1

  printf '%s\n' "$out" | grep -q "title=Άσπρη Μέρα" || return 1
  printf '%s\n' "$out" | grep -q "title=Zeibekiko" || return 1
  printf '%s\n' "$out" | grep -q "title=Ωδή" || return 1

  local d1 d2 d3
  d1=$(printf '%s\n' "$out" | grep "title=Άσπρη Μέρα" | grep -oE "duration=[0-9]+" | cut -d= -f2)
  d2=$(printf '%s\n' "$out" | grep "title=Zeibekiko" | grep -oE "duration=[0-9]+" | cut -d= -f2)
  d3=$(printf '%s\n' "$out" | grep "title=Ωδή" | grep -oE "duration=[0-9]+" | cut -d= -f2)

  [[ -n "$d1" && -n "$d2" && -n "$d3" ]] || return 1
  (( d1 > 4500 && d1 < 5500 )) || return 1
  (( d2 > 6500 && d2 < 7500 )) || return 1
  (( d3 > 8500 && d3 < 9500 )) || return 1

  return 0
}

# Scan ladder: try each method in order, stop at the first that verifies.
SCAN_METHOD=""

echo
echo "Scan attempt 1: content call scan_file (per file)"
for f in f1 f2 f3; do
  "${ADB[@]}" shell content call --uri content://media --method scan_file \
    --arg "$REMOTE_DIR/$f.$EXT" >/dev/null 2>&1 || true
done
sleep 2
if verify; then
  SCAN_METHOD="content call --uri content://media --method scan_file --arg <path> (per file)"
fi

if [[ -z "$SCAN_METHOD" ]]; then
  echo "Scan attempt 2: content call scan_volume external_primary"
  "${ADB[@]}" shell content call --uri content://media --method scan_volume \
    --arg external_primary >/dev/null 2>&1 || true
  sleep 2
  if verify; then
    SCAN_METHOD="content call --uri content://media --method scan_volume --arg external_primary"
  fi
fi

if [[ -z "$SCAN_METHOD" ]]; then
  echo "Scan attempt 3: cmd jobscheduler run -f com.android.providers.media.module -201"
  "${ADB[@]}" shell cmd jobscheduler run -f com.android.providers.media.module -201 >/dev/null 2>&1 || true
  sleep 3
  if verify; then
    SCAN_METHOD="cmd jobscheduler run -f com.android.providers.media.module -201"
  fi
fi

if [[ -z "$SCAN_METHOD" ]]; then
  echo "FAIL: none of the scan methods (scan_file, scan_volume, jobscheduler) produced" >&2
  echo "FAIL: 3 correctly-tagged MediaStore rows. Last query output:" >&2
  echo "${VERIFY_OUTPUT:-<empty>}" >&2
  exit 1
fi

echo
echo "Scan method that worked: $SCAN_METHOD"
echo
echo "Final content query output:"
echo "$VERIFY_OUTPUT"
echo
echo "OK: 3 fixtures indexed correctly."
