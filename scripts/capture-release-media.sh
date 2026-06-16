#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEDIA_DIR="$ROOT_DIR/docs/media"
mkdir -p "$MEDIA_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools first." >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No Android device visible to adb." >&2
  exit 1
fi

PACKAGE="dev.libreqc.probe"
ACTIVITY="$PACKAGE/.MainActivity"

echo "Launching LibreQC..."
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 4

echo "Capture overview-device.png"
adb exec-out screencap -p > "$MEDIA_DIR/overview-device.png"

echo "Saved media in $MEDIA_DIR"
