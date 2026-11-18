package devicemonitor

import devicemonitor.data.playerdebug.*
import devicemonitor.domain.releaseguard.DeviceCapability
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class MediaSnapshotCollectorTest {
    @Test fun capturesOnlyCapabilityBackedProvidersAndBoundsArtifacts() {
        val commands = mutableListOf<String>()
        val collector = collector(
            shell = { command, _ -> commands += command; "x".repeat(5000) },
            capabilities = setOf(DeviceCapability.MEDIA_CODEC_DIAGNOSTICS, DeviceCapability.AUDIO_DIAGNOSTICS),
            maxBytes = 1024
        )
        val snapshots = collector.collectPeriodic(1)
        assertEquals(setOf(MediaSnapshotProvider.CODEC, MediaSnapshotProvider.AUDIO), snapshots.map { it.provider }.toSet())
        assertTrue(snapshots.all { it.status == MediaSnapshotStatus.CAPTURED && it.bytes == 1024 })
        assertTrue(snapshots.all { it.artifact?.length() == 1024L })
        assertEquals(2, commands.size)
    }

    @Test fun failureBacksOffWithoutCallingShellAgain() {
        var calls = 0
        val collector = collector(shell = { _, _ -> calls++; error("denied") })
        assertEquals(MediaSnapshotStatus.FAILED, collector.collectPeriodic(1).single().status)
        assertEquals(MediaSnapshotStatus.BACKED_OFF, collector.collectPeriodic(2).single().status)
        assertEquals(1, calls)
    }

    @Test fun triggeredSnapshotsAreGloballyRateLimited() {
        val collector = collector(shell = { _, _ -> "ok" })
        assertEquals(MediaSnapshotStatus.CAPTURED, collector.collectTriggered("error", 1).single().status)
        assertEquals(MediaSnapshotStatus.RATE_LIMITED, collector.collectTriggered("buffer", 2).single().status)
    }

    @Test fun emptyOutputIsDegradedAndNoArtifactIsWritten() {
        val collector = collector(shell = { _, _ -> "" })
        val result = collector.collectPeriodic(1).single()
        assertEquals(MediaSnapshotStatus.EMPTY, result.status)
        assertNull(result.artifact)
    }

    private fun collector(
        shell: (String, Long) -> String,
        capabilities: Set<DeviceCapability> = setOf(DeviceCapability.MEDIA_CODEC_DIAGNOSTICS),
        maxBytes: Int = 1024 * 1024
    ) = MediaSnapshotCollector(
        shell = shell,
        artifactDirectory = createTempDirectory("media-snapshots").toFile(),
        capabilities = capabilities,
        maxBytes = maxBytes
    )
}
