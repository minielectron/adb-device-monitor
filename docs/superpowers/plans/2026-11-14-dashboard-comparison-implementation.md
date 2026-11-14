# Dashboard and Comparison Redesign Implementation Plan

## Goal

Apply the approved shared command-center visual system to Home, Live Dashboard, and Run Comparison without changing application behavior or external dependencies.

## Work sequence

1. Record the current Dashboard and Compare element-ID and JavaScript behavior contracts.
2. Add `StudioUi.kt` with shared tokens, components, responsive rules, motion, focus, and reduced-motion styles.
3. Inject the shared style through `WebServer` and migrate Home to shared tokens without changing its composition.
4. Recompose Dashboard markup and page styles into the approved command bar, health strip, categorized metric regions, process workspaces, media workspace, and event surface. Preserve all chart definitions and handlers.
5. Recompose Compare markup and styles into the approved paired-run workflow, result context, and categorized chart regions. Preserve parsing, validation, comparison, and export behavior.
6. Add the smallest JavaScript presentation hooks needed to assign existing charts to categories and hide empty categories. Do not move data ownership into shared UI code.
7. Compare element IDs with the baseline and run JavaScript syntax checks.
8. Build and test the Kotlin application, run the HTTP smoke test, and inspect Dashboard and Compare at desktop, tablet, and mobile widths.
9. Exercise the available interactive states and inspect browser console/client logs.
10. Rebuild and launch the packaged macOS application.

## Guardrails

- No API, payload, metric, threshold, polling, CSV, or export changes.
- No frontend framework, bundler, or dependency.
- Existing uncommitted Home and build-script changes must be preserved.
- Generated reports are outside this phase.
- A failed compatibility check blocks packaging until corrected.

