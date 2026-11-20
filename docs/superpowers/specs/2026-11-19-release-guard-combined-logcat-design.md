# Release Guard Combined Scenario Logcat Design

## Summary

Release Guard will produce one run-level `logcat.log` containing the complete device logcat from every measured iteration in chronological iteration order. Existing per-iteration `attempt-NN/logcat.log` files remain available for focused investigation.

Warm-up attempts are intentionally excluded. The combined artifact represents only the measured evidence used by the release decision.

## Goals

- Provide one file that developers and QA can search or share for the full measured scenario.
- Preserve raw per-iteration log lines without filtering or transformation.
- Make iteration boundaries and outcomes explicit.
- Retain completed iteration logs when a later iteration fails or the run is cancelled.
- Expose the combined log through the existing in-application artifact viewer.

## Non-goals

- Combining warm-up logs.
- Removing per-iteration log files.
- Maintaining a second, run-wide ADB logcat process.
- Changing logcat filtering, timestamp format, buffer-clearing behavior, or redaction policy.

## Artifact layout

Each run folder contains:

```text
<scenario>_<UTC timestamp>_<short id>/
  logcat.log
  attempt-01/logcat.log
  attempt-02/logcat.log
  ...
```

The run-level file uses stable text boundaries:

```text
===== ITERATION 1 OF 3 | PASS =====
<raw attempt-01/logcat.log bytes>
===== END ITERATION 1 =====

===== ITERATION 2 OF 3 | FAIL | <single-line failure reason> =====
<raw attempt-02/logcat.log bytes>
===== END ITERATION 2 =====
```

Failure text is normalized to one line so it cannot break the boundary format. Missing or empty iteration logs still produce boundaries and an explicit `LOGCAT UNAVAILABLE` line, and the affected iteration cannot pass.

## Data flow and recovery

After each measured iteration stops and its capture session finalizes, `ScenarioRunner` appends that iteration to the run-level file before persisting the finalized attempt manifest. The append uses streaming file I/O rather than loading potentially large logs into memory.

The combined artifact is registered under the manifest key `logcat.log` after the first iteration and remains registered for subsequent updates. Because the file is extended after every completed iteration, cancellation or failure during a later iteration preserves every earlier completed iteration. The original per-iteration artifacts remain registered under their existing keys.

The result screen adds **Open complete scenario log** alongside the existing exported evidence and per-iteration log buttons. It uses the current state-preserving artifact viewer and introduces no browser or network dependency.

## Error handling

Failure to read or append an iteration log is an evidence-finalization failure and must not silently produce a passing release result. A missing or empty log is represented explicitly in the combined file and marks the iteration unsuccessful because its required evidence is incomplete. The combined file remains registered so earlier iterations and the failure marker are still available.

The merge happens only after the capture session has been stopped, preventing concurrent reads of a growing iteration file. A process or host crash during an active iteration can leave that unfinished iteration incomplete, but previously appended iterations remain recoverable.

## Verification

- Unit tests verify ordered iteration boundaries, raw log preservation, PASS and FAIL metadata, single-line failure normalization, and the explicit unavailable marker.
- Scenario runner tests verify that the combined artifact is registered and updated after each measured iteration.
- Offline UI tests verify the **Open complete scenario log** action.
- The complete offline Gradle test suite and macOS packaging build run after implementation.

