# Offline Player Debugger Design

## Summary

ADB Performance Studio will add an offline Player Debugger for Android developers and QA teams. It reconstructs playback sessions, player instances, failures, and delivery handoffs from ADB evidence, then presents them as a correlated flight recorder rather than isolated logs.

The feature is player-, project-, vendor-, and Android-form-factor agnostic. It works in a zero-integration passive mode and becomes deterministic when an application emits the optional Open Player Telemetry for Debugging protocol (OPTD). Retail, CastLabs, MediaKind, UCGMS, and DVB are validation cases and reference integrations; none becomes a product dependency.

The feature remains fully offline. It requires no cloud service, account, CDN, remote analytics SDK, or internet-based classification.

## Goals

- Explain why playback failed, stalled, rendered black, lost audio, or switched delivery.
- Correlate application events with device, process, codec, rendering, network, and memory evidence on one monotonic timeline.
- Model multiple overlapping player instances inside one logical viewing session.
- Diagnose multicast to OTT fallback and bidirectional OTT/DVB handoffs without hard-coded vendor logic.
- Work with any ADB-authorized Android device, while reporting the diagnostic depth available on that device.
- Provide useful passive diagnostics without requiring application changes.
- Offer a small, optional Android adapter for reliable application-reported state and handoff correlation.
- Preserve portable evidence that can be replayed, shared, compared, and inspected without the original device.
- Extend the current monitoring, dashboard, report-viewer, and Release Guard architecture without breaking existing features.

## Non-goals for the first release

- iOS or non-ADB devices.
- Remote device farms, cloud ingestion, hosted dashboards, or automatic uploads.
- Bundling or linking CastLabs, MediaKind, UCGMS, DVB, Media3, or application SDKs into the desktop product.
- Parsing every vendor's private log format in the core product.
- Decrypting media, capturing protected video, bypassing DRM, or recording content frames.
- Proving internal player state from passive evidence when Android does not expose it.
- Automatically modifying customer projects.
- A general-purpose log viewer unrelated to playback incidents.

## Product modes

The same UI and domain model support three evidence sources.

### Passive probe

Passive mode requires only an ADB-authorized device. It consumes the existing monitoring samples and logcat stream plus bounded, capability-gated media snapshots. It can report process lifecycle, crashes, ANRs, codec and DRM evidence visible in logs, CPU, memory, PSI, network, thermal state, audio/media service state, and rendering evidence exposed by the device.

Passive conclusions are never promoted to application-reported facts. Each observation is labelled `MEASURED`, `INFERRED`, or `UNAVAILABLE`, with its source and confidence.

### Structured telemetry

An application may emit OPTD newline-delimited JSON through one logcat tag. The desktop app auto-discovers the protocol from a capabilities event. Structured telemetry provides stable viewing-session IDs, player-instance IDs, state transitions, errors, metrics, and handoff outcomes.

The adapter is optional. Its absence must not disable passive collection.

### Imported evidence

A user may reopen an exported Player Debugger bundle without a device. Imported sessions use the same parser, session graph, incident engine, and UI as live sessions. Re-analysis never mutates original evidence.

## Architecture

Player Debugger extends the current layered architecture and reuses one `MonitorSession`, one logcat process, one monotonic session clock, and the existing artifact lifecycle.

```text
Android device
  |-- existing metric samples
  |-- shared logcat stream
  |-- bounded media/device snapshots
  `-- optional OPTD events
             |
             v
      Evidence normalizers
             |
             v
      PlaybackEvent stream
             |
       +-----+------------------+
       |                        |
       v                        v
 SessionGraphBuilder      IncidentEngine
       |                        |
       +------------+-----------+
                    v
             PlayerDebugSession
                    |
       +------------+-------------+
       |            |             |
       v            v             v
 Playback      Flight Recorder  Support bundle
 Cockpit       and Inspector    and report
```

New code follows current package boundaries:

```text
domain/playerdebug/
  PlayerDebugModels.kt
  PlayerTelemetryProtocol.kt
  IncidentModels.kt

data/playerdebug/
  OptdParser.kt
  PassivePlaybackProbe.kt
  MediaSnapshotCollector.kt
  PlayerDebugArtifactStore.kt

application/playerdebug/
  PlayerDebugService.kt
  SessionGraphBuilder.kt
  PlayerIncidentEngine.kt
  SupportBundleService.kt

