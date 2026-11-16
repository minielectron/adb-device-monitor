package devicemonitor.application.playerdebug

import devicemonitor.application.MonitorSession
import devicemonitor.application.SessionConfig
import devicemonitor.data.playerdebug.MediaSnapshot
import devicemonitor.data.playerdebug.OptdParseDiagnostic
import devicemonitor.domain.playerdebug.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

interface PlayerMonitorCapture {
    val status: MonitorSession.Status
    val error: String?
    val runDirectory: File?
    fun start()
    fun stop()
    fun events(): List<PlaybackEvent>
    fun diagnostics(): List<OptdParseDiagnostic>
    fun snapshots(): List<MediaSnapshot>
}

fun interface PlayerMonitorCaptureFactory {
    fun create(config: SessionConfig): PlayerMonitorCapture
}

class MonitorPlayerCapture(private val session: MonitorSession) : PlayerMonitorCapture {
    override val status: MonitorSession.Status get() = session.status
    override val error: String? get() = session.error
    override val runDirectory: File? get() = session.runDir
    override fun start() = session.startAsync()
    override fun stop() = session.requestStop()
    override fun events(): List<PlaybackEvent> = synchronized(session.playerDebugEvents) { session.playerDebugEvents.toList() }
    override fun diagnostics(): List<OptdParseDiagnostic> = synchronized(session.playerDebugDiagnostics) { session.playerDebugDiagnostics.toList() }
    override fun snapshots(): List<MediaSnapshot> = synchronized(session.playerDebugSnapshots) { session.playerDebugSnapshots.toList() }
}

data class PlayerDebugProgress(
    val state: PlayerCaptureState = PlayerCaptureState.SELECT,
    val message: String = "Waiting",
    val eventCount: Int = 0,
    val diagnosticCount: Int = 0,
    val snapshotCount: Int = 0,
    val runDirectory: String? = null,
    val error: String? = null
)

class PlayerDebugService(private val captureFactory: PlayerMonitorCaptureFactory) {
    @Volatile private var capture: PlayerMonitorCapture? = null
    @Volatile private var worker: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    @Volatile var progress = PlayerDebugProgress(); private set
    @Volatile var graph: PlayerSessionGraph? = null; private set

    @Synchronized fun start(config: SessionConfig) {
        require(worker?.isAlive != true) { "A Player Debugger capture is already active" }
        require(!config.packageName.isNullOrBlank()) { "Player Debugger requires a package name" }
        stopRequested.set(false)
        graph = null
        progress = PlayerDebugProgress(PlayerCaptureState.PROBE, "Preparing Player Debugger capture")
        val active = captureFactory.create(config.copy(logcatEnabled = true, playerDebugEnabled = true))
        capture = active
        worker = Thread({ runCapture(active) }, "player-debugger").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopRequested.set(true)
        capture?.stop()
    }

    fun isRunning(): Boolean = worker?.isAlive == true

    private fun runCapture(active: PlayerMonitorCapture) {
        try {
            progress = progress.copy(state = PlayerCaptureState.READY, message = "Starting capture")
            if (stopRequested.get()) {
                progress = status(active, PlayerCaptureState.CANCELLED, "Capture stopped")
                return
            }
            active.start()
            while (active.status == MonitorSession.Status.STARTING) {
                if (stopRequested.get()) active.stop()
                Thread.sleep(25)
            }
            progress = progress.copy(state = PlayerCaptureState.CAPTURING, message = "Capturing playback evidence")
            while (active.status == MonitorSession.Status.RUNNING || active.status == MonitorSession.Status.STARTING) {
                if (stopRequested.get()) active.stop()
                progress = status(active, PlayerCaptureState.CAPTURING, "Capturing playback evidence")
                Thread.sleep(100)
            }
            progress = status(active, PlayerCaptureState.FINALIZING, "Finalizing evidence")
            val events = active.events()
            progress = status(active, PlayerCaptureState.ANALYZING, "Reconstructing player sessions")
            graph = SessionGraphBuilder().build(EventReplay().replay(events))
            val finalState = when {
                stopRequested.get() -> PlayerCaptureState.CANCELLED
                active.status == MonitorSession.Status.DONE -> PlayerCaptureState.COMPLETE
                events.isNotEmpty() -> PlayerCaptureState.PARTIAL
                else -> PlayerCaptureState.FAILED
            }
            progress = status(active, finalState, when (finalState) {
                PlayerCaptureState.COMPLETE -> "Capture complete"
                PlayerCaptureState.CANCELLED -> "Capture stopped"
                PlayerCaptureState.PARTIAL -> "Partial evidence recovered"
                else -> "Capture failed"
            })
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            active.stop()
            progress = status(active, PlayerCaptureState.CANCELLED, "Capture interrupted")
        } catch (e: Exception) {
            active.stop()
            progress = status(active, PlayerCaptureState.FAILED, "Capture failed", e.message ?: e.toString())
        }
    }

    private fun status(active: PlayerMonitorCapture, state: PlayerCaptureState, message: String, error: String? = active.error) =
        PlayerDebugProgress(
            state = state,
            message = message,
            eventCount = active.events().size,
            diagnosticCount = active.diagnostics().size,
            snapshotCount = active.snapshots().size,
            runDirectory = active.runDirectory?.absolutePath,
            error = error
        )
}
