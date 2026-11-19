# adb-device-monitor

ADB Performance Studio is a Kotlin/JVM tool for Dev and QA that monitors any authorized,
`adb`-connected Android phone, tablet, TV, STB, Fire TV device or emulator over USB or TCP/IP. It
ships as a native macOS application, local web application and scriptable CLI. Runtime collection,
analysis and reports remain local; no cloud account or internet API is required.

Detailed documentation starts at [`docs/README.md`](docs/README.md). The main technical references
are [metrics](docs/metrics.md), [Layout Inspector](docs/layout-inspector.md),
[Player Debugger](docs/player-debugger.md), [Release Guard](docs/release-guard.md), and
[Diagnostics](docs/diagnostics.md).

## Current feature state

| Feature | State and data source | Local output / important limit |
|---|---|---|
| Home and run configuration | Implemented. Lists devices from `adb devices -l`, reads device properties, and creates a `SessionConfig`. The initial Home selection enables CPU utilization, per-core CPU, top-process CPU, memory used, PSI and top-process memory; the saved form draft overrides those defaults on return. | Starts one monitoring session at a time. Package-specific metrics need a valid running package; unavailable Android sources remain blank. |
| Live Dashboard | Implemented. Polls the local `/api/session` endpoint and renders the `Sample` stream collected from `/proc`, sysfs, dumpsys and optional logcat. | Live charts, health badges, zap/scenario/anomaly events and Stop/report controls. Browser rendering does not create a second measurement stream. See [web-dashboard.md](docs/web-dashboard.md). |
| PDF, CSV and Run Comparison | Implemented. PDF and CSV serialize the completed `Sample` stream; comparison reads two stored `samples.csv` files. | `report.pdf`, `samples.csv` and local comparison view. Comparison only charts columns present in both runs. See [reports-and-comparison.md](docs/reports-and-comparison.md). |
| Device Snapshot | Implemented one-shot capture. Uses `getprop`, `uname`, CPU sysfs, `/proc/meminfo`, launcher resolution and `dumpsys meminfo`. | Self-contained `device-snapshot_<timestamp>.html`; process categorization is heuristic. |
| Procrank Snapshot | Implemented one-shot capture. Tries `procrank`, `su 0 procrank`, `su -c procrank`, then falls back to `ps`. | HTML plus a raw command debug sidecar. The `ps` fallback has RSS/VSS but not real PSS/USS. See [device-and-procrank-snapshot.md](docs/device-and-procrank-snapshot.md). |
| Lite Layout Inspector | Implemented one-shot capture. Joins `uiautomator dump` element metadata with a `screencap` PNG and maps bounds to the screenshot. | Self-contained `layout-inspector_<timestamp>.html`, searchable elements, click-to-select boundary and in-app Refresh. Secure/custom-rendered surfaces may be absent. See [layout-inspector.md](docs/layout-inspector.md). |
| Player Debugger | Implemented. Combines captured logcat, Android media/codec/audio/SurfaceFlinger dumps, selected performance metrics, optional app-reported OPTD/1 events and deterministic inference. | Handoff Map, Flight Recorder, Incident Lens, static HTML and sanitized support bundle. Passive evidence cannot always identify a vendor player's logical instance. See [player-debugger.md](docs/player-debugger.md). |
| Performance Release Guard | Implemented in Studio and headless CLI. Executes versioned Android scenarios, probes capabilities, captures measured repetitions and evaluates optional absolute/baseline budgets. | Local HTML, PDF, JSON, JUnit XML, CSV and combined logcat evidence with stable CI exit codes. See [release-guard.md](docs/release-guard.md). |
| Diagnostics ZIP | Implemented host/application support export. Reads JVM state, in-flight requests, session summary, a thread dump and application/run log tails. | `adb-monitor-diagnostics_<timestamp>.zip`. It is not a device screenshot, full logcat or Player Debugger export. See [diagnostics.md](docs/diagnostics.md). |
| CLI and native macOS app | Implemented. CLI calls the same collectors directly; the Tauri app starts the Kotlin engine on a loopback-only port and displays it in the system WebView. | CLI run folders or packaged `.app`/`.dmg`. Loopback HTTP is local IPC, not an internet dependency. See [build/run](docs/build-run-package.md) and [desktop app](docs/desktop-app.md). |

## How the displayed data is obtained

The application distinguishes four evidence types:

