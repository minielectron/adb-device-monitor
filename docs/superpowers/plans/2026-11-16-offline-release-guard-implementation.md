# Offline Performance Release Guard Implementation Plan

## Goal

Add a production-ready, fully offline performance release gate to ADB Performance Studio. A developer or QA engineer can define a portable Android scenario, execute it on any capability-compatible ADB device, collect the existing performance signals, evaluate explicit budgets and an optional baseline, and receive a recoverable Pass, Warning, or Fail result with local evidence and CI exports.

## Constraints

- Work on `feature/release-guard`.
- Preserve Home, Live Dashboard, Compare, diagnostics, snapshot reports, CLI monitoring, and macOS packaging.
- Reuse `MonitorSession` for metric capture and `STUDIO_UI_CSS` for presentation.
- Keep runtime operation fully offline and bind the desktop service to loopback.
- Select behavior through probed capabilities rather than manufacturer or model-name branches.
- Keep every run recoverable from its artifact folder even when the SQLite index is unavailable.
- Preserve unsaved Home and scenario-editor state during in-application navigation.
- Add tests before or with each behavior change and keep the application buildable after every milestone.

## Milestone 1: Release-guard domain and evaluation engine

### Task 1: Add immutable domain models

**Create**

- `src/main/kotlin/devicemonitor/domain/releaseguard/ReleaseGuardModels.kt`
- `src/test/kotlin/devicemonitor/ReleaseGuardModelTest.kt`

**Implement**

- Stable value types and enums for project, scenario revision, target device, capabilities, selector, scenario step, measured section, budget, baseline identity, attempt summary, evaluation, finding, verdict, and execution state.
- A sealed `ScenarioStep` hierarchy covering the approved initial actions.
- Schema version constants and stable string identifiers for persisted enum values.
- Validation that rejects blank IDs, invalid timeouts, unmatched section markers, duplicate section names, selector-less semantic actions, invalid percentages, impossible repetitions, empty scenarios, and budgets without a limit.
- Capability requirements derived from steps and enabled metrics.

**Test**

- Valid touch and DPAD scenarios.
- Every validation failure class.
- Capability derivation.
- Forward-incompatible schema rejection.

### Task 2: Implement deterministic metric evaluation

**Create**

- `src/main/kotlin/devicemonitor/application/releaseguard/EvaluationEngine.kt`
- `src/test/kotlin/devicemonitor/ReleaseGuardEvaluationTest.kt`

**Implement**

- Minimum, maximum, mean, median, p90, p95, count, rate, and start-to-end growth aggregations.
- Absolute maximum/minimum budgets and relative baseline regression budgets.
- Required versus optional metric behavior.
- Critical and noncritical findings.
- Pass, Warning, Fail, and Not evaluated outcomes.
- Calculation evidence containing sample count, contributing attempts, current value, limit, baseline, and delta.
- Strict default baseline identity matching by model, Android version, firmware, scenario revision, and metric definition.
- Compatible-device baseline checks limited to normalized metrics.

**Test**

- Percentile interpolation and small-sample behavior.
- Boundary equality.
- Improvement, acceptable regression, warning, and critical failure.
- Missing optional and required metrics.
- Same-device and incompatible-device baseline selection.
- Multiple repetitions with a failed attempt excluded but reported.

## Milestone 2: Portable schemas, artifacts, and SQLite index

### Task 3: Add versioned JSON serialization

**Modify**

- `build.gradle.kts`

**Create**

- `src/main/kotlin/devicemonitor/data/storage/ReleaseGuardJson.kt`
- `src/test/kotlin/devicemonitor/ReleaseGuardJsonTest.kt`

**Implement**

- Add a Kotlin-compatible JSON serialization dependency and pin its version.
- Encode/decode project, scenario, run manifest, attempt, evaluation, and baseline records.
- Reject unknown schema versions with a diagnostic that includes the supported version.
- Preserve unknown optional metadata fields when reopening and rewriting an artifact where practical.
- Write canonical JSON for stable golden tests and readable diffs.

