# ADB Device Monitor — macOS App

This builds **ADB Device Monitor.app**, a self-contained macOS application with an
embedded Java runtime. Double-clicking it starts the tool and opens the dashboard in
your browser — no Java installation required on the machine that runs it.

## Build it (one time, on a Mac)

You need a **JDK 17 or newer that includes `jpackage`** (Temurin/Adoptium or Oracle JDK
both do). Check with `jpackage --version`.

```bash
cd adb-device-monitor
./packaging/package-macos.sh
```

This produces, under `build/dist/`:

- `ADB Device Monitor.app` — the application (drag it to /Applications)
- `ADB Device Monitor-1.0.0.dmg` — a drag-to-Applications installer to share with others

## Run it

Double-click the app. It starts the built-in web server on `http://localhost:8080`
(or the next free port if 8080 is taken) and opens your default browser at the
dashboard. Pick a connected device, configure the run, and press Start.

To quit: `Cmd-Q`, or right-click the Dock icon → Quit. A run in progress still writes
its report on exit.

## Requirements at run time

- **adb must be installed.** The app shells out to `adb` to talk to devices. It looks
  for adb automatically in these places (no PATH setup needed):
  - `$ANDROID_HOME/platform-tools/adb` and `$ANDROID_SDK_ROOT/platform-tools/adb`
  - `~/Library/Android/sdk/platform-tools/adb` (Android Studio default)
  - `/opt/homebrew/bin/adb` (Homebrew, Apple silicon) and `/usr/local/bin/adb` (Intel)
  - every entry on `PATH`

  If you don't have adb: `brew install android-platform-tools`, or install Android Studio.

- A device connected over USB (with USB debugging) or TCP/IP (`adb connect <ip>`).

## First-launch security (Gatekeeper)

The app is **not code-signed / notarized**, so on first launch macOS will warn that it
"cannot be opened because the developer cannot be verified."

- Right-click the app → **Open** → **Open** (you only do this once), **or**
- Remove the quarantine flag:
  ```bash
  xattr -dr com.apple.quarantine "/Applications/ADB Device Monitor.app"
  ```

To distribute without this prompt you'd need an Apple Developer ID to sign and notarize
the app — out of scope for this internal tool, but the build is ready for it if needed.

## Notes

- The same jar still works as a CLI / web tool: `java -jar adb-device-monitor-1.0.0-all.jar --help`.
- `--app` is the launch mode the bundled app uses (start web server + open browser).
