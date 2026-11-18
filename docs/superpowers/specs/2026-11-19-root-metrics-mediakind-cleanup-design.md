# Root metric correction and MediaKind lifecycle cleanup

## Goal

Correct metric availability for the tested device environment and remove the project-specific
MediaKind lifecycle dependency from the generic product. Preserve generic Android performance,
tunnel-mode and Player Debugger behavior.

## Correct root-required metrics

The root-required set is exactly:

- PSI pressure stall (`m_psi` on Home and `psiPressure` in Player Debugger)
- CPU temperature (`m_thermal` on Home)

Top Process CPU, Main-thread CPU and Media/HAL CPU are not root-gated. They remain selectable on
non-rooted devices and report unavailable data if a particular Android build restricts their source.

The existing `GET /api/devices` root status remains the only capability source. With no selected
device or with `available`, `unavailable`, or `unknown` root status, PSI and CPU temperature are
unchecked and disabled. Their saved selections are restored when a device reports `root`.

Request construction remains defensive: Home sends PSI and CPU temperature as enabled only for a
rooted selected device; Player Debugger does the same for PSI. No collector or API schema changes are
needed for root gating.

## Remove project-specific MediaKind lifecycle handling

The generic application must no longer recognize `mk-player-lifecycle` log lines or present their raw
`onXxx` values as a video-player state. Remove:

- the MediaKind lifecycle regular expression and recognized-state table;
- inferred Player Debugger `state.changed` events derived from those log lines;
- the Live Dashboard Video Player State chart, lifecycle notice and player-state badge;
- command-line and documentation claims that `--playback` tracks a player lifecycle; and
- MediaKind lifecycle test fixtures and expectations.

Keep the generic tunnel-mode path:

- MediaCodec, ACodec and CCodec `TUNNELED` / `NON-TUNNELED` detection;
- the `isTunneledPlaybackEnabled` build/config signal;
- the Live Dashboard Tunnel Mode chart and tunnel status;
- Player Debugger OPTD events, generic platform error evidence, tunnel changes, handoffs and bounded
  Android media snapshots; and
- the `--playback` option, reworded as tunnel-mode tracking.

## Compatibility boundary

Keep the nullable `videoState` sample/API field and the `video_state` CSV column for existing stored
runs, imports and readers. New generic monitoring runs do not populate them from MediaKind logs. This
avoids a storage and API migration while removing the project-dependent acquisition and UI.

The standalone playback log analysis keeps tunnel configuration counts and the build tunnel flag but
does not report lifecycle transitions or a final player state.

## Presentation

Root-required controls keep the existing red-dot `ROOT` treatment and disabled styling. The labels
move from Top Process CPU, Main-thread CPU and Media/HAL CPU to PSI Pressure Stall and CPU
Temperature. Player Debugger moves the label from Top Process CPU to PSI Pressure Stall.

Home renames the `Playback and tunnel` metric to `Tunnel mode`. Live Dashboard keeps one compact
tunnel-analysis card and removes MediaKind-specific copy.

## Error handling

An unknown or failed root probe safely disables PSI and CPU temperature without losing their saved
preferences. Tunnel detection continues to tolerate missing log lines by reporting unknown or not
observed. Removing the lifecycle parser does not convert missing player state into a healthy state;
the generic Player Debugger continues to show only evidence actually supplied by OPTD or recognized
platform signals.

## Verification

Automated checks cover:

- root metadata and defensive serialization for PSI and CPU temperature only;
- Top Process CPU, Main-thread CPU and Media/HAL CPU remaining ungated;
- Player Debugger PSI gating and preference preservation;
- absence of `mk-player-lifecycle` and the raw player-state dashboard surface;
- continued tunnel-mode parsing, dashboard rendering and analysis;
- embedded JavaScript syntax; and
- updated documentation and default-selection expectations.

Run the full Kotlin test suite and the production macOS package build.

## Out of scope

- User-configurable lifecycle regular expressions
- Removing legacy `videoState` fields or CSV columns
- Changing OPTD schemas or Player Debugger incident rules
- General per-metric capability probing
