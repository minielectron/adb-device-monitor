# Release Guard App PSS and Repetition Limit Design

## Goal

Allow Release Guard scenarios to run up to 1,000 measured repetitions and capture application PSS
with useful heap-memory detail by default, while preserving saved scenario choices and the existing
offline, scenario-level reporting model.

## Repetition limit

- Accept measured repetition counts from 1 through 1,000 inclusive.
- Apply the same limit in the HTML number input and domain validation so UI and headless scenarios
  behave consistently.
- Reject zero, negative values, and values above 1,000 during scenario validation.
- Keep warm-up limits and execution semantics unchanged.
- Continue using one monitoring session for all measured repetitions.

## App PSS capture

- Add an **App PSS** card to Release Guard capture configuration.
- Enable it by default for new scenarios and for legacy scenarios whose capture configuration has
  never been explicitly saved.
- Preserve the stored choice for configured scenarios, including scenarios where App PSS was
  intentionally disabled.
- Represent the selection with the existing `app_pss_mb` metric ID.
- Reuse the existing `dumpsys meminfo <package>` collector and `PssSample`; do not add a second ADB
  command path or dependency.
- Sample App PSS at the scenario's configured capture interval. The collector may return no value
  while the package is not running or when a device omits a category; missing PSS must not crash the
  run.

The captured breakdown remains the one already parsed by the application:

- Total PSS
- Java heap
- Native heap
- Code
- Stack
- Graphics
- Private other
- System

## Scenario report

- Include Total App PSS in the normalized scenario performance profile using the existing
  repetition-normalization rules.
- Add one compact **App memory breakdown** panel rather than separate charts for every category.
- For each available category, show median and peak MB across the complete measured scenario.
- Omit individual unavailable categories and show a clear unavailable state if no PSS samples were
  captured.
- Keep reports self-contained and offline, with no JavaScript or remote resources.
- Keep performance budgets scoped to the existing Total App PSS metric. Heap-specific budget IDs
  are not part of this change.

The generated `performance-report.pdf` continues to use the existing PSS breakdown series from the
shared monitoring report. The scenario-level `report.html` gains the compact breakdown panel.

## Compatibility and persistence

- No schema-version change is required because `CaptureSettings.metrics` already stores arbitrary
  metric definitions and `app_pss_mb` is already supported.
- Existing configured scenarios retain their exact capture selection.
- Existing unconfigured or newly created scenarios receive App PSS as another default diagnostic.
- Headless JSON scenarios can enable App PSS by including `app_pss_mb`, as they can today.

## Verification

- Domain tests accept 1,000 repetitions and reject 1,001.
- Offline UI tests verify the input maximum is 1,000 and App PSS is checked by default.
- Runner tests verify `app_pss_mb` enables `SessionConfig.appPssEnabled`.
- Report tests verify Total PSS graph selection and compact heap median/peak output.
- Report tests verify graceful output when PSS data is absent.
- The full Gradle test suite, offline JavaScript syntax check, and macOS packaging build must pass.

## Out of scope

- Heap-specific performance budgets.
- A different PSS sampling interval from the scenario capture interval.
- Per-repetition PSS reports or charts.
- Changes to warm-up capture or the single scenario-level artifact contract.