presentation/web/
  PlayerDebuggerPage.kt

presentation/report/
  PlayerDebugReport.kt
```

The existing `data.logcat.Playback` behavior is preserved during migration. Its patterns become one passive normalizer rather than a separate player-debugging subsystem. No second logcat process is introduced.

## Domain model

### Logical viewing session

A `ViewingSession` represents the user's attempt to watch one logical piece of content or channel. It may contain several sequential or overlapping player instances. A content change creates a new viewing session only when the adapter reports it or a conservative passive rule has strong evidence; ambiguity remains visible rather than silently merging unrelated playback.

### Player instance

A `PlayerInstance` represents one concrete player lifecycle. Its identity is opaque and scoped to the capture. Core fields are:

```kotlin
data class PlayerInstance(
    val id: String,
    val viewingSessionId: String,
    val family: String?,
    val implementation: String?,
    val delivery: String?,
    val role: PlayerRole,
    val startedAtNs: Long,
    val endedAtNs: Long?,
    val capabilities: Set<String>
)
```

`family`, `implementation`, and `delivery` are strings, not compiled vendor enums. Suggested delivery categories include `ott`, `multicast`, `dvb-c`, `dvb-t`, and `local`, but custom values are valid. `role` is `PRIMARY`, `CANDIDATE`, `FALLBACK`, or `UNKNOWN`.

### Normalized event

Every observation becomes an immutable `PlaybackEvent` with:

- schema version and event type;
- session-relative monotonic timestamp;
- optional device wall-clock timestamp for display only;
- package, process, viewing-session, and player-instance identifiers where known;
- normalized attributes and optional raw vendor attributes;
- evidence source: `APP_REPORTED`, `DEVICE_MEASURED`, `LOG_PARSED`, or `ENGINE_INFERRED`;
- confidence from 0.0 to 1.0;
- references to raw log lines or snapshot artifacts; and
- redaction status.

The incident engine uses monotonic time exclusively for ordering and durations. Wall-clock changes cannot reorder a session.

### Handoff

A `PlayerHandoff` links source and target instances and records request time, activation time, source-release time, reason, result, and overlap duration. Either endpoint may be missing when passive evidence is incomplete.

Supported result categories are `SUCCEEDED`, `FAILED`, `CANCELLED`, `LOOPED`, and `UNKNOWN`. Delivery and player names remain open strings.

## Open Player Telemetry for Debugging protocol

OPTD version 1 is a logcat transport for debug and QA builds. It is not a remote telemetry service.

### Transport

- Tag: `OPTD`.
- Payload: one UTF-8 JSON object per log line.
- Maximum serialized event size: 8 KiB. Larger diagnostics are summarized and written by the application to an explicitly exported file only when the customer integration supports that workflow.
- Ordering: receivers sort by `timeNs`, then input sequence.
- Timestamp: `SystemClock.elapsedRealtimeNanos()` from the application process.
- Unknown fields are ignored.
- Unknown event types are retained as raw events but do not drive state rules.
- Malformed lines are retained in parser diagnostics and never terminate capture.

### Required envelope

```json
{
  "schema": "optd/1",
  "type": "state.changed",
  "timeNs": 8124112039000,
  "viewSessionId": "view-42",
  "instanceId": "player-18",
  "data": {
    "from": "buffering",
    "to": "playing"
  }
}
```

`schema`, `type`, `timeNs`, and `data` are required. Identifiers are required when the event relates to a session or player. Package and process are taken from logcat metadata rather than trusted from the JSON payload.

### Event families

- `capabilities`: adapter name/version, supported event types, metric names, and optional player families.
- `session.started`, `session.content_changed`, `session.ended`.
- `player.created`, `player.activated`, `player.released`.
- `state.changed`.
- `buffer.started`, `buffer.ended`.
- `render.first_frame`, `render.video_available`, `render.video_unavailable`.
- `audio.available`, `audio.unavailable`.
- `track.changed`, `quality.changed`.
- `metric.sample`.
- `error.reported`.
- `handoff.requested`, `handoff.completed`.
- `diagnostic.snapshot` containing only bounded, redacted structured values.

The protocol defines semantic event meanings, not player APIs. Customer adapters translate their own callbacks.

### Capability discovery

The first `capabilities` event establishes structured integration for the package and process. The UI lists each event family as reported, passive-only, or unavailable. If an application restarts, the next capabilities event starts a new adapter process epoch without discarding the logical viewing session.

### Version handling

The desktop app accepts the latest supported major version and ignores additive fields. An unsupported major version is displayed as incompatible, while passive mode continues. Exported bundles preserve raw protocol lines for future re-analysis.

## Passive collection

Passive collection is capability-driven and bounded. It uses only signals exposed by the connected Android build.

### Continuous inputs

- The existing logcat process, preferably using a monotonic output clock when supported.
- Existing device and target-process performance samples.
- Process start, death, ANR, Java crash, and native crash evidence.
- Recognized platform codec, DRM, audio, rendering, and media-service messages.
- Existing configurable playback patterns, including current lifecycle and tunnel-mode detection.

### Periodic inputs

Media snapshots run on a background collector and never block the primary sampling loop. The capability probe determines which bounded commands are safe and available. Possible providers include codec, audio, SurfaceFlinger, media metrics, and package/process state. A provider that times out is backed off and marked degraded.

Normal sampling remains lightweight. Expensive dumps are event-triggered on an error, prolonged buffer, failed first-frame deadline, handoff request, or manual Capture now action. Triggered snapshots are rate-limited and deduplicated.

### Passive identity

Without OPTD, instance and session identities are synthetic. The graph builder opens or closes candidates from high-confidence lifecycle evidence. It must not infer a vendor from class names unless a user-supplied signature pack explicitly says so. Synthetic nodes are visually marked as inferred.

## Evidence and confidence

The UI uses four explicit states:

- `Reported`: emitted by the application adapter.
- `Measured`: directly observed from the device or process.
- `Inferred`: derived by a documented deterministic rule.
- `Unavailable`: unsupported, blocked, timed out, or not emitted.

Each inference stores the rule ID, rule version, inputs, threshold, and confidence. Reports distinguish absence of evidence from evidence that an event did not occur.

## Session graph and handoff intelligence

`SessionGraphBuilder` is a deterministic reducer over ordered events. It creates viewing sessions, player instances, state intervals, and handoff edges. Replaying the same event stream produces the same graph.

It handles overlapping players deliberately:

- A target may initialize and buffer while the source still plays.
- Successful handoff requires target activation or first-frame evidence, not only a request.
- Source release is tracked separately from target success.
- A source remaining active beyond the configured grace period creates an overlap observation.
- Repeated transitions between the same delivery pair inside a bounded window form a fallback-loop candidate.
- Application-reported handoff IDs take priority over temporal inference.

Default thresholds vary by delivery category but are editable in a local rule profile. Missing delivery information uses conservative generic thresholds.

## Incident engine

The first release uses deterministic, explainable rules. No cloud or opaque machine-learning model is required.

Initial incidents are:

- playback error;
- startup timeout before first frame;
- prolonged or repeated buffering;
- black-screen candidate: playing/audio evidence without video availability or frame progress;
- audio-only candidate: audio present while video remains unavailable;
- silent-video candidate: video present while expected audio is unavailable;
- failed or slow handoff;
- fallback loop;
- zombie player: replaced instance continues consuming measurable resources or remains active;
- concurrent-decoder risk during prolonged overlap;
- decoder, surface, or process failure visible in platform evidence;
- excessive dropped-frame or decoder-stall rate when reported/measured;
- memory-pressure or thermal correlation around a playback failure; and
- repeated error fingerprint.

An incident contains severity, status, rule explanation, evidence links, affected session/instances, confidence, and suggested next checks. It never claims a root cause when evidence only supports correlation.

Error fingerprints normalize volatile values such as timestamps, addresses, session IDs, URLs, tokens, and media identifiers before hashing. Original raw evidence remains subject to redaction rules.

## Retail reference mapping

The inspected Retail project validates the protocol without shaping the product core.

- `playercommon.IPlayer` and `PlayerState` map common lifecycle, buffer, playback, and error events.
- `player.MediaSession` is the preferred common seam for instance creation, subscription, and release.
- `playerui.PlayerActivity` supplies player-selection, retry, multicast-to-OTT fallback, and OTT/DVB handoff intent and result.
- MediaKind enriches stalls, quality, throughput, dropped frames, startup timing, and diagnostics.
- UCGMS enriches decoder stalls, dropped frames, bitrate, bytes, tracks, Widevine diagnostics, channel changes, and first frame.
- DVB enriches tuning, video availability, buffering, signal-related failures, and audio-only state.
- CastLabs enriches DRM, buffering, bandwidth, bitrate, and first-frame information.

The reference adapter should be a small Retail-owned module or source set that depends on the generic OPTD helper. The generic helper must not depend on Retail modules or vendor SDKs. Retail modifications require separate authorization and are outside this specification's first implementation boundary.

## UI design

All screens reuse `STUDIO_UI_CSS`, existing dark surfaces, gold primary actions, violet/teal evidence accents, focus treatment, reduced-motion behavior, and the in-application webview. No remote web assets are used.

### Playback Cockpit

The entry screen shows device/package, capture state, adapter status, evidence coverage, current viewing session, active player instances, delivery path, and highest-severity open incident. The primary action starts or stops capture. Capture configuration persists independently from route visibility, so returning Home does not reset it.

### Flight Recorder

The central timeline uses synchronized lanes for:

- viewing sessions and content changes;
- player instances and states;
- handoffs and fallback edges;
- buffering, first frame, audio/video availability, quality, and errors;
- CPU, memory, PSI, network, thermal, and process evidence; and
- device/platform snapshots.

Zoom, pan, time-range selection, and incident navigation affect every lane together. Selecting an event opens its normalized fields and raw evidence without leaving the timeline.

### Incident Lens

Incident Lens orders actionable failures above informational observations. Each card states what happened, why the rule fired, confidence, affected player/handoff, supporting evidence, and suggested checks. Similar fingerprints are grouped without hiding individual occurrences.

### Live Inspector

The inspector shows normalized state, adapter-reported values, device-measured values, raw OPTD JSON, matching log lines, and snapshot excerpts. Users can filter by viewing session, player instance, event type, source, confidence, and text.

### Handoff Map

The map shows delivery/player nodes and directed transition edges. Edge labels include reason, duration, result, overlap, and incident count. It supports multicast to OTT and bidirectional OTT/DVB transitions without assuming those are the only possibilities.

### Evidence labels

Reported, Measured, Inferred, and Unavailable use text and icons as well as color. Reduced motion disables timeline entrance and pulse animations without hiding state changes.

## Capture workflow

```text
SELECT DEVICE/PACKAGE
  -> PROBE CAPABILITIES
  -> READY
  -> CAPTURING
  -> FINALIZING
  -> ANALYZING
  -> COMPLETE | PARTIAL | CANCELLED | FAILED