- **Measured:** Android counters and snapshots such as `/proc/stat`, `/proc/meminfo`, PSI, sysfs,
  `dumpsys gfxinfo`, `dumpsys meminfo`, SurfaceFlinger latency and screenshots.
- **Log parsed:** bounded patterns and OPTD lines read from the locally captured
  `logcat -v threadtime` file.
- **App reported:** valid OPTD/1 telemetry emitted by the application under test.
- **Inferred:** counter deltas, rates, health thresholds, player relationships and deterministic
  incident/budget rules derived from stored evidence.

Unsupported, blocked or empty Android sources are shown as unavailable, partial or fallback data;
they are not silently treated as healthy. The exact command and transformation for every monitoring
signal is in [docs/metrics.md](docs/metrics.md).

## Web UI (ADB Performance Studio)

```bash
./gradlew fatJar
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --web 8080
# open http://localhost:8080
```

The Home page shows connected devices, authorization state and live availability. On userdebug/eng
builds, **Enable root** runs `adb root`; production builds hide it because production adbd cannot
restart as root. Pick an authorized device, configure the run and press **Start**:

- duration and sampling interval
- **Channel zapping** toggle — CHANNEL_UP every N ms, optional stop-after-n-zaps
- **Logcat capture** toggle — enables logcat.log, anomaly flagging with timestamps, and app build info extracted from analytics events
- **App package** — enables the PSS memory breakdown
- **UI jank tracking** toggle — janky-frame % per tick from `dumpsys gfxinfo`

The Home form and metric selections are saved locally so navigating to another Studio screen and
back does not reset unsaved changes. The live dashboard shows the selected charts, event feed,
health badges, Stop control and completed PDF. Only one performance session, Player Debugger capture
or Release Guard check owns the collectors at a time.

### Quick Diagnostics versus Diagnostics

The three Home **Quick diagnostics** cards inspect the selected Android device without starting a
monitoring run: Device Snapshot, Procrank Snapshot and Layout Inspector. The top navigation
**Diagnostics** action instead downloads a small host/JVM support ZIP for troubleshooting Studio.
It does not perform a fresh device capture. See [docs/diagnostics.md](docs/diagnostics.md).

### Offline Player Debugger

Player Debugger reconstructs concurrent player instances, playback state, buffer/render/A/V events,
delivery changes and arbitrary handoffs from Android evidence. It works passively with logcat,
dumpsys and device metrics on standard Android devices; apps can optionally emit the open **OPTD/1**
line protocol for precise session and player identity. It does not import or depend on CastLabs,
MediaKind, multicast, DVB, ExoPlayer or any other player SDK.

The first-use set enables CPU utilization, per-core CPU, top-process CPU, memory used, PSI and
top-process memory; package PSS and app process CPU are also enabled by the current API defaults.
User changes are restored after navigation and restart. Captures, reports and the index stay under
`~/ADB Device Monitor/PlayerDebugger/`. The
Handoff Map, synchronized Flight Recorder and Incident Lens remain usable offline, and static HTML
evidence opens inside the desktop application. Sanitized support bundles hash identifiers, redact
sensitive text, omit raw JSON, screenshots, media, traces and DRM material, and can be previewed
before export. Integration and QA details are in
[docs/player-debugger.md](docs/player-debugger.md); the optional zero-dependency Android emitter is in
[`integrations/android-optd/`](integrations/android-optd/).

The Home page also provides Device Snapshot, Procrank Snapshot and Lite Layout Inspector as
independent one-shot reports. Their HTML, charts, screenshot and data are local and self-contained.

### Offline Performance Release Guard

Release Guard turns repeatable Android journeys into an offline release decision for Dev and QA. It stores versioned scenarios, probes device capabilities instead of relying on manufacturer rules, runs warm-ups and measured repetitions, compares optional explicit budgets with a promoted baseline, and produces local HTML, PDF, JSON, JUnit XML, CSV, and combined logcat evidence. Without budgets it reports **Evidence only / Not evaluated**. Open **Release Guard** from Studio; drafts survive navigation and restarts.

For a headless CI run:

```bash
java -jar build/libs/adb-device-monitor-1.0.0-all.jar \
  --release-guard ./scenarios/cold-launch.json \
  --device emulator-5554 \
  --release-root ./release-guard-results \
  --release-json
```

The mode is fully offline. Exit codes are stable: `0` pass, `2` warning, `3` budget failure, `4` invalid scenario/capability configuration, `5` infrastructure failure, and `6` evidence-only/not evaluated. Use `--release-project <id>` for a saved scenario ID, `--apk <file>` to install a candidate build first, and `--allow-compatible-baseline` only when cross-device normalized metrics are intentional.

