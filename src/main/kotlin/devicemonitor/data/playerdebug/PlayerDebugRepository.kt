package devicemonitor.data.playerdebug

import devicemonitor.domain.playerdebug.PlayerIncident
import devicemonitor.domain.playerdebug.PlayerSessionGraph

data class IndexedPlayerCapture(val manifest: PlayerDebugManifest, val manifestPath: String)

interface PlayerDebugRepository : AutoCloseable {
    fun upsert(
        manifest: PlayerDebugManifest,
        manifestPath: String,
        graph: PlayerSessionGraph? = null,
        incidents: List<PlayerIncident> = emptyList()
    )
    fun capture(captureId: String): IndexedPlayerCapture?
    fun captures(packageName: String? = null): List<IndexedPlayerCapture>
    fun rebuildFromArtifacts(): Int
    override fun close()
}