**Test**

- Round trips for every step type.
- Golden scenario, manifest, and evaluation files.
- Invalid JSON and unsupported schema versions.
- Paths and text containing Unicode and spaces.

### Task 4: Add the recoverable artifact store

**Create**

- `src/main/kotlin/devicemonitor/data/storage/ArtifactStore.kt`
- `src/test/kotlin/devicemonitor/ReleaseGuardArtifactStoreTest.kt`

**Implement**

- Sanitized project/scenario directory segments and collision-resistant run IDs.
- Approved folder layout with one folder per attempt.
- Atomic metadata writes through a sibling temporary file and atomic move fallback.
- Root containment checks for every artifact read or write.
- Recovery scan that identifies complete, incomplete, and corrupt manifests without deleting evidence.
- Registration of existing `MonitorSession` outputs under an attempt.

**Test**

- Normal creation and recovery.
- Path traversal, absolute-path injection, symlink escape where supported, interrupted temporary file, and corrupt manifest.
- Two runs started in the same millisecond.

### Task 5: Add the embedded SQLite repository

**Modify**

- `build.gradle.kts`

**Create**

- `src/main/kotlin/devicemonitor/data/storage/RunRepository.kt`
- `src/main/kotlin/devicemonitor/data/storage/SqliteRunRepository.kt`
- `src/test/kotlin/devicemonitor/SqliteRunRepositoryTest.kt`

**Implement**

- Add and pin the SQLite JDBC dependency.
- Create idempotent schema migrations for projects, scenarios, scenario revisions, devices, runs, attempts, budgets, baselines, evaluations, and artifacts.
- Use transactions for scenario revisions, baseline promotion, and finalized evaluations.
- Enforce immutable scenario and baseline revisions.
- List/filter runs without reading every large artifact.
- Rebuild missing index rows from artifact manifests.
- Store paths relative to the Release Guard root.

**Test**

- Fresh database, repeated migration, CRUD, revision immutability, transaction rollback, active baseline replacement, query ordering, and rebuild from artifacts.

## Milestone 3: Capability-driven device automation

### Task 6: Add the device-driver boundary and capability probe

**Create**

- `src/main/kotlin/devicemonitor/data/device/DeviceDriver.kt`
- `src/main/kotlin/devicemonitor/data/device/AdbDeviceDriver.kt`
- `src/main/kotlin/devicemonitor/data/device/CapabilityProbe.kt`
- `src/test/kotlin/devicemonitor/CapabilityProbeTest.kt`

**Modify**

- `src/main/kotlin/devicemonitor/data/adb/Adb.kt`

**Implement**

- Device identity, properties, transport, inferred form factor, and observable capabilities.
- Bounded driver operations for install, uninstall, clear data, force-stop, launch, permissions, key events, text, normalized tap/swipe, UI hierarchy, screenshot, process/activity/log waits, and advanced shell.
- UI hierarchy parsing for resource ID, accessibility description, and visible text selectors.
- Logical key mapping shared by touch and DPAD devices.
- Screen-size normalization at execution time.
- Capability caching with an explicit refresh and short failure lifetime.
- No manufacturer or model-name feature branches.

**Test**

- Fake ADB fixtures for phone, tablet, emulator, TV/STB, root/non-root, missing gfxinfo, missing Perfetto, timeout, unauthorized, offline, and disconnect/reconnect.
- Selector priority and ambiguous selector errors.
- Coordinate normalization at multiple resolutions and orientations.
- Command argument handling for spaces and special text.

## Milestone 4: Execution state machine and monitor integration

### Task 7: Add scenario markers to monitoring output

**Modify**

