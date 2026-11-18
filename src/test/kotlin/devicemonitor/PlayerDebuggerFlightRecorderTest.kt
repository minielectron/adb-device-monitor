package devicemonitor

import devicemonitor.application.SessionConfig
import devicemonitor.application.sampleToPlayerDebugEvent
import devicemonitor.domain.ProcMemSample
import devicemonitor.domain.ProcessSample
import devicemonitor.domain.PssSample
import devicemonitor.domain.Sample
import devicemonitor.domain.playerdebug.EvidenceSource
import devicemonitor.presentation.web.PLAYER_DEBUGGER_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDebuggerFlightRecorderTest {
    @Test fun exposesPagedSynchronizedLanesInspectorAndIncidentEvidence() {
        listOf(
            "Sessions", "Players", "Handoffs", "States", "Buffering", "First frame",
            "Audio / video", "Quality", "Errors", "Performance", "Snapshots"
        ).forEach { assertTrue(PLAYER_DEBUGGER_PAGE.contains("'$it'"), "Missing $it lane") }
        listOf(
            "filterSession", "filterInstance", "filterType", "filterSource", "filterConfidence",
            "filterText", "rangeStart", "rangeEnd"
        ).forEach { assertTrue(PLAYER_DEBUGGER_PAGE.contains("id=\"$it\""), "Missing $it filter") }

        assertTrue(PLAYER_DEBUGGER_PAGE.contains("&sequence="))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("timelinePage.nextOffset"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("ArrowLeft"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("ArrowRight"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("/api/player/evidence"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("suggestedChecks"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("similar fingerprints"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("Unavailable in this window"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("prefers-reduced-motion: reduce"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("id=\"pdHandoffMap\""))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("function renderHandoffMap()"))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("Reason · "))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("Activation · "))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("Overlap · "))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("Incidents · "))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("id=\"playerReportViewer\""))
        assertTrue(PLAYER_DEBUGGER_PAGE.contains("/api/player/report?captureId="))
        assertFalse(PLAYER_DEBUGGER_PAGE.contains("window.open(playerReportUrl"))
    }

    @Test fun normalizesOnlyEnabledDeviceMeasurements() {
        val sample = sample()
        val event = assertNotNull(sampleToPlayerDebugEvent(
            sample,
            config(cpuUtilEnabled = true, psiEnabled = true, topProcsEnabled = true),
            timeNs = 9_000
        ))

        assertEquals("metric.sample", event.type)
        assertEquals(9_000, event.timeNs)
        assertEquals(EvidenceSource.DEVICE_MEASURED, event.source)
        assertEquals(64.0, event.measurements["cpuUtilPct"])
        assertEquals(2.5, event.measurements["psiMemory"])
        assertEquals(82.0, event.measurements["topProcessCpuPct"])
        assertFalse(event.measurements.containsKey("memoryUsedMb"))
        assertFalse(event.measurements.containsKey("appPssMb"))
        assertEquals("buffering", event.attributes["videoState"])
    }

    @Test fun omitsPerformanceEventWhenNoSelectedMetricIsAvailable() {
        assertNull(sampleToPlayerDebugEvent(sample(), config(psiEnabled = false), timeNs = 1))
    }

    private fun config(
        cpuUtilEnabled: Boolean = false,
        psiEnabled: Boolean = false,
        topProcsEnabled: Boolean = false
    ) = SessionConfig(
        target = "device",
        durationSec = 10.0,
        intervalSec = 1.0,
        packageName = "com.example.player",
        cpuUtilEnabled = cpuUtilEnabled,
        psiEnabled = psiEnabled,
        topProcsEnabled = topProcsEnabled,
        appPssEnabled = false
    )

    private fun sample() = Sample(
        elapsedSec = 1.0,
        cpuUtilPct = 64.0,
        cpuIdlePct = 36.0,
        cpuPsiSomeAvg10 = 1.0,
        memPsiSomeAvg10 = 2.5,
        memPsiFullAvg10 = 0.5,
        ioPsiSomeAvg10 = 0.25,
        memTotalMb = 4096.0,
        memUsedMb = 2048.0,
        memUsedPct = 50.0,
        memAvailableMb = 2048.0,
        cachedMb = 512.0,
        swapUsedMb = 0.0,
        load1 = 1.25,
        perCoreUtilPct = listOf(60.0, 68.0),
        netRxKbps = 1200.0,
        netTxKbps = 120.0,
        cpuTempC = 58.0,
        cpuFreqMhz = 1800.0,
        majorFaultsPerSec = 0.0,
        directReclaimPerSec = 0.0,
        pss = PssSample(220.0, null, null, null, null, null, null, null),
        jankPct = 4.0,
        mainThreadCpuPct = 12.0,
        appCpuPct = 35.0,
        mediaProcs = mapOf("media.codec" to 7.0),
        topProcs = listOf(ProcessSample(12, "player", 82.0)),
        videoState = "buffering",
        tunnelMode = "non_tunneled",
        topMemProcs = listOf(ProcMemSample(12, "player", 220.0)),
        screenFps = 59.9,
        videoFps = 24.0
    )
}
