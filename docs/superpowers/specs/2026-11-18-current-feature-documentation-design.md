# Current Feature Documentation Design

**Date:** 2026-11-18
**Status:** Approved structure, pending content implementation

## Goal

Document the application as it exists today so Dev and QA users can understand what every top-level
feature does, where its data comes from, what artifacts it creates, which permissions it needs, how
it behaves offline, and what it cannot prove.

The documentation must describe implemented behavior from the Kotlin, Tauri and build sources. It
must not present an earlier design proposal as a shipped feature.

## Documentation structure

Use a layered structure instead of duplicating full technical explanations in `README.md`.

### Product overview

Update `README.md` with a compact current-state feature matrix covering:

- Home and monitoring-run configuration
- Live Dashboard
- Reports and Run Comparison
- Device Snapshot
- Procrank Snapshot
- Layout Inspector
- Player Debugger
- Release Guard
- Diagnostics support bundle
- CLI and native macOS application

For every feature, state its current implementation status, primary data sources, offline behavior,
main output, and important limitation. Link each entry to the authoritative detailed document.

### New focused documents

Add these technical references:

- `docs/layout-inspector.md`: capture pipeline, hierarchy and screenshot sources, coordinate mapping,
  element selection, refresh behavior, report format, security boundaries, and Android limitations.
- `docs/diagnostics.md`: distinguish the Home Quick Diagnostics tools from the navigation-level
  application Diagnostics bundle; list exact archive contents and exclusions.
- `docs/release-guard.md`: capability probing, scenario execution, repetitions, baselines, budget
  evaluation, evidence artifacts, offline behavior, and CI exit codes.

### Existing documents

Update the existing references rather than replacing them:

- `docs/player-debugger.md`: add a source/acquisition matrix for logcat, OPTD/1, media dumps,
  SurfaceFlinger, `/proc`, and sampled metrics. Explain what each source can prove and when it is
  absent or inferred.
- `docs/device-and-procrank-snapshot.md`: document exact commands, source selection, fallbacks,
  root expectations, asynchronous API flow, and generated files.
- `docs/web-dashboard.md`: list all current pages and relevant APIs, including Player Debugger,
  Release Guard, Layout Inspector and Diagnostics.
- `docs/reports-and-comparison.md`: verify current report artifacts, comparison inputs and offline
  dependencies.
- `docs/metrics.md`: keep the metric reference authoritative and correct any source/default-status
  drift discovered during the audit.
- `docs/architecture.md`: include the current Layout Inspector, Player Debugger and Release Guard
  pipelines without duplicating their detailed references.
- `docs/desktop-app.md`: describe the embedded local web application, in-app HTML report viewing,
  and network independence accurately.
- `docs/build-run-package.md`: document the current build entry points, diagnostics archive and
  generated locations.
- `docs/README.md`: index every current feature document and provide a short navigation guide.

## Data-source documentation rule

Every user-visible value must be described using the closest stable source that explains it:

1. Name the Android or host source, such as `/proc/stat`, `dumpsys meminfo`, logcat, OPTD/1,
   `uiautomator dump`, `screencap`, stored CSV, JVM runtime state, or a generated artifact.
2. State whether the value is directly measured, parsed, reported by the app, or inferred.
3. State the important transformation, such as a counter delta, aggregation, fallback, rule
   evaluation, or coordinate scaling.
4. State the failure behavior. Unreadable or unsupported sources must be documented as unavailable,
   partial, or fallback data rather than silently equivalent data.

Code identifiers and API routes may be included when they help contributors verify behavior, but the
primary explanation must remain readable by Dev and QA users.

## Diagnostics terminology

The documentation must keep these concepts separate:

- **Quick Diagnostics:** device-facing, independent one-shot tools on Home: Device Snapshot,
  Procrank Snapshot, and Layout Inspector.
- **Diagnostics:** a host/application troubleshooting ZIP downloaded from the top navigation or Live
  Dashboard. It contains `info.txt`, `threads.txt`, up to 3 MiB of `adb-monitor.log`, and, when
  available, up to 3 MiB of `run.log`.

The Diagnostics ZIP must explicitly be documented as not including a device screenshot, hierarchy
XML, full device logcat, Player Debugger capture, Release Guard evidence, or general run artifacts.

## Offline and privacy claims

Only claim full offline behavior when runtime assets and analysis are bundled or local. Distinguish
local loopback HTTP used inside the desktop application from internet access. Document when raw logs,
device identifiers, screenshots, hierarchy text, or other potentially sensitive evidence remains on
the workstation, and distinguish raw capture folders from sanitized Player Debugger exports.

## Validation

The documentation change is complete when:

- Every top-level feature appears in the README matrix and documentation index.
- Layout Inspector, Diagnostics and Release Guard have focused current-state documents.
- Player Debugger has an exact acquisition/source matrix.
- Quick Diagnostics and the Diagnostics ZIP cannot reasonably be confused.
- Commands, routes, filenames, limits, fallbacks and exit codes agree with the current source.
- Internal Markdown links resolve.
- `git diff --check` passes.
- No application code or runtime behavior is changed by this documentation-only task.

## Out of scope

- New runtime features or collector changes
- Marketing claims, pricing, licensing or launch copy
- Reformatting historical implementation plans and design specifications
- External publishing systems such as a hosted documentation site or Confluence
