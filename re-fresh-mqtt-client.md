# Session refresh — add MQTT client to push device metrics/events remotely

Continuing work in the **adb-device-monitor** project ("ADB Performance Studio"), working dir
`/Users/prakashsharma/Documents/adb-tools/adb-device-monitor`. A Kotlin/Gradle fat-jar tool that
monitors adb-connected Android/STB devices and already produces per-tick metric samples + logcat
anomaly events, exposed via a web dashboard, PDF/CSV reports.

Goal of this session: add a **remote telemetry feature over MQTT** — a client module that connects
to an MQTT broker and publishes the tool's metrics and events, so device updates can be consumed
remotely. Needs a configurable MQTT client (broker URL, topic, credentials, QoS) that subscribes to
the existing sample/event stream and pushes selected metrics as they're produced.

## Where things stand
- The app is fully working; the last thread of work (memory chart bug) is resolved — unrelated to
  this goal but the codebase is in a clean, building state (build stamp `2026-07-12 15:03`).
- No MQTT code exists yet. This is a net-new module.
- Metrics are already collected once per sampling tick as a `Sample` object; anomaly/activity/
  playback events are already extracted from logcat. The MQTT publisher should hook into that same
  per-tick production point rather than re-collecting anything.
- `WebServer.sessionJson()` is the existing pattern for serializing a `Sample` to JSON — reuse its
  field shape (or factor a shared serializer) so MQTT payloads match the dashboard's.

## Design notes / suggested approach
- Add the **Eclipse Paho** MQTT client (`org.eclipse.paho:org.eclipse.paho.client.mqttv3`) to
  `build.gradle` dependencies; make sure it's included in the `fatJar` task's shaded classpath.
- Add MQTT settings to the `Config` data class in `Session.kt` (broker URL, clientId, topic prefix,
  username/password, QoS, enable flag, publish interval / which metrics). Parse them in `Main.kt`
  (CLI flags) and/or `WebServer` start form.
- Create `Mqtt.kt` (a publisher class): connect with auto-reconnect, publish a JSON payload per
  sample (device serial in topic, e.g. `adb-monitor/<serial>/metrics`), and publish events
  (anomalies/activity) to `adb-monitor/<serial>/events`. Keep publish off the sampling thread (fire
  to a bounded queue / background thread) so a slow/unreachable broker can't stall sampling — mirror
  the existing background-collector + hardened-timeout pattern already used in the codebase.
- Wire it in `Session.doRun()`: after each `samples += collector.toSample(...)`, hand the new sample
  to the publisher; also publish new anomalies from the incremental scanner.
- Publish a retained "status"/LWT (last-will) message so remote consumers see connect/disconnect.

## Next steps
1. Read the files below to learn the sample/event production points and the JSON field shape.
2. Add Paho dependency to `build.gradle` + confirm it's bundled by the `fatJar` task.
3. Add MQTT fields to `Config` (Session.kt) and parse them in `Main.kt` (+ optional web form).
4. Implement `Mqtt.kt` publisher (async, auto-reconnect, LWT, bounded queue).
5. Hook publisher into `Session.doRun()` sample loop + anomaly scanner; gate behind the enable flag.
6. Build in `/tmp/adb-device-monitor-build` (`./gradlew fatJar`), copy jar back to `build/libs/`,
   smoke-test against a local broker (e.g. Mosquitto) with `mosquitto_sub`.

## Files to open (read these, don't re-derive)
- `src/main/kotlin/devicemonitor/Session.kt` — `Config` data class (~line 40, add MQTT flags),
  `doRun()` sampling loop (~line 293) where each `Sample` is produced and where the background
  "slow-collector" thread pattern lives (copy that async pattern for non-blocking publish).
- `src/main/kotlin/devicemonitor/Metrics.kt` — the `Sample` data class (~line 7) and `toSample()`
  (~line 799): the exact metric fields available to publish.
- `src/main/kotlin/devicemonitor/WebServer.kt` — `sessionJson()` (~line 318): existing per-sample
  JSON serialization to mirror for MQTT payloads; also how a session is started/configured.
- `src/main/kotlin/devicemonitor/Main.kt` — CLI arg parsing + `main()` entry; add MQTT flags here.
- `src/main/kotlin/devicemonitor/Adb.kt` — device serial/target handling (used to build topics).
- `build.gradle` — add the Paho dependency and confirm the `fatJar` task shades it.

## Build & workflow notes
- Sandbox can't build in-place reliably; the working pattern is: `rsync` the project (excluding
  `.git`, `build`, `.gradle`) to `/tmp/adb-device-monitor-build`, run `./gradlew fatJar` there, then
  copy `build/libs/adb-device-monitor-1.0.0-all.jar` back to the project's `build/libs/`.
- The sandbox **cannot run git** (mount blocks `.git` writes) — commit on the Mac via the project's
  `git-setup.sh`.
- Run/verify: `java -jar "build/libs/adb-device-monitor-1.0.0-all.jar" --web 8080`. Logs also write
  to `~/ADB Device Monitor/adb-monitor.log`.
