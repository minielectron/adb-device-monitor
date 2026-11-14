# Offline Diagnostics and Remaining UI Design

## Objective

Make ADB Performance Studio fully self-contained at runtime and finish the remaining Home and diagnostic-report presentation. The installed application must render, monitor, compare, export, and generate diagnostic reports without an internet connection.

The embedded localhost server and WebView remain internal implementation details. This phase does not replace the desktop shell or rewrite the application as a native widget toolkit.

## Scope

This phase covers:

- the Home Quick diagnostics workspace;
- consistency improvements across the remaining Home sections and states;
- the standalone Device Snapshot and Procrank HTML reports;
- local delivery of Dashboard and Compare JavaScript dependencies;
- offline comparison PDF export;
- automated checks that prevent remote runtime dependencies from returning.

Metric collection, API payloads, polling intervals, capture semantics, and generated PDF report content are unchanged.

## Offline Architecture

Pin and bundle minified Chart.js and jsPDF distributions inside JVM application resources. Include the applicable upstream license notices beside those assets. The web server exposes the exact allowlisted files under local `/assets/...` routes with explicit JavaScript content types and stable cache headers.

Dashboard and Run Comparison load these local asset URLs. They must not contain CDN fallbacks or dynamically request remote scripts.

Device Snapshot no longer loads Chart.js or chartjs-plugin-datalabels. Its memory visualization is rendered server-side as inline SVG with accessible textual totals and percentages. Procrank continues using its existing server-rendered inline SVG approach.

Both generated diagnostic HTML reports remain standalone files. They must contain all required CSS, SVG, data, and behavior and remain useful when opened directly from disk while networking is unavailable.

The application may display documentation URLs as optional links or plain text, but rendering and operation must never depend on them.

## Quick Diagnostics Workspace

Quick diagnostics becomes a first-class section rather than a compact sidebar card.

It contains two equal tool cards:

1. **Device Snapshot** — capabilities, configuration, system memory composition, and device/process details.
2. **Process Memory** — per-process PSS/USS when procrank and root are available, otherwise the existing RSS/VSS fallback.

Each tool card contains:

- a concise purpose statement;
- the selected-device context;
- device/root expectations;
- an explicit action;
- its own idle, running, progress, success, failure, retry, and open-report presentation.

The tools remain independent. Starting or polling one diagnostic must not overwrite, disable, or visually obscure the other tool's state. Both remain usable without configuring or starting a monitoring run. The existing selected-device requirement and server-side busy/error validation remain authoritative.

Wide layouts show the two tools side by side. Narrow layouts stack them without horizontal overflow or hidden actions.

## Remaining Home Consistency Pass

Device selection, run configuration, metric categories, and launch summary continue using their approved composition and behavior while adopting shared design-system components for status, actions, surfaces, and messages.

The pass must preserve:

- every current element ID and form field used by JavaScript;
- device polling, automatic first-device selection, refresh, and root handling;
- run configuration defaults and validation;
- metric/category disclosure behavior;
- busy-session resume behavior;
- start-run payloads and navigation;
- snapshot and procrank API calls, status polling, and report actions.

Loading, no-device, busy-session, disabled, error, and success states use consistent accessible status treatments. Status is never communicated by color alone.

## Standalone Diagnostic Reports

Device Snapshot and Procrank reports adopt the application graphite, gold, violet, teal, green, amber, and red visual language. They use responsive layouts, readable tables, visible focus states, robust wrapping for device/process values, and print-friendly rules.

Device Snapshot uses inline SVG for its memory chart. The chart includes accessible labels and is accompanied by textual values so the report remains understandable without SVG support.

Procrank retains server-rendered SVG plus its existing progressive-enhancement table filter/sort. The statically rendered chart and table remain visible if JavaScript is unavailable.

No generated report may contain a remote script, stylesheet, font, image, iframe, fetch, or network-backed asset.

## Asset Route Security and Failure Handling

Asset routing is classpath-backed and allowlisted. Request paths are matched to known asset names; user-controlled filesystem paths and directory traversal are not accepted.

Missing bundled assets return a clear HTTP error, are recorded in application logs, and fail build-time tests. The application must never recover by requesting a CDN.

Existing diagnostic network/API errors remain visible in the relevant tool card. Client-side polling retains its existing cadence and stops on success or failure. Browser-side errors continue through the existing client logging endpoint.

Interactive pages receive this Content Security Policy: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`. It permits the existing inline page code while preventing remote runtime dependencies. The policy must be verified against all Home, Dashboard, and Compare workflows before release.

## Verification

Implementation is complete only after:

1. verifying pinned local assets and license notices exist in the fat JAR and packaged application;
2. scanning interactive pages and generated reports for remote runtime asset references;
3. confirming Dashboard and Compare scripts load only from `/assets`;
4. checking Quick diagnostics idle, progress, success, failure, retry, and report-open states for each independent tool;
5. generating Device Snapshot and Procrank reports and opening them directly from disk with networking unavailable;
6. checking Dashboard live charts, categorized visibility, Stop, Report, and error states;
7. checking Compare selection, validation, charts, category visibility, and PDF export;
8. checking Home, Dashboard, Compare, and diagnostic reports at desktop, tablet, and phone widths;
9. inspecting browser/client logs for unexpected errors;
10. running JavaScript syntax checks, Gradle tests, the HTTP smoke suite, and the offline-dependency guard;
11. rebuilding the macOS `.app` and DMG from a minimal shell PATH;
12. launching the packaged application and confirming the Home screen loads with network access unavailable.

## Non-goals

- Replacing the embedded WebView or localhost server;
- introducing React, another frontend framework, or a bundler;
- rewriting Chart.js or jsPDF;
- changing collectors, metrics, thresholds, report calculations, or API schemas;
- redesigning the generated performance PDF in this phase;
- making optional external documentation links available offline.
