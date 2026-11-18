package devicemonitor

import devicemonitor.application.playerdebug.*
import devicemonitor.domain.playerdebug.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerIncidentEngineTest {
    private val s = 1_000_000_000L

    @Test fun detectsStartupTimeoutAndBlackScreenButNotAfterFirstFrame() {
        val failed = incidents(
            event(0, 0, "player.created", player = "p", data = mapOf("delivery" to "ott")),
            event(1, 1 * s, "state.changed", player = "p", data = mapOf("to" to "playing"))
        , end = 15 * s)
        assertTrue(failed.any { it.ruleId == "startup_timeout" })
        assertTrue(failed.any { it.ruleId == "black_screen_candidate" })

        val healthy = incidents(
            event(0, 0, "player.created", player = "p", data = mapOf("delivery" to "ott")),
            event(1, 1 * s, "state.changed", player = "p", data = mapOf("to" to "playing")),
            event(2, 2 * s, "render.first_frame", player = "p")
        , end = 15 * s)
        assertFalse(healthy.any { it.ruleId == "startup_timeout" || it.ruleId == "black_screen_candidate" })
    }

    @Test fun usesDeliveryAwareBufferThresholds() {
        val incidents = incidents(
            event(0, 0, "player.created", player = "p", data = mapOf("delivery" to "multicast")),
            event(1, 1 * s, "state.changed", player = "p", data = mapOf("to" to "buffering")),
            event(2, 5 * s, "state.changed", player = "p", data = mapOf("to" to "playing")),
            event(3, 5 * s, "render.first_frame", player = "p")
        , end = 6 * s)
        assertTrue(incidents.any { it.ruleId == "prolonged_buffering" })
    }

    @Test fun detectsFailedSlowAndZombieHandoffs() {
        val failed = incidents(
            player("a", "multicast", 0), player("b", "ott", 1),
            handoff(2, 2 * s, "h1", "a", "b", "requested"),
            handoff(3, 7 * s, "h1", "a", "b", "failed")
        , end = 8 * s)
        assertTrue(failed.any { it.ruleId == "failed_handoff" })

        val slowZombie = incidents(
            player("a", "multicast", 0), player("b", "ott", 1),
            handoff(2, 2 * s, "h2", "a", "b", "requested"),
            event(3, 8 * s, "player.activated", player = "b"),
            handoff(4, 8 * s, "h2", "a", "b", "succeeded")
        , end = 15 * s)
        assertTrue(slowZombie.any { it.ruleId == "slow_handoff" })
        assertTrue(slowZombie.any { it.ruleId == "zombie_player" })
    }

    @Test fun detectsFallbackLoopAcrossOpenDeliveryNames() {
        val incidents = incidents(
            player("a", "custom-a", 0), player("b", "custom-b", 1),
            handoff(2, 2 * s, "h1", "a", "b", "succeeded"),
            handoff(3, 3 * s, "h2", "b", "a", "succeeded"),
            handoff(4, 4 * s, "h3", "a", "b", "succeeded")
        , end = 5 * s)
        assertTrue(incidents.any { it.ruleId == "fallback_loop" })
    }

    @Test fun detectsAudioOnlyAndSilentVideoFromExplicitEvidence() {
        val audioOnly = incidents(
            player("p", "ott", 0),
            event(1, 1 * s, "audio.available", player = "p"),
            event(2, 2 * s, "render.video_unavailable", player = "p")
        , end = 3 * s)
        assertTrue(audioOnly.any { it.ruleId == "audio_only" })

        val silent = incidents(
            player("p", "ott", 0),
            event(1, 1 * s, "render.first_frame", player = "p"),
            event(2, 2 * s, "audio.unavailable", player = "p")
        , end = 3 * s)
        assertTrue(silent.any { it.ruleId == "silent_video" })
    }

    @Test fun detectsDecoderStressAndLabelsPressureAsCorrelationOnly() {
        val incidents = incidents(
            player("p", "ott", 0),
            event(1, 1 * s, "metric.sample", player = "p", measurements = mapOf("droppedFrames" to 31.0)),
            event(2, 2 * s, "resource.pressure", player = "p", measurements = mapOf("psiMemory" to 2.0)),
            event(3, 3 * s, "error.reported", player = "p")
        , end = 4 * s)
        assertTrue(incidents.any { it.ruleId == "decoder_stress" })
        assertTrue(incidents.single { it.ruleId == "resource_pressure_correlation" }.correlationOnly)
        assertTrue(incidents.any { it.ruleId == "playback_error" })
    }

    @Test fun ruleOutputIsDeterministic() {
        val events = arrayOf(player("p", "ott", 0), event(1, 1 * s, "error.reported", player = "p"))
        assertTrue(incidents(*events, end = 2 * s) == incidents(*events.reversedArray(), end = 2 * s))
    }

    private fun incidents(vararg events: PlaybackEvent, end: Long): List<PlayerIncident> {
        val replay = EventReplay().replay(events.asList())
        return PlayerIncidentEngine().analyze(SessionGraphBuilder().build(replay), end)
    }

    private fun player(id: String, delivery: String, sequence: Long) =
        event(sequence, sequence, "player.created", player = id, data = mapOf("delivery" to delivery))

    private fun handoff(sequence: Long, time: Long, id: String, from: String, to: String, result: String): PlaybackEvent {
        val type = if (result == "requested") "handoff.requested" else "handoff.completed"
        val data = mutableMapOf("handoffId" to id, "fromInstanceId" to from, "toInstanceId" to to)
        if (result != "requested") data["result"] = result
        return event(sequence, time, type, data = data)
    }

    private fun event(
        sequence: Long,
        time: Long,
        type: String,
        player: String? = null,
        data: Map<String, String> = emptyMap(),
        measurements: Map<String, Double> = emptyMap()
    ) = PlaybackEvent(
        sequence = sequence,
        schema = "optd/1",
        type = type,
        timeNs = time,
        packageName = "example.app",
        pid = 1,
        viewingSessionId = "view",
        instanceId = player,
        eventId = "event-$sequence",
        attributes = data,
        measurements = measurements,
        source = EvidenceSource.APP_REPORTED,
        confidence = 1.0
    )
}
