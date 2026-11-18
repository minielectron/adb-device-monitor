# Player Debugger

Player Debugger is an offline playback evidence system for Dev and QA. It is intentionally agnostic
to player SDK, delivery technology, Android form factor, device manufacturer, and application
architecture. A capture can represent one player, four parallel players, or a changing graph such as
multicast → OTT fallback and OTT ↔ DVB switching without a product-specific adapter in the desktop
tool.

## Acquisition and data sources

Player Debugger starts the normal `MonitorSession` with logcat and normalized player capture enabled.
The UI does not read a player SDK directly. It combines the following bounded sources and preserves
their evidence type instead of presenting all of them as equally certain.

| Source | How it is collected | What it contributes | Important limit |
|---|---|---|---|
| Device logcat | ADB starts `logcat -v threadtime` after a best-effort buffer clear and writes `logcat.log` throughout the capture. The passive probe reads only new bytes, up to 2 MiB per scan. | App/native crash, ANR, codec, DRM and audio errors; generic tunnel patterns; evidence references by byte offset and line. | OEM/vendor messages may be absent, obfuscated or rotated. Rotation creates an explicit evidence-gap event. |
| OPTD/1 lines | The app writes bounded newline JSON with logcat tag `OPTD`; the desktop parser validates schema, package, depth, types, finite numbers and size. | Exact session, player, delivery, state, buffering, first-frame, A/V, quality, error and handoff identity as **App reported** evidence. | Optional app integration. Invalid lines become parser diagnostics and are not trusted events. |
| Android media snapshots | Capability-gated `dumpsys media.codec`, `dumpsys media.audio_flinger`, `dumpsys SurfaceFlinger` and `dumpsys media.player`. | Bounded raw device context around codec, audio, surface and playback behavior. | Each provider is capped at 1 MiB, uses a five-second command timeout, normally samples no faster than ten seconds, rate-limits event-triggered captures to five seconds, and backs off after failures. |
| Performance snapshot | Batched `/proc/stat`, `/proc/meminfo`, `/proc/pressure/*` plus enabled process collectors. | CPU, per-core CPU, memory, PSI, top CPU and top memory aligned with playback time. | Production builds can restrict `/proc`; missing signals remain unavailable. |
| Package measurements | `dumpsys meminfo <package>` and process CPU sources used by `MetricsCollector`. | App PSS/breakdown and app process cost for the selected package. | Requires the package to be valid and running; dumpsys is heavier than basic `/proc` reads. |
| Engine reconstruction | Ordered normalized events are replayed through `SessionGraphBuilder` and versioned incident rules. | Sessions, instances, handoff edges, overlap/duration, loops and incidents as **Inferred** evidence with confidence and linked sources. | An inference explains the evidence relationship; it is not a player-reported fact. Re-analysis is deterministic over stored events. |

The first-use UI checkboxes enable CPU utilization, per-core CPU, top-process CPU, memory used, CPU/
memory/I/O PSI and top-process memory. PSI is disabled until the selected device reports rooted ADB;
its saved preference is restored on a rooted device. The capture API also enables package PSS and app
process CPU unless a caller explicitly disables them. Logcat evidence is required for every Player
Debugger capture.

## Capture modes

### Passive capture — no app integration

The tool records logcat and the selected device metrics through ADB. It recognizes bounded platform
evidence including app/native crashes, ANRs, codec/DRM/audio errors, generic tunnel mode, and
process/resource behavior. Media snapshots use public Android dumpsys/proc interfaces when
the device exposes them.

Passive mode is the universal baseline. It can prove that an error or resource spike happened, but
obfuscated/vendor logs may not expose which logical player instance owned it. The UI therefore marks
missing identity as unavailable or inferred rather than inventing a vendor-specific answer.

### Structured capture — optional OPTD/1

OPTD (Open Player Telemetry for Debugging) is a newline JSON protocol written to logcat with tag
`OPTD`. The helper in [`../integrations/android-optd/`](../integrations/android-optd/) is Kotlin
standard-library-only and accepts an injected logging sink, so it can be copied into an Android app
without adding a runtime SDK dependency. It has no knowledge of player implementations.

The app chooses its own neutral labels, for example:

```kotlin
val optd = Optd(enabled = BuildConfig.DEBUG) { tag, payload -> Log.i(tag, payload) }
optd.sessionStarted("view-42")
optd.playerCreated("view-42", "player-a", family = "ott", delivery = "unicast")
optd.handoffRequested("view-42", "handoff-7", "player-a", "player-b", "signal_loss")
optd.handoffCompleted("view-42", "handoff-7", "player-a", "player-b", "succeeded")
```

The emitter is opt-in and does no validation, clock work, serialization, or output while disabled.
When enabled it enforces monotonic time, bounded identifiers/attributes, an 8 KiB event maximum,
finite numbers, JSON escaping, and rejection of sensitive keys, labelled secrets, and URLs. The
desktop parser independently enforces schema, depth, value-count, type and package boundaries; a bad
line becomes a parser diagnostic and does not stop the capture.

Useful structured events include capabilities, session start/change/end, player
create/activate/release, state changes, buffering, first frame, audio/video availability, track and
quality changes, metrics, errors, handoff request/completion, and diagnostic snapshots.

## Evidence and confidence

The UI never collapses unlike sources into a single claim:

- **Reported** — valid app OPTD telemetry.
- **Measured** — directly observed device data.
- **Log parsed** — recognized Android/log pattern.
- **Inferred** — a deterministic relationship reconstructed by the engine.
- **Unavailable** — the input was absent or inaccessible; this is not a passing result.

