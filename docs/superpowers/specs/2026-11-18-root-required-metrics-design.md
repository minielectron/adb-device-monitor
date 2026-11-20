# Root-required performance metrics

> Superseded for metric classification by
> [2026-11-19-root-metrics-mediakind-cleanup-design.md](2026-11-19-root-metrics-mediakind-cleanup-design.md).

## Goal

Prevent users from selecting performance metrics that cannot provide their intended data unless the
selected Android device is currently running ADB as root. Keep root-sensitive metrics selectable
when they have non-root data sources or fallbacks.

## Root-required set

The initial root-required set is deliberately explicit:

- Top process CPU (`m_topprocs` on Home, `topProcessCpu` in Player Debugger)
- Main-thread CPU (`m_maincpu` on Home)
- Media and HAL CPU (`m_mediacpu` on Home)

App process CPU remains available because it falls back to `dumpsys cpuinfo`. Top process memory
remains available because it uses `ps`/RSS. Thermal, CPU frequency and other OEM-dependent signals
remain selectable and report unavailable data when a device does not expose them.

## Capability source

Reuse the root status already returned for every connected device by `GET /api/devices`:

- `root`: ADB currently runs as UID 0; root-required metrics are enabled.
- `available`, `unavailable`, or `unknown`: root-required metrics are disabled.
- No selected authorized device: root-required metrics are disabled.

No additional device probe or API is introduced. After **Enable adb root** restarts ADB, the existing
device refresh supplies the new status and the metric controls are reevaluated.

## Presentation and interaction

Each root-required metric has a compact red `ROOT` indicator next to its name. The indicator and the
disabled control expose explanatory text stating that the metric needs a rooted ADB device.

When the selected device is not rooted, the application:

1. remembers whether each root-required metric was selected;
2. unchecks and disables the control;
3. excludes it from the run or Player Debugger request; and
4. restores the remembered selection if a rooted device is selected later.

The remembered preference must survive unrelated form edits and the existing local draft-save flow.
Changing devices must not reset any non-root-gated form value.

Home and Player Debugger use the same behavior. Player Debugger currently exposes only Top process
CPU from the root-required set.

## Implementation boundaries

- Add static root-required metadata to the existing metric markup.
- Add a small page-local function that applies availability from the selected device root status.
- Make draft serialization preserve the remembered selection rather than the temporary disabled
  state.
- Keep request construction defensive: a root-required metric is sent as enabled only when the
  selected device is rooted.
- Do not change collectors, sampling cadence, metric payloads, device probing, or backend request
  models.

## Error handling

An unavailable or failed device refresh is treated as non-rooted until a later successful refresh.
The normal device-scan error remains visible. Enabling root continues to use the existing ADB-root
flow; a failed root attempt leaves the controls disabled and does not discard the remembered metric
preferences.

## Verification

Automated checks cover:

- the red root indicator and root-required metadata on all four rendered controls;
- disabled and unchecked behavior for no device and non-rooted devices;
- enabled behavior and preference restoration for rooted devices;
- defensive request serialization for Home and Player Debugger;
- preservation of existing draft behavior; and
- documentation of the exact root-required set and non-root fallbacks.

The full Kotlin test suite and macOS packaging checks remain unchanged.

## Out of scope

- Dynamically probing individual `/proc` or sysfs paths
- Treating OEM-dependent or fallback-backed signals as root-required
- Adding root enablement to Player Debugger
- Changing the meaning or collection source of any metric
