package devicemonitor.data.device

import devicemonitor.domain.releaseguard.*
import java.io.File

data class StepOutput(val message: String, val artifact: File? = null)

interface DeviceDriver {
    val serial: String
    fun target(refresh: Boolean = false): TargetDevice
    fun isConnected(): Boolean
    fun perform(
        step: ScenarioStep,
        packageName: String,
        artifactDirectory: File,
        cancelled: () -> Boolean = { false }
    ): StepOutput
}

