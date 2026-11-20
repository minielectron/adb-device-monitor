# Release Guard Lazy Attempt Artifacts Design

## Goal

Stop creating empty `attempt-NN` directories for measured repetitions while preserving directories
that contain screenshots or other step-produced evidence.

## Design

- Resolve each attempt's confined artifact path without creating it.
- Pass that path to the device driver exactly as today.
- Let a file-producing step create the directory at write time. The existing screenshot step already
  calls `Files.createDirectories` before pulling its PNG.
- Scan and register attempt artifacts only when the path became a real directory.
- Keep artifact keys and paths unchanged: files remain under `attempt-NN/<file>`.
- Do not delete existing attempt directories from previous runs.

## Alternatives considered

- Creating then deleting empty directories was rejected because it adds filesystem churn and leaves
  stale folders when a run is interrupted.
- Pre-scanning scenarios for screenshot steps was rejected because it couples the runner to known
  artifact-producing step types and would be fragile when new steps are added.

## Verification

- A scenario with repetitions but no file-producing steps creates no `attempt-NN` directories.
- A step that writes evidence still creates its attempt directory and registers the file.
- Existing artifact path-containment validation remains in `ArtifactStore`.
- The full Gradle suite and macOS packaging build must pass.

## Out of scope

- Removing empty directories from historical runs.
- Moving step-produced evidence to the run root.
- Changing attempt numbering or artifact manifest keys.
