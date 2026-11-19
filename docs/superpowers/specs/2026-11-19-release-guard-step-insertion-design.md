# Release Guard Per-Step Insertion Controls Design

## Summary

The Release Guard journey editor will replace its single global **Add step** action with insertion controls on every step card. Users can add a new step immediately above or below the step they are editing without adding at the end and repeatedly reordering it.

Existing move-up, move-down, delete, draft persistence, validation, and readiness behavior remain unchanged.

## Goals

- Make insertion position explicit at the point of editing.
- Support insertion before the first step and after the last step.
- Keep insertion visually distinct from reordering.
- Preserve keyboard access, accessible labels, responsive layout, and the current Studio theme.
- Keep an obvious recovery action when the journey has no steps.

## Interaction design

Each populated step card gains two centered boundary controls:

- **Add above** at the top edge, with a plus and upward direction indicator.
- **Add below** at the bottom edge, with a plus and downward direction indicator.

The controls use text or combined plus/direction iconography rather than a bare arrow so they cannot be mistaken for the existing reorder buttons. Native button semantics, `aria-label`, and `title` attributes provide keyboard and assistive-technology access.

The header-level **Add step** button is removed. The existing move-up, move-down, and delete buttons remain in the step header.

When the journey is empty, its empty state contains an **Add first step** button. This includes a newly created empty scenario and the state after deleting the final step.

## Behavior and state

The editor adds `insertStep(index)` using normal array insertion. **Add above** inserts at the current index; **Add below** inserts at `index + 1`. A new item uses the existing default `launchApp` step, after which the user can choose any step type.

Every insertion re-renders the list, persists the local draft, and recalculates readiness. No project, scenario, package, budget, or device state is reset.

Boundary controls are hidden from printed/exported evidence because they exist only in the scenario editor. At narrower widths they remain inside the card width and preserve a touch target consistent with existing Studio buttons; they must not overlap adjacent cards or input fields.

## Error handling

Insertion accepts only an index produced by the rendered step list. The function clamps or rejects out-of-range indices defensively so malformed browser calls cannot create sparse or reordered state.

Removing the global action must not leave an empty editor without an insertion path. The empty-state button is therefore part of the same change and its regression test.

## Verification

- Offline page tests verify that the global **Add step** action is absent.
- Tests verify **Add above**, **Add below**, and **Add first step** labels and handlers.
- Embedded JavaScript syntax validation covers the updated renderer.
- Browser-level source checks verify insertion uses array splice, saves the draft, and updates readiness.
- The complete offline Gradle test suite and macOS packaging build run after implementation.

