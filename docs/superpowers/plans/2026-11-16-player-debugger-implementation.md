# Offline Player Debugger Implementation Plan

## Goal

Add a production-ready, fully offline Android Player Debugger to ADB Performance Studio. It must capture useful passive playback evidence without application changes, accept optional vendor-neutral OPTD events for deterministic player and handoff correlation, reconstruct multi-player viewing sessions, detect explainable incidents, and reopen a sanitized support bundle without the original device.

## Constraints

- Work on `feature/release-guard` unless the user requests a different branch.
- Keep the product independent of Retail and every player/vendor SDK.
- Do not modify `/Users/prakashsharma/StudioProjects/retail` during this plan.
- Reuse the existing `MonitorSession`, logcat process, monotonic capture clock, run folder, ADB timeouts, abort behavior, and offline webview.
- Preserve the existing `Playback` lifecycle/tunnel feature while migrating it behind the new normalizer.
- Never start a second logcat reader for Player Debugger.
- Keep expensive media snapshots off the primary sampling loop and capability-gate them.
- Keep all runtime assets local; add no cloud, CDN, account, or remote inference dependency.
- Store player and delivery identifiers as open strings, not vendor enums.
- Label every value as reported, measured, inferred, or unavailable.
- Preserve Home and Player Debugger form state across in-app navigation.
- Keep the six approved performance signals enabled on first use without overriding saved preferences.
- Add tests with each non-trivial behavior and keep the application buildable after every milestone.

## Milestone 1: Protocol and normalized evidence

### Task 1: Add immutable Player Debugger domain models

**Create**

- `src/main/kotlin/devicemonitor/domain/playerdebug/PlayerDebugModels.kt`
- `src/main/kotlin/devicemonitor/domain/playerdebug/IncidentModels.kt`
- `src/test/kotlin/devicemonitor/PlayerDebugModelTest.kt`

**Implement**

- Stable capture, process-epoch, viewing-session, player-instance, state-interval, handoff, event, evidence-reference, incident, and capability models.
- Open string fields for player family, implementation, delivery, state, metrics, and vendor attributes.
- Stable enums only for product-owned concepts: evidence source, availability, confidence class, player role, handoff result, capture state, incident severity, and incident status.
- Session-relative monotonic nanoseconds as the authoritative timeline field.
- Validation for blank IDs, negative times, invalid confidence, cross-session handoffs, event references outside the capture, and terminal states without end times.
- Constants for Player Debugger artifact and event schema versions.

**Test**

- Valid single-player and overlapping-player sessions.
- Multicast to OTT, OTT to DVB, and DVB to OTT handoffs.
- Custom player and delivery strings.
- Every model validation failure.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebugModelTest
```

### Task 2: Implement the OPTD parser

**Create**

- `src/main/kotlin/devicemonitor/domain/playerdebug/PlayerTelemetryProtocol.kt`
- `src/main/kotlin/devicemonitor/data/playerdebug/OptdParser.kt`
- `src/test/kotlin/devicemonitor/OptdParserTest.kt`

**Implement**

- Parse one `OPTD` logcat JSON object into a normalized event using Gson already in the project.
- Require `schema`, `type`, `timeNs`, and object-valued `data`.
- Extract package, PID, TID, priority, tag, and device timestamp from logcat metadata rather than the JSON body.
- Support all approved v1 event families while retaining unknown v1 event types as raw events.
- Ignore additive unknown fields.
- Reject unsupported major versions without stopping passive capture.
- Bound payload processing to 8 KiB, nesting depth, collection sizes, and retained raw attributes.
- Record parser diagnostics for malformed, oversized, unsupported, and incomplete lines.
- Keep parsing side-effect-free and deterministic.

**Test**

- Every approved event family.
- Additive fields and unknown event types.
- Malformed JSON, missing envelope fields, wrong value types, oversized lines, extreme nesting, and unsupported versions.
- Unicode strings and escaped values.
- Spoofed package/PID fields cannot override logcat metadata.

**Verify**

```bash
./gradlew test --tests devicemonitor.OptdParserTest
```

### Task 3: Add redaction and stable fingerprints

**Create**

- `src/main/kotlin/devicemonitor/data/playerdebug/PlayerDebugRedactor.kt`
- `src/test/kotlin/devicemonitor/PlayerDebugRedactorTest.kt`

**Implement**

- Redact tokens, authorization values, cookies, query strings, license payloads, subscriber/device/content IDs, and configured sensitive keys.
- Reduce URLs to scheme and host by default.
- Salt stable identifiers per exported bundle.
- Normalize timestamps, addresses, UUID-like values, URLs, and session identifiers before hashing error fingerprints.
- Return redaction counts and categories for export preview.
- Never transform the original local log file in place.

**Test**

- Representative URLs, headers, DRM-related text, JSON attributes, stack traces, and identifiers.
- Equivalent errors with volatile values produce one fingerprint.
- Meaningfully different errors remain distinct.
- Already-redacted input stays stable.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebugRedactorTest
```

