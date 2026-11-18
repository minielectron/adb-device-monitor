# Lite Layout Inspector

**Current state:** implemented and available from **Home → Quick diagnostics**. It runs independently
of a monitoring session and does not need an internet connection.

Layout Inspector captures the current Android accessibility/UI hierarchy and a screenshot at nearly
the same point in time, then joins them in a self-contained HTML report. It is intended for quick
resource-ID, bounds, focus and visibility debugging; it is not a replacement for Android Studio's
runtime Layout Inspector.

## How the data is captured

| Report data | Android source | Treatment |
|---|---|---|
| Element hierarchy | `uiautomator dump <temporary.xml>` | XML is read through `adb shell`, parsed with external entities and DTDs disabled, then the remote file is removed. |
| Screenshot | `screencap -p <temporary.png>` | PNG is pulled through ADB, validated and embedded into the HTML as a data URL. Temporary device and host files are removed. |
| Type | UI node `class` attribute | The short class name is displayed, while the full class remains searchable. |
| Resource ID, text, description and package | UI node attributes | Blank values are shown as unavailable. Password-node text and description are replaced with `Protected`. |
| Position and size | UI node `bounds="[left,top][right,bottom]"` | Width, height and center are derived from the bounds. |
| State | `enabled`, `clickable`, `focusable`, `focused`, `scrollable`, `selected`, `checked`, `password` | Parsed as booleans and shown as state chips. |
| Rotation | hierarchy root `rotation` attribute | Shown when Android supplies it. |

The capture accepts at most 8 MiB of hierarchy XML, 16 MiB of PNG data and 5,000 parsed elements.
If more nodes exist, the report explicitly says that it shows the first 5,000.

## How screenshot selection works

The screenshot's native PNG width and height define the coordinate system. Element bounds are
clamped to that area and converted to percentages only for display, so resizing the report does not
change the selected Android coordinates.

- Selecting a row draws only that element's boundary, label and center crosshair.
- Clicking the screenshot converts the click back to native screen coordinates and selects the
  smallest element whose bounds contain that point. This makes nested controls easier to reach than
  selecting the full-screen parent.
- Search matches type, full class, resource ID, text, content description, package and bounds.

The hierarchy and PNG are separate Android captures, so rapidly changing or animated screens can
move between the two operations. Refresh captures both sources again; it does not mutate the device.

## API and saved output

The Home card uses an asynchronous sequence:

1. `POST /api/layout-inspector` with the authorized device serial.
2. `GET /api/layout-inspector-status` while capture progresses.
3. `GET /api/layout-inspector-report` to display the completed HTML.

The file is saved as `layout-inspector_<yyyyMMdd_HHmmss>.html` in the resolved reports directory.
For the packaged app that is normally `~/ADB Device Monitor/`; a writable CLI working directory can
be selected instead. The HTML contains the PNG and hierarchy data and remains viewable offline.

The **Refresh screen** button works while the report is served inside ADB Performance Studio because
it calls the local capture API. In a separately opened `file://` copy, the report remains interactive
but Refresh is disabled because there is no local API origin.

## Permissions, privacy and limitations

- The selected target must be connected and in ADB state `device`; root is not required on normal
  Android builds.
- Some OEM, secure or policy-managed builds disable `uiautomator`, screenshot capture, or access to
  particular windows. The report fails explicitly if either required capture is unavailable.
- `SurfaceView`, DRM video, hardware overlays and secure windows may appear black or may not expose
  meaningful child nodes. Compose and custom-drawn UIs expose only what their accessibility
  semantics make available.
- Bounds describe Android's reported screen coordinates, not view layout constraints, margins or
  rendering layers.
- The HTML contains the real screenshot, visible text, descriptions, package names and resource IDs.
  It stays local but should still be treated as potentially sensitive evidence.
- Layout captures are not automatically added to the Diagnostics ZIP or sanitized Player Debugger
  support bundles.

## Useful workflow

Capture the problem screen, click the visible control, copy its resource ID and bounds, then Refresh
after navigation or focus movement. Use the result to verify automation selectors, DPAD focus,
accessibility exposure, unexpected full-screen containers and controls positioned outside the
expected screen area.
