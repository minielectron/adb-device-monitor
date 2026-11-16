# Offline Performance Release Guard Design

## Summary

ADB Performance Studio will add an Offline Performance Release Guard for Android development and QA teams. It turns the existing monitoring engine into a repeatable release-check workflow: prepare an application, execute a portable scenario, collect performance evidence, compare the result with explicit budgets and an optional known-good baseline, and produce a release-ready verdict.

The feature remains fully offline. It requires no account, cloud service, CDN, telemetry endpoint, or network-based license check. It targets Android phones, tablets, TVs, set-top boxes, emulators, Fire OS devices, and custom OEM builds that expose ADB. Device behavior is selected through runtime capabilities rather than manufacturer or model checks.

## Goals

- Give developers and QA a repeatable performance release gate with Pass, Warning, and Fail outcomes.
- Detect performance regressions before a build is approved.
- Preserve evidence for every outcome: samples, logs, screenshots, traces, and calculations.
- Run on different Android device types without hard-coded model rules.
- Work interactively in the desktop UI and non-interactively from CI or local scripts.
- Extend the current collector, dashboard, comparison, diagnostics, and reporting features without breaking them.
- Preserve unfinished form and scenario edits when navigating within the application.

## Non-goals for the first release

- iOS support.
- Cloud storage, remote accounts, hosted dashboards, or internet-dependent analysis.
- A public multi-host device farm.
- Automatic source-code modification.
- Claiming that raw performance numbers from unlike hardware are directly comparable.

## Architecture

The Release Guard orchestrates the existing `MonitorSession`; it does not duplicate metric collection.

```text
Studio UI
   |
ReleaseGuardService
   |-- ScenarioRunner -------- DeviceDriver
   |                              `-- AdbDeviceDriver
   |-- MonitorSession -------- Existing collectors
   |-- EvaluationEngine ------ Budgets and baselines
   `-- RunRepository --------- SQLite index and run artifacts
```

New packages follow the current dependency direction:

```text
domain/releaseguard/
  Project.kt
  Scenario.kt
  ScenarioStep.kt
  PerformanceBudget.kt
  Baseline.kt
  Evaluation.kt

application/releaseguard/
  ReleaseGuardService.kt
  ScenarioRunner.kt
  EvaluationEngine.kt
  BaselineService.kt

data/device/
  DeviceDriver.kt
  AdbDeviceDriver.kt
  CapabilityProbe.kt

data/storage/
  RunRepository.kt
  SqliteRunRepository.kt
  ArtifactStore.kt

presentation/web/
  ReleaseGuardPage.kt
  ScenarioEditorPage.kt
  ReleaseResultPage.kt
```

Domain models remain immutable and independent of I/O. Application services coordinate use cases. Device and storage adapters implement external operations. The web layer exposes the UI and loopback API. `Main.kt` remains the composition root.

## Device-independent execution

Device independence means independence from Android form factor, manufacturer, screen size, input method, root status, and USB or TCP transport. The first release remains Android/ADB-specific.

Every target is represented as:

```kotlin
data class TargetDevice(
    val id: String,
    val platform: Platform,
    val transport: Transport,
    val formFactor: FormFactor?,
    val properties: Map<String, String>,
    val capabilities: Set<DeviceCapability>
)
```

`CapabilityProbe` tests observable behavior such as package installation, UI hierarchy inspection, touch input, DPAD input, gfxinfo, Perfetto, PSI, thermal nodes, root/procrank, screen capture, battery statistics, and playback diagnostics. Production code must not use manufacturer or model-name checks to enable behavior.

Metric providers and scenario steps declare required capabilities. Unsupported optional metrics are reported as Not available. A missing required capability fails preflight before the measured run begins.

Scenario selectors use this priority:

1. Resource ID.
2. Accessibility description.
3. Visible text.
4. Normalized screen coordinate as an explicit fallback.

Touch actions use screen percentages rather than fixed pixels. TV and STB flows use logical DPAD and key actions through the same runner. Device templates may provide defaults but cannot bypass capability checks.

## Scenario model

Scenarios are versioned, validated, and portable. Their persisted representation includes `schemaVersion`, identity, package, repetition and warm-up settings, preparation rules, ordered steps, capture settings, and budgets.

The initial sealed `ScenarioStep` hierarchy supports:

- install and uninstall APK;
- clear application data;
- launch and force-stop package;
- grant permissions;
- wait for a duration;
- begin and end a named measured section;
- press a logical key or DPAD action;
- find and tap a semantic UI selector;
- percentage-based tap and swipe;
- enter text;
- wait for an activity, UI element, log pattern, or process;
- capture a screenshot; and
- execute a clearly labelled advanced ADB shell command.

Unknown step types produce a schema compatibility error. Invalid selectors, duplicate section names, unmatched section boundaries, impossible timeouts, and capability conflicts are rejected before execution.

## Execution state machine

Each release check follows:

```text
DISCOVER
  -> PREFLIGHT
  -> PREPARE
  -> WARM_UP
  -> EXECUTE_AND_CAPTURE
  -> FINALIZE_ARTIFACTS
  -> EVALUATE
  -> EXPORT
  -> PASSED | WARNING | FAILED | CANCELLED
```

- **Discover:** resolve the selected device, scenario revision, application build, and baseline.
- **Preflight:** verify connectivity, free host storage, package or APK validity, required capabilities, and selectors that can be checked safely.
- **Prepare:** optionally install the APK, clear data, grant permissions, reset logs, and stop the package.
- **Warm up:** execute configured unmeasured repetitions.
- **Execute and capture:** start `MonitorSession`, execute the scenario, and add named step and section markers to its timeline.
- **Finalize artifacts:** stop collectors, pull traces, close logs, and preserve partial evidence.
- **Evaluate:** aggregate successful repetitions and apply budgets and baseline rules.
- **Export:** write the result formats and final manifest.