## Milestone 2: Deterministic session graph

### Task 4: Implement ordered event replay

**Create**

- `src/main/kotlin/devicemonitor/application/playerdebug/EventReplay.kt`
- `src/test/kotlin/devicemonitor/PlayerEventReplayTest.kt`

**Implement**

- Sort by monotonic timestamp and stable input sequence.
- Reorder events inside a bounded lateness window.
- Mark events arriving beyond the window as late without deleting them.
- Deduplicate only by an explicit stable event ID within the same process epoch.
- Create a new process epoch after app restart/capabilities reappearance.
- Preserve evidence-gap markers for logcat restarts or truncated input.

**Test**

- In-order, out-of-order, late, duplicate, same-timestamp, process-restart, and evidence-gap fixtures.
- Replaying identical input yields byte-equivalent normalized event output.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerEventReplayTest
```

### Task 5: Build viewing sessions, player instances, and handoffs

**Create**

- `src/main/kotlin/devicemonitor/application/playerdebug/SessionGraphBuilder.kt`
- `src/test/kotlin/devicemonitor/SessionGraphBuilderTest.kt`

**Implement**

- Reduce ordered events into viewing sessions, player instances, state intervals, and handoff edges.
- Prefer application-reported identities and handoff correlation IDs.
- Generate visibly synthetic identities only for passive evidence.
- Support overlapping source and target instances.
- Track request, target activation/first frame, source release, overlap duration, reason, and outcome independently.
- Detect handoff candidates without assuming vendor names or a fixed delivery pair.
- Retain ambiguous observations instead of force-merging sessions.
- Return immutable graph snapshots suitable for live polling and final replay.

**Test**

- Single player lifecycle.
- Prewarmed target overlapping a still-playing source.
- Successful and failed multicast to OTT fallback.
- Successful OTT/DVB transitions in both directions.
- Source never released, target never activates, cancellation, fallback loop candidate, application restart, and ambiguous passive input.
- Deterministic graph serialization for replay fixtures.

**Verify**

```bash
./gradlew test --tests devicemonitor.SessionGraphBuilderTest
```

## Milestone 3: Passive evidence and capture integration

### Task 6: Replace tail replay with one incremental playback evidence reader

**Modify**

- `src/main/kotlin/devicemonitor/data/logcat/Playback.kt`
- `src/main/kotlin/devicemonitor/application/Session.kt`
- `src/main/kotlin/devicemonitor/domain/MetricModels.kt`
- `src/test/kotlin/devicemonitor/ParserTest.kt`
- `src/test/kotlin/devicemonitor/AdbAndLogTest.kt`

**Create**

- `src/main/kotlin/devicemonitor/data/playerdebug/PassivePlaybackProbe.kt`
- `src/test/kotlin/devicemonitor/PassivePlaybackProbeTest.kt`

**Implement**

- Read newly appended logcat bytes once and feed both the existing lifecycle/tunnel state and Player Debugger normalizers.
- Preserve current MediaKind lifecycle and tunnel-mode outputs exactly for existing dashboard/report consumers.
- Add generic platform evidence for process death, ANR, Java/native crash, MediaCodec, DRM, audio, and rendering messages where the platform line is unambiguous.
- Parse OPTD from the same new-line stream.
- Anchor events to the capture's monotonic clock; retain the raw file offset/line reference.
- Surface log rotation/truncation as an evidence gap.
- Avoid rereading the trailing 512 KiB on every sample after migration.

**Test**

- Existing `Playback.scan`, tracker, tunnel, and post-hoc analysis behavior remains compatible.
- Incremental append, partial line, file truncation, invalid UTF-8 replacement, high-volume input, and no duplicate output.
- Mixed lifecycle, platform, and OPTD lines retain chronological order.

**Verify**

```bash
./gradlew test --tests devicemonitor.ParserTest --tests devicemonitor.AdbAndLogTest --tests devicemonitor.PassivePlaybackProbeTest
```

### Task 7: Add bounded media snapshot providers

**Create**

- `src/main/kotlin/devicemonitor/data/playerdebug/MediaSnapshotCollector.kt`
- `src/test/kotlin/devicemonitor/MediaSnapshotCollectorTest.kt`

**Modify**

- `src/main/kotlin/devicemonitor/data/device/CapabilityProbe.kt`
- `src/main/kotlin/devicemonitor/application/Session.kt`
- `src/test/kotlin/devicemonitor/CapabilityProbeTest.kt`

**Implement**

- Define small providers for only the codec, audio, rendering, media-service, package, and process evidence exposed by a device.
- Probe commands/services by bounded observable behavior, never device model or manufacturer.
- Run periodic snapshots on a dedicated background worker.
- Trigger rate-limited snapshots after playback error, prolonged buffer, missed first frame, handoff request, and manual capture.
- Apply timeout, adaptive backoff, degraded status, deduplication, and per-provider size limits.
- Save raw snapshot artifacts and emit only normalized summaries into the event stream.
- Stop/abort the collector with the existing session abort lifecycle.

**Test**

- Available, unavailable, permission-denied, empty, timeout, oversized, and changing-provider fixtures.
- Backoff and event-trigger rate limits.
- Slow providers never delay the primary sample loop.
- Stop cancels in-flight ADB work without marking a healthy run failed.

**Verify**

```bash
./gradlew test --tests devicemonitor.MediaSnapshotCollectorTest --tests devicemonitor.CapabilityProbeTest
```

### Task 8: Add the Player Debugger capture service

**Create**

- `src/main/kotlin/devicemonitor/application/playerdebug/PlayerDebugService.kt`
- `src/test/kotlin/devicemonitor/PlayerDebugServiceTest.kt`

**Modify**

- `src/main/kotlin/devicemonitor/application/Session.kt`

**Implement**

- Orchestrate SELECT/PROBE/READY/CAPTURING/FINALIZING/ANALYZING and terminal states.
- Attach to one existing `MonitorSession` and its run directory.
- Expose immutable live status, event windows, graph snapshots, adapter capabilities, provider health, and parser diagnostics.
- Preserve partial evidence after stop, device loss, app restart, analysis failure, or storage exhaustion.
- Reconnect only to the same device identity within the configured window.
- Prevent simultaneous Player Debugger and Release Guard captures from owning the same physical device.
- Keep capture useful if no OPTD event is ever observed.

**Test**

- Passive-only and structured capture.
- Normal stop, early stop, device loss/reconnect, wrong-device reconnect, app restart, malformed telemetry, provider degradation, analysis failure, and storage exhaustion.
- Existing `MonitorSession` status, stop, report, and Release Guard capture behavior does not regress.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebugServiceTest --tests devicemonitor.ScenarioRunnerTest
```

