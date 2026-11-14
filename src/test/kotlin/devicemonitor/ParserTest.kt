package devicemonitor

import devicemonitor.data.logcat.AnomalyScanner
import devicemonitor.data.metrics.FpsCollector
import devicemonitor.data.metrics.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    @Test fun psiAvg10() {
        val psi = "some avg10=1.50 avg60=1.00 avg300=0.50 total=1\nfull avg10=0.25 avg60=0.00 avg300=0.00 total=0"
        assertEquals(1.50, Snapshot.parsePsi(psi, "some"))
        assertEquals(0.25, Snapshot.parsePsi(psi, "full"))
        assertNull(Snapshot.parsePsi(null, "some"))
        assertNull(Snapshot.parsePsi("cat: /proc/pressure/cpu: No such file", "some"))
    }

    @Test fun pssAppSummary() {
        val dump = """
            ** MEMINFO in pid 1234 [com.fake.app] **
             App Summary
                       Java Heap:    10240
                     Native Heap:    20480
                            Code:     5120
                        Graphics:     2048
                       TOTAL PSS:   102400            TOTAL RSS:   200000
        """.trimIndent()
        val p = assertNotNull(Snapshot.parsePss(dump))
        assertEquals(100.0, p.totalMb)
        assertEquals(10.0, p.javaHeapMb)
        assertEquals(20.0, p.nativeHeapMb)
        assertNull(Snapshot.parsePss("No process found for: com.fake.app"))
    }

    @Test fun snapshotCommandOnlyIncludesEnabledSections() {
        val cmd = Snapshot.buildCommand(
            thermal = false, network = false, pageFaults = false, cpuFreq = false,
            psi = true, load = false, zoneinfo = false
        )
        assertTrue("===STAT===" in cmd && "===MEM===" in cmd && "===PSI_CPU===" in cmd)
        assertTrue("===NET===" !in cmd && "===THERMAL===" !in cmd && "===LOAD===" !in cmd)
    }

    @Test fun anomalyRules() {
        val anchor = AnomalyScanner.parseAnchor("11-13 10:00:00")
        val anr = assertNotNull(AnomalyScanner.matchLine(
            "11-13 10:00:05.000  1000  1000 E ActivityManager: ANR in com.fake.app", anchor))
        assertEquals("ANR", anr.type)
        assertEquals(5.0, anr.elapsedSec)
        assertEquals("Fatal exception",
            AnomalyScanner.matchLine("11-13 10:00:06.000  1 1 E AndroidRuntime: FATAL EXCEPTION: main", anchor)?.type)
        assertNull(AnomalyScanner.matchLine("11-13 10:00:07.000  1 1 I Fake: all good", anchor))
        // Small frame skips are below the reporting threshold.
        assertNull(AnomalyScanner.matchLine("11-13 10:00:08.000  1 1 I Choreographer: Skipped 2 frames!", anchor))
    }

    @Test fun fpsFromSurfaceFlingerLatency() {
        // 61 frames presented 16.666 ms apart → 60 fps.
        val sb = StringBuilder("16666666\n")
        var t = 1_000_000_000L
        repeat(61) { sb.append("${t - 5_000_000} $t ${t - 1_000_000}\n"); t += 16_666_666L }
        val fps = assertNotNull(FpsCollector.fpsFromLatency(sb.toString(), 5.0))
        assertTrue(fps in 59.9..60.1, "fps=$fps")
        // Pending frames (INT64_MAX) and an idle surface are not counted.
        assertNull(FpsCollector.fpsFromLatency("16666666\n0 0 0\n0 ${Long.MAX_VALUE} 0\n", 5.0))
        assertNull(FpsCollector.fpsFromLatency("", 5.0))
    }
}
