# Release Guard Interactive Timeline and Chart Clarity Design

**Date:** 2026-11-20  
**Status:** Approved for implementation

## Goal

Make the standalone Release Guard performance report as clean as Live Dashboard while allowing Dev
and QA to investigate a precise period in a long scenario. Every chart must share one interactive
time viewport and remain fully functional when the HTML file is opened without Studio, a server or
an internet connection.

The motivating real report contains 2,054 performance samples, 3,000 scenario markers and 717
anomalies over approximately 5,118 seconds. The captured metric series are valid and ordered. The
current visual corruption is caused by drawing every scenario marker and anomaly as a vertical line
over every chart.

## Scope

- Preserve the existing `interactive-performance-report.html` artifact and embedded-data model.
- Match the spacing, typography, chart cards, colors, legends and statistics used by Live Dashboard.
- Add a single shared timeline viewport for all metric charts.
- Add mouse, trackpad, keyboard-accessible button and range-selection controls.
- Move dense scenario evidence out of the chart plotting area and present it in a compact timeline
  rail and the existing event table.
- Keep the report self-contained and dependency-free beyond the Chart.js runtime already bundled in
  the generated file.
- Keep the existing static HTML and PDF exports unchanged.

Adding a new zoom library, changing metric collection, modifying Release Guard execution, or adding
per-iteration reports is outside this change.

## Interaction Model

One timeline controller owns the visible start and end time. It updates every chart and visible-range
statistic together.

- Mouse-wheel vertical movement or trackpad pinch over a chart zooms around the pointer position at
  a 1.12 factor per normalized wheel step.
- Dragging horizontally over a chart pans the visible window.
- Horizontal trackpad scrolling pans the visible window.
- Shift-dragging over a chart draws a selection and zooms to that exact interval on release.
- `−` and `+` buttons zoom out and in by a factor of two around the viewport center.
- **Reset view** restores the complete scenario range and clears the metric search.
- The timeline navigator shows the complete run, the selected viewport and draggable start/end
  handles. Dragging the selected window pans it; dragging a handle resizes it.
- The current start, end and duration remain visible as formatted times.

All interactions clamp to the complete scenario range and enforce a minimum window of the greater of
one second or 1/10,000 of the complete duration. They must not change or discard embedded samples.

## Timeline Navigator

Replace the current pair of independent sliders with one compact navigator below the search field.
The navigator contains:

- a low-detail overview derived from the primary available signal;
- a translucent selected-window overlay;
- start and end handles;
- a scenario evidence rail beneath the overview;
- anomaly clusters and section/attempt boundaries positioned against the complete run;
- a tooltip or accessible label that reports cluster count and time.

The navigator stays readable for runs with hundreds of repetitions. Evidence is bucketed into slots
at least eight CSS pixels wide at render time. Multiple events in one bucket become a single marker
with a count instead of overlapping lines.

## Chart Evidence Rules

Metric lines remain the visual priority.

- At the complete-run view, charts show no individual step lines.
- Attempt or section boundaries may appear as subtle lines only while the complete set of chart
  evidence can remain at or below 24 overlay lines.
- Anomalies are clustered for wide ranges. Individual anomaly lines appear only after the viewport is
  narrow enough to keep them visually distinct.
- Step markers become eligible when the visible range is no more than 5% of the complete duration and
  remain capped by the same 24-line density limit.
- The event table remains the complete, unclustered record of steps and anomalies.

This adaptive rule fixes the current vertical striping without deleting evidence.

## Chart Rendering

The exporter continues embedding raw captured samples. Each dataset is sorted by elapsed time before
chart creation and duplicate timestamps are collapsed deterministically if encountered. Chart.js
decimation remains enabled for drawing long series, but statistics always use the full raw samples in
the current viewport.

Chart cards keep Live Dashboard's two-column desktop layout, single-column responsive layout, fixed
plot height, legend toggles, hover tooltips and min/average/max rows. Panning or zooming updates the
x-axis and statistics without rebuilding chart instances.

## Architecture

The existing report script gains three small responsibilities:

1. `TimelineViewport` stores the complete and visible ranges, clamps changes and notifies charts.
2. The navigator renders the overview, handles pointer gestures and clusters evidence.
3. A Chart.js interaction plugin handles wheel zoom, drag pan, Shift-drag selection and adaptive
   evidence overlays.

These components operate only on the embedded report payload. No fetch, external script, CDN, API or
loopback route is introduced.

## Error Handling and Accessibility

- A report with no samples keeps the existing explicit unavailable state.
- A report with one timestamp expands to a safe minimum range.
- Invalid or non-finite values are ignored without breaking other datasets.
- Pointer cancellation ends pan or selection without changing the viewport.
- Zoom buttons and Reset have visible labels and keyboard focus styles.
- Navigator handles expose range semantics and formatted values through ARIA attributes.
- Reduced-motion preferences disable nonessential transitions.

## Verification

- Unit-level export tests verify the new navigator, zoom/pan controls and offline guarantees.
- A dense fixture verifies thousands of markers do not become thousands of chart overlay lines.
- JavaScript syntax is checked from a generated report.
- Headless Chrome opens the generated file directly through `file://` and verifies that wheel zoom,
  drag pan, Shift-drag selection, navigator changes and Reset update every chart together.
- The real long-run report shape—2,054 samples, 3,000 markers and 717 anomalies—is rendered for visual
  comparison with Live Dashboard.
- The complete Gradle test suite and packaged fat-jar build must pass.

## Acceptance Criteria

- Metric lines are clean at the complete-run view and no longer obscured by vertical marker stripes.
- A user can zoom to a particular time, pan forward or backward, and reset without reloading.
- Every visible chart and its statistics use the same selected timeline.
- Hover and legend interactions continue working.
- The complete event record remains inspectable.
- The report contains no external resource, network or application-server dependency.
