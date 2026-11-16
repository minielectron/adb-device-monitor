package devicemonitor

import devicemonitor.application.playerdebug.EventReplay
import devicemonitor.application.playerdebug.SessionGraphBuilder
import devicemonitor.domain.playerdebug.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionGraphBuilderTest {
    @Test fun buildsOverlappingFallbackWithIndependentMilestones() {
        val graph = build(
            event(0, 10, "session.started", view = "view"),
            event(1, 20, "player.created", view = "view", player = "multi", data = mapOf("delivery" to "multicast", "role" to "primary")),
            event(2, 30, "state.changed", view = "view", player = "multi", data = mapOf("to" to "playing")),
            event(3, 50, "handoff.requested", view = "view", id = "h1", data = mapOf("fromInstanceId" to "multi", "toInstanceId" to "ott", "reason" to "decoder_stall")),
            event(4, 55, "player.created", view = "view", player = "ott", data = mapOf("delivery" to "ott", "role" to "fallback")),
            event(5, 70, "render.first_frame", view = "view", player = "ott"),
            event(6, 75, "handoff.completed", view = "view", id = "h1", data = mapOf("result" to "succeeded")),
            event(7, 90, "player.released", view = "view", player = "multi")
        )
        val session = graph.viewingSessions.single()
        assertEquals(setOf("multicast", "ott"), session.playerInstances.mapNotNull { it.delivery }.toSet())
        val handoff = session.handoffs.single()
        assertEquals(HandoffResult.SUCCEEDED, handoff.result)
        assertEquals(70, handoff.activatedAtNs)
        assertEquals(90, handoff.sourceReleasedAtNs)
        assertEquals(20, handoff.overlapNs)
        assertEquals("decoder_stall", handoff.reason)
    }

    @Test fun supportsOttAndDvbInBothDirections() {
        val graph = build(
            event(0, 1, "session.started", view = "view"),
            event(1, 2, "player.created", view = "view", player = "ott", data = mapOf("delivery" to "ott")),
            event(2, 3, "player.created", view = "view", player = "dvb", data = mapOf("delivery" to "dvb-t")),
            event(3, 4, "handoff.requested", view = "view", id = "to-dvb", data = mapOf("fromInstanceId" to "ott", "toInstanceId" to "dvb")),
            event(4, 5, "handoff.completed", view = "view", id = "to-dvb", data = mapOf("result" to "success")),
            event(5, 6, "handoff.requested", view = "view", id = "to-ott", data = mapOf("fromInstanceId" to "dvb", "toInstanceId" to "ott")),
            event(6, 7, "handoff.completed", view = "view", id = "to-ott", data = mapOf("result" to "playing"))
        )
        assertEquals(2, graph.viewingSessions.single().handoffs.size)
        assertTrue(graph.viewingSessions.single().handoffs.all { it.result == HandoffResult.SUCCEEDED })
    }

    @Test fun createsVisibleSyntheticSessionAndPlayerForPassiveEvents() {
        val graph = build(
            event(0, 10, "state.changed", view = null, player = null, data = mapOf("to" to "playing"), source = EvidenceSource.LOG_PARSED)
        )
        val session = graph.viewingSessions.single()
        assertTrue(session.synthetic)
        assertTrue(session.playerInstances.single().synthetic)
        assertEquals("playing", session.stateIntervals.single().state)
    }

    @Test fun closesStateAndMarksFailedHandoff() {
        val graph = build(
            event(0, 1, "player.created", view = "view", player = "p1"),
            event(1, 2, "state.changed", view = "view", player = "p1", data = mapOf("to" to "buffering")),
            event(2, 8, "state.changed", view = "view", player = "p1", data = mapOf("to" to "playing")),
            event(3, 9, "handoff.completed", view = "view", id = "h", data = mapOf("fromInstanceId" to "p1", "toInstanceId" to "p2", "result" to "failed"))
        )
        val session = graph.viewingSessions.single()
        assertEquals(8, session.stateIntervals.first().endedAtNs)
        assertEquals(HandoffResult.FAILED, session.handoffs.single().result)
        assertNotNull(session.playerInstances.find { it.id == "p2" })
    }

    @Test fun buildIsDeterministic() {
        val events = arrayOf(
            event(1, 20, "state.changed", view = "view", player = "p", data = mapOf("to" to "playing")),
            event(0, 10, "player.created", view = "view", player = "p")
        )
        assertEquals(build(*events), build(*events.reversedArray()))
    }

    private fun build(vararg events: PlaybackEvent) =
        SessionGraphBuilder().build(EventReplay().replay(events.asList()))

    private fun event(
        sequence: Long,
        time: Long,
        type: String,
        view: String?,
        player: String? = null,
        id: String? = null,
        data: Map<String, String> = emptyMap(),
        source: EvidenceSource = EvidenceSource.APP_REPORTED
    ) = PlaybackEvent(
        sequence = sequence,
        schema = "optd/1",
        type = type,
        timeNs = time,
        packageName = "example.app",
        pid = 42,
        viewingSessionId = view,
        instanceId = player,
        eventId = "event-$sequence",
        attributes = if (id == null) data else data + ("handoffId" to id),
        source = source,
        confidence = if (source == EvidenceSource.APP_REPORTED) 1.0 else 0.7
    )
}
