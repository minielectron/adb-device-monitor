package devicemonitor.application.releaseguard

import devicemonitor.data.device.DeviceDriver
import devicemonitor.data.storage.ProjectRecord
import devicemonitor.domain.releaseguard.*

class ReleaseGuardService(private val runnerFactory: () -> ScenarioRunner) {
    @Volatile private var activeRunner: ScenarioRunner? = null
    @Volatile private var activeThread: Thread? = null

    @Synchronized
    fun start(
        project: ProjectRecord,
        scenario: ReleaseScenario,
        driver: DeviceDriver,
        baseline: BaselineRevision? = null,
        allowCompatibleDeviceBaseline: Boolean = false
    ) {
        require(activeThread?.isAlive != true) { "A Release Guard run is already active" }
        val runner = runnerFactory()
        activeRunner = runner
        activeThread = Thread({ runner.run(project, scenario, driver, baseline, allowCompatibleDeviceBaseline) }, "release-guard").apply {
            isDaemon = true
            start()
        }
    }

    fun progress(): ReleaseGuardProgress = activeRunner?.progress ?: ReleaseGuardProgress()
    fun latestManifest() = activeRunner?.latestManifest
    fun cancel() = activeRunner?.cancel()
    fun isRunning(): Boolean = activeThread?.isAlive == true
}
