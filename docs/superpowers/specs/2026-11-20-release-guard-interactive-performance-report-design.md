# Release Guard Interactive Performance Report Design

## Goal

Add a portable, interactive HTML performance report that matches the Live Dashboard design and
lets Dev and QA inspect any captured point after a Release Guard scenario finishes. Existing PDF
and HTML summary artifacts remain unchanged.

## Artifact contract

- Generate `interactive-performance-report.html` in the Release Guard run directory.
- Register it as one run-level artifact in `run.json`.
- Add an **Open interactive performance report** action to the Release Guard result screen.
- Keep `report.html`, `report.pdf`, and `performance-report.pdf` unchanged.
- The file must remain functional when copied outside the application.

## Offline packaging

The report is one self-contained HTML file containing:

- Live Dashboard-derived CSS and visual tokens.
- The already bundled Chart.js runtime embedded inline.
- Captured device metadata, samples, anomalies, and scenario markers serialized inline.
- Report JavaScript for chart creation and interactions.

The generated file must not reference HTTP resources, application APIs, local asset routes, or a
running server. Embedded text is JSON-encoded and protected against closing the inline script or
introducing executable markup.

## Visual design

The report follows the Live Dashboard rather than the existing PDF design:

- Dark studio background and ambient color treatment.
- Dashboard app bar, run identity, device context, and capture status.
- The same rounded metric cards, spacing, typography, grid, palette, chart line styling, legends,
  and statistic strips.
- The same logical chart groups when their data exists: system health, application analysis,
  memory, playback/device signals, and run events.
- Responsive one-column behavior for narrow windows.

No separate visual theme or report-only chart palette is introduced.

## Data and chart coverage

Render charts only for enabled metrics with usable values. Supported data includes:

- CPU utilization and idle, per-core CPU, load, temperature, and frequency.
- Memory capacity, swap, PSI, page faults, and reclaim.
- Network throughput.
- App PSS total and heap breakdown.
- App/main-thread CPU.
- Top-process CPU and memory.
- Jank/frame phases, screen FPS, and video FPS when captured.
- Scenario repetition, section, step, anomaly, and zap markers when available.

The report embeds raw captured samples. Chart.js decimation may reduce rendered points for browser
performance, but the source values remain in the HTML so a selected time range can be inspected at
full available resolution.

## Interaction

- Hover tooltips show elapsed time and exact series values.
- Chart legends toggle individual series.
- A synchronized range control limits every chart to the same elapsed-time window.
- Reset restores the complete scenario range.
- Metric search/filter hides non-matching chart cards without discarding data.
- Each chart shows min, average, and max for its visible range.
- Scenario and anomaly markers remain visible in the applicable time range.

The report is read-only; it does not modify the run, baseline, or scenario.

## Generation path

Release Guard export already receives the complete `ScenarioRunEvidence`. A dedicated interactive
report renderer will consume that evidence and the existing scenario/manifest metadata, embed the
bundled chart runtime, and write the new artifact during the existing export phase. It will not
capture a browser screenshot or depend on the live dashboard API.

## Failure handling

- Missing optional metrics omit their cards.
- No samples produces a valid report with an explicit unavailable state.
- Missing Chart.js bundled resource fails export clearly instead of producing a broken report.
- Large reports use chart decimation and bounded initial rendering; raw evidence remains present.

## Verification

- Export tests verify the artifact is registered and non-empty.
- Offline tests reject remote URLs, API fetches, and external script sources.
- Tests verify embedded samples, device context, anomalies, scenario markers, and App PSS details.
- JavaScript syntax is checked with Node.
- A generated report is opened locally to verify hover, legend toggles, range synchronization,
  filtering, reset, and responsive layout.
- The full Gradle suite and macOS packaging build must pass.

## Out of scope

- Redesigning or removing existing PDF reports.
- Editing scenarios or budgets from the exported report.
- Live polling after export.
- Cloud sharing or hosted report storage.
