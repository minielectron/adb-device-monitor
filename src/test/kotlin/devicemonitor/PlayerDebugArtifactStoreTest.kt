package devicemonitor

import devicemonitor.data.playerdebug.*
import devicemonitor.domain.playerdebug.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PlayerDebugArtifactStoreTest {
    @Test fun writesPortableArtifactsAndRecoversManifest() {
        val store = store()
        val location = store.createCapture("example.app")
        val event = event()
        val graph = PlayerSessionGraph(emptyList(), emptyList(), listOf(event))
        val manifest = manifest(location, PlayerCaptureState.COMPLETE, mapOf("events" to store.relative(File(location.directory, "events.ndjson"))))
        store.writeEvents(location, listOf(event))
        store.writeGraph(location, graph)
        store.writeIncidents(location, emptyList())
        store.writeManifest(location, manifest)

        assertEquals(event, PlayerDebugJson.parseEvent(File(location.directory, "events.ndjson").readLines().single()))
        val recovered = store.scan().single()
        assertEquals(PlayerCaptureRecoveryStatus.COMPLETE, recovered.status)
        assertEquals(location.captureId, recovered.manifest?.captureId)
    }

    @Test fun partialAndCorruptCapturesRemainVisible() {
        val store = store()
        val partial = store.createCapture("app")
        store.writeManifest(partial, manifest(partial, PlayerCaptureState.CAPTURING))
        val corrupt = store.createCapture("app")
        store.atomicWrite(corrupt.manifestFile, "{")
        val states = store.scan().associate { it.directory.name to it.status }
        assertEquals(PlayerCaptureRecoveryStatus.PARTIAL, states[partial.directory.name])
        assertEquals(PlayerCaptureRecoveryStatus.CORRUPT, states[corrupt.directory.name])
    }

    @Test fun rejectsTraversalAndAbsoluteArtifactPaths() {
        val store = store()
        assertFailsWith<IllegalArgumentException> { store.resolve("../../outside") }
        assertFailsWith<IllegalArgumentException> { store.resolve("/tmp/outside") }
        val location = store.createCapture("app")
        assertFailsWith<IllegalArgumentException> {
            store.writeManifest(location, manifest(location, PlayerCaptureState.COMPLETE, mapOf("bad" to "../secret")))
        }
    }

    @Test fun captureIdsDoNotCollide() {
        var id = 0
        val root = createTempDirectory("player-store").toFile()
        val store = PlayerDebugArtifactStore(root, fixedClock(), idFactory = { "id-${id++}" })
        assertNotEquals(store.createCapture("app").captureId, store.createCapture("app").captureId)
    }

    @Test fun streamsBoundedTimelinePages() {
        val store = store()
        val location = store.createCapture("app")
        val file = store.writeEvents(location, (0L..5L).map { event().copy(sequence = it, timeNs = it) })
        val first = store.readEventPage(file, 0, 2)
        assertEquals(listOf(0L, 1L), first.events.map { it.sequence })
        assertEquals(2, first.nextOffset)
        val last = store.readEventPage(file, 4, 2)
        assertEquals(listOf(4L, 5L), last.events.map { it.sequence })
        assertNull(last.nextOffset)
    }

    private fun store(): PlayerDebugArtifactStore {
        var id = 0
        return PlayerDebugArtifactStore(createTempDirectory("player-store").toFile(), fixedClock()) { "fixed-${id++}" }
    }
    private fun fixedClock() = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC)

    private fun manifest(location: PlayerCaptureLocation, state: PlayerCaptureState, artifacts: Map<String, String> = emptyMap()) =
        PlayerDebugManifest(
            captureId = location.captureId,
            deviceId = "device",
            packageName = "example.app",
            state = state,
            startedAt = "2026-11-17T00:00:00Z",
            updatedAt = "2026-11-17T00:00:01Z",
            eventCount = 1,
            artifacts = artifacts
        )

    private fun event() = PlaybackEvent(
        sequence = 1,
        schema = "optd/1",
        type = "state.changed",
        timeNs = 1,
        source = EvidenceSource.APP_REPORTED,
        confidence = 1.0
    )
}
