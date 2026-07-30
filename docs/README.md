# ADB Device Monitor — Documentation

ADB Device Monitor ("ADB Performance Studio") is a self-contained Kotlin/JVM tool that samples
live CPU / memory / app performance from adb-connected Android & STB devices, renders a live web
dashboard, writes PDF/CSV reports, and captures logcat with anomaly flagging.

This folder documents each feature: **how it's implemented**, **how it works technically**, **how to
read the values**, and **how to use it during development**.

## Index

| Doc | What it covers |
|-----|----------------|
| [architecture.md](architecture.md) | Clean-architecture layout, package responsibilities, how a sample flows through the system, threading model |
| [build-run-package.md](build-run-package.md) | Building the fat jar, all CLI flags, running the web UI, packaging the macOS app, log files |
| [metrics.md](metrics.md) | Every metric: the adb command behind it, how it's parsed, how to read the value, and what it tells you in dev |
| [web-dashboard.md](web-dashboard.md) | The live dashboard: polling protocol, charts, interactive top-process charts, health badges, Stop/report flow |
| [device-and-procrank-snapshot.md](device-and-procrank-snapshot.md) | One-shot Device Snapshot and Procrank Snapshot features (server-rendered HTML) |
| [reports-and-comparison.md](reports-and-comparison.md) | PDF report, CSV schema, and the two-run comparison view |

> The legacy single-file [`../documentation.md`](../documentation.md) contains the original deep-dive
> on individual metrics and remains valid; `metrics.md` here supersedes and reorganizes it.

## 30-second mental model

```
adb device ──> MetricsCollector (one batched `adb shell` per tick)
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
