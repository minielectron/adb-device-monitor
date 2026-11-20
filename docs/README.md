# ADB Device Monitor — Documentation

ADB Device Monitor ("ADB Performance Studio") is a self-contained Kotlin/JVM tool that samples
live CPU / memory / app performance from adb-connected Android & STB devices, renders a live web
dashboard, writes offline evidence, and captures logcat with anomaly flagging. Quick Diagnostics add
one-shot device/memory/layout reports; Release Guard adds repeatable performance decisions; Player
Debugger reconstructs multi-player playback and handoffs.

This folder documents each feature: **how it's implemented**, **how it works technically**, **how to
read the values**, and **how to use it during development**.

## Index

| Doc | What it covers |
|-----|----------------|
| [architecture.md](architecture.md) | Clean-architecture layout, package responsibilities, how a sample flows through the system, threading model |
| [build-run-package.md](build-run-package.md) | Building the fat jar, all CLI flags, running the web UI, packaging the macOS app, log files |
| [desktop-app.md](desktop-app.md) | The native macOS app: Tauri shell + Kotlin sidecar, build script, verification checklist, signing |
| [metrics.md](metrics.md) | Every metric: the adb command behind it, how it's parsed, how to read the value, and what it tells you in dev |
| [web-dashboard.md](web-dashboard.md) | The live dashboard: polling protocol, charts, interactive top-process charts, health badges, Stop/report flow |
| [device-and-procrank-snapshot.md](device-and-procrank-snapshot.md) | One-shot Device Snapshot and Procrank Snapshot features (server-rendered HTML) |
| [layout-inspector.md](layout-inspector.md) | Lite Layout Inspector capture pipeline, screenshot coordinate mapping, refresh, privacy and Android limitations |
| [diagnostics.md](diagnostics.md) | Difference between Home Quick Diagnostics and the application/JVM Diagnostics ZIP |
| [reports-and-comparison.md](reports-and-comparison.md) | PDF report, CSV schema, and the two-run comparison view |
| [player-debugger.md](player-debugger.md) | Player Debugger capture modes, OPTD, evidence confidence, storage/privacy, support bundles, and QA checklist |
| [release-guard.md](release-guard.md) | Release Guard scenarios, capability probing, repetitions, budgets, baselines, evidence and CI exit codes |

> The legacy single-file [`../documentation.md`](../documentation.md) is retained for history and may
> describe earlier defaults. The documents indexed above and the current source are authoritative.

## 30-second mental model

```
adb device ──> MetricsCollector (batched core snapshot + enabled optional collectors)
                    │  produces a Sample (immutable snapshot of the tick)
                    ▼
              MonitorSession (orchestrates the run: sampling loop, logcat,
                    │          anomaly scan, background collectors)
        ┌───────────┼────────────────────┐
        ▼           ▼                    ▼
   WebApp        PdfReport           samples.csv
 (live JSON)   (PDF + charts)        (raw data)
```

A **Sample** is the unit of everything: the collector builds one per tick, and every consumer
(dashboard JSON, PDF, CSV) is just a different serialization of the same `Sample` stream.

Player Debugger adds a second normalized stream, `PlaybackEvent`. It combines passive Android
evidence, optional app-reported OPTD events, device measurements, and explicitly labelled engine
inference. See [player-debugger.md](player-debugger.md).

Quick Diagnostics bypass `MonitorSession` and create bounded one-shot HTML reports. The Diagnostics
navigation action is different again: it packages host/JVM state and log tails for application
support. See [diagnostics.md](diagnostics.md).
