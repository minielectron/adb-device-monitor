# Release Guard Background Diagnostics Design

## Goal

Run a Release Guard scenario as a single background job that survives page navigation, restores its live UI when the user returns, and captures the default performance diagnostics concurrently during every measured iteration.

## Chosen approach

Reuse the existing `ReleaseGuardService`, `ScenarioRunner`, and `MonitorSession` pipeline. The service remains the source of truth for whether a run is active; the browser page only starts, stops, and polls that job. This avoids a second capture engine and keeps Release Guard reports compatible with existing dashboards and artifacts.

Two alternatives were rejected:

- Copying the complete Player Debugger form would duplicate package, target, and duration fields already defined by the Release Guard project, target, and journey.
- Always enabling every collector would add avoidable device load and would fail inconsistently on devices without the required capabilities.

## Background run and navigation state

- Starting a release check creates one service-owned background job. Navigating away must not cancel or replace it.
- `/api/release/status` is the authoritative state. Every Release Guard page load polls it immediately.
- When a job is running, the page restores its phase, attempt, active journey step, completed steps, and Stop action without starting another run.
- Run is disabled from the first start request until the service reports that the job is no longer running. A second start request remains rejected by the server as a safety backstop.
- Stop requests cancellation at the existing safe boundary. The button becomes available again only after status confirms the job stopped or reached a terminal result.
- Draft project, scenario, steps, budgets, diagnostics, and sample interval remain in local storage while navigating.
- A completed manifest remains viewable after returning to the page.
- Device ownership remains exclusive: Release Guard can run while the user navigates, but another capture job cannot use the same application monitor concurrently.

## Capture configuration

Add a compact Capture configuration section to the Release Guard gate. It reuses the selected ADB target and the project package instead of duplicating them. Scenario steps determine capture duration.

The following diagnostics are enabled by default and can be toggled independently:

1. CPU utilization (`cpu_util_pct`)
2. Per-core CPU (`per_core_cpu_pct`)
3. Top process CPU (`top_process_cpu_pct`)
4. Memory used (`memory_used_mb`)
5. PSI pressure stall (`psi_cpu`, `psi_memory`, and `psi_io`)
6. Top process memory (`top_process_memory_mb`)

Logcat remains required and always enabled. The sample interval supports 0.5, 1, and 2 seconds and defaults to 1 second.

`CaptureSettings` stores the selected metric definitions and interval. Existing scenarios with empty capture settings receive the six defaults in the UI when loaded, preserving backward compatibility while making newly saved revisions explicit.

## Capability behavior

- The selected device is probed before running.
- PSI is marked as root-required. When the target does not expose PSI, its option is disabled and excluded from saved capture metrics for that run.
- CPU temperature is not part of this default set.
- CPU utilization, per-core CPU, top process CPU, memory used, and top process memory remain available without root.
- Readiness includes capabilities required by the selected diagnostics, journey steps, and budgets.

## Parallel data flow

`ScenarioRunner` translates capture metric IDs into one `SessionConfig`. `MonitorSession` then starts its existing collectors together for the measured attempt. No collector runs sequentially with the journey and no separate Player Debugger session is created.

Each measured iteration follows this flow:

1. Create the attempt capture session with the saved interval and selected diagnostic flags.
2. Start all enabled collectors and required logcat capture.
3. Execute journey steps while collectors sample in the background.
4. Stop and finalize collectors after the journey.
5. Store samples, reports, step markers, and iteration logcat in the existing report folder.

Warm-up iterations continue to execute journey steps without measured capture.

## Error handling

- A start failure clears the page's optimistic busy state and displays the server error.
- Temporary status polling failures keep the Run button locked while a known job is active and retry polling.
- Missing required capabilities block the run with a readable readiness item.
- Cancellation and failed steps retain completed evidence and restore a terminal result state.
- Stale polling responses cannot overwrite a newer run state.

## Verification

- Unit-test capture metric and interval mapping into `SessionConfig`.
- Unit-test live progress across warm-up and measured steps.
- Verify legacy empty capture settings receive UI defaults.
- Verify PSI is disabled for a non-root target without disabling non-root diagnostics.
- Verify Run is disabled during a restored active job and enabled only after completion or confirmed stop.
- Verify the embedded page JavaScript parses successfully.
- Run the project test suite and macOS build.