- `src/main/kotlin/devicemonitor/domain/MetricModels.kt`
- `src/main/kotlin/devicemonitor/application/Session.kt`
- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`
- `src/main/kotlin/devicemonitor/presentation/report/Report.kt`
- related session/report tests

**Implement**

- Thread-safe scenario markers with elapsed time, attempt, step, section, type, and outcome.
- A monotonic session start reference for deterministic marker placement.
- Marker serialization in live session JSON and CSV or companion JSON.
- Marker rendering on live and final timelines without changing existing zap/anomaly behavior.

**Test**

- Concurrent marker writes.
- Stable elapsed ordering.
- Existing sessions with no markers.
- Report generation with step, section, zap, and anomaly markers together.

### Task 8: Implement `ScenarioRunner`

**Create**

- `src/main/kotlin/devicemonitor/application/releaseguard/ScenarioRunner.kt`
- `src/main/kotlin/devicemonitor/application/releaseguard/ReleaseGuardService.kt`
- `src/test/kotlin/devicemonitor/ScenarioRunnerTest.kt`

**Implement**

- DISCOVER, PREFLIGHT, PREPARE, WARM_UP, EXECUTE_AND_CAPTURE, FINALIZE_ARTIFACTS, EVALUATE, EXPORT, and terminal states.
- Atomic state persistence after every transition.
- Warm-ups and measured repetitions.
- Step timeouts, attempt-level failure, cancellation, and partial-artifact finalization.
- Device reconnect wait with same-identity verification and safe-boundary resume.
- Existing `MonitorSession` construction from scenario capture settings.
- Evaluation and repository finalization after capture.
- Observable immutable status snapshots for web and CLI consumers.

**Test**

- Successful touch and DPAD flows.
- Preflight rejection before capture.
- Failed warm-up, failed measured step, cancellation, monitor failure, device reconnect, wrong-device reconnect, export failure, and process-restart recovery.
- Successful attempts retained when another repetition fails.

## Milestone 5: Loopback API and consistent Studio UI

### Task 9: Add release-guard API routes

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`

**Create**

- `src/test/kotlin/devicemonitor/ReleaseGuardApiTest.kt`

**Implement**

- Project and scenario list/read/save endpoints.
- Scenario validation and device-capability endpoints.
- Start, stop, status, result, baseline promotion, and artifact endpoints.
- Structured error objects with stable error codes.
- POST-only mutations and request-size limits.
- Identifier validation, artifact-root containment, and no arbitrary file serving.
- Concurrency rule: one measured capture per physical device while read-only history remains available.

**Test**

- Success and validation errors for every endpoint.
- Unsupported methods, malformed payloads, oversized payloads, traversal attempts, unknown IDs, and concurrent start conflict.
- Existing endpoint regression coverage.

### Task 10: Build the Release Guard workspace and scenario editor

**Create**

- `src/main/kotlin/devicemonitor/presentation/web/ReleaseGuardPage.kt`

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/HomePage.kt`
- `src/main/kotlin/devicemonitor/presentation/web/StudioUi.kt`
- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`
- `src/test/kotlin/devicemonitor/OfflineAssetsTest.kt`

**Implement**

- Add Release Guard navigation without changing existing element IDs or flows.
- Reuse current app bar, panels, status dots, buttons, spacing, palette, focus treatment, entry motion, responsive rules, and reduced-motion behavior.
- Project/scenario cards, recent results, active baseline, and compact trend summary.
- Scenario editor for application, device, warm-up/repetition, ordered steps, metric capture, and budgets.
- Capability/readiness panel with required, available, and unavailable explanations.
- Reorder, duplicate, disable, and remove steps with keyboard-accessible controls.
- Persist unsaved drafts locally by project/scenario draft ID and restore them after navigation or restart.
- Explicit discard with confirmation; successful save clears only the matching draft.

**Test**

- Bundled assets and CSP remain offline.
- Required controls, labels, focus order, and reduced-motion CSS.
- Draft preservation and isolation between scenarios.
- Mobile width has no horizontal overflow.
- Current Home state remains unchanged after visiting Release Guard.

### Task 11: Add live execution and result views

**Create**

- `src/main/kotlin/devicemonitor/presentation/web/ReleaseResultPage.kt`

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/DashboardPage.kt`
- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`
- `src/test/kotlin/devicemonitor/OfflineAssetsTest.kt`

**Implement**

