#!/usr/bin/env bash
# Screenshots of the app on the connected phone/tablet in each look. Cold-starts the app for every shot
# (force-stop + am start -W, so the start time is measured too) — warm intents are not reliable on Huawei.
# Usage: bash tools/shots.sh <out dir> ["acts1" "acts2" ...]   default: the 4 looks + English. Ends on light,classic,th.
set -uo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
export MSYS_NO_PATHCONV=1
OUT="${1:?out dir}"; shift || true
mkdir -p "$OUT"
LIST=("$@"); [ ${#LIST[@]} -eq 0 ] && LIST=("light,classic,th" "dark,classic,th" "light,glass,th" "dark,glass,th" "light,glass,en")
adb shell cmd statusbar collapse >/dev/null 2>&1
for acts in "${LIST[@]}"; do
  adb shell am force-stop com.prism.cam
  t=$(adb shell am start -W -n com.prism.cam/.MainActivity --es act "$acts" | tr -d '\r' | sed -n 's/^TotalTime: //p')
  sleep 2.5
  f="$OUT/${acts//,/-}.png"
  adb exec-out screencap -p > "$f"
  echo "$acts  start=${t}ms  → $f"
done
adb shell am force-stop com.prism.cam
adb shell am start -n com.prism.cam/.MainActivity --es act light,classic,th >/dev/null
