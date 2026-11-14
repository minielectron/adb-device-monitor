#!/usr/bin/env bash
#
# Build (or run in dev) the native macOS app: Tauri shell + Kotlin engine sidecar + bundled Java runtime.
#
#   desktop/build-macos.sh          # release build → .app and .dmg
#   desktop/build-macos.sh dev      # run the shell in dev mode (same engine + runtime, debug shell)
#   desktop/build-macos.sh prepare  # only refresh resources/app.jar and resources/runtime
#
# One-time prerequisites on the build Mac:
#   - Xcode command line tools:  xcode-select --install
#   - Rust:                      curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
#   - Node 18+ and a JDK 17+ (jlink) — the same JDK you already use for gradle/jpackage
#
set -euo pipefail
cd "$(dirname "$0")"            # → desktop/
MODE="${1:-build}"
RES="src-tauri/resources"
JAR="../build/libs/adb-device-monitor-1.0.0-all.jar"
# Same module set as packaging/package-macos.sh (AWT for charts + folder dialog, httpserver for the UI, PDFBox deps).
MODULES="java.base,java.desktop,java.logging,java.naming,java.management,java.sql,java.xml,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,java.net.http"

[[ "$(uname)" == "Darwin" ]] || { echo "This script must run on macOS." >&2; exit 1; }
for tool in cargo node npm jlink; do
  command -v "$tool" >/dev/null || { echo "Missing '$tool' — see the prerequisites at the top of this script." >&2; exit 1; }
done

echo "==> Building the engine (fat jar)"
(cd .. && ./gradlew fatJar --no-daemon -q)
mkdir -p "$RES"
cp "$JAR" "$RES/app.jar"

# The runtime only changes with the JDK, so rebuild it only when missing (delete the folder to refresh).
if [[ ! -x "$RES/runtime/bin/java" ]]; then
  echo "==> Creating the bundled Java runtime (jlink)"
  rm -rf "$RES/runtime"
  jlink --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --compress=2 --output "$RES/runtime"
fi
"$RES/runtime/bin/java" -jar "$RES/app.jar" --version

[[ "$MODE" == "prepare" ]] && exit 0

[[ -d node_modules ]] || npm install
if [[ "$MODE" == "dev" ]]; then
  exec npx tauri dev
fi

echo "==> Building the app (first build compiles Tauri and takes a few minutes)"
npx tauri build
OUT="src-tauri/target/release/bundle"
echo
echo "Done:"
ls -d "$OUT"/macos/*.app "$OUT"/dmg/*.dmg 2>/dev/null || true
echo
echo "The app is not signed yet. On another Mac, clear the quarantine flag before first launch:"
echo "  xattr -dr com.apple.quarantine \"/Applications/ADB Performance Studio.app\""
