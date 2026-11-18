package devicemonitor

import devicemonitor.application.playerdebug.EventReplay
import devicemonitor.domain.playerdebug.EvidenceSource
import devicemonitor.domain.playerdebug.PlaybackEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerEventReplayTest {
    @Test fun ordersEventsAndMarksOnlyBeyondWindowAsLate() {
        val result = EventReplay(latenessWindowNs = 20).replay(
            listOf(event(0, 100), event(1, 90), event(2, 50), event(3, 110))
        )
        assertEquals(listOf(50L, 90L, 100L, 110L), result.events.map { it.timeNs })
        assertTrue(result.events.first { it.timeNs == 50L }.late)
        assertFalse(result.events.first { it.timeNs == 90L }.late)
        assertEquals(1, result.lateEvents)
    }

    @Test fun deduplicatesOnlyStableIdsInsideOneEpoch() {
        val duplicate = event(1, 2, eventId = "same")
        val result = EventReplay().replay(
            listOf(event(0, 1, eventId = "same"), duplicate, event(2, 3, eventId = null))
        )
        assertEquals(2, result.events.size)
        assertEquals(1, result.duplicateEvents)
    }

    @Test fun repeatedCapabilitiesStartsNewProcessEpoch() {
        val result = EventReplay().replay(
            listOf(
                event(0, 1, type = "capabilities", attributes = mapOf("adapterName" to "sample")),
                event(1, 2),
                event(2, 10, type = "capabilities", attributes = mapOf("adapterName" to "sample-v2")),
                event(3, 11)
            )
        )
        assertEquals(2, result.processEpochs.size)
        assertEquals("sample", result.processEpochs[0].adapterName)
        assertEquals("sample-v2", result.processEpochs[1].adapterName)
        assertEquals(result.processEpochs[0].id, result.events[0].processEpochId)
        assertEquals(result.processEpochs[1].id, result.events.last().processEpochId)
    }

    @Test fun pidChangeClosesPreviousProcessEpoch() {
        val result = EventReplay().replay(
            listOf(event(0, 1), event(1, 10).copy(pid = 84))
        )
        assertEquals(2, result.processEpochs.size)
        assertEquals(10, result.processEpochs.first().endedAtNs)
        assertEquals(84, result.processEpochs.last().pid)
    }

    @Test fun replayIsDeterministic() {
        val input = listOf(event(2, 30), event(0, 10), event(1, 20))
        val replay = EventReplay()
        assertEquals(replay.replay(input), replay.replay(input.reversed()))
    }

    private fun event(
        sequence: Long,
        time: Long,
        type: String = "state.changed",
        eventId: String? = null,
        attributes: Map<String, String> = emptyMap()
    ) = PlaybackEvent(
        sequence = sequence,
        schema = "optd/1",
        type = type,
        timeNs = time,
        packageName = "example.app",
        pid = 42,
        eventId = eventId,
        attributes = attributes,
        source = EvidenceSource.APP_REPORTED,
        confidence = 1.0
    )
}
