# Release Guard Live Step Focus Design

## Goal

Make an active Release Guard run feel alive and immediately understandable without adding a second journey view or interrupting navigation. The page must show the current phase, identify the exact running step, and smoothly bring that step into view.

## Chosen approach

Use the existing journey cards as the live execution surface. The active card receives a phase-aware accent, animated status treatment and automatic focus. Earlier cards in the current attempt receive a restrained completed state. The sticky progress panel mirrors the active step name and changes color with the execution phase.

Alternatives not selected:

- A duplicate step list in the sticky sidebar would remain visible but would repeat the scenario and compete for limited width.
- A separate animated timeline would be visually richer but add unnecessary state, layout and accessibility complexity.

## Visual behavior

- Discovery and active capture use teal.
- Warm-up uses amber.
- Evaluation and export use violet.
- Successful terminal states use green.
- Failure and cancellation use red.
- The active journey card has a colored leading rail, soft gradient, glow and a small `Running` badge.
- Completed cards in the current attempt show a subtle green `Done` badge without overpowering the active step.
- The sticky progress card shows a phase label, current step title, context line and progress bar in the same accent.
- Motion uses short CSS transitions and a gentle active pulse. `prefers-reduced-motion` disables pulsing and smooth scrolling.

## Focus behavior

- Backend progress reports journey steps during warm-ups and measured attempts.
- When the one-based step index changes, the page highlights the matching existing journey card and calls `scrollIntoView` with centered, smooth positioning.
- Focus movement occurs only while a step is actively executing and only once per distinct phase/attempt/step combination.
- Polling the same step does not repeatedly scroll.
- Terminal, finalization and idle states remove the active highlight while preserving the result panel.
- Browser keyboard focus is not stolen; the behavior changes viewport focus only.

## State and navigation

The background service remains authoritative. Returning to Release Guard restores the current phase and step through `/api/release/status`, reapplies the correct colors, and centers the active card. Draft inputs and the Run/Stop locking behavior remain unchanged.

## Backend progress

`ScenarioRunner` updates `ReleaseGuardProgress.step` and its message before every journey step for both warm-up and measured execution. Measured marker behavior remains unchanged. No new execution thread or capture session is introduced.

## Accessibility

- Phase and active-step text remain available through the progress region.
- The active card uses `aria-current="step"`.
- Status badges provide text in addition to color.
- Reduced-motion users receive immediate positioning and no pulse animation.

## Verification

- Unit-test that warm-up and measured driver calls expose the current one-based step.
- Static UI tests verify active/completed styles, phase accents, reduced-motion handling and step-follow code.
- Parse the embedded JavaScript.
- Run the full test suite and macOS build.
