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
| `--sidecar` (with `--web`) | backend mode for the native app: loopback only, exits when stdin closes — see [desktop-app.md](desktop-app.md) |
| `--zap-time <ms>` / `--zap-count <n>` | channel-zapping stress test (sends CHANNEL_UP) |
| `--label <name>` | tag the run/folder/report (e.g. `tunnel-on`) for comparisons |
| `--serve <port>` | also expose the live dashboard for this CLI run |
| `--device <serial>` / `--ip <host[:port]>` | target a specific device / connect over TCP-IP |
| `--output-dir` / `--output` / `--csv` | override output locations |
| `--adb <path>` | explicit adb binary path |
| `--scan-logcat <path\|run\|last>` | analyze an existing logcat with no device attached |

Each run creates `run_<timestamp>_<manufacturer>_<model>/` containing `report.pdf`, `samples.csv`,
`logcat.log`, `anomalies.log`, `device-info.log`.

## Run — Offline Release Guard (headless CI)

Use a scenario JSON exported from the Release Guard workspace, or a saved scenario ID from the same `--release-root`:

```bash
java -jar build/libs/adb-device-monitor-1.0.0-all.jar \
  --release-guard ./scenarios/cold-launch.json \
  --device emulator-5554 \
  --release-root ./release-guard-results \
  --build-label commit-abc123 \
  --release-json
```

`--release-json` writes only the absolute evaluation JSON path to stdout; progress stays on stderr. Artifacts under the local root include `report.html`, `report.pdf`, `evaluation.json`, `result.junit.xml`, `summary.csv`, attempt reports, samples, and logs. No network service or cloud account is used.

| Exit | Meaning |
|------|---------|
| `0` | all budgets pass |
| `2` | warning budgets require review |
| `3` | a release-blocking budget fails |
| `4` | invalid scenario, missing required capability, or bad CLI configuration |
| `5` | ADB, capture, storage, export, or cancellation infrastructure failure |

Optional flags: `--release-project <id>` for a saved scenario, `--release-project-name <name>` for a file, `--apk <candidate.apk>` to install before the journey, and `--allow-compatible-baseline` for explicitly normalized cross-device budgets.

## Logs and diagnostics

Line format: `2026-11-13 22:18:58.132 INFO  [monitor-session] message` (level, thread name).

| File | Content |
|------|---------|
| `~/ADB Device Monitor/adb-monitor.log` | everything, across runs; rotated to `.log.1` at 5 MB |
| `<run folder>/run.log` | the same lines, for that run only — travels with the report |

DEBUG lines go to the files only (the terminal shows INFO and above). What to grep for:

| Tag | Meaning |
|-----|---------|
| `[adb] 42ms exit=0 out=1832B: -s … shell …` | every adb command with duration; WARN when > 3 s, `TIMEOUT` when killed |
| `[http] SLOW 2300ms GET /api/…` | a handler took > 1.5 s; `[http] … failed` + stack trace on exceptions |
| `[watchdog] run appears HUNG: phase=…` | the sampling loop / finalize stalled; a `THREAD DUMP` follows showing where each thread is blocked |
| `[watchdog] HTTP request stuck …` | same, for a web request stuck > 10 s |
| `[client] error @ /dashboard: …` | JavaScript errors from the browser (onerror, unhandled rejections, console.error) |
| `[run]`, `[loop]`, `[collector]`, `[top-mem]`, `[stop]` | run lifecycle |

**Export Diagnostics** (Home/Dashboard navigation or `GET /api/diagnostics`) downloads
`adb-monitor-diagnostics_<timestamp>.zip` containing `info.txt`, `threads.txt`, up to the latest
3 MiB of `adb-monitor.log`, and up to the latest 3 MiB of `run.log` when available. This is host/JVM
support evidence; it does not include a screenshot, hierarchy, full device logcat, Player Debugger
capture or Release Guard evidence. See [diagnostics.md](diagnostics.md) for the exact boundary.

## Tests

```
./gradlew test            # unit tests: parsers, adb timeout/abort gate, logger (needs network once for kotlin-test)
scripts/smoke-test.sh     # end-to-end against scripts/fake-adb.sh: start → stop → report → compare → diagnostics
```

`scripts/fake-adb.sh` is a stand-in `adb` (`--adb scripts/fake-adb.sh`) for working without a device;
`FAKE_ADB_DELAY`, `FAKE_ADB_HANG`, `FAKE_ADB_HANG_FILE` simulate a slow or frozen device.

## Package as a macOS app

`desktop/build-macos.sh` builds the fat jar, prepares a bundled jlink Java runtime and builds the
Tauri `.app` and `.dmg` (no Java install needed on the target Mac):

```bash
desktop/build-macos.sh          # release .app + .dmg
desktop/build-macos.sh dev      # launch a development build
desktop/build-macos.sh prepare  # refresh app.jar and the runtime only
```

> **Important:** the `.app` bundles a *frozen copy* of the jar taken at package time. After changing
> code you must re-run the build script (or run the jar directly) — relaunching the old `.app`
> runs the old jar. The dashboard build badge (bottom-right) shows which build is actually live.

## adb discovery

A Finder-launched app inherits a minimal `PATH`, so `Adb.locate()` checks: the `--adb` override,
`$ANDROID_HOME`/`$ANDROID_SDK_ROOT`, the standard macOS/Linux SDK locations, Homebrew prefixes, and
finally every `PATH` entry — so `adb` is found even when launched from the GUI.

## Notes for contributors

- Build in a scratch copy for speed, then copy the jar back:
  `rsync -a --exclude=.git --exclude=build --exclude=.gradle <proj> /tmp/build && cd /tmp/build && ./gradlew fatJar`.
- Git may be restricted in some sandboxes; commit from your real checkout (`git-setup.sh` helps).