```

Start validates device connectivity, target package, storage, and at least one usable evidence provider. A device disconnect preserves partial evidence and waits for the same device within a bounded reconnection window. Stop terminates capture, finalizes current artifacts, and runs deterministic analysis. Application restart creates a new process epoch. Capture remains usable if no OPTD adapter appears.

## Storage and portable bundles

Player Debugger uses the application's existing local storage conventions. A capture folder is self-describing and recoverable without its SQLite index:

```text
PlayerDebugger/
  <package-or-project>/
    <capture-id>/
      manifest.json
      events.ndjson
      session-graph.json
      incidents.json
      metrics.csv
      logcat.log
      parser-diagnostics.json
      snapshots/
      report.html
```

SQLite indexes captures, devices, packages, viewing sessions, player instances, handoffs, incidents, and artifact paths. Large evidence remains in files. Metadata writes use temporary files and atomic replacement. Interrupted captures reopen as `PARTIAL` and can be re-indexed from their manifests.

A support bundle is a ZIP containing the portable capture after a redaction preview. It includes no screenshots or protected content unless the user explicitly adds an existing artifact.

## Privacy and safety

- The feature is local-only and opens no outbound connection.
- URLs retain scheme/host only by default; query strings and path identifiers are redacted.
- Authorization headers, cookies, license challenges, tokens, subscriber IDs, device IDs, and content IDs are removed or salted per bundle.
- Raw OPTD attributes not declared safe by the protocol are redacted before export.
- DRM payloads, media segments, decoded frames, and protected surfaces are never captured.
- The UI previews files and redaction counts before bundle export.
- Raw local logs remain available to the user but are clearly marked as potentially sensitive.
- Advanced shell commands are not introduced by Player Debugger.

## Default performance signals

On first use, Player Debugger enables:

- CPU utilization;
- per-core CPU;
- top process CPU;
- memory used;
- PSI pressure stall; and
- top process memory.

Existing saved signal preferences take precedence. Enabling Player Debugger never resets a user's metric selections. Disabling logcat disables structured telemetry and log-derived passive evidence only after a clear dependency warning.

## API surfaces

The loopback application API adds endpoints for:

- capability probing;
- capture start, stop, and status;
- capture/session/incident listing;
- timeline-window retrieval;
- raw-evidence retrieval by registered artifact reference;
- capture re-analysis;
- local rule profiles; and
- support-bundle creation.

Mutating endpoints use POST, validate device/package/capture identifiers, and reject arbitrary file paths. Artifact endpoints serve only registered files beneath the Player Debugger storage root.

The first release does not require a public CLI capture workflow. The stored bundle format is documented and replayable so CLI support can be added without changing the domain model.

## Failure handling

- Malformed OPTD events are counted and retained in parser diagnostics.
- Unsupported protocol majors degrade to passive mode.
- A slow snapshot provider times out, backs off, and becomes degraded without stalling metric sampling.
- Logcat rotation or reader restart creates a visible evidence gap.
- Duplicate events are deduplicated only when their stable event ID matches; similar events are preserved.
- Out-of-order events are reordered inside a bounded window and marked late beyond it.
- Storage exhaustion stops capture safely and preserves the last valid manifest.
- Device disconnection preserves a partial capture and never silently switches targets.
- Rule failures affect only the failing rule and appear in analysis diagnostics.
- Missing data yields `UNAVAILABLE`, not a healthy result.

## Testing and acceptance criteria

### Unit tests

- OPTD parsing, additive fields, unknown event types, malformed lines, and unsupported versions.
- Monotonic ordering, bounded late events, process epochs, and deterministic replay.
- Session, player-instance, overlap, handoff, and fallback-loop graph reduction.
- Incident rules and confidence/source propagation.
- Redaction and normalized error fingerprints.
- Capability degradation and provider backoff.

### Integration fixtures

Replayable fixtures cover:

- passive OTT startup and failure;
- structured successful OTT playback;
- UCGMS multicast to OTT fallback;
- OTT to DVB and DVB to OTT handoffs;
- overlapping players with late source release;
- failed fallback and fallback loop;
- black-screen, audio-only, and silent-video candidates;
- decoder stall/dropped-frame escalation;
- application restart, device disconnect, missing capabilities, and malformed telemetry; and
- restricted production device exposing only minimal passive evidence.

Fixtures use generic player names in core tests. Retail-specific names appear only in reference mapping fixtures.

### UI regression tests

- Start/stop and partial-capture recovery.
- Playback Cockpit capability and adapter status.
- Flight Recorder synchronized navigation and evidence selection.
- Incident Lens filtering and evidence links.
- Handoff Map for multicast/OTT/DVB transitions.
- Saved signal preferences and unsaved capture configuration survive navigation.
- Keyboard navigation, accessible evidence labels, and reduced motion.
- All assets load offline inside the desktop webview.

### Acceptance criteria

The first implementation is complete when:

1. A user can capture and inspect a passive playback session with no app changes.
2. A sample app or fixture can emit OPTD and produce deterministic player instances and state intervals.
3. Multicast to OTT and bidirectional OTT/DVB handoffs appear as correlated graph edges.
4. At least startup timeout, prolonged buffering, playback error, failed handoff, fallback loop, black-screen candidate, and zombie-player rules produce linked evidence.
5. Capture and re-analysis work without internet access.
6. A redacted support bundle reopens on another installation without the device.
7. Existing Home, Dashboard, Compare, diagnostics, reports, Release Guard, and desktop navigation continue to pass regression tests.
8. No production code imports Retail or a player-vendor SDK.

## Delivery sequence

1. Normalized domain models, OPTD parser, redaction, and replay fixtures.
2. Session graph builder and deterministic handoff model.
3. Passive probe integration using the shared logcat and monitor session.
4. Incident engine and evidence links.
5. Artifact storage, recovery, and support-bundle export.
6. Loopback APIs and Playback Cockpit.
7. Flight Recorder, Incident Lens, Live Inspector, and Handoff Map.
8. Generic Android OPTD helper and sample integration outside vendor modules.
9. Full regression, offline, desktop-webview, and real-device verification.

The Retail reference adapter is a separate follow-up requiring explicit permission to modify the Retail repository. It is not required for the generic Player Debugger to ship.