- Live state, attempt/repetition, active step, section, elapsed time, and reconnect status.
- Scenario markers on existing live charts and event feed.
- Stop behavior that preserves partial evidence.
- Release Ready, Review, or Blocked verdict using existing green, amber, and red semantics.
- Regressions before improvements, budget evidence, baseline overlay, attempt table, and links to registered evidence.
- In-app offline report viewer integration for HTML results.
- Retry only from a well-defined starting boundary; never silently resume a destructive step.

**Test**

- Polling survives a failed chart or unavailable metric.
- Correct verdict and finding order.
- Evidence links cannot escape the run root.
- Navigation preserves editor and Home drafts.

## Milestone 6: Exports and headless execution

### Task 12: Add offline result exporters

**Create**

- `src/main/kotlin/devicemonitor/presentation/report/ReleaseGuardReport.kt`
- `src/main/kotlin/devicemonitor/presentation/report/JunitExporter.kt`
- `src/test/kotlin/devicemonitor/ReleaseGuardExportTest.kt`

**Implement**

- Self-contained HTML using inline SVG and bundled styling.
- PDF using the existing PDFBox/JFreeChart stack.
- Canonical evaluation JSON.
- JUnit XML with one test case per budget plus infrastructure failures.
- CSV summary suitable for spreadsheets.
- Redaction of configured sensitive fields from exported logs and metadata.

**Test**

- Golden JSON/JUnit/HTML output.
- HTML contains no remote URL and renders without JavaScript where possible.
- PDF opens and contains the verdict, device identity, baseline, findings, and evidence summary.
- XML escaping and CI failure semantics.

### Task 13: Add Release Guard CLI mode

**Modify**

- `src/main/kotlin/devicemonitor/Main.kt`
- `README.md`
- `docs/build-run-package.md`

**Create**

- CLI integration tests using `scripts/fake-adb.sh`

**Implement**

- `--release-guard <scenario-file-or-id>` plus device, APK, build label, storage root, and export options.
- Stable exit codes for Pass, Warning, Fail, invalid configuration, and infrastructure failure.
- Human-readable progress on stderr and machine-readable final JSON path on stdout when requested.
- Help text and offline CI examples.

**Test**

- Every exit code and malformed invocation.
- Fake-device pass, warning, failure, cancellation, and missing capability.

## Milestone 7: Regression, packaging, and real-device verification

### Task 14: Run the complete automated suite

**Run**

- `./gradlew test fatJar`
- `cargo check --manifest-path desktop/src-tauri/Cargo.toml`
- existing smoke tests with `scripts/fake-adb.sh`

**Verify**

- No remote asset references.
- Existing API contracts and report generation remain intact.
- Release Guard storage remains within the configured root.
- Fat JAR launches with a clean temporary home and no internet access.

### Task 15: Verify the desktop application

**Run**

- `desktop/build-macos.sh`
- launch the newly built application bundle

**Verify**

- Home, Dashboard, Compare, diagnostics, and report viewer.
- Release Guard project creation, scenario draft recovery, capability preflight, live run, evaluation, baseline promotion, and exports.
- Desktop and 390-pixel responsive widths.
- No browser popup for internal reports.
- No console errors or unexpected external requests.

### Task 16: Verify device independence

**Verify when hardware is available**

- One touch device or emulator using semantic selectors and normalized gestures.
- One TV/STB or DPAD emulator using logical key actions.
- One capability-limited fixture where unsupported optional metrics are Not evaluated.
- Same scenario schema opens on each target; only capability-incompatible steps are rejected.
- Relative baseline comparison refuses incompatible hardware unless explicitly limited to normalized metrics.

## Commit strategy

Use focused commits after green tests:

1. `Add Release Guard domain and evaluation engine`
2. `Add recoverable Release Guard storage`
3. `Add capability-driven device automation`
4. `Add Release Guard scenario execution`
5. `Add Release Guard API and Studio workflow`
6. `Add offline Release Guard exports and CLI`
7. `Verify Release Guard desktop integration`

Do not push or merge the feature branch until the implementation and verification results have been reviewed.
