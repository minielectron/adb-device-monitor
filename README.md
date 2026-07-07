# adb-device-monitor

Kotlin tool that monitors any `adb`-connected Android device (phone, TV, STB, Fire Stick) over USB or TCP/IP, samples live system metrics, and renders a PDF report with graphs. Comes with **ADB Performance Studio** — a web UI for configuring and watching runs live — plus a scriptable CLI.

## Web UI (ADB Performance Studio)

```bash
gradle fatJar
java -jar build/libs/adb-device-monitor-1.0.0-all.jar --web 8080
# open http://localhost:8080
```

The home page shows every adb-connected device as a card (serial, model, Android version, authorization state, auto-refreshing). On userdebug/eng builds an **⚡ Enable root** button runs `adb root` (the card shows "✓ adbd running as root" once active — useful for reading `/data/anr`, protected logs, etc.); on production builds the button is hidden since adbd can't run as root there. Pick a device, configure the run in the browser and press **Start**:

- duration and sampling interval
- **Channel zapping** toggle — CHANNEL_UP every N ms, optional stop-after-n-zaps
- **Logcat capture** toggle — enables logcat.log, anomaly flagging with timestamps, and app build info extracted from analytics events
- **App package** — enables the PSS memory breakdown
- **UI jank tracking** toggle — janky-frame % per tick from `dumpsys gfxinfo`

The live dashboard shows all charts updating in real time, a zap/anomaly event feed, live **health verdict badges** (CPU, Memory, Thermal incl. throttling detection, UI Jank, Stability), a Stop button, and a PDF download button when the run finishes. Only one session runs at a time; everything is still written to the run folder. The terminal keeps printing progress, and Ctrl+C in the terminal shuts the studio down (a running session still writes its report).

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
| App PSS: Total / Java Heap / Native Heap / Code / Stack / Graphics / Private Other / System (MB) | `dumpsys meminfo <pkg>` App Summary | only with `--package` |
| UI jank (% janky frames per tick) | `dumpsys gfxinfo <pkg> framestats` counter deltas | only with jank tracking enabled (needs package) |
| Frame phases: input / animation / measure-layout / draw (avg ms per frame) | `gfxinfo framestats` PROFILEDATA | with jank tracking; shows which Choreographer phase eats the frame budget |
| App main-thread CPU (%) | `/proc/<pid>/task/<pid>/stat` deltas (main thread tid == pid) | automatic when a package is set |
| Main-thread hot methods, app-package methods, jank culprits | ART sampling profiler (`am profile start --sampling`) | with the method profiler enabled; three tables in the PDF + `main-thread-methods.txt` + raw `method-profile.trace`. App must be debuggable |
| Full system trace | `perfetto` (sched + gfx/view/input/binder) | with Perfetto enabled; `trace.perfetto-trace` for ui.perfetto.dev |

All files are read in a single `adb shell` round-trip per tick, so sampling overhead on the device is minimal. Metrics whose source is unreadable on the device are reported as `n/a (no data captured)` and skipped in graphs. If `--package` PSS sampling fails at startup, the console prints the device's raw `dumpsys meminfo` reply so you can see whether the package name is wrong, the app isn't running, or the output format differs.

## Requirements

- JDK 11+ and Gradle (or just open in IntelliJ/Android Studio)
- `adb` on PATH (or pass `--adb /path/to/adb`)
- Device connected via USB with USB debugging enabled — or over Wi-Fi (`adb connect <ip>` / `--ip` flag)

## Build

```bash
cd adb-device-monitor
gradle fatJar
# → build/libs/adb-device-monitor-1.0.0-all.jar
```

No Gradle wrapper is checked in. If you don't have standalone Gradle, either open the project in IntelliJ/Android Studio (it bundles Gradle) or run `gradle wrapper` once and use `./gradlew fatJar` afterwards.

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

`--serve <port>` starts an embedded web server (no extra dependencies) with live charts that update while monitoring runs: CPU, memory, PSI pressure, network, temperature/frequency, load/page-faults, and App PSS when `--package` is set — plus a live event table of zaps and flagged anomalies. The page needs internet access for the Chart.js CDN. When the run finishes the dashboard shows "finished" and stays available until you Ctrl+C; the PDF report and run folder are generated as usual.

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
