package devicemonitor.data.playerdebug

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import devicemonitor.domain.playerdebug.*

data class PlayerDebugManifest(
    val schemaVersion: Int = PLAYER_DEBUG_SCHEMA_VERSION,
    val captureId: String,
    val deviceId: String,
    val packageName: String,
    val state: PlayerCaptureState,
    val startedAt: String,
    val updatedAt: String,
    val eventCount: Int = 0,
    val sessionCount: Int = 0,
    val incidentCount: Int = 0,
    val artifacts: Map<String, String> = emptyMap(),
    val error: String? = null
) {
    fun validate() {
        require(schemaVersion == PLAYER_DEBUG_SCHEMA_VERSION) { "Unsupported Player Debugger schema version $schemaVersion" }
        require(captureId.isNotBlank()) { "Capture id is required" }
        require(deviceId.isNotBlank()) { "Device id is required" }
        require(packageName.isNotBlank()) { "Package name is required" }
        require(startedAt.isNotBlank() && updatedAt.isNotBlank()) { "Capture timestamps are required" }
        require(eventCount >= 0 && sessionCount >= 0 && incidentCount >= 0) { "Capture counts cannot be negative" }
        require(artifacts.values.all(::safeRelativePath)) {
            "Artifact paths must be relative and contained"
        }
    }
}

private fun safeRelativePath(value: String): Boolean {
    if (value.isBlank()) return false
    val path = try { java.nio.file.Paths.get(value) } catch (_: Exception) { return false }
    if (path.isAbsolute) return false
    return path.none { it.toString() == ".." }
}

object PlayerDebugJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val compact: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun manifest(value: PlayerDebugManifest): String = gson.toJson(value)
    fun parseManifest(json: String): PlayerDebugManifest = gson.fromJson(json, PlayerDebugManifest::class.java).also { it.validate() }
    fun graph(value: PlayerSessionGraph): String = gson.toJson(value)
    fun parseGraph(json: String): PlayerSessionGraph = gson.fromJson(json, PlayerSessionGraph::class.java)
    fun incidents(value: List<PlayerIncident>): String = gson.toJson(value)
    fun parseIncidents(json: String): List<PlayerIncident> = gson.fromJson(
        json,
        com.google.gson.reflect.TypeToken.getParameterized(List::class.java, PlayerIncident::class.java).type
    )
    fun event(value: PlaybackEvent): String = compact.toJson(value)
    fun parseEvent(json: String): PlaybackEvent = compact.fromJson(json, PlaybackEvent::class.java)
}
