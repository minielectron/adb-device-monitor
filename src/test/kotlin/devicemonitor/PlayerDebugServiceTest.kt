package devicemonitor

import devicemonitor.application.MonitorSession
import devicemonitor.application.SessionConfig
import devicemonitor.application.playerdebug.*
import devicemonitor.domain.playerdebug.*
import kotlin.test.*

class PlayerDebugServiceTest {
    @Test fun enablesRequiredCaptureSignalsAndBuildsGraph() {
        lateinit var received: SessionConfig
        val fake = FakeCapture(events = listOf(event("session.started", 1, view = "v"), event("player.created", 2, view = "v", player = "p")))
        val service = PlayerDebugService { config -> received = config; fake }
        service.start(config())
        awaitDone(service)
        assertTrue(received.logcatEnabled)
        assertTrue(received.playerDebugEnabled)
        assertEquals(PlayerCaptureState.COMPLETE, service.progress.state)
        assertEquals(1, service.graph?.viewingSessions?.size)
    }

    @Test fun rejectsMissingPackageAndConcurrentCapture() {
        val fake = FakeCapture(autoFinish = false)
        val service = PlayerDebugService { fake }
        assertFailsWith<IllegalArgumentException> { service.start(config().copy(packageName = null)) }
        service.start(config())
        assertFailsWith<IllegalArgumentException> { service.start(config()) }
        service.stop()
        fake.statusValue = MonitorSession.Status.DONE
        awaitDone(service)
    }

    @Test fun failedCaptureWithEvidenceIsPartial() {
        val fake = FakeCapture(events = listOf(event("app.crash", 1)), terminal = MonitorSession.Status.FAILED)
        val service = PlayerDebugService { fake }
        service.start(config())
        awaitDone(service)
        assertEquals(PlayerCaptureState.PARTIAL, service.progress.state)
        assertNotNull(service.graph)
    }

    @Test fun analyzesIncidentsAfterCapture() {
        val fake = FakeCapture(events = listOf(event("error.reported", 1, view = "v", player = "p")))
        val service = PlayerDebugService { fake }
        service.start(config())
        awaitDone(service)
        assertTrue(service.incidents.any { it.ruleId == "playback_error" })
    }

    private fun config() = SessionConfig("device", 1.0, 0.1, packageName = "example.app")

    private fun awaitDone(service: PlayerDebugService) {
        repeat(200) { if (!service.isRunning()) return; Thread.sleep(10) }
        fail("Player Debugger service did not finish")
    }

    private fun event(type: String, time: Long, view: String? = null, player: String? = null) = PlaybackEvent(
        sequence = time,
        schema = "optd/1",
        type = type,
        timeNs = time,
        packageName = "example.app",
        pid = 1,
        viewingSessionId = view,
        instanceId = player,
        source = EvidenceSource.APP_REPORTED,
        confidence = 1.0
    )

    private class FakeCapture(
        private val events: List<PlaybackEvent> = emptyList(),
        private val autoFinish: Boolean = true,
        private val terminal: MonitorSession.Status = MonitorSession.Status.DONE
    ) : PlayerMonitorCapture {
        @Volatile var statusValue = MonitorSession.Status.STARTING
        override val status: MonitorSession.Status get() = statusValue
        override val error: String? get() = if (statusValue == MonitorSession.Status.FAILED) "failed" else null
        override val runDirectory = null
        override fun start() { statusValue = MonitorSession.Status.RUNNING; if (autoFinish) statusValue = terminal }
        override fun stop() { if (statusValue != MonitorSession.Status.DONE) statusValue = MonitorSession.Status.DONE }
        override fun events() = events
        override fun diagnostics() = emptyList<devicemonitor.data.playerdebug.OptdParseDiagnostic>()
        override fun snapshots() = emptyList<devicemonitor.data.playerdebug.MediaSnapshot>()
    }
}
