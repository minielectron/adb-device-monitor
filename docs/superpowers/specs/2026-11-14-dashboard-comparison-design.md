# Live Dashboard and Run Comparison Redesign

## Objective

Extend the approved Home command-center design across the Live Dashboard and Run Comparison screens. The result should feel like one production-grade desktop application while preserving every existing workflow, API route, chart, control, export, and state transition.

This phase covers only the interactive Live Dashboard and Run Comparison pages. Generated capability, procrank, and PDF reports remain unchanged for a later phase.

## Design Direction

The Home page is the visual source of truth: graphite surfaces, warm amber primary emphasis, violet and teal analytical accents, restrained ambient depth, crisp typography, rounded panels, and smooth but purposeful transitions.

The redesign must prioritize information hierarchy over decoration. Motion communicates state or spatial relationships and must never interfere with reading live charts. Live Chart.js dataset animation remains disabled.

## Shared UI Architecture

Create a small code-native design system in `StudioUi.kt`. It will provide:

- color, spacing, radius, shadow, and typography tokens;
- page shell, navigation, surface, button, form, badge, status, and table styles;
- responsive layout primitives;
- focus-visible and keyboard interaction treatments;
- entrance, disclosure, status, and feedback motion;
- `prefers-reduced-motion` behavior.

`WebServer` will inject the shared stylesheet into Home, Dashboard, and Compare page templates. Page-specific markup, data rendering, and JavaScript remain in their existing page files. No external UI framework or new runtime dependency will be introduced.

The Home page will be migrated onto the shared tokens without changing its approved composition. This establishes one reusable foundation for later report-screen work and prevents the three interactive screens from drifting.

## Compatibility Contract

The redesign is presentation-only. It must preserve:

- every existing element ID used by JavaScript;
- all API routes and request/response formats;
- dashboard polling, sample accumulation, health badges, chart creation, process selection, playback rendering, Stop, Report, Home, and diagnostics behavior;
- comparison run loading, CSV parsing, validation, chart creation, tunnel guidance, and PDF export;
- loading, empty, disconnected, stopping, finished, failed, and no-common-metrics states;
- Chart.js and jsPDF versions and behavior.

Shared UI code must not perform API calls or own application/session state.

## Live Dashboard

### Command bar

Use a compact sticky command bar containing application identity, run status, device context, build information, and the existing Home, Stop, Report, and diagnostics actions. Destructive Stop styling remains unmistakable. Loading and stopping states retain their current disabled and busy behavior.

### Run context and health

Place current activity directly below the command bar. Present health badges as a responsive status strip with clear healthy, warning, and critical treatments. The layout must handle missing badges and long device/activity values without overflow.

### Metric organization

Retain every existing metric card and chart definition. Group visible cards into these presentation categories:

1. System — CPU utilization, CPU idle, load, temperature, frequency, and network.
2. Memory — memory amount, memory percentage, swap, zones, PSI, reclaim, and page faults.
3. Application — app PSS, PSS breakdown, app CPU, main-thread CPU, and process analysis.
4. Rendering — UI jank and frame phases.
5. Media — media/audio process CPU, playback state, and tunnel mode.

Cards whose capability flags are false remain hidden exactly as they are now. Categories with no visible cards should not leave empty visual sections.

### Process analysis

Keep the existing top-process CPU and memory selectors, automatic selection, history, and chart behavior. Present each as a focused analysis workspace: compact ranked selector at the side on wide screens and stacked above the chart on narrow screens.

### Playback and events

Playback and tunnel diagnostics use a distinct media-analysis surface while preserving the build banner, lifecycle warning, badges, raw player-state chart, and tunnel chart.

Events use a readable timeline/table treatment with severity tags and robust wrapping for long diagnostic messages. Column headings remain visible while the event region scrolls. Event ordering and content remain unchanged.

## Run Comparison

### Selection workflow

Use a sticky application header and comparison introduction. Present Run A and Run B as balanced selector cards with a clear baseline-versus-candidate relationship. Keep the current select elements, validation, labels, and Compare action.

### Comparison context

After comparison, show a compact context bar containing the existing legend, Android target reference, tunnel guidance when applicable, and PDF export. A and B must have consistent, accessible visual identities across selectors, legend, and charts.

### Result organization

Retain every current metric and render only metrics with data. Organize resulting chart cards under:

1. Application;
2. Pressure;
3. Rendering;
4. Media;
5. System.

The empty common-metrics message remains visible when no chart can be produced. Error and loading feedback stays adjacent to the selection workflow.

## Responsive and Accessible Behavior

- Wide layouts may use two-column metric grids; narrow layouts stack without horizontal page overflow.
- Process selectors and charts stack on tablet and mobile widths.
- All primary actions remain visible and operable at every supported width.
- Interactive controls retain explicit labels, focus-visible styling, and keyboard behavior.
- Status must not be communicated by color alone.
- Motion is restrained and automatically reduced or removed when the operating system requests reduced motion.
- Long filenames, device identifiers, process names, and event details must wrap or truncate with a discoverable full value where appropriate.

## Error Handling

Existing network and rendering error paths remain authoritative. The visual layer will provide consistent presentation for errors but will not swallow exceptions, change retry timing, or fabricate successful states. Browser-side errors continue to flow through the existing client logging mechanism.

## Verification

Implementation is complete only after:

1. comparing pre- and post-change element IDs and confirming no required ID disappeared;
2. checking extracted JavaScript syntax;
3. running the Gradle test suite and fat-JAR build;
4. running the existing HTTP smoke test;
5. testing Dashboard and Compare at desktop, tablet, and mobile widths;
6. exercising dashboard running, stopping, finished, failed, report, diagnostics, process selection, and playback presentation where fixtures permit;
7. exercising comparison loading, same-run validation, successful comparison, empty metrics, tunnel guidance, and PDF-button state where fixtures permit;
8. confirming no unexpected browser console errors;
9. rebuilding and launching the packaged macOS application.

## Non-goals

- Redesigning generated HTML or PDF reports;
- changing metric collection, thresholds, sampling, or report content;
- adding new charts, comparison calculations, filters, or navigation destinations;
- replacing Chart.js or jsPDF;
- introducing a frontend framework, bundler, or dependency.
