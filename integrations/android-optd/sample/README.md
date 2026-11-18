# Framework-neutral player mapping

Keep one opaque viewing-session ID for the user-visible playback attempt and one opaque instance ID per player object. IDs only need to correlate callbacks inside one capture.

```kotlin
optd.sessionStarted(viewId)
optd.playerCreated(viewId, castLabsId, family = "castlabs", delivery = "ott", role = "candidate")
optd.playerCreated(viewId, multicastId, family = "generic", delivery = "multicast", role = "primary")
optd.playerCreated(viewId, dvbId, family = "generic", delivery = "dvb", role = "fallback")

// Map normal callbacks; no vendor type crosses the OPTD boundary.
optd.stateChanged(viewId, multicastId, from = "preparing", to = "playing")
optd.firstFrame(viewId, multicastId)
optd.metricSample(viewId, multicastId, mapOf("bitrate" to bitrate, "droppedFrames" to droppedFrames))
```

Represent every fallback or switch as a directed handoff. The same calls cover multicast → OTT, OTT → DVB, DVB → OTT, and custom delivery types:

```kotlin
val handoffId = newOpaqueId()
optd.handoffRequested(viewId, handoffId, sourceInstanceId = multicastId, targetInstanceId = castLabsId, reason = "source unavailable")

// When the target is actually usable:
optd.playerActivated(viewId, castLabsId)
optd.firstFrame(viewId, castLabsId)
optd.handoffCompleted(viewId, handoffId, multicastId, castLabsId, result = "succeeded")

// Release can happen after target activation; this exposes overlap/zombie-player issues.
releaseOldPlayer()
optd.playerReleased(viewId, multicastId)
```

Report stable error codes, not exception dumps, URLs, DRM material, or customer-facing strings:

```kotlin
optd.errorReported(viewId, playerId, code = "decoder_init", category = "codec")
```

At teardown, emit player releases before `sessionEnded(viewId)`. If a callback is unavailable on a player/device, omit it; ADB Performance Studio will label that evidence unavailable rather than inventing a healthy value.
