package devicemonitor

import devicemonitor.domain.playerdebug.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerDebugModelTest {
    @Test fun acceptsOpenPlayerAndDeliveryNames() {
        val player = PlayerInstance(
            id = "player-1",
            viewingSessionId = "view-1",
            family = "customer-player",
            delivery = "satellite-over-custom-link",
            role = PlayerRole.PRIMARY,
            startedAtNs = 10
        )
        val session = ViewingSession("view-1", 10, playerInstances = listOf(player))
        assertEquals("customer-player", session.playerInstances.single().family)
        assertEquals("satellite-over-custom-link", session.playerInstances.single().delivery)
    }

    @Test fun supportsOverlappingPlayersAndHandoffDirections() {
        val multicast = player("multicast", "multicast", 10, 80)
        val ott = player("ott", "ott", 50, 140)
        val dvb = player("dvb", "dvb-t", 120, null)
        val toOtt = handoff("h1", "multicast", "ott", 45, 60, 80)
        val toDvb = handoff("h2", "ott", "dvb", 115, 130, 140)
        val session = ViewingSession(
            id = "view-1",
            startedAtNs = 10,
            playerInstances = listOf(multicast, ott, dvb),
            handoffs = listOf(toOtt, toDvb)
        )
        assertEquals(20, toOtt.overlapNs)
        assertEquals(10, toDvb.overlapNs)
        assertEquals(3, session.playerInstances.size)
    }

    @Test fun validatesCrossSessionReferencesAndTimes() {
        val player = player("p1", "ott", 10, null)
        assertFailsWith<IllegalArgumentException> {
            ViewingSession("other", 10, playerInstances = listOf(player))
        }
        assertFailsWith<IllegalArgumentException> {
            PlayerStateInterval("missing", "playing", 20, source = EvidenceSource.APP_REPORTED, confidence = 1.1)
        }
        assertFailsWith<IllegalArgumentException> {
            PlayerHandoff(
                id = "h",
                viewingSessionId = "view-1",
                requestedAtNs = 10,
                source = EvidenceSource.ENGINE_INFERRED,
                confidence = 0.5
            )
        }
    }

    @Test fun terminalCaptureRequiresEndTime() {
        assertFailsWith<IllegalArgumentException> {
            PlayerDebugCapture(
                id = "capture",
                deviceId = "device",
                packageName = "example.app",
                state = PlayerCaptureState.COMPLETE,
                startedAtNs = 1
            )
        }
        val capture = PlayerDebugCapture(
            id = "capture",
            deviceId = "device",
            packageName = "example.app",
            state = PlayerCaptureState.COMPLETE,
            startedAtNs = 1,
            endedAtNs = 2
        )
        assertTrue(capture.state.isTerminal())
    }

    @Test fun confidenceClassUsesStableThresholds() {
        fun event(confidence: Double) = PlaybackEvent(
            sequence = 0,
            schema = "optd/1",
            type = "state.changed",
            timeNs = 1,
            source = EvidenceSource.APP_REPORTED,
            confidence = confidence
        )
        assertEquals(ConfidenceClass.HIGH, event(0.8).confidenceClass)
        assertEquals(ConfidenceClass.MEDIUM, event(0.5).confidenceClass)
        assertEquals(ConfidenceClass.LOW, event(0.49).confidenceClass)
    }

    private fun player(id: String, delivery: String, start: Long, end: Long?) = PlayerInstance(
        id = id,
        viewingSessionId = "view-1",
        delivery = delivery,
        startedAtNs = start,
        endedAtNs = end
    )

    private fun handoff(id: String, from: String, to: String, requested: Long, activated: Long, released: Long) =
        PlayerHandoff(
            id = id,
            viewingSessionId = "view-1",
            sourceInstanceId = from,
            targetInstanceId = to,
            requestedAtNs = requested,
            activatedAtNs = activated,
            sourceReleasedAtNs = released,
            result = HandoffResult.SUCCEEDED,
            source = EvidenceSource.APP_REPORTED,
            confidence = 1.0
        )
}