## Milestone 4: Explainable incidents

### Task 9: Implement deterministic incident rules

**Create**

- `src/main/kotlin/devicemonitor/application/playerdebug/PlayerIncidentEngine.kt`
- `src/main/kotlin/devicemonitor/application/playerdebug/PlayerIncidentRules.kt`
- `src/test/kotlin/devicemonitor/PlayerIncidentEngineTest.kt`

**Implement**

- Versioned rules for playback error, startup timeout, prolonged/repeated buffer, failed/slow handoff, fallback loop, black-screen candidate, audio-only candidate, silent-video candidate, zombie player, prolonged decoder overlap, excessive dropped frames/decoder stalls, and resource-pressure correlation.
- Delivery-category threshold profiles with conservative generic fallback values.
- Evidence requirements, confidence, severity, rule explanation, and suggested checks for every finding.
- Distinguish root-cause evidence from temporal correlation.
- Group matching error fingerprints while preserving individual occurrences.
- Treat missing evidence as unavailable, never healthy.

**Test**

- Positive, boundary, and negative case for each rule.
- Structured and passive variants of the same incident.
- Missing video/audio/decoder signals do not create false healthy outcomes.
- User threshold overrides remain local and versioned.
- Re-analysis with the same rule version is deterministic.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerIncidentEngineTest
```

### Task 10: Add generic replay fixtures

**Create**

- `src/test/resources/playerdebug/passive-ott-failure/`
- `src/test/resources/playerdebug/structured-ott-success/`
- `src/test/resources/playerdebug/multicast-to-ott/`
- `src/test/resources/playerdebug/ott-to-dvb/`
- `src/test/resources/playerdebug/dvb-to-ott/`
- `src/test/resources/playerdebug/fallback-loop/`
- `src/test/resources/playerdebug/overlap-zombie/`
- `src/test/resources/playerdebug/restricted-device/`
- `src/test/kotlin/devicemonitor/PlayerDebugFixtureTest.kt`

**Implement**

- Store minimal logcat, OPTD, sample, snapshot, expected graph, and expected incident artifacts.
- Use generic names in core fixtures; keep Retail/vendor names out of expected core behavior.
- Add one fixture generator helper only if hand-authored files become inconsistent.
- Assert full replay from raw evidence through graph and incidents.

**Test**

- Every fixture reproduces its golden graph and incident summaries.
- Fixture re-analysis performs no network access and no ADB call.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebugFixtureTest
```

