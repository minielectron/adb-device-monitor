# Architecture

The codebase follows a pragmatic **clean-architecture / layered** design. Dependencies point
inward: outer layers know about inner layers, never the reverse. Everything ships as one Gradle
fat-jar, but the package boundaries make the responsibilities explicit and keep files small.

## Package map

```
devicemonitor/
├── Main.kt                         # composition root / CLI entry point (wires everything together)
│
├── common/                         # cross-cutting helpers with no layer of their own
│   └── Build.kt                    #   buildTag() — reads the jar manifest build stamp
│
├── domain/                         # pure data models (no logic, no I/O, no dependencies)
│   ├── MetricModels.kt             #   Sample, CpuTicks, PssSample, ProcessSample, ProcMemSample,
│   │                               #   FramePhases, GfxData, DeviceInfo
│   ├── Anomaly.kt                  #   Anomaly (a flagged logcat event)
│   └── TraceModels.kt              #   MethodStat, JankMethodStat, TraceAnalysis
│
├── data/                           # everything that talks to the device / OS / files
│   ├── adb/
│   │   ├── Adb.kt                  #   thin wrapper around the adb binary (AdbDevice + Adb)
│   │   └── FolderPicker.kt         #   native OS folder-picker dialog
│   ├── metrics/
│   │   ├── Snapshot.kt             #   parses one batched `adb shell` dump into typed values
│   │   └── MetricsCollector.kt     #   builds the batched command + turns a Snapshot into a Sample
│   └── logcat/
│       ├── Anomalies.kt            #   EventInfo (app metadata) + AnomalyScanner (incremental)
│       ├── LogMetrics.kt           #   GrowTailCounter + LogMetrics (direct-reclaim etc.)
│       ├── Playback.kt             #   STB video-player state + tunnel-mode tracking
│       └── TraceParser.kt          #   ART method-trace / jank analysis
│
├── application/                    # orchestration / use-cases
│   └── Session.kt                  #   SessionConfig + MonitorSession (the run lifecycle) + CSV writer
│
├── presentation/                   # user-facing output
│   ├── web/
│   │   ├── WebServer.kt            #   WebApp: HTTP server, endpoints, session JSON serialization
│   │   ├── HomePage.kt             #   HOME_PAGE  (device picker + run config)
│   │   ├── DashboardPage.kt        #   DASHBOARD_PAGE (live charts)
│   │   └── ComparePage.kt          #   COMPARE_PAGE (two-run comparison)
│   └── report/
│       ├── Report.kt               #   PdfReport: PDF generation with JFreeChart images
│       └── Capabilities.kt         #   one-shot Device / Procrank snapshot HTML
```

## Dependency direction

```
                         ┌─────────────┐
                         │   domain    │   (depends on nothing)
                         └──────▲──────┘
                                │
        ┌───────────────┬───────┴───────┬──────────────────┐
        │               │               │                  │
   ┌────┴─────┐   ┌──────┴──────┐   ┌────┴─────┐     ┌──────┴──────┐
   │ data.adb │   │ data.metrics│   │data.logcat│    │   common    │
   └────▲─────┘   └──────▲──────┘   └────▲──────┘    └─────────────┘
        └───────────────┴───────────────┘
                        │
                 ┌──────┴───────┐
                 │ application  │  (MonitorSession orchestrates data collectors)
                 └──────▲───────┘
        ┌───────────────┴────────────────┐
   ┌────┴──────┐                   ┌──────┴───────────┐
   │presentation│                   │      Main        │
   │ web/report │                   │ (composition root)│
   └────────────┘                   └──────────────────┘
```

- **domain** is the stable core — plain immutable data classes. It imports nothing from the project.
- **data** turns raw adb/logcat/file bytes into domain objects. `data.metrics` and `data.logcat`
  depend on `domain` (and `data.adb` for the device handle).
- **application** (`MonitorSession`) is the only place that knows the full run lifecycle. It wires the
  collectors together, owns the sampling loop and threads, and pushes each `Sample` to the outputs.
- **presentation** is the output adapter — it consumes domain models and the session.
- **Main** is the composition root: it parses CLI flags, builds an `Adb`, a `SessionConfig`, and either
  starts a `WebApp` or runs a headless `MonitorSession`.

## How a Sample flows (the hot path)

1. `MetricsCollector.snapshot()` runs **one** batched `adb shell` command (`Snapshot.buildCommand()`)
   that concatenates `/proc/stat`, `/proc/meminfo`, `/proc/pressure/*`, etc., separated by `===MARKER===`
   lines. This keeps the per-tick device round-trips to a single call.
2. `Snapshot(raw)` parses that blob into typed fields (CPU ticks, memory KB, PSI, zoneinfo…).
3. `MetricsCollector.toSample(prev, snap, …)` computes deltas (CPU %, network KB/s, fault rate) and
   returns an immutable `Sample`.
4. `MonitorSession` appends the `Sample` and hands it to each active output: the web buffer
   (`WebApp` serializes on demand) and the CSV writer.
5. Expensive/optional reads (top-process memory via `ps`, foreground activity, per-process CPU) run
   on a **background collector thread** and are merged into the next `Sample` non-blockingly, so a slow
   read never stalls the fixed-interval loop.

## Threading model (why the app stays responsive)

| Thread | Owns | Notes |
|--------|------|-------|
| `monitor-session` | the fixed-interval sampling loop | waits in 150 ms slices polling the stop flag (never interrupted) |
| `slow-collector` | top-process memory (`ps`), foreground activity | adaptive back-off (4→9→15s) if a read is slow |
| logcat reader (Process) | streaming `adb logcat` to `logcat.log` | scanned incrementally for anomalies |
| `http-N` (cached daemon pool) | serving dashboard requests | unbounded, so one slow handler (frozen device, folder dialog) cannot starve the dashboard poll |
| `watchdog` (per run) | stall detection for the sampling loop / finalize | dumps all thread stacks to the log when the loop makes no progress for max(15 s, 6× interval) |
| `http-watchdog` | stall detection for HTTP handlers | thread dump when a request is stuck > 10 s |

Two hardening rules run throughout:

- **`Adb.exec` is bounded.** Every adb command runs with a timeout and a separate reader thread; a
  hung command (e.g. `su`) is `destroyForcibly`-killed instead of blocking forever.
- **Stop aborts in-flight work.** `requestStop()` sets the flag and calls
  `Adb.beginAbort(runThreads)`: the adb commands owned by the run's threads (sampling, slow
  collector, zapper) are killed, and any further `exec` on those threads returns `""` immediately
  until `finalizeOutputs()` calls `endAbort()`. The gate is per-thread, so the device list and
  one-shot snapshots keep working while a run stops. The sampling thread is not interrupted.
- **Every HTTP handler goes through `route()`** (WebServer.kt): timed, exceptions logged and turned
  into a 500, exchange always closed. `/api/devices` caches the per-device version/root probe
  (30 s) and bounds it to 5 s.
- **Logging is central** (`common/Log.kt`): levelled, timestamped, thread-named lines; `println`
  is captured too. See [build-run-package.md](build-run-package.md#logs).

## Conventions

- Models are immutable `data class`es; collectors are stateless-ish and receive `Adb` + target.
- Cross-package references use wildcard imports (`import devicemonitor.domain.*`). Names are unique
  across the project, so there is never an ambiguity.
- Web pages are Kotlin raw-string constants (`internal val HOME_PAGE` …). They use plain JS (no
  template literals) so the Kotlin `$` interpolation never clashes; the build stamp is injected at
  serve time by replacing the `__BUILD__` placeholder.
