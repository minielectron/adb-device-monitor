package devicemonitor

import devicemonitor.data.playerdebug.PlayerDebugRedactor
import devicemonitor.data.playerdebug.RedactionCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlayerDebugRedactorTest {
    @Test fun redactsUrlsSecretsAndIdentifiers() {
        val input = "Authorization: Bearer top-secret url=https://media.example.test/path/content-42?token=abc device_id=box-9 cookie=session-cookie"
        val result = PlayerDebugRedactor.redactText(input)
        assertFalse("top-secret" in result.value)
        assertFalse("content-42" in result.value)
        assertFalse("box-9" in result.value)
        assertFalse("session-cookie" in result.value)
        assertTrue("https://media.example.test/[REDACTED]" in result.value)
        assertTrue((result.counts[RedactionCategory.URL] ?: 0) >= 1)
        assertTrue((result.counts[RedactionCategory.SECRET] ?: 0) >= 2)
        assertTrue((result.counts[RedactionCategory.IDENTIFIER] ?: 0) >= 1)
    }

    @Test fun redactsStructuredAttributesByKeyAndValue() {
        val result = PlayerDebugRedactor.redactAttributes(
            linkedMapOf(
                "access_token" to "abc",
                "contentId" to "movie-42",
                "message" to "failed at https://example.test/asset/42?auth=yes",
                "state" to "playing"
            )
        )
        assertEquals("[REDACTED]", result.value["access_token"])
        assertEquals("[REDACTED]", result.value["contentId"])
        assertEquals("playing", result.value["state"])
        assertFalse("asset/42" in result.value.getValue("message"))
        assertEquals(3, result.total)
    }

    @Test fun volatileValuesProduceStableFingerprint() {
        val first = "2026-11-16T20:00:01Z decoder failed session=view-123 requestId=abc-111 at 0x7ffee123 after 123456 ms"
        val second = "2026-11-17T21:02:03Z decoder failed session=view-999 requestId=xyz-222 at 0xabc999 after 987654 ms"
        assertEquals(PlayerDebugRedactor.errorFingerprint(first), PlayerDebugRedactor.errorFingerprint(second))
        assertNotEquals(
            PlayerDebugRedactor.errorFingerprint(first),
            PlayerDebugRedactor.errorFingerprint(first.replace("decoder failed", "license failed"))
        )
    }

    @Test fun saltsIdentifiersPerBundle() {
        val first = PlayerDebugRedactor.saltIdentifier("device-42", "bundle-a")
        assertEquals(first, PlayerDebugRedactor.saltIdentifier("device-42", "bundle-a"))
        assertNotEquals(first, PlayerDebugRedactor.saltIdentifier("device-42", "bundle-b"))
        assertFalse("device-42" in first)
    }

    @Test fun alreadyRedactedTextDoesNotLeakOrGrow() {
        val once = PlayerDebugRedactor.redactText("token=[REDACTED]").value
        val twice = PlayerDebugRedactor.redactText(once).value
        assertEquals(once, twice)
        assertFalse("token=[REDACTED][REDACTED]" in twice)
    }
}