## Milestone 5: Recoverable storage and support bundles

### Task 11: Add Player Debugger artifacts and index

**Create**

- `src/main/kotlin/devicemonitor/data/playerdebug/PlayerDebugArtifactStore.kt`
- `src/main/kotlin/devicemonitor/data/playerdebug/PlayerDebugRepository.kt`
- `src/main/kotlin/devicemonitor/data/playerdebug/SqlitePlayerDebugRepository.kt`
- `src/test/kotlin/devicemonitor/PlayerDebugArtifactStoreTest.kt`
- `src/test/kotlin/devicemonitor/SqlitePlayerDebugRepositoryTest.kt`

**Modify**

- `src/main/kotlin/devicemonitor/data/storage/ReleaseGuardJson.kt` only if a generic canonical JSON helper can be reused without coupling the features.

**Implement**

- Persist `manifest.json`, `events.ndjson`, `session-graph.json`, `incidents.json`, parser diagnostics, snapshots, and registered monitoring artifacts.
- Use atomic metadata replacement and root-containment checks.
- Index captures, devices, packages, process epochs, viewing sessions, players, handoffs, incidents, and relative artifact paths.
- Rebuild index rows from a self-contained capture folder.
- Recover interrupted captures as partial without deleting raw evidence.
- Stream large event files rather than loading the full capture for timeline windows.

**Test**

- Normal capture, partial recovery, corrupt manifest, unsupported schema, duplicate indexing, traversal, symlink escape where supported, concurrent IDs, and large event-window pagination.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebugArtifactStoreTest --tests devicemonitor.SqlitePlayerDebugRepositoryTest
```

### Task 12: Export and reopen redacted support bundles

**Create**

- `src/main/kotlin/devicemonitor/application/playerdebug/SupportBundleService.kt`
- `src/test/kotlin/devicemonitor/PlayerSupportBundleTest.kt`

**Implement**

- Produce an offline ZIP from registered capture artifacts.
- Build a redaction preview containing files, findings, redaction counts, excluded items, and estimated size.
- Exclude screenshots and content imagery unless explicitly selected.
- Exclude DRM payloads, media segments, decoded frames, protected surfaces, and arbitrary unregistered files unconditionally.
- Reopen and re-analyze a compatible bundle without a device.
- Preserve raw schema/version metadata for future re-analysis.

**Test**

- Default sanitized export, explicit safe-artifact inclusion, excluded sensitive artifacts, path traversal in imported ZIPs, decompression limits, corrupt bundle, unsupported version, and round-trip replay.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerSupportBundleTest
```

## Milestone 6: Loopback API and Studio UI

