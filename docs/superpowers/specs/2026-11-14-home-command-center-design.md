# Home Command Center Redesign

**Date:** 2026-11-14  
**Status:** Approved for implementation  
**Scope:** Home/setup page only

## Objective

Redesign the ADB Performance Studio home page as a premium production-grade command center. The result should combine a confident, high-end visual identity with clear categorization, accessible controls, smooth motion, and reliable offline operation.

The redesign must preserve the current monitoring workflow, API contracts, form values, and diagnostic capabilities. It changes how the home page presents and organizes those capabilities, not what the backend does.

## Design Direction

The selected direction is **Premium Command Center**:

- A near-black graphite canvas with restrained amber, violet, and teal ambient light.
- Compact application chrome instead of a large marketing hero.
- Strong editorial headings, understated uppercase labels, and monospaced technical values.
- Layered smoked surfaces with subtle grain, edge highlights, and controlled depth.
- Amber as the primary action color, green for healthy devices, violet and teal for diagnostic tools.
- Short, natural transitions that support orientation without delaying work.
- CSS-generated graphics and system fonts so the desktop app remains fully functional offline.

The interface may take inspiration from premium consumer products and Material motion principles, but it must have its own identity and remain appropriate for an engineering tool.

## Information Architecture

The page is organized as a three-stage workspace.

### 1. Choose a device

Connected devices are displayed as selectable tiles containing:

- Model name
- Serial number
- Android version
- Connection state
- Root state and contextual root action

The selected device is visually dominant and clearly announced to assistive technology. Refreshing devices displays an in-place scanning state without collapsing or shifting the grid.

Device Snapshot and Procrank Snapshot are grouped in a neighboring **Quick diagnostics** panel. Their progress and results remain separate from the primary performance-run workflow.

### 2. Configure the run

The existing settings are grouped as follows:

- **Run essentials:** duration, sampling interval, app package, run label, and reports folder.
- **Capture tools:** logcat, channel zapping, UI jank, method profiler, and Perfetto.
- **System metrics:** CPU utilization, CPU idle, per-core CPU, load, temperature, frequency, network, and top-process CPU.
- **Memory metrics:** memory amount and percentage, swap, zone watermarks, PSI, page faults, reclaim, and top-process memory.
- **App and media:** PSS, app CPU, main-thread CPU, media/HAL CPU, and playback/tunnel data.

Run essentials are always visible. Capture tools and metric groups use animated disclosure panels with enabled-count badges. Controls retain their current IDs, defaults, dependencies, and submitted values.

### 3. Review and launch

A persistent summary card displays:

- Selected device
- Duration and sampling interval
- App package when provided
- Enabled capture tools
- Enabled metric count

The primary launch action lives in this card. It is unavailable until a usable device is selected. Validation and launch feedback appear in an accessible status region near the action.

On wide screens, configuration and review use a two-column layout with a sticky summary. At narrower widths, the page becomes one column and the summary follows configuration in document order.

## Component Boundaries

The page remains a single server-provided HTML document in `HomePage.kt`, matching the existing architecture and avoiding a new frontend toolchain.

Within the document, the UI is divided into explicit sections with independent styling and state updates:

- Application header
- Active-session banner
- Device selection grid
- Quick diagnostics
- Run essentials form
- Capture-tool group
- Metrics groups
- Run summary and launch action
- Toast/status feedback region

Small JavaScript functions update device state, disclosure state, progress, summary values, and accessibility attributes. Existing API functions and endpoint usage remain the source of truth for server interaction.

No new runtime dependency, framework, external font, icon library, image CDN, or network-loaded asset is introduced.

## Interaction and Motion

Motion is purposeful and restrained:

- Initial sections enter with short staggered opacity and vertical transitions.
- Device cards lift slightly on hover and gain an animated edge treatment when selected.
- Disclosure panels animate height/opacity without hiding focusable content incorrectly.
- Toggle changes and summary counters update with brief feedback.
- Snapshot and Procrank tasks use inline progress states with clear completion or failure treatment.
- The launch action uses a short pressed/loading transition before navigation.
- Ambient background light moves slowly and does not interfere with reading.

All nonessential animation is disabled or reduced under `prefers-reduced-motion: reduce`. Interaction state must never depend on motion alone.

## Accessibility

The redesign must provide:

- Visible keyboard focus for all interactive elements.
- Native controls where practical and keyboard-operable custom interactions.
- Appropriate `aria-expanded`, `aria-selected`, `aria-live`, and status semantics.
- Sufficient text, border, and focus contrast on dark surfaces.
- Labels that remain visible rather than relying on placeholders.
- Meaningful disabled states and contextual error messages.
- A logical document and tab order in both desktop and narrow layouts.

## Data Flow and Compatibility

The backend and HTTP routes are unchanged.

- `/api/defaults` supplies the reports directory and build value.
- `/api/devices` supplies device and busy state.
- `/api/root` retains the existing root action.
- `/api/start` receives the same form fields and values as before.
- Snapshot, Procrank, folder selection, diagnostics, comparison, and dashboard routes retain their existing behavior.

Existing element IDs are retained where they are used by JavaScript or backend-facing behavior. Presentation-only markup may be renamed or reorganized.

The summary is derived locally from the current form state. It is informational and does not introduce a second configuration model.

## Error and Empty States

- No devices: show a calm empty state with troubleshooting guidance and an explicit retry action.
- Device API failure: preserve the last useful device state when available and expose retry feedback.
- Unusable device: keep it visible but clearly disabled with its reported state.
- Root action: show in-place progress and an actionable failure message.
- Snapshot/Procrank failure: stop progress animation and present a clear retryable error.
- Invalid run configuration: identify the relevant section and keep the user on the home page.
- Active run: show a prominent banner that resumes the dashboard instead of encouraging a second run.

Failures must not be swallowed silently. Existing client logging remains active.

## Verification

Implementation is complete when:

1. Existing Gradle tests pass.
2. The repository smoke test passes against the packaged or freshly built JAR.
3. All backend-bound field names, endpoint calls, and important DOM IDs match the pre-redesign behavior.
4. Device selection, refresh, root, snapshots, folder selection, configuration, start, comparison, diagnostics, and active-session resume work.
5. The layout is usable at the native desktop minimum size and narrow browser widths.
6. Keyboard navigation and visible focus work through the full setup flow.
7. Reduced-motion mode removes nonessential animation.
8. The native desktop shell loads the redesigned page from its bundled engine.

## Non-Goals

- Restyling the live dashboard or comparison page
- Changing backend routes, metric collection, report generation, or persistence
- Adding user accounts, saved presets, themes, onboarding, or analytics
- Adding a frontend framework or build pipeline
- Reproducing another product's branding or proprietary visuals

