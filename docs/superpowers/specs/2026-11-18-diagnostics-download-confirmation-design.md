# Diagnostics Download Confirmation Design

**Date:** 2026-11-18
**Status:** Approved design, pending implementation

## Problem

The Home **Diagnostics** action successfully generates and downloads a support ZIP, but the native
desktop shell immediately opens Finder with the file selected. Studio provides no in-app feedback,
so the action appears to leave the application and do nothing useful.

The export itself is working and must remain export-only. The downloaded ZIP is valid and is saved
under the macOS Downloads directory with a collision-safe filename.

## Desired behavior

When a Diagnostics ZIP finishes downloading in the native macOS application:

- Keep the user inside ADB Performance Studio.
- Do not open Finder.
- Show an accessible in-app confirmation that identifies the saved filename and Downloads location.
- Show a failure message if the native download does not complete.

PDF, HTML and other downloads keep their existing behavior. This change applies only to files whose
name matches the Diagnostics bundle naming contract: `adb-monitor-diagnostics_*.zip`.

## Architecture

Keep the existing direct link to `GET /api/diagnostics` and the existing Tauri download destination
logic. No new API route, IPC command, dependency or Diagnostics screen is needed.

The Tauri `on_download` handler will retain enough information about the active destination to:

1. Recognize a Diagnostics ZIP from its filename.
2. Save it to the unique path already selected under Downloads.
3. On `DownloadEvent::Finished`, avoid the current `open -R` Finder command for Diagnostics only.
4. Evaluate a small script in the current WebView that dispatches a browser event containing the
   outcome and safe display filename.

The Home page will listen for that event and render a themed status notification. Non-diagnostics
downloads continue to be revealed in Finder exactly as they are today.

## Browser event

Use a private application event name such as `studio-download-finished`. Its detail contains:

- `kind`: `diagnostics`
- `success`: boolean
- `fileName`: basename only, never an arbitrary script fragment

Rust serializes the event detail as JSON before passing it to `Webview::eval`; no path or filename is
interpolated into JavaScript without JSON escaping. The UI does not need the absolute filesystem
path because the user-facing destination is the standard Downloads folder.

## Home confirmation

Add one lightweight notification region using the existing Studio palette and typography:

- Success: `Diagnostics saved to Downloads — <filename>`
- Failure: `Diagnostics download failed. Try exporting again.`

The region uses `role="status"` and `aria-live="polite"`, appears above normal page content, and
automatically dismisses after a short interval. A repeated download replaces and restarts the same
notification; it does not create stacked elements.

The direct export link remains keyboard accessible and requires no new modal or navigation state.

## Browser-mode behavior

When Studio runs in a normal external browser, the browser owns the download destination and does
not emit the Tauri event. Existing browser behavior remains unchanged. The application must not
claim a file was saved when it cannot observe completion.

## Error handling

- A successful Diagnostics download dispatches the success event and does not launch Finder.
- A failed Diagnostics download dispatches the failure event and does not launch Finder.
- A non-diagnostics download follows the existing reveal-in-Finder behavior on success.
- Missing or malformed filenames are treated as non-diagnostics, preserving current behavior.

## Testing

- Add a small Rust unit test for Diagnostics filename recognition, including a valid timestamped ZIP,
  unrelated ZIP/PDF files and misleading prefixes/extensions.
- Keep the Home page script syntax-valid after the listener/notification addition.
- Build/test the Tauri Rust target and Kotlin tests in proportion to the touched files.
- In the packaged app, click Diagnostics and verify:
  - one new ZIP appears in Downloads;
  - the ZIP passes integrity testing;
  - Finder does not open or take focus;
  - Studio displays the success confirmation;
  - another report/PDF download retains its previous Finder behavior.

## Out of scope

- An in-app Diagnostics viewer or log browser
- Changing ZIP contents or the `/api/diagnostics` response
- Changing destinations or exposing a Save As dialog
- Changing Finder behavior for reports, PDFs, HTML files or other downloads
- Desktop notifications or new Tauri capabilities
