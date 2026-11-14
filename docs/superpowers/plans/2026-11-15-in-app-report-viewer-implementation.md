# In-App Diagnostic Report Viewer Implementation Plan

## Goal

Open completed diagnostic HTML reports inside a full-window viewer in the existing Home WKWebView while preserving all unsaved Home state.

## Constraints

- Keep the current Kotlin-rendered UI and Tauri WKWebView architecture.
- Do not add dependencies or a second native window.
- Preserve every existing diagnostic endpoint, element ID, capture action, and polling flow.
- Keep direct report URLs standalone and offline-capable.
- Allow only the two diagnostic report endpoints inside the viewer.

## Implementation

1. **Add the viewer presentation to Home**
   - Add responsive viewer, toolbar, loading, failure, and iframe styles to `HomePage.kt`.
   - Add reduced-motion behavior and prevent background scrolling while open.
   - Add the hidden accessible viewer markup after the main application content.

2. **Add the viewer controller**
   - Add an exact report-path allowlist.
   - Implement open, load-complete, timeout/failure, retry, external-open, and close functions.
   - Make the Home header and main content inert while the viewer is open.
   - Restore scrolling and focus to the triggering diagnostic action when closed.
   - Support Escape as a close action.

3. **Route both diagnostic success flows through the viewer**
   - Replace `_blank` report links with same-window viewer actions.
   - Replace automatic `window.open` calls with `openReport`.
   - Keep the explicit Open externally toolbar action as the only diagnostic popup.

4. **Add regression coverage**
   - Assert that Home contains the report viewer and both allowlisted endpoints.
   - Assert that automatic report success code no longer opens either endpoint in a new window.
   - Keep the existing standalone/offline report tests.

5. **Verify behavior**
   - Run Gradle tests and the fat JAR build.
   - Verify Home at desktop and mobile widths with no overflow.
   - Capture both report types and confirm they open in the embedded viewer.
   - Confirm form values, device selection, disclosure state, status, focus restoration, Escape, Retry, and explicit external opening.
   - Run Rust checks, rebuild the macOS app/DMG, and launch the fresh bundle.

6. **Publish**
   - Commit the complete approved UI/offline/report-viewer implementation.
   - Add `https://github.com/minielectron/adb-device-monitor.git` as the repository remote if no remote exists.
   - Push the completed local history to the remote `master` branch without force-pushing.
