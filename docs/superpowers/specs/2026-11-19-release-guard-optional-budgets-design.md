# Release Guard Optional Performance Budgets Design

## Summary

Release Guard will allow a scenario to run without performance budgets. A successful zero-budget run collects complete local evidence but does not claim that the release passed. It finishes with the explicit neutral outcome **Evidence only / Not evaluated**.

Budgeted scenarios retain their existing Pass, Warning, and Fail behavior. Execution failures still fail a zero-budget run.

## Goals

- Let developers and QA capture repeatable scenario evidence before defining thresholds.
- Keep the **Run Release Check** action available when the budget list is empty.
- Prevent an unevaluated run from appearing release-ready in UI, exports, storage, or CI.
- Preserve baseline promotion from successful evidence-only measurements.
- Keep existing scenario JSON compatible because `budgets` is already optional and defaults to an empty list.

## Domain outcomes

The domain gains:

- `Verdict.NOT_EVALUATED`
- terminal `ExecutionState.EVIDENCE_ONLY`

`ExecutionState.isTerminal()` includes `EVIDENCE_ONLY`. When evaluation receives no budgets:

- any unsuccessful measured attempt or execution error returns `FAIL`;
- otherwise it returns `NOT_EVALUATED` with no budget results and a message explaining that no performance budgets were configured.

The runner maps `NOT_EVALUATED` to `EVIDENCE_ONLY`. Existing verdict and terminal-state mappings are unchanged.

## Editor and result UI

The readiness panel no longer treats an empty budget list as a blocking check. Instead it shows an informational successful prerequisite:

```text
No performance budgets · evidence-only run
```

All other readiness requirements remain mandatory. The run button remains disabled for missing project/scenario data, steps, target, or required device capabilities.

A completed zero-budget run displays:

- title: **Evidence only**;
- status: **Not evaluated**;
- explanation: **No performance budgets were configured**.

The neutral state gets its own visual treatment and is not styled like Pass, Warning, or Fail. Successful attempts still enable baseline promotion because their measurements can become a future comparison baseline.

## Exports, storage, and CLI

JSON manifests and SQLite persist `NOT_EVALUATED` and `EVIDENCE_ONLY` using the existing enum-name representation. HTML and PDF reports show **NOT EVALUATED** and the explanatory message. CSV measurement evidence remains unchanged.

JUnit XML represents a zero-budget run as one skipped test case named `No performance budgets configured`, avoiding both a false passing assertion and a build failure.

The headless CLI adds exit code `6` for `NOT_EVALUATED`. This keeps CI from interpreting evidence collection as release approval while distinguishing it from configuration, infrastructure, warning, or release-failure outcomes. Existing exit codes remain unchanged.

## Compatibility

Previously stored scenarios and runs continue to parse. New evidence-only manifests introduce enum values that older application builds do not recognize; this follows the existing forward-compatibility boundary for newly introduced persisted outcomes. The current build must document the new values and exit code.

No schema-version increment is required because the JSON structure does not change and enum evolution is already handled as a build compatibility boundary.

## Verification

- Evaluation tests cover zero budgets with successful and failed attempts.
- Scenario runner tests verify `EVIDENCE_ONLY`, terminal handling, and baseline-eligible successful attempts.
- UI tests verify budgets are nonblocking and neutral copy is rendered.
- Export tests verify HTML, PDF text, JSON, and skipped JUnit output.
- CLI tests verify exit code `6` and all existing exit codes.
- Repository round-trip tests cover the new state and verdict.
- The complete offline Gradle test suite and macOS packaging build run after implementation.

