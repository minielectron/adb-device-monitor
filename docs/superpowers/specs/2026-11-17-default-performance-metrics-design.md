# Default Performance Metrics Design

## Goal

Give first-time users the same balanced performance-metric selection on Home and Player Debugger:

- CPU utilization
- Per-core CPU
- Top process CPU
- Memory used
- PSI pressure stall
- Top process memory

## Behavior

The six metrics are checked in the initial HTML for both screens. Home no longer selects App PSS by
default. Existing saved drafts remain authoritative: when local storage contains a prior choice, the
screen restores that choice instead of forcing the defaults again. Navigating between screens or
restarting the desktop app therefore does not reset user configuration.

The change affects only presentation defaults. Request parameter names, monitor configuration,
collectors, Player Debugger capture logic, and backend defaults remain unchanged.

## Alternatives considered

1. **HTML first-use defaults with saved-draft precedence — selected.** This follows the existing UI
   pattern and requires the smallest change.
2. Force the six values on every page load. Rejected because it would overwrite user preferences.
3. Add a shared server-side defaults API. Rejected because two static pages do not justify a new
   configuration boundary and it would add failure modes without changing the result.

## Verification

Update the offline-assets regression test to assert that all six Home and Player Debugger checkboxes
are initially checked and that Home App PSS is not initially checked. Run the focused test, then the
full test suite when the active external Gradle cache lock permits it.

## Scope and compatibility

No stored data is migrated or deleted. Existing drafts remain compatible because checkbox IDs and
the local-storage schema do not change. The change remains fully offline and adds no dependency.
