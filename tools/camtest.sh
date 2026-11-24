#!/usr/bin/env bash
# Cold-start the app with a test-hook act list, wait, screenshot, print the new log lines.
# Usage: bash tools/camtest.sh <name> "<acts>" [wait seconds] [out dir]
set -uo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
export MSYS_NO_PATHCONV=1
NAME="$1"; ACTS="$2"; WAIT="${3:-5}"
OUT="${4:-$(cygpath -u "$TEMP")/prismcam-test}"
mkdir -p "$OUT"
LOG=/sdcard/Android/data/com.prism.cam/files/prism.log
before=$(adb shell "wc -l < $LOG" 2>/dev/null | tr -d '\r ' || echo 0)
adb shell am force-stop com.prism.cam
adb shell am start -W -n com.prism.cam/.MainActivity --es act "$ACTS" | tr -d '\r' | grep -E "TotalTime" | sed "s/^/[$NAME] /"
sleep "$WAIT"
adb exec-out screencap -p > "$OUT/$NAME.png"
adb shell "tail -n +$((before + 1)) $LOG" | tr -d '\r' | grep -vE "^.{24}(HEAT|ACT done)" | sed "s/^/[$NAME] /"
echo "[$NAME] screenshot → $OUT/$NAME.png"
