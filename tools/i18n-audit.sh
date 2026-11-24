#!/usr/bin/env bash
# Lists Thai strings used with T("…") that have no English entry in En.kt. Empty output = all good.
cd "$(dirname "$0")/.."
SRC=app/src/main/java
EN=$SRC/com/prism/cam/i18n/En.kt
grep -rhoE 'T\("[^"]+"' "$SRC" --include=*.kt | sed -E 's/^T\("//; s/"$//' | sort -u | while IFS= read -r s; do
  grep -qF "\"$s\" to" "$EN" || echo "missing EN: $s"
done
