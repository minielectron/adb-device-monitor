# Diagnostics and Quick Diagnostics

ADB Performance Studio has two similarly named features with different purposes.

## Quick Diagnostics: inspect the Android device

**Home → Quick diagnostics** contains independent, one-shot device tools. They do not require a
monitoring run and each creates a local HTML report.

| Tool | Device sources | Output |
|---|---|---|
| Device Snapshot | `getprop`, `uname`, CPU sysfs, `/proc/meminfo`, launcher resolution and the `dumpsys meminfo` Total-PSS process table | `device-snapshot_<timestamp>.html` |
| Procrank Snapshot | `procrank`, root/su fallbacks, then `ps` if proportional memory is unavailable | `procrank-snapshot_<timestamp>.html` plus `.debug.txt` |
| Layout Inspector | `uiautomator dump` and `screencap` | `layout-inspector_<timestamp>.html` |

See [device-and-procrank-snapshot.md](device-and-procrank-snapshot.md) and
[layout-inspector.md](layout-inspector.md) for commands, fallbacks and limitations.

## Diagnostics: troubleshoot the desktop engine

The **Diagnostics** navigation action on Home and Live Dashboard calls `GET /api/diagnostics` and
downloads `adb-monitor-diagnostics_<yyyyMMdd_HHmmss>.zip`. It is a host/application support bundle
for diagnosing a stuck run, HTTP handler, JVM or ADB command. It does not start a new Android
capture.

| ZIP entry | Current content |
|---|---|
| `info.txt` | Build tag and time; Java, OS and architecture; locale and working directory; application-log and reports paths; JVM heap use and maximum; in-flight HTTP requests; and active session status, error, sample count, run directory and configuration. |
| `threads.txt` | A live JVM thread dump captured when the bundle is requested. |
| `adb-monitor.log` | Up to the latest 3 MiB of the application-wide log. |
| `run.log` | Up to the latest 3 MiB of the active or most recent run log, when that file exists. |

The archive is assembled in memory and returned immediately. In the native macOS application, it is
saved under `~/Downloads` and Studio confirms the completed filename without opening Finder. Name
collisions are handled by adding a numeric suffix. In ordinary browser mode, the browser controls
the download location and its own completion UI.

### What is not included

The bundle does not currently contain:

- A fresh device screenshot or UI hierarchy
- Full device logcat or the rest of a monitoring run folder
- `samples.csv`, PDF reports, Perfetto or ART traces
- Player Debugger captures or sanitized support bundles
- Release Guard scenarios, baselines or evidence folders
- Device Snapshot, Procrank Snapshot or Layout Inspector reports

`run.log` can contain messages about those features, but that is not the same as exporting their raw
evidence.

## When to use each feature

- Use **Diagnostics** when Studio is hanging, a request is slow, a run will not stop, an ADB command
  times out, or a maintainer needs the JVM and application-log context.
- Use **Device Snapshot** for device identity and an immediate memory overview.
- Use **Procrank Snapshot** to identify proportional per-process memory when the build exposes it.
- Use **Layout Inspector** for the current screen, resource IDs, UI states and bounds.
- Use **Player Debugger** for playback sessions, handoffs and incident evidence.
- Use **Release Guard** for repeatable scenario-based performance decisions.

All of these operations are local. The embedded desktop interface uses loopback HTTP between the
Tauri WebView and Kotlin process; it does not require an internet service.
