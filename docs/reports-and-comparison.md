# Reports, CSV & Comparison

**Current state:** implemented. The PDF and CSV are generated from the same in-memory `Sample`
stream used by the dashboard; comparison reads completed CSV files and does not re-query the device.

Every run writes a folder `run_<timestamp>_<manufacturer>_<model>/` containing `report.pdf`,
`samples.csv`, `logcat.log`, `anomalies.log`, and `device-info.log`.

## PDF report — `presentation/report/Report.kt` (`PdfReport`)

- **What:** a multi-page PDF summarizing the run — device info, a health verdict, one chart per active
  metric (rendered as JFreeChart PNGs embedded via PDFBox), the anomaly timeline, and — with
  `--profile` — a top main-thread methods table.
- **How:** charts are rasterized (in parallel for speed) from the `Sample` list; Android performance
  targets are drawn as reference lines so a reader sees "good vs bad" without prior context.
- **Read:** each chart's caption states what it measures and its healthy range; the verdict at the top
  aggregates PSI/memory/jank into a pass/warn/fail.
- **Dev use:** the shareable artifact — attach it to a bug so others see the whole run without adb.

## CSV — `samples.csv` (`writeCsv`, in `application/Session.kt`)

- **What:** one row per sampling tick, one column per `Sample` field (elapsed seconds, CPU, memory,
  PSI, swap, thermal, freq, network, app PSS + breakdown, app/main CPU, jank + frame phases, zone
  watermarks, media-process CPU columns…).
- **Read:** `elapsed_sec` is the x-axis; blank cells mean the metric was unavailable/disabled that run.
- **Dev use:** the machine-readable source of truth. Load it in pandas/Excel/Grafana for custom
  analysis, regression baselines, or plotting metrics the PDF doesn't chart.

## Comparison view — `/compare` (`ComparePage.kt`)

- **What:** pick two past runs (by folder/label) and overlay their CSVs metric-by-metric — Run A solid,
  Run B dashed, with Android target reference lines. Exports the comparison to PDF client-side (jsPDF).
- **How:** the page fetches each run's CSV via `/api/runcsv?folder=`, parses it locally, and charts
  only metrics present in both. Chart and PDF-export assets are bundled by the application; the
  comparison does not call a CDN.
- **Dev use:** the intended A/B workflow. Run the same scenario twice with different `--label`s (e.g.
  `tunnel-off` vs `tunnel-on`, or before/after a fix) and read the deltas directly.

Blank CSV cells mean disabled or unavailable data, not zero. A comparison can show correlation and
regression between two captures, but it does not establish statistical significance or normalize
different devices, firmware, durations or scenarios automatically. Release Guard is the repeatable,
budgeted workflow when those controls matter.

### Tunnel-mode example
```bash
java -jar adb-...-all.jar --duration 300 --interval 2 --package <pkg> --playback --label tunnel-off
# toggle tunneled playback on the device, then:
java -jar adb-...-all.jar --duration 300 --interval 2 --package <pkg> --playback --label tunnel-on
# open /compare, pick the two runs
```
Expect App PSS to *drop* in tunnel mode (video bypasses the app render path) while CPU PSI may *rise*
as work shifts to media/HAL processes — confirm via the `media.codec` / `audioserver` CPU columns.
