package devicemonitor

import devicemonitor.data.storage.*
import devicemonitor.domain.releaseguard.ExecutionState
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ReleaseGuardArtifactStoreTest {
    @Test fun createsCollisionResistantRunsAndRecoversState() {
        val root = Files.createTempDirectory("release-guard-artifacts").toFile()
        val ids = ArrayDeque(listOf("aaaaaaaa", "bbbbbbbb"))
        val store = ArtifactStore(
            root,
            Clock.fixed(Instant.parse("2026-11-16T10:15:30.123Z"), ZoneOffset.UTC),
            { ids.removeFirst() }
        )
        val first = store.createRun("Player / QA", "Cold launch")
        val second = store.createRun("Player / QA", "Cold launch")
        assertNotEquals(first.runId, second.runId)
        assertTrue(first.runId.startsWith("Cold_launch_20261116_101530_123_"))
        assertEquals(first.runId, first.directory.name)
        assertTrue(first.directory.toPath().startsWith(root.toPath()))
        assertTrue("Player_QA" in first.directory.absolutePath)

        store.writeManifest(first, manifest(first.runId, ExecutionState.EXECUTE_AND_CAPTURE))
        store.writeManifest(second, manifest(second.runId, ExecutionState.PASSED))
        val recovered = store.scan().associateBy { it.manifest?.runId }
        assertEquals(RecoveryStatus.INCOMPLETE, recovered[first.runId]?.status)
        assertEquals(RecoveryStatus.COMPLETE, recovered[second.runId]?.status)
    }

    @Test fun atomicWriteReplacesManifestAndLeavesNoTemporaryFile() {
        val root = Files.createTempDirectory("release-guard-atomic").toFile()
        val store = ArtifactStore(root, idFactory = { "run" })
        val run = store.createRun("p", "s")
        store.writeManifest(run, manifest(run.runId, ExecutionState.PREFLIGHT))
        store.writeManifest(run, manifest(run.runId, ExecutionState.FAILED))

        assertEquals(ExecutionState.FAILED, ReleaseGuardJson.parseManifest(run.manifestFile.readText()).state)
        assertTrue(run.directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun rejectsTraversalAbsolutePathsAndSymlinkEscape() {
        val root = Files.createTempDirectory("release-guard-root").toFile()
        val outside = Files.createTempDirectory("release-guard-outside")
        val store = ArtifactStore(root)
        assertFailsWith<IllegalArgumentException> { store.resolve("../outside.txt") }
        assertFailsWith<IllegalArgumentException> { store.resolve(outside.resolve("outside.txt").toString()) }

        val link = root.toPath().resolve("escape")
        try {
            Files.createSymbolicLink(link, outside)
            assertFailsWith<IllegalArgumentException> { store.resolve("escape/file.txt") }
        } catch (_: UnsupportedOperationException) {
            // Filesystem does not support symlinks; traversal checks above still run.
        }
    }

    @Test fun registersOutputsInsideAttemptAndDetectsCorruptManifest() {
        val root = Files.createTempDirectory("release-guard-register").toFile()
        val store = ArtifactStore(root, idFactory = { "run" })
        val run = store.createRun("p", "s")
        val attempt = store.attemptDirectory(run, 1)
        val source = Files.createTempFile("samples", ".csv").toFile().apply { writeText("time,cpu\n0,10") }
        val copied = store.registerOutput(attempt, "samples.csv", source)
        assertEquals(source.readText(), copied.readText())
        assertTrue(store.relative(copied).endsWith("attempt-01/samples.csv"))

        run.manifestFile.writeText("{not json")
        val recovered = store.scan().single()
        assertEquals(RecoveryStatus.CORRUPT, recovered.status)
        assertNotNull(recovered.error)
    }

    private fun manifest(runId: String, state: ExecutionState) = RunManifest(
        runId = runId,
        projectId = "player",
        scenarioId = "browse",
        scenarioRevision = 1,
        state = state,
        startedAt = "2026-11-16T10:15:30Z",
        updatedAt = "2026-11-16T10:15:31Z"
    )
}
