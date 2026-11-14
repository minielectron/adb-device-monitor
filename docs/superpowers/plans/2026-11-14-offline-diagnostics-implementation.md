# Offline Diagnostics Implementation Plan

## Goal

Finish the Quick diagnostics and remaining Home presentation, make all runtime UI assets local, and keep generated diagnostic HTML portable without changing monitoring or capture behavior.

## Work sequence

1. Record current Home diagnostic IDs, API calls, and runtime remote-asset references.
2. Vendor pinned Chart.js 4.4.3 and jsPDF 2.5.1 distributions plus license notices into JVM resources.
3. Add an allowlisted classpath asset route with JavaScript content type, cache headers, logging, and traversal rejection.
4. Replace Dashboard and Compare CDN URLs with local asset URLs and add the approved Content Security Policy to interactive responses.
5. Redesign Quick diagnostics into independent Device Snapshot and Process Memory tool cards while preserving every ID, endpoint, progress poll, and report action.
6. Apply the shared status/action/surface treatments to remaining Home loading, empty, busy, disabled, success, and error states.
7. Replace the Device Snapshot Chart.js visualization with accessible server-rendered inline SVG and align both standalone diagnostic reports with the shared visual language.
8. Add automated checks for local asset presence, route behavior, traversal rejection, and remote runtime dependencies.
9. Run ID and JavaScript compatibility checks, Gradle tests, the HTTP smoke suite, and offline report generation.
10. Test Home and both diagnostic reports at desktop, tablet, and phone widths; exercise independent diagnostic progress and result states.
11. Rebuild the macOS app and DMG from a minimal PATH, launch the packaged app, and verify local assets load without runtime internet access.

## Guardrails

- Preserve all existing uncommitted UI and build-script work.
- No API schema, collector, polling cadence, metric, threshold, or capture changes.
- No CDN fallback, frontend framework, or bundler.
- Only exact allowlisted classpath assets are served.
- Generated diagnostic HTML must not require the running application.
- Any missing ID, remote runtime asset, failing report, or browser error blocks packaging.

