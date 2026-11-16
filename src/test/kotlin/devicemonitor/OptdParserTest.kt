package devicemonitor

import devicemonitor.data.playerdebug.*
import devicemonitor.domain.playerdebug.PlayerTelemetryProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptdParserTest {
    private val parser = OptdParser("com.example.player")

    @Test fun parsesThreadtimeMetadataAndData() {
        val line = "11-16 23:11:00.123  1234  1235 I OPTD: " +
            """{"schema":"optd/1","type":"state.changed","timeNs":8124112039000,"viewSessionId":"view-42","instanceId":"player-18","eventId":"e-1","packageName":"spoofed","pid":999,"data":{"from":"buffering","to":"playing","bitrate":4200000,"secure":true}}"""
        val accepted = assertIs<OptdParseResult.Accepted>(parser.parseLine(line, 7, lineNumber = 11, byteOffset = 200))
        val event = accepted.event
        assertEquals("com.example.player", event.packageName)
        assertEquals(1234, event.pid)
        assertEquals(1235, event.tid)
        assertEquals("view-42", event.viewingSessionId)
        assertEquals("player-18", event.instanceId)
        assertEquals("buffering", event.attributes["from"])
        assertEquals("true", event.attributes["secure"])
        assertEquals(4_200_000.0, event.measurements["bitrate"])
        assertEquals(11, event.evidence.single().line)
        assertTrue(accepted.knownType)
    }

    @Test fun acceptsEveryV1FamilyAndUnknownAdditiveType() {
        for ((index, type) in PlayerTelemetryProtocol.V1_EVENT_TYPES.withIndex()) {
            val result = parser.parsePayload(payload(type), metadata(), index.toLong())
            assertTrue((result as OptdParseResult.Accepted).knownType, type)
        }
        val unknown = assertIs<OptdParseResult.Accepted>(
            parser.parsePayload(payload("vendor.future_event"), metadata(), 99)
        )
        assertTrue(!unknown.knownType)
        assertEquals("value", unknown.event.attributes["extra"])
    }

    @Test fun ignoresOtherTagsAndRejectsUnsupportedSchema() {
        assertIs<OptdParseResult.Ignored>(
            parser.parsePayload(payload("state.changed"), metadata(tag = "OTHER"), 0)
        )
        val rejected = assertIs<OptdParseResult.Rejected>(
            parser.parsePayload(payload("state.changed").replace("optd/1", "optd/2"), metadata(), 0)
        )
        assertEquals("unsupported_schema", rejected.diagnostic.code)
    }

    @Test fun rejectsMalformedAndInvalidEnvelopes() {
        assertCode("malformed_json", "{")
        assertCode("invalid_envelope", "[]")
        assertCode("missing_schema", """{"type":"state.changed","timeNs":1,"data":{}}""")
        assertCode("missing_type", """{"schema":"optd/1","timeNs":1,"data":{}}""")
        assertCode("missing_time", """{"schema":"optd/1","type":"state.changed","timeNs":1.5,"data":{}}""")
        assertCode("invalid_data", """{"schema":"optd/1","type":"state.changed","timeNs":1,"data":[]}""")
        assertCode("invalid_id", """{"schema":"optd/1","type":"state.changed","timeNs":1,"instanceId":"","data":{}}""")
    }

    @Test fun boundsPayloadDepthAndValueCount() {
        val oversized = "x".repeat(PlayerTelemetryProtocol.MAX_EVENT_BYTES + 1)
        assertCode("oversized", oversized)

        var nested = "1"
        repeat(PlayerTelemetryProtocol.MAX_JSON_DEPTH + 1) { nested = "{\"x\":$nested}" }
        assertCode("too_deep", nested)

        val values = (0..PlayerTelemetryProtocol.MAX_JSON_VALUES).joinToString(",") { "\"k$it\":$it" }
        assertCode("too_many_values", "{$values}")
    }

    @Test fun directPayloadDoesNotTrustEmbeddedProcessMetadata() {
        val accepted = assertIs<OptdParseResult.Accepted>(
            parser.parsePayload(
                """{"schema":"optd/1","type":"error.reported","timeNs":2,"packageName":"bad","pid":888,"data":{"message":"failed"}}""",
                metadata(),
                1
            )
        )
        assertEquals("com.example.player", accepted.event.packageName)
        assertEquals(12, accepted.event.pid)
        assertNull(accepted.event.attributes["packageName"])
    }

    private fun payload(type: String) =
        """{"schema":"optd/1","type":"$type","timeNs":1,"data":{"extra":"value"}}"""

    private fun metadata(tag: String = "OPTD") = OptdLogMetadata(
        deviceTime = "11-16 23:11:00.123",
        pid = 12,
        tid = 13,
        priority = "I",
        tag = tag,
        packageName = "com.example.player"
    )

    private fun assertCode(code: String, payload: String) {
        val result = assertIs<OptdParseResult.Rejected>(parser.parsePayload(payload, metadata(), 0))
        assertEquals(code, result.diagnostic.code)
    }
}