## Metrics collected

| Metric | Source | Notes |
|---|---|---|
| CPU utilization (%) | `/proc/stat` deltas | aggregate across all cores |
| CPU pressure (PSI avg10) | `/proc/pressure/cpu` | Android 10+ / kernel 4.20+ |
| Memory pressure (PSI some/full avg10) | `/proc/pressure/memory` | Android 10+ / kernel 4.20+ |
| IO pressure (PSI avg10) | `/proc/pressure/io` | Android 10+ / kernel 4.20+ |
| Memory used / available / cached / total (MB) | `/proc/meminfo` | used = MemTotal − MemAvailable |
| Swap used (MB) | `/proc/meminfo` | skipped if device has no swap/zram |
| Load average (1 min) | `/proc/loadavg` | |
| Per-core CPU utilization (%) | `/proc/stat` `cpuN` line deltas | one series per core |
| Network throughput RX/TX (KB/s) | `/proc/net/dev` deltas | sum of all non-loopback interfaces |
| CPU temperature (°C) | `/sys/class/thermal/thermal_zone*` | hottest CPU/SoC zone |
| CPU frequency (MHz) | `/sys/.../cpufreq/scaling_cur_freq` | mean across cores; shows thermal throttling |
| Major page faults (faults/s) | `/proc/vmstat` `pgmajfault` delta | sustained high rate = memory thrashing |
| Memory zones — free pages vs min/low/high watermarks | `/proc/zoneinfo` | free below `low` = kswapd; below `min` = direct reclaim |
| Direct reclaim (events/s) | logcat | allocations blocking on reclaim — hard memory-pressure signal |
| Top process CPU (top consumers) | `cat /proc/[0-9]*/stat` deltas | interactive chart — pick any PID to plot |
| Top process memory (RSS) | one `ps -A -o PID,RSS,NAME` per tick | cheap, no root; interactive chart (procrank not used on the hot path) |
| App PSS: Total / Java Heap / Native Heap / Code / Stack / Graphics / Private Other / System (MB) | `dumpsys meminfo <pkg>` App Summary | only with `--package` |
| UI jank (% janky frames per tick) | `dumpsys gfxinfo <pkg> framestats` counter deltas | only with jank tracking enabled (needs package) |
| Frame phases: input / animation / measure-layout / draw (avg ms per frame) | `gfxinfo framestats` PROFILEDATA | with jank tracking; shows which Choreographer phase eats the frame budget |
| App main-thread CPU (%) | `/proc/<pid>/task/<pid>/stat` deltas (main thread tid == pid) | automatic when a package is set |
| Main-thread hot methods, app-package methods, jank culprits | ART sampling profiler (`am profile start --sampling`) | with the method profiler enabled; three tables in the PDF + `main-thread-methods.txt` + raw `method-profile.trace`. App must be debuggable |
| Full system trace | `perfetto` (sched + gfx/view/input/binder) | with Perfetto enabled; `trace.perfetto-trace` for ui.perfetto.dev |

Core `/proc` and sysfs values are batched into one `adb shell` snapshot per tick. Optional package,
gfxinfo, SurfaceFlinger and process collectors use additional bounded commands only when their
signals are enabled. Metrics whose source is unreadable are reported as unavailable and skipped in
graphs. If package PSS sampling fails at startup, the console prints the bounded raw
`dumpsys meminfo` reply so a wrong package, stopped app or incompatible format can be diagnosed.

## Requirements

- JDK 11+ for the Kotlin application; the checked-in Gradle wrapper supplies Gradle
- `adb` on PATH (or pass `--adb /path/to/adb`)
- Device connected via USB with USB debugging enabled — or over Wi-Fi (`adb connect <ip>` / `--ip` flag)
- Native macOS packaging additionally needs Xcode command-line tools, Rust, Node 18+ and JDK 17+

## Build

```bash
cd adb-device-monitor
./gradlew fatJar
# → build/libs/adb-device-monitor-1.0.0-all.jar
```

The Gradle wrapper is checked in, so a separate Gradle installation is not required. The first build
may need network access to populate dependency caches; application runtime does not.

## Sharing the macOS app (.app / .dmg)