### Task 13: Add Player Debugger API routes

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`

**Create**

- `src/test/kotlin/devicemonitor/PlayerDebuggerApiTest.kt`

**Implement**

- Routes for capability probe, capture start/stop/status, capture history, graph summary, paginated timeline windows, incidents, evidence lookup, re-analysis, rule profiles, export preview, bundle export, and bundle import.
- POST-only mutations, request-size limits, stable structured errors, device/package/capture ID validation, and registered-artifact containment.
- One capture owner per physical device across ordinary monitoring, Release Guard, and Player Debugger.
- Timeline pagination that does not serialize the full capture on each poll.
- Register Player Debugger service/repository cleanup in `WebApp.stop()`.

**Test**

- Success and validation paths for every route.
- Unsupported method, malformed/oversized JSON, unknown IDs, traversal, capture conflict, unavailable device, partial capture, and imported bundle.
- Existing Home, session, report, diagnostics, Compare, and Release Guard routes remain compatible.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebuggerApiTest --tests devicemonitor.ReleaseGuardApiTest
```

### Task 14: Build Playback Cockpit and capture configuration

**Create**

- `src/main/kotlin/devicemonitor/presentation/web/PlayerDebuggerPage.kt`

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/HomePage.kt`
- `src/main/kotlin/devicemonitor/presentation/web/StudioUi.kt`
- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`
- `src/test/kotlin/devicemonitor/OfflineAssetsTest.kt`

**Implement**

- Add Player Debugger navigation and route without changing current Home control IDs or flows.
- Reuse the current dark Studio theme, app bar, typography, gold actions, violet/teal evidence accents, focus behavior, transitions, and reduced-motion rules.
- Device/package selection, adapter status, evidence coverage, provider health, current viewing session, active players, delivery path, highest-severity incident, and start/stop capture.
- Default-enable CPU utilization, per-core CPU, top process CPU, memory used, PSI pressure stall, and top process memory only when no saved preference exists.
- Warn before disabling logcat-dependent evidence.
- Store draft capture configuration independently from route visibility and restore it after returning Home or restarting the desktop UI.
- Render reported/measured/inferred/unavailable with icon and text, not color alone.

**Test**

- Required controls, offline assets, CSP, labels, focus order, reduced motion, narrow layout, and saved preferences.
- Home and Player Debugger drafts survive navigation independently.
- No external URLs or runtime asset downloads.

**Verify**

```bash
./gradlew test --tests devicemonitor.OfflineAssetsTest
```

### Task 15: Build Flight Recorder, Incident Lens, and Inspector

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/PlayerDebuggerPage.kt`
- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`
- `src/test/kotlin/devicemonitor/OfflineAssetsTest.kt`

**Create**

- `src/test/kotlin/devicemonitor/PlayerDebuggerUiContractTest.kt`

**Implement**

- Synchronized lanes for sessions, players, handoffs, states, buffer/first-frame/audio/video/quality/errors, performance signals, and device snapshots.
- Shared zoom, pan, selection, range, and incident navigation.
- Windowed timeline loading for long captures.
- Incident cards with rule explanation, confidence, affected instances, evidence links, fingerprint group, and suggested checks.
- Live Inspector filters for session, instance, event type, source, confidence, and text.
- Registered raw evidence excerpts without arbitrary file access.
- Graceful rendering when charts, metrics, or player identity are unavailable.

**Test**

