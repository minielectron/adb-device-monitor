# Build, Run & Package

## Build

Single self-contained fat jar (Kotlin 1.9, JVM toolchain 11):

```bash
./gradlew fatJar
# → build/libs/adb-device-monitor-1.0.0-all.jar   (bundles JFreeChart, PDFBox)
```

The `fatJar` task stamps the manifest with `Build-Time`; `--version` and the dashboard's build badge
read it back so you can always confirm which build is running.

```bash
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --version
# ADB Device Monitor v1.0.0 (build 2026-07-14 22:49:42)
```

There is also a `--selftest` flag that renders the Device/Procrank snapshot HTML from fixed test data
(no device needed) — handy for verifying the report generators after a change.

## Run — web mode (recommended)

```bash
java -jar adb-device-monitor-1.0.0-all.jar --web 8080
```

Opens **ADB Performance Studio** at `http://localhost:8080`: pick a connected device, choose which
metrics to sample, press Start, and watch live charts. Reports land in
`run_<timestamp>_<manufacturer>_<model>/`. See [web-dashboard.md](web-dashboard.md).

## Run — CLI (headless)

```bash
java -jar adb-device-monitor-1.0.0-all.jar --duration 300 --interval 2 [options]
```

Key options (full list via `--help`):

| Flag | Purpose |
|------|---------|
| `--duration <s>` / `--interval <s>` | run length and sampling period (required in CLI mode) |
| `--package <pkg>` | track that app's PSS (and app-CPU, media procs) via dumpsys |
| `--jank` | UI jank % + frame-phase breakdown (needs `--package`) |
| `--profile` | ART sampling profiler → top main-thread methods table (needs debuggable app) |
| `--perfetto` | record a Perfetto system trace for the run |
| `--playback` | STB: track player state + tunnel mode from logcat |
| `--zap-time <ms>` / `--zap-count <n>` | channel-zapping stress test (sends CHANNEL_UP) |
| `--label <name>` | tag the run/folder/report (e.g. `tunnel-on`) for comparisons |
| `--serve <port>` | also expose the live dashboard for this CLI run |
| `--device <serial>` / `--ip <host[:port]>` | target a specific device / connect over TCP-IP |
| `--output-dir` / `--output` / `--csv` | override output locations |
| `--adb <path>` | explicit adb binary path |
| `--scan-logcat <path\|run\|last>` | analyze an existing logcat with no device attached |

Each run creates `run_<timestamp>_<manufacturer>_<model>/` containing `report.pdf`, `samples.csv`,
`logcat.log`, `anomalies.log`, `device-info.log`.

## Logs

All stdout/stderr is tee'd to `~/ADB Device Monitor/adb-monitor.log` (so a double-clicked GUI app,
which has no terminal, still produces readable logs). The log starts with the build stamp and the
log-file path. Useful lines: `[top-mem] strategy=…`, `[collector] cycle …`, `[loop] sample #…`,
`[stop] …`.

## Package as a macOS app

`packaging/package-macos.sh` runs `./gradlew fatJar` and wraps the jar with `jpackage` into a
`.app`/`.dmg` that opens the web dashboard on launch (no separate Java install needed).

> **Important:** the `.app` bundles a *frozen copy* of the jar taken at package time. After changing
> code you must re-run the packaging script (or run the jar directly) — relaunching the old `.app`
> runs the old jar. The dashboard build badge (bottom-right) shows which build is actually live.

## adb discovery

A Finder-launched app inherits a minimal `PATH`, so `Adb.locate()` checks: the `--adb` override,
`$ANDROID_HOME`/`$ANDROID_SDK_ROOT`, the standard macOS/Linux SDK locations, Homebrew prefixes, and
finally every `PATH` entry — so `adb` is found even when launched from the GUI.

## Notes for contributors

- Build in a scratch copy for speed, then copy the jar back:
  `rsync -a --exclude=.git --exclude=build --exclude=.gradle <proj> /tmp/build && cd /tmp/build && ./gradlew fatJar`.
- Git may be restricted in some sandboxes; commit from your real checkout (`git-setup.sh` helps).