An attempt failure does not discard successful attempts. Cancellation and device loss still finalize partial artifacts. The run manifest is updated atomically after every state transition so an interrupted process can be recovered as an incomplete run after restart.

## Budgets, baselines, and verdicts

A performance budget identifies a metric, optional measured section, aggregation, optional absolute limit, optional relative regression limit, severity, and whether the metric is required.

Supported aggregations include minimum, maximum, mean, median, p90, p95, count, rate, and start-to-end growth. Examples include:

- cold startup p90 at or below 1,500 ms;
- janky frames at or below 5 percent;
- application PSS median at or below 350 MB;
- CPU p95 at or below 75 percent;
- memory growth at or below 10 percent during a soak section;
- zero ANRs, fatal exceptions, or native crashes; and
- startup regression no greater than 15 percent from baseline.

Evaluation uses aggregated values across repetitions. Each calculation records its input attempts, sample count, aggregation, current value, limit, baseline value, delta, and result.

Absolute budgets may be applied to any device meeting the scenario capability contract. Relative baselines match the same model, Android version, and firmware by default. An explicitly configured compatible-device baseline may compare only normalized metrics or absolute budgets. The UI must not imply that unlike hardware has equivalent raw performance.

A critical budget violation yields Fail. A noncritical violation yields Warning. Missing optional metrics yield Not evaluated. Missing required metrics fail preflight. A run passes only when every evaluated critical budget passes and no execution failure invalidates the result.

Baselines are immutable revisions. Promoting a newer run creates a new active revision without deleting earlier baseline evidence.

## Offline storage and recovery

Embedded SQLite indexes projects, scenario versions, devices, test runs, attempts, budgets, baselines, evaluations, and artifact locations. Large artifacts remain in recoverable folders:

```text
ReleaseGuard/
  <project>/
    <scenario>/
      <run-id>/
        run.json
        evaluation.json
        report.html
        report.pdf
        result.junit.xml
        attempt-01/
          samples.csv
          logcat.log
          trace.perfetto-trace
          screenshots/
```

The database is an index rather than the sole source of truth. Every run folder contains enough versioned JSON to reopen or rebuild its index. Metadata files use write-to-temporary-file followed by an atomic rename.

## UI design

All new screens reuse `STUDIO_UI_CSS`: the current dark surfaces, typography, app bar, status indicators, gold primary actions, violet and teal accents, motion timing, focus treatment, responsive layout, and reduced-motion behavior.

The Release Guard workspace contains project and scenario cards, recent verdicts, baseline status, and result trends. The scenario editor contains device and APK configuration, an ordered step list, performance-budget cards, a capability/readiness panel, and a Run Release Check action.

Live execution embeds the existing charts and adds the current state, repetition, active step, scenario timeline, and Stop action. Returning between Studio screens must not reset unsaved scenario or Home configuration. Draft editor state is held independently from route visibility and persisted locally until saved or explicitly discarded.

The result screen leads with Release Ready, Review, or Blocked; lists regressions before improvements; overlays baseline and current metrics; and links every finding to its timeline region, log evidence, screenshot, or trace. Exports are available from the same screen and render in the existing in-application report viewer.

## API and CLI surfaces

The loopback web service adds endpoints for projects, scenarios, validation, capabilities, release-check start/stop/status, results, baseline promotion, and exports. Mutating endpoints accept POST requests, validate identifiers and paths, and return structured errors. Artifact endpoints serve only files registered under the Release Guard storage root.

The CLI accepts a scenario file or stored scenario ID, a device selector, an optional APK/build label, an output directory, and an export selection. It exits with a stable code for Pass, Warning, Fail, invalid configuration, or infrastructure error. JSON and JUnit XML allow Jenkins, GitHub Actions runners, GitLab runners, and local scripts to consume results without an internet connection.

## Failure handling

- Device disconnection pauses the current attempt for a configurable reconnection window.
- Reconnection resumes only for the same device identity and only at a safe step boundary.
- A different device never silently replaces the selected target.
- Existing ADB timeouts and abort behavior remain in force.
- Stop finalizes partial output and marks unfinished evaluations clearly.
- Application restart recovers interrupted manifests as incomplete runs.
- Database corruption does not destroy run folders; the index can be rebuilt.
- Unsupported capabilities and missing required data are reported explicitly.

## Verification

- Unit tests cover scenario validation, budget evaluation, baseline matching, percentiles, verdict rules, and state transitions.
- Fake-ADB integration fixtures cover phone, tablet, TV/STB, emulator, unsupported capabilities, timeouts, and disconnections.
- Golden tests cover scenario JSON, run manifests, evaluation JSON, JUnit XML, and offline HTML reports.
- Web regression tests cover scenario editing, unsaved-state preservation, live execution, result rendering, CSP, and bundled assets.
- Existing Home, Dashboard, Compare, diagnostic, snapshot, report, CLI, and stop flows remain covered.
- Release verification includes one real touch device and one real DPAD device when available.

## Delivery sequence

1. Domain models, schema validation, budgets, and evaluation engine.
2. SQLite repository, artifact store, and recovery manifests.
3. Capability-driven device driver.
4. Scenario runner and execution state machine.
5. Loopback API and CLI surfaces.
6. Release Guard workspace and scenario editor.
7. Live execution integration and result screen.
8. JSON, JUnit, HTML, and PDF exports.
9. Fake-device regression suite and real-device verification.

Each stage must preserve the current application's behavior and remain usable without internet access.