- Timeline lane and incident contracts from fixtures.
- Keyboard navigation, filter behavior, evidence links, unavailable-state rendering, incremental polling, long-capture windows, and partial capture.
- One failed lane does not prevent other lanes or Stop from working.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebuggerUiContractTest --tests devicemonitor.OfflineAssetsTest
```

### Task 16: Build the Handoff Map and offline report

**Create**

- `src/main/kotlin/devicemonitor/presentation/report/PlayerDebugReport.kt`
- `src/test/kotlin/devicemonitor/PlayerDebugReportTest.kt`

**Modify**

- `src/main/kotlin/devicemonitor/presentation/web/PlayerDebuggerPage.kt`
- `src/main/kotlin/devicemonitor/presentation/web/WebServer.kt`

**Implement**

- Directed player/delivery nodes with reason, duration, result, overlap, and incident-count edges.
- Support arbitrary transitions while rendering multicast/OTT/DVB examples clearly.
- Generate a self-contained offline HTML report with capture summary, capability coverage, graph, timeline summary, incidents, and evidence references.
- Open the report in the existing in-application report viewer.
- Keep raw sensitive values out of report summaries by default.

**Test**

- Multicast to OTT, OTT to DVB, DVB to OTT, custom delivery, loop, missing endpoint, and overlapping handoffs.
- Report contains no external dependency and reopens in the desktop webview.
- Sensitive fixture values do not appear in sanitized report output.

**Verify**

```bash
./gradlew test --tests devicemonitor.PlayerDebugReportTest --tests devicemonitor.OfflineAssetsTest
```

## Milestone 7: Optional generic Android helper

### Task 17: Publish the OPTD Android helper and sample

**Create**

- `integrations/android-optd/README.md`
- `integrations/android-optd/src/main/kotlin/devicemonitor/optd/Optd.kt`
- `integrations/android-optd/src/test/kotlin/devicemonitor/optd/OptdTest.kt`
- `integrations/android-optd/sample/README.md`

**Implement**

- A small Kotlin source/library that serializes the approved OPTD envelope to Android logcat.
- Session/player identity helpers, capabilities, lifecycle, buffer, render, error, metric, and handoff methods.
- Input validation, 8 KiB enforcement, monotonic timestamps, bounded attributes, and opt-in debug/QA enablement.
- No dependency on Retail, Media3, CastLabs, MediaKind, UCGMS, DVB, analytics SDKs, networking, or reflection.
- Framework-neutral examples showing how any player callback can map to OPTD.
- Clear warning not to include tokens, URLs with identifiers, DRM payloads, or customer data.

**Test**

- Golden protocol lines accepted by the desktop `OptdParser` fixtures.
- Disabled mode emits nothing.
- Oversized/sensitive attributes are rejected or redacted according to the helper contract.

**Verify**

```bash
./gradlew test
```

The exact helper packaging will follow the repository's build conventions discovered during this task. Do not add a second build system merely to publish one source helper; a separately consumable Gradle module is justified only if the project already supports it cleanly.

## Milestone 8: End-to-end regression and desktop verification

### Task 18: Run full verification and update documentation

**Modify**

- `docs/architecture.md`
- `docs/metrics.md`
- `docs/desktop-app.md`
- `docs/README.md`
- `README.md` if it contains the product feature overview

**Implement**

- Document passive versus structured capability, OPTD, evidence labels, storage, privacy, support bundles, and known OEM restrictions.
- Document the first-use default signals and saved-preference behavior.
- Document that Retail is a reference architecture only and is not bundled.
- Add a manual QA checklist for passive capture, structured fixture replay, multicast/OTT/DVB handoffs, support-bundle reopen, and offline operation.

**Verify**

```bash
./gradlew clean test fatJar
./desktop/build-macos.sh
```

Then launch the packaged application and manually verify:

1. Home, Live Dashboard, Compare, diagnostics, report viewer, and Release Guard still work.
2. Returning Home does not reset unsaved run or Player Debugger configuration.
3. A passive capture works with no OPTD adapter.
4. Structured fixtures reconstruct player instances and handoffs.
5. Multicast to OTT and both OTT/DVB directions render correctly.
6. Stop and device loss preserve partial evidence.
7. A sanitized support bundle reopens without a device.
8. The application operates with network access disabled.
9. No runtime class or artifact depends on Retail or a player-vendor SDK.

## Commit sequence

Keep commits small and independently testable:

1. `Add Player Debugger domain and OPTD parser`
2. `Build deterministic player session graph`
3. `Integrate passive playback evidence collection`
4. `Add bounded media snapshot providers`
5. `Detect explainable playback incidents`
6. `Store recoverable Player Debugger captures`
7. `Export sanitized player support bundles`
8. `Expose Player Debugger loopback API`
9. `Add Playback Cockpit and flight recorder`
10. `Add handoff map and offline player report`
11. `Publish generic OPTD Android helper`
12. `Verify Player Debugger desktop integration`

Do not combine unrelated refactoring, Retail changes, remote-service work, licensing, or public CLI capture into these commits.