The `.app`/`.dmg` produced by `desktop/build-macos.sh` is **not code-signed or notarized** unless a
Developer ID is configured, so macOS Gatekeeper can quarantine a downloaded build and refuse to
open it — you'll see
*"…is damaged and can't be opened"* or *"cannot be opened because Apple cannot check it for malicious
software."* This is expected for an unsigned internal build; there is no setting on your side that
other Macs will honor — trust requires an Apple signature.

**Quick fix for a trusted/internal recipient** — strip the download quarantine flag after copying the
app to `/Applications` (or on the `.dmg` before opening):

```bash
xattr -dr com.apple.quarantine "/Applications/ADB Performance Studio.app"
# or for the generated dmg before opening (adjust version/architecture as needed):
xattr -dr com.apple.quarantine ~/Downloads/ADB\ Performance\ Studio_1.0.0_aarch64.dmg
```

Without Terminal: double-click, let it get blocked, then **System Settings → Privacy & Security →
"Open Anyway"** (and Open once more). Sending the `.dmg` inside a `.zip` also avoids some "damaged"
errors.

**For wider distribution** sign + notarize with an Apple *Developer ID* (Apple Developer Program,
$99/yr): `codesign --options runtime`, `xcrun notarytool submit … --wait`, `xcrun stapler staple`.
After that, any Mac opens it with no warnings. Tauri signing/notarization uses the Apple signing
identity and notarization credentials described in [docs/desktop-app.md](docs/desktop-app.md).

> **Architecture note:** the desktop bundle is architecture-specific. An app built on Apple Silicon
> won't launch on Intel Macs (and vice-versa) — that also looks like "not accessible." Build on both
> architectures for a mixed fleet.

## Run

Connect your device(s) via USB (or `adb connect`) first, then:

```bash
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --duration 300 --interval 2
```

Everything is written into the run folder by default: `report.pdf`, `samples.csv`, `logcat.log`, `anomalies.log`, `device-info.log`. Use `--output` / `--csv` only to place the PDF/CSV somewhere else.

Device selection:

- **One device connected** — used automatically.
- **Multiple devices** — the tool lists them and asks you to pick:
  ```
  Multiple devices connected:
    [1] R58M12ABCDE       product:beyond1  model:SM_G973F
    [2] G070VM1234567890  product:mantis   model:AFTMM
  Select device [1-2]:
  ```
- **No device** — exits with: "No device connected. Please connect a device using adb (USB, or 'adb connect <ip>') to use this application." Unauthorized/offline devices are listed but not selectable.

| Option | Required | Description |
|---|---|---|
| `--duration <sec>` | yes | total monitoring time |
| `--interval <sec>` | yes | sampling interval (decimals OK, e.g. 0.5) |
| `--package <pkg>` | no | also sample the app's PSS breakdown from `dumpsys meminfo` each tick (use interval ≥ 2 s — dumpsys is heavier than the /proc reads) |
| `--jank` | no | track UI jank % + frame phase breakdown via `gfxinfo framestats` (requires `--package`) |
| `--profile` | no | ART sampling profiler → top main-thread methods table (requires `--package`, debuggable app) |
| `--perfetto` | no | record a Perfetto system trace for the whole run |
| `--no-logcat` | no | disable logcat capture, anomaly flagging and app-info extraction |
| `--web <port>` | — | launch the web UI instead of a CLI run (ignores other options) |
| `--zap-time <ms>` | no | send `CHANNEL_UP` to the device every N milliseconds while monitoring (channel-zapping stress test). Omit it to just capture metrics as-is |
| `--zap-count <n>` | no | stop monitoring and generate the report after n zaps (requires `--zap-time`); `--duration` remains the upper bound if the count isn't reached |
| `--device <serial>` | no | use a specific serial, skips the picker (handy for scripts) |
| `--ip <host[:port]>` | no | connect over TCP/IP first; port defaults to 5555 |
| `--output <file.pdf>` | no | override report path (default `<run folder>/report.pdf`) |
| `--csv <file.csv>` | no | override CSV path (default `<run folder>/samples.csv`, always written) |
| `--adb <path>` | no | default `adb` on PATH |

Ctrl+C mid-run still generates the report from the samples collected so far.

### Run folders, logcat & anomaly flagging

Every run creates its own folder named `run_<timestamp>_<manufacturer>_<model>/` containing:

