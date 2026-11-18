# OPTD Android helper

`Optd.kt` is a small, dependency-free emitter for the Open Player Telemetry for Debugging (`optd/1`) envelope understood by ADB Performance Studio. It is device-, project-, and player-agnostic. It does not depend on this desktop application, AndroidX, Media3, CastLabs, MediaKind, UCGMS, DVB APIs, analytics SDKs, networking, or reflection.

The repository compiles the source through its existing Gradle build so the same golden events are tested against the desktop parser. Android projects can copy `Optd.kt` into a debug/QA source set or publish it through their existing internal build conventions; no second build system is required.

## Android setup

Create one application-scoped emitter and explicitly gate it to a debug or QA opt-in:

```kotlin
val optd = Optd(
    enabled = BuildConfig.DEBUG && qaSettings.playerTelemetryEnabled,
    emit = { tag, payload -> android.util.Log.i(tag, payload) }
)
```

Android adds the `OPTD` tag to logcat and the helper writes one compact JSON object per event. A disabled emitter performs no validation, clock read, serialization, or log call.

## Safety contract

- Never enable OPTD for production users by default.
- Never send authorization data, cookies, tokens, subscriber/device/content IDs, license challenges, DRM payloads, media segments, customer data, or URLs containing identifiers.
- Sensitive keys, URL-bearing strings, labeled secrets, non-finite numbers, attributes over 2 KiB, and payloads over 8 KiB are rejected before logcat emission.
- Use opaque per-playback session/player IDs. Do not reuse account, device, subscriber, or content identifiers.
- Treat local logcat as potentially sensitive evidence and use the desktop application's redaction preview before export.

## Event coverage

The helper provides convenience calls for capabilities, sessions, player creation/activation/release, state changes, buffering, first frame, audio/video availability, quality, metrics, errors, and handoffs. `event(...)` remains available for additive `optd/1` event types.

All timestamps come from a monotonic nanosecond clock and are forced to increase even if the supplied platform clock repeats. See [sample/README.md](sample/README.md) for framework-neutral callback mappings.
