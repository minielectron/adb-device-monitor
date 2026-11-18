# Native macOS app (Tauri shell + Kotlin sidecar)

The desktop app in `desktop/` is a thin native window around the existing engine. Nothing in the
monitoring code or the web UI is duplicated: the shell starts the fat jar as a child process and
shows its pages in a WKWebView.

```
ADB Performance Studio.app
├── MacOS/adb-performance-studio      Tauri shell (Rust, desktop/src-tauri/src/main.rs)
└── Resources/resources/
    ├── app.jar                       the Kotlin engine (same fat jar as `java -jar … --web`)
    └── runtime/                      jlink Java runtime — no Java install needed on the target Mac
```

## How it works

1. The shell opens a window on a bundled splash page (`desktop/dist/index.html`).
2. It picks a free loopback port and spawns
   `runtime/bin/java -Dapple.awt.UIElement=true -jar app.jar --web <port> --sidecar`.
3. When the port answers, the window is redirected to `http://127.0.0.1:<port>/`. If the engine
   fails to start, the splash shows the reason and points at the log.
4. **`--sidecar`** (Main.kt) makes the engine bind to `127.0.0.1` only and watch its stdin. The shell
   holds that pipe open; when the shell quits *or crashes* the engine sees EOF, stops the active
   run, writes its report and exits — so there is never an orphaned JVM, and quitting mid-run does
   not lose the report. The shell force-kills the engine only if it is still alive after 15 s.

| Browser behaviour | In the app |
|-------------------|------------|
| Downloads (PDF report, diagnostics zip) | saved to `~/Downloads` (auto-renamed if the name exists) and revealed in Finder |
| Device, Procrank and Layout Inspector HTML | opened inside Home's full-screen report viewer; **Open externally** deliberately uses the default browser |
| Player Debugger HTML report | opened inside Player Debugger's full-screen viewer; Back restores the existing debugger state |
| Release Guard registered HTML evidence | served from the loopback sidecar and kept inside the application when opened as an internal route |
| Explicit external links | opened in the default browser |
| Links to other sites (e.g. ui.perfetto.dev) | opened in the default browser |
| Reports-folder picker | unchanged — the engine's native dialog |

The UI uses loopback HTTP rather than a hosted web service or Tauri command API; the only Tauri
capability granted is `core:default` for the splash page. Internet access is not required after the
application has been built. Build-time Gradle, Cargo and npm dependency resolution can require a
network when their caches are empty.
Logs are unchanged: `~/ADB Device Monitor/adb-monitor.log` (look for `[sidecar]` and `[exit]` lines).

## Build and run

One-time setup on the build Mac: Xcode command line tools, Rust (rustup), Node 18+, JDK 17+.

```
desktop/build-macos.sh          # → desktop/src-tauri/target/release/bundle/{macos/*.app,dmg/*.dmg}
desktop/build-macos.sh dev      # run the shell in dev mode
desktop/build-macos.sh prepare  # only refresh resources/app.jar + resources/runtime
```

The script builds the fat jar, creates the jlink runtime once (delete
`desktop/src-tauri/resources/runtime` to rebuild it after a JDK change), then runs `tauri build`.
For quick engine iterations in dev you can skip the copy with
`ADBMON_JAR=/abs/path/to.jar ADBMON_JAVA=/abs/path/to/java`.

Without a device: `ADBMON_ADB="$PWD/scripts/fake-adb.sh" desktop/build-macos.sh dev`.

## Verify after the first build

Check the packaged app, not only `tauri dev`:

- [ ] `desktop/build-macos.sh` compiles; the app opens on the home page within ~2 s
- [ ] Start a run, Stop → "Download PDF report" lands in `~/Downloads` and Finder reveals it
- [ ] Export diagnostics downloads a zip
- [ ] Player Debugger → Open offline report stays inside the app; Back returns without losing the capture or form draft
- [ ] Device, Procrank and Layout Inspector reports open in Home's viewer; Layout Refresh captures a new screen
- [ ] The report viewer works with the network disconnected and Download HTML produces a self-contained file
- [ ] Device snapshot / Procrank snapshot still open successfully
- [ ] Run Comparison → "Export PDF" (a client-side blob download — the one path most likely to need a follow-up)
- [ ] Choose reports folder shows the native dialog, with no second Dock icon
- [ ] Quit (Cmd+Q) during a run → the run folder contains `report.pdf`; `pgrep -f app.jar` prints nothing
- [ ] `kill -9` the shell → the Java process exits by itself within a few seconds

Player Debugger capture data stays under `~/ADB Device Monitor/PlayerDebugger/` by default. The app
loads reports from its loopback-only sidecar, not from a public web service. The HTML report has no
scripts or remote assets and uses a restrictive content-security policy. See
[player-debugger.md](player-debugger.md) for privacy and support-bundle details.

The reports themselves have different security profiles: Player Debugger's static handoff report is
sanitized and script-free; the Layout Inspector is self-contained but includes a small inline script
for local search, hit-testing and in-app Refresh, plus the real screenshot and hierarchy metadata.

## Signing and notarization (later, with the Developer ID)

Until then the app is unsigned: recipients clear quarantine as described in the main README.
When the certificate is available:

1. Set `bundle.macOS.signingIdentity` in `tauri.conf.json` (or `APPLE_SIGNING_IDENTITY`), plus
   `APPLE_ID` / `APPLE_PASSWORD` / `APPLE_TEAM_ID` for notarization — `tauri build` then signs and notarizes.
2. The bundled Java runtime contains its own executables and dylibs (`runtime/bin/java`,
   `runtime/lib/jspawnhelper`, `runtime/lib/**/*.dylib`). Notarization requires each of them to be
   signed with the hardened runtime; `src-tauri/entitlements.plist` already carries the JIT
   entitlements the JVM needs. Expect to add a pre-sign step for these files in `build-macos.sh`.

## Windows (future)

The engine is already cross-platform. The shell needs: `open` replaced by the Windows equivalent in
`open_external` / reveal-in-Explorer, a Windows jlink runtime, and `bundle.targets` for msi/nsis.