| File | Contents |
|---|---|
| `report.pdf` | the charts + summary table |
| `samples.csv` | raw samples |
| `logcat.log` | full logcat captured during the run (`logcat -v threadtime`, buffer cleared at start) |
| `anomalies.log` | flagged events, one per line with device timestamp and `t=` seconds since run start |
| `device-info.log` | model, Android version, build fingerprint, run arguments |

Flagged anomaly types: ANR, fatal exception, native crash (`Fatal signal`), low-memory kill (lmkd/lowmemorykiller), Choreographer `Skipped N frames` with N ≥ 30, and `Davey!` janks. Each anomaly is also drawn as an orange dashed line (labelled with its type) on every chart, so you can see what the system metrics were doing when it happened — red dashed lines remain the zap markers.

If the app under test prints analytics `Event parameters: {...}` blocks to logcat, the tool extracts `app_version`, `device_model`, `os_name`, `firmware_version`, `account_id`, `screen_resolution` and `screen_size` from them (latest occurrence wins) and renders them as a table at the top of the report's summary page.

### Live web dashboard

```bash
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --duration 600 --interval 2 --serve 8080
# then open http://localhost:8080
```

`--serve <port>` starts an embedded web server (no extra dependencies) with live charts that update while monitoring runs: CPU, memory, PSI pressure, network, temperature/frequency, load/page-faults, and App PSS when `--package` is set — plus a live event table of zaps and flagged anomalies. Chart.js and report dependencies are bundled, so the page stays fully offline. When the run finishes the dashboard shows "finished" and stays available until you Ctrl+C; the PDF report and run folder are generated as usual.

### Zap testing

```bash
# change channel (CHANNEL_UP) every 5 seconds for 5 minutes
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --duration 300 --interval 2 --zap-time 5000

# exactly 50 zaps, then stop and generate the report (duration is just the safety cap)
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --duration 600 --interval 2 --zap-time 5000 --zap-count 50
```

Each zap is logged to the console, counted on the summary page, and drawn as a dashed red vertical line on every chart so you can correlate CPU/memory spikes with channel changes.

Or during development: `gradle run --args="--duration 60 --interval 1"`

## Report contents

- Page 1: device info (model, Android version, cores, RAM) + min/avg/max summary table for every metric
- Following pages: time-series line charts — CPU utilization, CPU pressure, memory usage, memory pressure, IO pressure, load average & swap

`sample_report.pdf` in this folder is an example report generated against a simulated device.

## PSI availability

At startup the tool probes PSI and prints the result. If the combined snapshot command returns no PSI data but a plain `cat /proc/pressure/cpu` works (seen on some operator builds), it automatically switches to per-file reads. If PSI is truly unreadable, the console shows the device's actual error (e.g. `No such file or directory` → kernel built without `CONFIG_PSI`, common on STBs shipping Android 12 with kernel < 4.20) instead of silently reporting n/a.

## Reading the trace files

**`main-thread-methods.txt` / PDF "Main-Thread Analysis" page** — three sections:

1. *Hot methods by self time* — self = time in the method itself, excluding callees. Framework wrappers (`Looper.loop`, `MessageQueue.next`) have ~0 self time so they don't drown the list, and idle waits (`nativePollOnce`, `Object.wait`, `Unsafe.park`) are excluded from the `%busy` base. If you instead rank by inclusive time, the top of the list is always `ZygoteInit.main → Looper.loop → MessageQueue.next` at ~100% — that's the call chain that wraps everything, not the work.
2. *Methods from your package* — only classes under the configured package, so you see your own code directly.
3. *Jank culprits* — every `Choreographer.doFrame` invocation in the trace is one frame; frames longer than 16.7 ms are janky, and your package's methods are ranked by how much time they spent inside those slow frames.

**`method-profile.trace`** — open in Android Studio (File → Open, shows the CPU Profiler call chart / flame chart per thread) or drag into https://ui.perfetto.dev. Select the `main` thread, look for wide frames.

**`trace.perfetto-trace`** — open at https://ui.perfetto.dev. Workflow: find your app's process → `main` thread track → look for `Choreographer#doFrame` slices wider than 16.6 ms → zoom in to see the nested `traversal` / `measure` / `layout` / `draw` / binder slices that filled the frame. The query bar accepts SQL, e.g. `select name, dur from slice order by dur desc limit 20`.

## Reading PSI values

`avg10` is the percentage of the last 10 seconds in which at least one task ("some") or all tasks ("full") stalled waiting for that resource. Rule of thumb: sustained memory `full` > 10 means the device is thrashing; CPU `some` > 40–50 means heavy contention.
