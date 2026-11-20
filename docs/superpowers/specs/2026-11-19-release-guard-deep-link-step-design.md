# Release Guard Package-Scoped Deep-Link Step Design

## Summary

Release Guard will add a first-class **Launch deep link** journey step. The step opens an absolute URI in the application package already configured by the scenario, making deep-link journeys repeatable without exposing raw ADB syntax or allowing a step to target a different package.

The feature remains fully offline and uses the existing ADB shell capability, scenario persistence, runner, reporting, and editor infrastructure.

## Goals

- Launch custom-scheme, HTTP, HTTPS, and Android intent-style deep links during a Release Guard scenario.
- Always scope resolution to the scenario package.
- Validate malformed input before a device run starts.
- Preserve the step through editor drafts, saved scenario JSON, API/CLI execution, and reports.
- Produce the same step timing, output, and failure evidence as other journey steps.

## Non-goals

- Selecting or overriding the target package per step.
- Configuring arbitrary intent actions, categories, flags, components, MIME types, or extras.
- Discovering or launching another installed application as a fallback.
- Fetching or validating remote deep-link content.

## Domain and persistence

The sealed `ScenarioStep` model gains:

```kotlin
data class LaunchDeepLink(val uri: String) : ScenarioStep
```

Its persisted discriminator is `launchDeepLink`, with one `uri` field. It uses the existing schema and polymorphic JSON adapter so saved scenarios remain portable and unknown step types continue to fail with the existing compatibility error.

The step requires only `DeviceCapability.SHELL`. The package is read from `Scenario.packageName`; it is neither duplicated in the step nor editable in the step card.

## Validation

Scenario validation rejects a deep-link step when the URI:

- is blank;
- exceeds 4,096 characters;
- contains control characters;
- is not a syntactically valid absolute URI; or
- has no nonblank scheme.

Spaces and other characters that are illegal in a URI must be percent-encoded. Validation is deliberately scheme-agnostic so applications can use project-specific custom schemes without changing Release Guard.

Validation does not query a network service or require the target package to be installed. Existing preflight/package checks and the execution result provide those device-specific failures.

## Device execution

`AdbDeviceDriver` executes the step through the existing checked-shell path:

```text
am start -W -a android.intent.action.VIEW -d <quoted-uri> <quoted-scenario-package>
```

Both the URI and package are passed through the existing shell-quoting helper. Supplying the package as the final `am start` argument constrains intent resolution to that package. There is no implicit-handler fallback.

The `-W` flag makes ADB wait for launch completion and returns launch diagnostics. A normal result records the standard `StepOutput`; command failures, Android activity-resolution failures, timeouts, or device loss fail the step through existing runner behavior and retain the ADB response in run evidence.

## Editor and reports

The Journey step type selector gains **Launch deep link**. Selecting it shows one required text input:

- Label: `Deep link URI`
- Example placeholder: `myapp://content/123?source=release-guard`

The existing step card, ordering, draft persistence, capability summary, validation display, motion, and visual theme are reused. Reports and live execution use the normal step label and timing; no new report surface is required.

## Compatibility and security

Existing scenario files are unchanged. New scenario files require a build that recognizes `launchDeepLink`, consistent with the current unknown-step policy.

The feature does not execute the URI as a shell fragment. Validation establishes URI shape, while shell quoting prevents command substitution and argument splitting. Limiting the model to a URI avoids turning the editor into an unrestricted intent-command builder; advanced cases remain available through the already-labelled Shell step.

## Verification

- JSON round-trip tests include `LaunchDeepLink` and its stable discriminator.
- Model tests cover blank, oversized, control-character, relative, missing-scheme, and valid custom-scheme URIs.
- Fake-ADB driver tests assert the exact package-scoped command and shell quoting.
- Web asset tests assert the new selector option and URI field.
- The complete offline test suite and macOS packaging build run after implementation.
- Documentation for Release Guard lists the new step, validation, data source, and package-scoped behavior.

