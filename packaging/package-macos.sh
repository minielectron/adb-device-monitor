#!/usr/bin/env bash
#
# Build a self-contained macOS application for ADB Device Monitor.
#
# Produces:
#   build/dist/ADB Device Monitor.app   — double-clickable app with an embedded Java runtime
#   build/dist/ADB Device Monitor-<ver>.dmg — drag-to-Applications installer
#
# The app bundles its own JRE, so the target Mac does NOT need Java installed.
# (adb itself is still required — see README-macos-app.md.)
#
# Requirements to BUILD (on a Mac):
#   - JDK 17+ that includes `jpackage` (e.g. Temurin/Adoptium or Oracle JDK)
#   - Xcode command line tools (for the .dmg step)
#
# Usage:
#   cd adb-device-monitor
#   ./packaging/package-macos.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."   # → adb-device-monitor/ (repo root)

APP_NAME="ADB Device Monitor"
APP_VERSION="1.0.0"
MAIN_JAR="adb-device-monitor-${APP_VERSION}-all.jar"
MAIN_CLASS="devicemonitor.MainKt"
DEST="build/dist"
STAGE="build/jpackage-input"
ICON="packaging/AppIcon.icns"

if [[ "$(uname)" != "Darwin" ]]; then
  echo "This script must run on macOS (jpackage builds for the host OS)." >&2
  exit 1
fi
command -v jpackage >/dev/null || {
  echo "jpackage not found. Install a JDK 17+ that bundles jpackage (e.g. Temurin) and ensure it's on PATH." >&2
  exit 1
}

echo "==> Building fat jar"
./gradlew fatJar --no-daemon

echo "==> Building app icon (.icns)"
if [[ ! -f "$ICON" && -f "packaging/appicon.png" ]]; then
  ./packaging/make-icns.sh packaging/appicon.png "$ICON" || echo "   (icon step skipped — using default)"
fi
ICON_ARG=(); [[ -f "$ICON" ]] && ICON_ARG=(--icon "$ICON")

echo "==> Staging jar"
rm -rf "$STAGE" "$DEST"
mkdir -p "$STAGE" "$DEST"
cp "build/libs/${MAIN_JAR}" "$STAGE/"

# Modules needed by the app: AWT/desktop (JFreeChart, Desktop.browse), httpserver (web UI),
# xml/naming/management/sql (PDFBox), crypto (TLS), unsupported (sun.misc.Unsafe).
MODULES="java.base,java.desktop,java.logging,java.naming,java.management,java.sql,java.xml,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,java.net.http"

echo "==> jpackage: building .app (app-image)"
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$STAGE" \
  --main-jar "$MAIN_JAR" \
  --main-class "$MAIN_CLASS" \
  --arguments "--app" \
  --add-modules "$MODULES" \
  --java-options "-Xss4m" \
  --mac-package-name "ADBDeviceMon" \
  "${ICON_ARG[@]}" \
  --dest "$DEST"

echo "==> jpackage: wrapping .app into a .dmg"
jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --app-image "$DEST/$APP_NAME.app" \
  "${ICON_ARG[@]}" \
  --dest "$DEST"

echo
echo "Done."
echo "  App: $DEST/$APP_NAME.app"
echo "  DMG: $DEST/$APP_NAME-$APP_VERSION.dmg"
echo
echo "First launch: right-click the app → Open (unsigned app — Gatekeeper asks once)."
echo "If macOS still blocks it:  xattr -dr com.apple.quarantine \"$DEST/$APP_NAME.app\""
