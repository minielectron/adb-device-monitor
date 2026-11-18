# Device Snapshot and Procrank Snapshot

**Current state:** both are implemented under **Home → Quick diagnostics**. They are independent,
one-shot captures; no monitoring run is required. Each saves and serves a self-contained HTML report
without a CDN, analytics or remote assets.

Layout inspection is a third Quick Diagnostics tool and is documented separately in
[layout-inspector.md](layout-inspector.md). The navigation-level Diagnostics ZIP is an application
support bundle, not one of these device captures; see [diagnostics.md](diagnostics.md).

## Device Snapshot

Device Snapshot provides device identity and a current memory-footprint overview.

| Displayed value | Source |
|---|---|
| Manufacturer, brand, model, device, board/platform, Android release/SDK/build type and hardware | batched `getprop` reads |
| Kernel | `uname -r` |
| CPU cores | `nproc`, falling back to processor count in `/proc/cpuinfo` |
| CPU governor | `/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor` |
| Total/free/available RAM and swap | `/proc/meminfo` |
| Default launcher | `cmd package resolve-activity` for the HOME intent |
| Per-process PSS | the `Total PSS by process` block from `dumpsys meminfo`, bounded to 250 lines |

Processes are grouped as Android, launcher or third-party using the resolved launcher package plus
name/prefix heuristics. The resulting doughnut is therefore an operational overview, not Android's
memory accounting authority. PSS values and the full process table remain the more direct evidence.

The report is `device-snapshot_<yyyyMMdd_HHmmss>.html` in the resolved reports directory. It uses
server-rendered SVG and static HTML, so a captured file can be read offline.

## Procrank Snapshot

Procrank Snapshot is the deliberate, heavier per-process memory view. It tries these sources in
order and only accepts output containing real process rows:

1. `procrank`
2. `su 0 procrank`
3. `su -c procrank`
4. `ps` fallback when proportional-memory data is not available

With procrank, the report contains VSS, RSS, PSS and USS. With the `ps` fallback, PSS and USS are
left unavailable rather than copied from RSS. The report header states the actual source.

- **PSS** proportionally shares mapped pages and is the fairest per-process total.
- **USS** is private memory that would be reclaimed when the process exits.
- **RSS** counts resident shared pages in every process and can overstate system use.
- **VSS** is virtual address space and is usually not a useful pressure measure by itself.

Outputs are `procrank-snapshot_<yyyyMMdd_HHmmss>.html` and a neighboring
`procrank-snapshot_<yyyyMMdd_HHmmss>.debug.txt`. The debug sidecar records the attempted commands and
raw responses so an empty/header-only OEM implementation or permission failure can be diagnosed.

## API flow

Both tools run on background threads so the Home UI remains responsive:

| Tool | Start | Status | Report |
|---|---|---|---|
| Device Snapshot | `POST /api/snapshot` | `GET /api/snapshot-status` | `GET /api/snapshot-report` |
| Procrank Snapshot | `POST /api/procrank-snapshot` | `GET /api/procrank-status` | `GET /api/procrank-report` |

Only one capture of each type runs at once. The selected serial must be present in the request; ADB
or parse failures are returned as explicit error state.

## Why live Top Process Memory is different

`procrank` walks process memory maps and is commonly root-gated, so running it every sample would
disturb the workload and can stall on OEM builds. The live chart instead uses a cheap `ps` RSS read,
with `/proc` fallback strategies where supported.

| | Live chart | Procrank Snapshot |
|---|---|---|
| Source | `ps`/`/proc` strategies | `procrank` with su attempts, then `ps` |
| Primary metric | RSS | PSS when available, otherwise RSS |
| Cost | Low enough for periodic sampling | Deliberate on-demand deep dive |
| Root | Not normally required | Usually required for complete PSS/USS |

## Privacy and limitations

The HTML and debug sidecar can contain device properties, package/process names and memory values.
They remain local but are not sanitized support bundles. Production devices may restrict procrank,
parts of `/proc`, sysfs or dumpsys. Missing data is shown as unavailable or a named fallback; it is
not treated as zero or as a successful proportional-memory capture.
