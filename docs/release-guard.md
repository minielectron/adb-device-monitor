# Offline Performance Release Guard

**Current state:** implemented in the Studio UI and headless CLI. Release Guard executes repeatable
Android journeys, captures the same monitoring signals used by Studio, evaluates optional explicit
budgets, and writes either a local release decision or a neutral evidence-only result. It is
Android-device and player-SDK agnostic.

## Execution pipeline

1. **Discover** the selected ADB target.
2. **Preflight** the scenario and probe device capabilities.
3. **Prepare** project, scenario, optional APK and local artifact directories.
4. Run configured **warm-ups** without using them as measured results.
5. Run one to 100 measured **repetitions**, recording step/section markers and performance samples.
6. Finalize attempt artifacts and recover any usable partial evidence.
7. Evaluate absolute and optional baseline-relative budgets.
8. Export HTML, PDF, JSON, JUnit XML and CSV evidence.

Only one monitoring session, Player Debugger capture or Release Guard check can own the device
collector at a time.

## Capability-based device support

Release Guard probes the device instead of selecting behavior from manufacturer names. The probe
checks ADB shell, package install, UI hierarchy, touch/DPAD input, gfxinfo, Perfetto, PSI, thermal,
root, procrank, screenshot, battery, logcat and Android media/codec/audio/SurfaceFlinger diagnostics.

Every scenario step and metric declares its required capabilities. Missing required capability is a
configuration/preflight failure, not a passing measurement. This lets the same scenario model target
phones, tablets, TVs, STBs and emulators while making device restrictions explicit.

## Supported scenario steps

Scenarios are versioned JSON (`schemaVersion: 1`) and can install/uninstall an APK, clear data,
launch or force-stop the app, launch a package-scoped deep link, grant a permission, wait, begin/end a named section, press logical
keys, tap a selector or percentage coordinate, swipe, enter text, wait for an element/activity/log
pattern/process, capture a PNG screenshot, or run an explicit shell command.

A deep-link step accepts an absolute URI with a scheme, such as
`myapp://content/123?source=release-guard`. Release Guard validates it before execution, quotes it as
one shell argument, and runs `am start -W -a android.intent.action.VIEW -d <uri> <scenario-package>`.
The scenario package is mandatory and cannot be overridden by the step, so Android cannot fall back
to a handler in another application.

Element selectors can use resource ID, accessibility description or text, with an optional
percentage-coordinate fallback. UI hierarchy comes from `uiautomator`; input uses Android shell
input commands. A shell step is intentionally powerful and should be reviewed like any local test
script.

## Measurements and budgets

Measured attempts reuse `MonitorSession`, so metric sources and transformations are those documented
in [metrics.md](metrics.md). Step and section boundaries become timestamped scenario markers, which
allow a budget to evaluate either the whole attempt or one named section.

A budget selects a metric and aggregation: minimum, maximum, mean, median, P90, P95, count, rate or
growth percent. It can define:

- An absolute maximum or minimum
- A permitted percentage regression against a promoted baseline
- Critical severity, which fails the gate, or warning severity, which produces review status
- Whether missing/unusable data is required (fail) or optional (not evaluated)

Budgets are optional. A successful scenario with no budgets finishes as **Evidence only / Not
evaluated** rather than Pass, so the collected logs and measurements cannot be mistaken for release
approval. Any attempt or infrastructure failure still fails the run.

The engine evaluates only successful measured attempts. A baseline must match the same scenario ID
and revision. By default its device model, Android version and firmware must also match exactly.
`--allow-compatible-baseline` permits a different device only for metrics explicitly declared
normalized across devices.

## Local storage and artifacts

The default root is `~/ADB Device Monitor/ReleaseGuard/`; `--release-root` selects another local
root. It contains `release-guard.db` as an index plus project/scenario/run artifact folders. Each
leaf run folder is named `<scenario>_<UTC timestamp>_<short id>` so copied evidence remains
identifiable without opening its manifest. A run
directory is confined beneath the configured root and includes an atomic `run.json` manifest,
attempt directories and registered outputs.

Each measured attempt requires the device logcat capability. Release Guard clears the buffer when
capture starts, streams the complete unfiltered run log to `attempt-NN/logcat.log`, and stops the
stream during finalization even when a journey step fails before enough performance samples exist.
The log is registered as evidence and can be opened from the result screen. Release Guard also
builds a run-level `logcat.log` after each measured iteration. It contains every iteration in order
with explicit PASS/FAIL boundaries while retaining the original `attempt-NN/logcat.log` files.

Completed exports currently include `report.html`, `report.pdf`, `evaluation.json`,
`result.junit.xml`, `summary.csv`, and attempt evidence such as monitoring reports, samples and logs.
Scenario screenshot steps add requested PNG files. The manifest records artifact paths and final
state so incomplete or corrupt runs can be distinguished during recovery.

No cloud account, hosted database, analytics SDK, CDN or internet API is used. The UI and CLI read
the same local SQLite index and files.

## UI and API

Open **Release Guard** from Home to manage projects/scenarios, probe capabilities, run or cancel a
check, inspect results, promote a completed run with at least one successful attempt to baseline and
open registered artifacts. Each journey card can insert a new step directly above or below itself;
the empty journey state provides **Add first step**. Removing every budget enables evidence-only
mode without disabling the run action.

The local routes are `/api/release/projects`, `/api/release/scenarios`,
`/api/release/capabilities`, `/api/release/start`, `/api/release/stop`,
`/api/release/status`, `/api/release/result`, `/api/release/baseline` and
`/api/release/artifact`.

## Headless CI

```bash
java -jar build/libs/adb-device-monitor-1.0.0-all.jar \
  --release-guard ./scenarios/cold-launch.json \
  --device emulator-5554 \
  --release-root ./release-guard-results \
  --build-label commit-abc123 \
  --release-json
```

`--release-guard` accepts a scenario JSON file or a saved scenario ID in the same root.
`--release-json` keeps machine output to the absolute evaluation JSON path while progress is written
to stderr.

| Exit code | Meaning |
|---:|---|
| `0` | All evaluated budgets pass. |
| `2` | At least one warning budget needs review. |
| `3` | A release-blocking budget fails. |
| `4` | Scenario, capability or CLI configuration is invalid. |
| `5` | ADB, capture, storage, export or cancellation infrastructure fails. |
| `6` | Evidence was collected without performance budgets; no release decision was made. |

## Limitations and evidence rules

- Results are only comparable when the journey, build state and device conditions are controlled.
- UI selectors depend on the hierarchy exposed by the app/OEM; secure and custom-rendered surfaces
  can require coordinate or log/activity checks.
- A successful automation step does not prove visual correctness. Add an explicit screenshot or
  measurable wait/budget when that evidence matters.
- Shell commands and screenshots can contain sensitive project or user data and remain local raw
  evidence; Release Guard does not apply Player Debugger's support-bundle redaction policy.
- Unsupported metrics are unavailable or fail a required budget. They are never replaced with a
  vendor-specific guess.
