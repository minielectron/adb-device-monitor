package devicemonitor

import devicemonitor.application.playerdebug.SupportBundleService
import devicemonitor.data.playerdebug.*
import devicemonitor.domain.playerdebug.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PlayerSupportBundleTest {
    @Test fun exportsSanitizedCorrelatedBundleAndExcludesImages() {
        val setup = setup()
        val event = event()
        val player = PlayerInstance("player-secret", "view-secret", delivery = "ott", startedAtNs = 1)
        val session = ViewingSession("view-secret", 1, playerInstances = listOf(player))
        val graph = PlayerSessionGraph(emptyList(), listOf(session), listOf(event))
        setup.store.writeEvents(setup.location, listOf(event))
        setup.store.writeGraph(setup.location, graph)
        setup.store.writeIncidents(setup.location, emptyList())
        val image = File(setup.location.directory, "source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val registeredImage = setup.store.register(setup.location, "screenshot.png", image)
        val manifest = manifest(setup, mapOf(
            "events" to setup.store.relative(File(setup.location.directory, "events.ndjson")),
            "screenshot.png" to setup.store.relative(registeredImage)
        ))
        setup.store.writeManifest(setup.location, manifest)

        val service = SupportBundleService(setup.store) { "fixed-salt" }
        val preview = service.preview(setup.location, manifest)
        assertTrue("screenshot.png" in preview.excludedFiles)
        val bundle = service.export(setup.location, manifest, File(setup.root, "bundle.zip"))
        ZipFile(bundle).use { zip ->
            assertNull(zip.getEntry("screenshot.png"))
            assertEquals(1, zip.entries().toList().count { it.name == "events.ndjson" })
            val sanitizedManifest = PlayerDebugJson.parseManifest(
                zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
            )
            assertEquals("events.ndjson", sanitizedManifest.artifacts["events"])
            assertFalse("screenshot.png" in sanitizedManifest.artifacts)
            val eventText = zip.getInputStream(zip.getEntry("events.ndjson")).bufferedReader().readText()
            assertFalse("secret-token" in eventText)
            assertFalse("asset/42" in eventText)
            val sanitized = PlayerDebugJson.parseEvent(eventText.trim())
            assertTrue(sanitized.redacted)
            assertNotEquals("view-secret", sanitized.viewingSessionId)
            assertNotEquals("player-secret", sanitized.instanceId)
            val graphText = zip.getInputStream(zip.getEntry("session-graph.json")).bufferedReader().readText()
            val sanitizedGraph = PlayerDebugJson.parseGraph(graphText)
            assertEquals(sanitized.viewingSessionId, sanitizedGraph.viewingSessions.single().id)
            assertEquals(sanitized.instanceId, sanitizedGraph.viewingSessions.single().playerInstances.single().id)
        }
    }

    @Test fun importsBundleWithoutDevice() {
        val setup = setup()
        val manifest = manifest(setup)
        setup.store.writeManifest(setup.location, manifest)
        val service = SupportBundleService(setup.store) { "salt" }
        val bundle = service.export(setup.location, manifest, File(setup.root, "bundle.zip"))
        val imported = service.importBundle(bundle, File(setup.root, "imports"))
        assertTrue(imported.directory.isDirectory)
        assertNotEquals(manifest.deviceId, imported.manifest.deviceId)
        assertTrue(imported.manifest.artifacts.values.all { File(imported.directory, it).isFile })
    }

    @Test fun rejectsZipTraversal() {
        val setup = setup()
        val malicious = File(setup.root, "bad.zip")
        ZipOutputStream(malicious.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> {
            SupportBundleService(setup.store).importBundle(malicious, File(setup.root, "imports"))
        }
        assertFalse(File(setup.root, "outside.txt").exists())
    }

    @Test fun rejectsUnsafeImportedManifestIdentifiers() {
        val setup = setup()
        val malicious = File(setup.root, "bad-id.zip")
        ZipOutputStream(malicious.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(PlayerDebugJson.manifest(manifest(setup).copy(captureId = "bad'id")).toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> {
            SupportBundleService(setup.store).importBundle(malicious, File(setup.root, "imports"))
        }
    }

    private data class Setup(val root: File, val store: PlayerDebugArtifactStore, val location: PlayerCaptureLocation)
    private fun setup(): Setup {
        val root = createTempDirectory("support-bundle").toFile()
        val store = PlayerDebugArtifactStore(File(root, "captures")) { "fixed" }
        return Setup(root, store, store.createCapture("example.app"))
    }

    private fun manifest(setup: Setup, artifacts: Map<String, String> = emptyMap()) = PlayerDebugManifest(
        captureId = setup.location.captureId,
        deviceId = "device-secret",
        packageName = "example.app",
        state = PlayerCaptureState.COMPLETE,
        startedAt = "2026-11-17T00:00:00Z",
        updatedAt = "2026-11-17T00:00:01Z",
        eventCount = 1,
        sessionCount = 1,
        artifacts = artifacts
    )

    private fun event() = PlaybackEvent(
        sequence = 1,
        schema = "optd/1",
        type = "error.reported",
        timeNs = 1,
        viewingSessionId = "view-secret",
        instanceId = "player-secret",
        eventId = "event-secret",
        attributes = mapOf("token" to "secret-token", "url" to "https://example.test/asset/42?token=bad"),
        source = EvidenceSource.APP_REPORTED,
        confidence = 1.0,
        rawJson = "secret-token"
    )
}
