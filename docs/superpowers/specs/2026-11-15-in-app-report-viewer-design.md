# In-App Diagnostic Report Viewer Design

## Objective

Open Device Snapshot and Memory Snapshot HTML reports inside the existing macOS application window without unloading Home or losing any unsaved configuration. The application remains a Tauri WKWebView backed by the existing Kotlin localhost server; this change does not introduce a custom native macOS UI.

## Architecture

Home owns a full-window report-viewer layer. The layer is hidden during normal operation and contains a small viewer toolbar plus a same-origin iframe. Opening a report reveals this layer above Home rather than navigating away from the Home document.

Because Home remains mounted underneath the viewer, the selected device, form values, disclosure state, diagnostic progress, and current messages remain unchanged. Closing the viewer reveals the exact prior Home state.

The generated Device Snapshot and Memory Snapshot reports remain standalone HTML files. Their rendering, filtering, sorting, printing, and offline behavior do not depend on the viewer and continue to work when a report is opened directly.

## Viewer Interface

The viewer contains:

- a Back to Studio action;
- the current report title;
- an Open externally action for users who explicitly want the system browser;
- a loading state;
- an error state with Retry and Back to Studio actions;
- a titled iframe containing the report.

The viewer covers the main application content within the existing window. It follows the current graphite, gold, violet, and teal visual system and adapts to desktop and narrow window sizes.

## Interaction and State

`openReport(path, title, trigger)` accepts only the exact Device Snapshot and Memory Snapshot report endpoints. It records the triggering control, loads the report into the iframe, makes the viewer visible, marks the background inert, and prevents background scrolling.

Both the automatic success behavior and the success message link call `openReport` instead of `window.open` or a `_blank` link.

`closeReport()` clears the iframe, removes the background inert state, restores scrolling, hides the viewer, and returns keyboard focus to the control that opened it. The Back to Studio action and Escape key both call this function.

Device polling may continue while the viewer is visible, but it must not reset run configuration values or diagnostic state. Opening and closing a report must never reload Home.

The Open externally action deliberately opens only the current allowlisted report URL in a new window. The existing Tauri new-window handler then sends that explicit action to the system browser. Other internal application navigation remains inside the primary WKWebView.

## Security and Accessibility

Only `/api/snapshot-report` and `/api/procrank-report` may be loaded into the viewer. Unknown, external, or malformed paths are rejected before the iframe source changes.

The viewer behaves as a modal region with an accessible name. While it is open, the underlying application is inert and hidden from assistive navigation. The iframe has a report-specific title. Focus moves into the viewer when opened and returns to the original action when closed. Escape closes the viewer. Reduced-motion preferences disable viewer transitions.

The iframe is same-origin and does not weaken the existing page Content Security Policy or asset allowlist. Generated reports remain free of remote runtime dependencies.

## Loading and Failure Handling

The loading state is visible until the iframe load event. A bounded timer exposes an error and Retry action if WebKit does not complete the load. Back to Studio remains available in every state.

Retry reloads the same allowlisted endpoint. Closing the viewer cancels the pending timeout and clears the iframe. The viewer must not replace or erase the successful diagnostic status shown on Home.

## Verification

Implementation is complete after verifying:

1. Device Snapshot and Memory Snapshot open inside the main application window.
2. No automatic diagnostic action launches the external browser.
3. Every Home form value, selected device, disclosure state, and status survives open and close.
4. Back to Studio, Escape, Retry, and focus restoration work.
5. Open externally remains an explicit user action.
6. Unknown report URLs are rejected.
7. Report filtering, sorting, SVG rendering, printing, and offline behavior remain intact.
8. The viewer has no horizontal overflow at desktop and narrow window widths.
9. Kotlin tests, JavaScript checks, Rust checks, macOS packaging, and packaged-app smoke tests pass.

## Non-Goals

- Rewriting the application with AppKit or SwiftUI.
- Adding a second native window or tab system.
- Persisting Home state across application restart or manual page reload.
- Changing diagnostic capture, report contents, report storage, or report endpoint schemas.
- Embedding arbitrary URLs or external sites.
