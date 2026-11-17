package devicemonitor.optd

import devicemonitor.data.playerdebug.OptdLogMetadata
import devicemonitor.data.playerdebug.OptdParseResult
import devicemonitor.data.playerdebug.OptdParser
import kotlin.test.*

class OptdTest {
    @Test fun emittedGoldenEventsAreAcceptedByDesktopParser() {
        val lines = mutableListOf<Pair<String, String>>()
        var time = 100L
        val optd = Optd(true, { tag, payload -> lines += tag to payload }, { time++ })

        optd.capabilities("generic-adapter", "1", setOf("state.changed", "handoff.requested"), setOf("bitrate"), setOf("custom"))
        optd.sessionStarted("view-1")
        optd.playerCreated("view-1", "multicast-1", family = "generic", delivery = "multicast", role = "primary")
        optd.playerCreated("view-1", "ott-1", family = "generic", delivery = "ott", role = "fallback")
        optd.stateChanged("view-1", "multicast-1", "idle", "playing")
        optd.bufferStarted("view-1", "multicast-1", "source stalled")
        optd.bufferEnded("view-1", "multicast-1")
        optd.firstFrame("view-1", "multicast-1")
        optd.videoAvailable("view-1", "multicast-1", true)
        optd.audioAvailable("view-1", "multicast-1", true)
        optd.qualityChanged("view-1", "multicast-1", mapOf("bitrate" to 4_000_000, "height" to 1080))
        optd.metricSample("view-1", "multicast-1", mapOf("droppedFrames" to 2))
        optd.handoffRequested("view-1", "handoff-1", "multicast-1", "ott-1", "multicast unavailable")
        optd.handoffCompleted("view-1", "handoff-1", "multicast-1", "ott-1", "succeeded", sourceReleased = true)
        optd.errorReported("view-1", "ott-1", "decoder_init", "codec")
        optd.playerActivated("view-1", "ott-1")
        optd.playerReleased("view-1", "multicast-1")
        optd.sessionEnded("view-1")

        assertTrue(lines.isNotEmpty())
        val parser = OptdParser("com.example.player")
        lines.forEachIndexed { index, (tag, payload) ->
            assertEquals(Optd.TAG, tag)
            assertTrue(payload.toByteArray().size <= Optd.MAX_EVENT_BYTES)
            assertIs<OptdParseResult.Accepted>(parser.parsePayload(payload, metadata(), index.toLong()))
        }
        val timestamps = lines.map { Regex("\"timeNs\":(\\d+)").find(it.second)!!.groupValues[1].toLong() }
        assertEquals(timestamps.sorted(), timestamps)
        assertEquals(timestamps.distinct().size, timestamps.size)
    }

    @Test fun disabledModeEmitsNothingAndSkipsValidation() {
        var calls = 0
        val optd = Optd(false, { _, _ -> calls++ }, { error("clock must remain untouched") })
        assertFalse(optd.event("INVALID TYPE", data = mapOf("token" to "secret")))
        assertEquals(0, calls)
    }

    @Test fun enforcesMonotonicTimeAndRejectsUnsafeOrOversizedData() {
        val lines = mutableListOf<String>()
        val times = ArrayDeque(listOf(50L, 40L, 40L))
        val optd = Optd(true, { _, payload -> lines += payload }, { times.removeFirstOrNull() ?: 40L })
        repeat(3) { optd.sessionStarted("view") }
        assertEquals(listOf(50L, 51L, 52L), lines.map { Regex("\"timeNs\":(\\d+)").find(it)!!.groupValues[1].toLong() })

        assertFailsWith<IllegalArgumentException> { optd.event("metric.sample", data = mapOf("token" to "secret")) }
        assertFailsWith<IllegalArgumentException> { optd.event("error.reported", data = mapOf("message" to "https://example.invalid/private")) }
        assertFailsWith<IllegalArgumentException> { optd.event("error.reported", data = mapOf("message" to "access_token=secret")) }
        assertFailsWith<IllegalArgumentException> { optd.event("metric.sample", data = mapOf("value" to Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { optd.event("metric.sample", data = mapOf("value" to "x".repeat(2049))) }
        assertFailsWith<IllegalArgumentException> {
            optd.event("metric.sample", data = mapOf("values" to List(64) { "🙂".repeat(40) }))
        }
    }

    @Test fun escapesJsonWithoutExternalSerializer() {
        var payload = ""
        Optd(true, { _, value -> payload = value }, { 1 }).event("state.changed", "view", "player", data = mapOf("state" to "playing\n\"secure\""))
        val accepted = assertIs<OptdParseResult.Accepted>(OptdParser().parsePayload(payload, metadata(), 0))
        assertEquals("playing\n\"secure\"", accepted.event.attributes["state"])
    }

    private fun metadata() = OptdLogMetadata("11-17 12:00:00.000", 1, 2, "I", Optd.TAG)
}
