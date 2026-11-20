# Release Guard Scenario-Level Report Design

## Goal

Release Guard must capture and explain the complete measured scenario run, not produce a separate performance analysis for every repetition. Repetitions remain useful for statistical confidence and budget evaluation, but the primary evidence is one run-level package.

## Capture lifecycle

- Warm-ups execute before monitoring and remain excluded from measured evidence.
- One `MonitorSession` starts immediately before measured repetition 1.
- The session stays active across all measured repetitions, including waits between journey steps and repetition boundaries.
- The session stops after the final measured repetition, cancellation, or an unrecoverable capture failure.
- Journey markers retain repetition and step identifiers so budgets and graph normalization can split the continuous sample stream internally.
- A failed journey repetition records its error and boundary, then subsequent configured repetitions may continue while the capture session remains healthy.

This replaces the current model that creates and finalizes one monitor session per repetition.

## Run-level artifact contract

Measured repetitions do not produce separate analysis artifacts or result actions. Their boundaries
exist only for normalization and budget aggregation. Any execution issue within a repetition is
consolidated into the run-level `anomalies.log` and affects the overall run verdict.

The run folder contains one primary set of evidence:

- `logcat.log`: complete unfiltered logcat for all measured repetitions.
- `device-info.log`: one device and build profile captured by the monitor session.
- `anomalies.log`: one aggregated anomaly list for the measured run.
- `samples.csv`: the complete continuous sample stream.
- `performance-report.pdf`: the existing detailed monitor report with time-series graphs.
- `report.html`: the Release Guard decision and normalized aggregate performance graphs.
- `report.pdf`, `evaluation.json`, `result.junit.xml`, `summary.csv`, and `run.json`: release decision and machine-readable outputs.

Iteration directories may still hold journey-created screenshots or explicit step evidence. They do not contain duplicate monitor reports, logcat, device profiles, anomaly reports, or sample files, and are not presented as separate analyses.

## Normalized aggregate graphs

Each successful measured repetition is mapped to 0–100% scenario progress using its first and last journey marker. Samples inside that boundary are assigned to fixed progress buckets.

For each selected diagnostic metric and bucket, the report calculates:

- Median as the primary trend line.
- P10–P90 range as the variability band.
- Minimum and maximum summary values for context.
- Number of contributing repetitions.

The HTML report uses self-contained SVG and no web dependency. Anomalies appear as aggregate counts and severity/type summaries; they are not attributed to individual journey steps in the primary report. Repetitions that failed before producing a valid boundary are excluded from normalized graphs and listed only as execution errors.

## Budget evaluation

Release budgets keep their current semantics. The continuous sample stream is sliced by repetition markers before metric extraction. Named-section budgets use section markers within the same repetition. Each repetition produces an internal `AttemptSummary`; the evaluation engine aggregates those summaries exactly as before.

The user-facing report does not include an iteration analysis table. It shows total configured repetitions, successful repetitions, failed repetitions, sample coverage, overall evaluation, normalized graphs, anomalies, device identity, and registered run-level evidence.

## Result UI

The Release Guard result exposes run-level actions only:

- Open scenario report.
- Open performance PDF.
- Open complete logcat.
- Open device information.
- Open anomalies.
- Open evaluation JSON, JUnit XML, and summary CSV.

Per-iteration log buttons are removed. Supporting screenshots remain available through the registered-evidence section of the scenario report.

## Failure and cancellation

- Capture startup failure ends the run before measured execution.
- Journey-step failures are recorded without stopping the shared capture unless the capture itself fails.
- Cancellation requests stop journey execution and finalize the shared session once.
- Finalization attempts to preserve every run-level file even after a failed repetition.
- Missing or empty required `logcat.log` fails the run and leaves an explicit evidence error.
- A report can be generated from partial samples when the session finalizes successfully; partial coverage is stated clearly.

## Compatibility

- Scenario JSON, project storage, baseline identity, and budget definitions remain unchanged.
- Existing historical runs with per-iteration artifacts remain readable.
- New runs register the scenario-level artifact names without requiring database migration.
- CLI and Studio use the same runner and receive the same consolidated output.

## Verification

- Unit-test that one capture session is created for multiple measured repetitions.
- Unit-test that warm-ups occur before capture starts.
- Unit-test that per-repetition budget samples are sliced from the shared stream.
- Unit-test that cancellation and failed steps finalize the shared session once.
- Verify one run-level log, device profile, anomaly file, sample CSV, and performance PDF are registered.
- Verify no per-iteration monitor report or log action is exposed for new runs.
- Verify normalized graph aggregation and empty/partial coverage behavior.
- Run the full test suite, embedded JavaScript syntax check, and macOS build.