Each normalized event carries a monotonic time, source, confidence and, where available, a registered
artifact/line reference. Session graph reconstruction is deterministic. Incident Lens rules are
versioned and return an explanation, affected session/player/handoff, confidence, linked evidence and
suggested checks. Re-analysis uses stored events and does not require the original device.

## First-use diagnostics and saved preferences

New Player Debugger drafts enable these diagnostics:

- CPU utilization and per-core CPU
- top-process CPU
- memory used and top-process memory
- CPU, memory and I/O PSI stalls
- required logcat playback evidence

This set is intentionally broad enough for playback triage without automatically collecting a full
system trace. The selected device, package, duration, interval and signal checkboxes are saved to
local browser storage. Navigating Home and back, or restarting the desktop shell, restores the draft.

## Handoff Map, Flight Recorder and Incident Lens

Handoff Map is a directed graph, not a fixed fallback chain. Every player/delivery combination is a
node, every request/completion is an edge, and the edge retains reason, activation duration, overlap,
result and related incident count. Self-loops, repeated edges, failures and missing endpoints remain
visible.

Flight Recorder pages through the normalized event artifact and aligns sessions, players, handoffs,
state, buffering, first frame, A/V, quality, errors, performance and snapshot lanes. Filters and the
event inspector operate on normalized fields; raw excerpts are available only through capture-
registered bounded text artifacts.

Incident Lens currently detects deterministic patterns such as startup timeout, playing without
video evidence, buffer loops, crash/ANR, codec/DRM/audio failures, A/V disagreement, handoff failure
and loop behavior. It reports evidence gaps separately from healthy behavior.

## Local storage and offline behavior

The default root is:

```
~/ADB Device Monitor/PlayerDebugger/
├── player-debugger.db
└── captures/<sanitized-package>/<capture-id>/
    ├── manifest.json
    ├── events.ndjson
    ├── session-graph.json
    ├── incidents.json
    ├── parser-diagnostics.json
    ├── metrics.csv
    ├── logcat.log
    └── player-debug-report.html
```

Files are written atomically and paths are confined to the capture root. An interrupted capture is
recoverable as partial evidence. The SQLite database is an index; the artifact folder remains the
source of truth and can be re-indexed.

The generated HTML report is static: no JavaScript, fonts, analytics, CDN assets, remote URLs, or
network requests. Its content-security policy permits only inline style and data images. The desktop
application displays it in its own full-screen viewer, and Back returns to the current debugger state.

## Privacy and support bundles

The capture directory may contain raw local diagnostic material and should be protected like any
developer log. Export is a separate, explicit action:

1. **Privacy preview** lists included/excluded files, estimated bytes and redaction categories.
2. **Export sanitized bundle** salts identifiers, strips raw event JSON, redacts sensitive text and
   limits each file and the expanded archive size.
3. Binary/screenshots, media segments, traces, DRM payloads and license challenges are excluded by
   policy. Only registered capture artifacts inside the capture directory can be opened or exported.
4. Import rejects path traversal, directories, excessive entry count/size and missing manifest
   references.

The HTML report is also sanitized: it summarizes evidence and shows artifact basenames, not device
serials, raw logs, raw JSON or arbitrary event attributes.

## OEM and Android limitations

- Production Android builds can restrict log buffers, `/proc`, sysfs, codec dumps and other dumpsys
  services. Root is never assumed.
- Log rotation or truncation creates an explicit evidence-gap event.
- OEM/vendor player messages may be absent or obfuscated. Passive identity then has lower confidence;
  OPTD is the portable way to add exact identity without changing the desktop engine.
- Android background restrictions, process death and USB/TCP disconnects can produce partial captures.
- DRM-protected media is not captured. The tool records diagnostic metadata and errors only.
- A signal that cannot be read is shown unavailable; incident rules do not treat absence as proof of
  health.

## Manual QA checklist

- [ ] Disconnect the Mac from the internet; launch the packaged app and complete the checks below.
- [ ] Passive: capture a standard Android app with no OPTD integration; confirm parsed/measured
  evidence appears and structured-only fields are unavailable rather than fabricated.
- [ ] Structured: replay valid and invalid OPTD lines; confirm valid events are ordered and invalid
  lines appear in parser diagnostics without ending the capture.
- [ ] Multi-player: exercise multicast → OTT fallback, OTT → DVB, DVB → OTT and a failed/looped
  switch; confirm Handoff Map edges, overlap, results and linked incidents.
- [ ] Parallel players: activate multiple instances and confirm Flight Recorder filtering by session,
  instance, type, source, confidence and text.
- [ ] Stop early and force an app/device disconnect; confirm partial evidence survives and re-analysis
  works without the device.
- [ ] Open the offline report inside the app, press Back, and confirm the capture and unsaved form
  draft remain intact. Download the report and inspect it with networking disabled.
- [ ] Preview and export a support bundle; confirm excluded binaries and redaction counts, then import
  the zip into a clean local data root and reopen the graph/timeline/incidents.
- [ ] Search the report and sanitized bundle for the device serial, tokens, URLs, raw JSON, media and
  DRM payloads; none should be present.
- [ ] Run a device whose OEM blocks at least one source; confirm the UI says unavailable and the rest
  of the capture remains usable.

## Project neutrality

The Retail project was used only to understand real multi-player orchestration challenges. It is not
a dependency, fixture, submodule or build input. Product-specific classes, SDKs, log tags and player
names are intentionally absent from the protocol and engine, so the same capture model can be used by
another Android phone, TV, STB, application or player stack.
