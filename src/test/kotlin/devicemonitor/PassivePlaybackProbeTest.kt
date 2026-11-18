package devicemonitor

import devicemonitor.data.playerdebug.PassivePlaybackProbe
import devicemonitor.domain.playerdebug.EvidenceSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class PassivePlaybackProbeTest {
    @Test fun incrementallyParsesPlaybackPlatformAndOptdWithoutDuplicates() {
        val file = tempFile()
        file.writeText("11-17 10:00:00.000  10  11 I MediaCodec: Configuring TUNNELED video playback\n")
        val probe = PassivePlaybackProbe("example.app", 1_000_000)
        val first = probe.scanNew(file)
        assertEquals("tunneled", first.tunnelMode)
        assertEquals(1, first.events.count { it.type == "tunnel.changed" })

        file.appendText("11-17 10:00:01.000  10  11 E MediaCodec: decoder failed error 12\n")
        file.appendText("11-17 10:00:02.000  10  11 I OPTD: {\"schema\":\"optd/1\",\"type\":\"render.first_frame\",\"timeNs\":3000000,\"viewSessionId\":\"v\",\"instanceId\":\"p\",\"data\":{}}\n")
        val second = probe.scanNew(file)
        assertEquals(1, second.events.count { it.type == "codec.error" })
        assertEquals(EvidenceSource.APP_REPORTED, second.events.single { it.type == "render.first_frame" }.source)
        assertTrue(probe.scanNew(file).events.isEmpty())
    }

    @Test fun keepsPartialLineUntilNewlineAndReportsTruncation() {
        val file = tempFile()
        val probe = PassivePlaybackProbe("example.app", 100)
        file.writeText("11-17 10:00:00.000  10  11 E AndroidRuntime: FATAL EXCEPTION")
        assertTrue(probe.scanNew(file).events.isEmpty())
        file.appendText(": main\n")
        assertEquals("app.crash", probe.scanNew(file).events.single().type)
        file.writeText("11-17 10:00:01.000  10  11 I Any: reset\n")
        val reset = probe.scanNew(file)
        assertTrue(reset.evidenceGap)
        assertTrue(reset.events.any { it.type == "evidence.gap" })
    }

    @Test fun latchesTunnelStateAndIgnoresProjectLifecycleAcrossUnrelatedBatches() {
        val file = tempFile()
        val probe = PassivePlaybackProbe(null, 100)
        file.writeText("11-17 10:00:00.000  1  1 I ProjectLifecycle: onPlaying\n")
        assertTrue(probe.scanNew(file).events.isEmpty())
        file.appendText("11-17 10:00:01.000  1  1 I CCodec: Configuring NON-TUNNELED video playback\n")
        assertEquals("non_tunneled", probe.scanNew(file).tunnelMode)
        file.appendText("11-17 10:00:02.000  1  1 I Other: unrelated\n")
        assertEquals("non_tunneled", probe.scanNew(file).tunnelMode)
    }

    private fun tempFile(): File = createTempDirectory("passive-probe").resolve("logcat.log").toFile()
}
