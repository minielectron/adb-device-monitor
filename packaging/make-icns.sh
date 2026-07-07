#!/usr/bin/env bash
# Convert a square PNG (ideally 1024x1024) into a macOS .icns using the built-in
# `sips` and `iconutil` tools. macOS only — on other OSes this is skipped.
set -euo pipefail

SRC="${1:?usage: make-icns.sh <source.png> <out.icns>}"
OUT="${2:?usage: make-icns.sh <source.png> <out.icns>}"

if ! command -v iconutil >/dev/null || ! command -v sips >/dev/null; then
  echo "make-icns: iconutil/sips not found (macOS only) — skipping icon."
  exit 1
fi

WORK="$(mktemp -d)"
SET="$WORK/AppIcon.iconset"
mkdir -p "$SET"

for s in 16 32 128 256 512; do
  sips -z "$s" "$s"          "$SRC" --out "$SET/icon_${s}x${s}.png"    >/dev/null
  d=$((s * 2))
  sips -z "$d" "$d"          "$SRC" --out "$SET/icon_${s}x${s}@2x.png" >/dev/null
done

iconutil -c icns "$SET" -o "$OUT"
echo "make-icns: wrote $OUT"
