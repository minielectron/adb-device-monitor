package devicemonitor

import devicemonitor.data.playerdebug.PlayerDebugManifest
import devicemonitor.domain.playerdebug.*
import devicemonitor.presentation.report.PlayerDebugReport
import java.nio.file.Files
import kotlin.test.*

class PlayerDebugReportTest {
    @Test fun mapsArbitraryBidirectionalMissingLoopAndOverlappingTransitions() {
        val graph = graph()
        val map = PlayerDebugReport.handoffMap(graph, listOf(incident("h1")))

        assertEquals(6, map.edges.size)
        assertTrue(map.nodes.any { it.label == "custom-link" })
        assertTrue(map.nodes.any { it.label == "Unknown source" && it.missing })
        assertTrue(map.edges.any { edge -> node(map, edge.sourceKey) == "multicast" && node(map, edge.targetKey) == "ott" })
        assertTrue(map.edges.any { edge -> node(map, edge.sourceKey) == "ott" && node(map, edge.targetKey) == "dvb" })
        assertTrue(map.edges.any { edge -> node(map, edge.sourceKey) == "dvb" && node(map, edge.targetKey) == "ott" })
        assertTrue(map.edges.any { it.sourceKey == it.targetKey })
        assertEquals(5_000_000_000, map.edges.first { it.id == "h1" }.overlapNs)
        assertEquals(1, map.edges.first { it.id == "h1" }.incidentCount)
    }

    @Test fun writesSanitizedStandaloneOfflineReport() {
        val secret = "private-token-938475"
        val event = PlaybackEvent(
            sequence = 1,
            schema = "optd/1",
            type = "metric.sample",
            timeNs = 1,
            attributes = mapOf("token" to secret, "url" to "https://secret.invalid/watch?id=42"),
            rawJson = "{\"password\":\"$secret\"}",
            source = EvidenceSource.APP_REPORTED,
            confidence = 1.0
        )
        val sensitiveGraph = graph("token=$secret https://secret.invalid/watch?id=42")
        val graph = sensitiveGraph.copy(events = sensitiveGraph.events + event)
        val manifest = PlayerDebugManifest(1, "capture-1", "serial-$secret", "com.example.player", PlayerCaptureState.COMPLETE, "2026-01-01", "2026-01-02")
        val finding = incident("h1").copy(evidence = IncidentEvidence(listOf(1), listOf(EvidenceReference("private/folder/codec.txt", line = 4)), "A deterministic handoff issue was detected."))
        val file = Files.createTempFile("player-debug-report", ".html").toFile()

        PlayerDebugReport.writeHtml(manifest, graph, listOf(finding), file)
        val html = file.readText()

        assertContains(html, "Player Debugger report")
        assertContains(html, "multicast")
        assertContains(html, "custom-link")
        assertContains(html, "5.00 s")
        assertContains(html, "codec.txt")
        assertFalse("private/folder" in html)
        assertFalse(secret in html)
        assertFalse("secret.invalid" in html)
        assertFalse(Regex("""(?:src|href)=[\"']https?://""", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("<script" in html.lowercase())
        assertContains(html, "<svg")
    }

    private fun graph(firstReason: String = "signal loss"): PlayerSessionGraph {
        val players = listOf(
            player("m", "multicast"), player("o", "ott"), player("d", "dvb"), player("c", "custom-link")
        )
        val handoffs = listOf(
            handoff("h1", "m", "o", 10, 15, 20, firstReason),
            handoff("h2", "o", "d", 30, 34, 36, "broadcast preferred"),
            handoff("h3", "d", "o", 40, 43, 45, "tune failed"),
            handoff("h4", "o", "c", 50, 55, 56, "custom policy"),
            handoff("h5", "c", "c", 60, 61, 62, "retry"),
            handoff("h6", null, "m", 70, 72, 73, "recovered source")
        )
        val events = handoffs.mapIndexed { index, handoff ->
            PlaybackEvent(index.toLong(), "optd/1", "handoff.requested", handoff.requestedAtNs, viewingSessionId = "view", eventId = handoff.id, source = EvidenceSource.APP_REPORTED, confidence = 1.0)
        }
        return PlayerSessionGraph(emptyList(), listOf(ViewingSession("view", 0, playerInstances = players, handoffs = handoffs)), events)
    }

    private fun player(id: String, delivery: String) = PlayerInstance(id, "view", delivery = delivery, startedAtNs = 0)

    private fun handoff(id: String, from: String?, to: String?, requested: Long, activated: Long, released: Long, reason: String) = PlayerHandoff(
        id, "view", from, to, requested * 1_000_000_000, activated * 1_000_000_000,
        released * 1_000_000_000, reason, HandoffResult.SUCCEEDED, EvidenceSource.APP_REPORTED, 1.0
    )

    private fun incident(handoff: String) = PlayerIncident(
        "incident-$handoff", "handoff", 1, "Handoff issue", IncidentSeverity.WARNING,
        startedAtNs = 12_000_000_000, handoffId = handoff, confidence = .9,
        evidence = IncidentEvidence(explanation = "Handoff evidence matched."), suggestedChecks = listOf("Inspect target readiness")
    )

    private fun node(map: devicemonitor.presentation.report.PlayerHandoffMap, key: String) = map.nodes.first { it.key == key }.label
}
