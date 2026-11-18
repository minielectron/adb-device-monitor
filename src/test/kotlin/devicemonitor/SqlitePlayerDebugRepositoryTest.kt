package devicemonitor

import devicemonitor.data.playerdebug.*
import devicemonitor.domain.playerdebug.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SqlitePlayerDebugRepositoryTest {
    @Test fun indexesListsAndReloadsCaptureGraph() {
        val setup = setup()
        setup.repository.use { repository ->
            val session = ViewingSession("view", 1, playerInstances = listOf(PlayerInstance("p", "view", delivery = "ott", startedAtNs = 1)))
            val graph = PlayerSessionGraph(emptyList(), listOf(session), emptyList())
            val incident = incident()
            val manifest = manifest(setup.location, mapOf("graph" to setup.store.relative(setup.store.writeGraph(setup.location, graph))))
            setup.store.writeManifest(setup.location, manifest)
            repository.upsert(manifest, setup.store.relative(setup.location.manifestFile), graph, listOf(incident))
            assertEquals(manifest, repository.capture(manifest.captureId)?.manifest)
            assertEquals(1, repository.captures("example.app").size)
            assertTrue(repository.captures("other.app").isEmpty())
        }
    }

    @Test fun rebuildsIndexFromPortableManifests() {
        val setup = setup()
        val manifest = manifest(setup.location)
        setup.store.writeManifest(setup.location, manifest)
        setup.repository.use { repository ->
            assertEquals(1, repository.rebuildFromArtifacts())
            assertNotNull(repository.capture(manifest.captureId))
        }
    }

    @Test fun rejectsEscapingManifestPath() {
        val setup = setup()
        setup.repository.use { repository ->
            assertFailsWith<IllegalArgumentException> { repository.upsert(manifest(setup.location), "../manifest.json") }
        }
    }

    private data class Setup(
        val store: PlayerDebugArtifactStore,
        val repository: SqlitePlayerDebugRepository,
        val location: PlayerCaptureLocation
    )

    private fun setup(): Setup {
        val root = createTempDirectory("player-repository").toFile()
        val store = PlayerDebugArtifactStore(root) { "fixed" }
        val location = store.createCapture("example.app")
        return Setup(store, SqlitePlayerDebugRepository(root.resolve("player-debug.db"), store), location)
    }

    private fun manifest(location: PlayerCaptureLocation, artifacts: Map<String, String> = emptyMap()) = PlayerDebugManifest(
        captureId = location.captureId,
        deviceId = "device",
        packageName = "example.app",
        state = PlayerCaptureState.COMPLETE,
        startedAt = "2026-11-17T00:00:00Z",
        updatedAt = "2026-11-17T00:00:01Z",
        sessionCount = 1,
        incidentCount = 1,
        artifacts = artifacts
    )

    private fun incident() = PlayerIncident(
        id = "i1", ruleId = "test", ruleVersion = 1, title = "Test",
        severity = IncidentSeverity.WARNING, startedAtNs = 1, confidence = 1.0,
        evidence = IncidentEvidence(explanation = "test")
    )
}
