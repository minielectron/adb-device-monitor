package devicemonitor

import devicemonitor.application.playerdebug.*
import devicemonitor.data.playerdebug.PassivePlaybackProbe
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerDebugFixtureTest {
    @Test fun replaysGenericFixturesEndToEnd() {
        val expectations = mapOf(
            "structured-ott-success" to emptySet(),
            "multicast-to-ott" to emptySet(),
            "fallback-loop" to setOf("fallback_loop"),
            "overlap-zombie" to setOf("zombie_player"),
            "passive-codec-failure" to setOf("playback_error")
        )
        for ((fixture, expected) in expectations) {
            val result = replay(fixture)
            val rules = result.second.map { it.ruleId }.toSet()
            assertTrue(rules.containsAll(expected), "$fixture expected $expected but got $rules")
            if (expected.isEmpty()) assertEquals(emptySet(), rules, "$fixture should be healthy")
        }
    }

    @Test fun replayDoesNotNeedDeviceOrNetwork() {
        val first = replay("multicast-to-ott")
        val second = replay("multicast-to-ott")
        assertEquals(first, second)
    }

    private fun replay(name: String): Pair<devicemonitor.domain.playerdebug.PlayerSessionGraph, List<devicemonitor.domain.playerdebug.PlayerIncident>> {
        val resource = checkNotNull(javaClass.getResource("/playerdebug/$name/logcat.log"))
        val file = File(createTempDirectory("player-fixture").toFile(), "logcat.log")
        file.writeBytes(resource.readBytes())
        val events = PassivePlaybackProbe("example.app", 1_000_000_000L).scanNew(file).events
        val graph = SessionGraphBuilder().build(EventReplay().replay(events))
        return graph to PlayerIncidentEngine().analyze(graph)
    }
}
