package devicemonitor.data.storage

import devicemonitor.domain.releaseguard.*

data class IndexedRun(val manifest: RunManifest, val manifestPath: String)

interface RunRepository : AutoCloseable {
    fun saveProject(project: ProjectRecord)
    fun project(id: String): ProjectRecord?
    fun projects(): List<ProjectRecord>

    fun saveScenario(projectId: String, scenario: ReleaseScenario)
    fun scenario(id: String, revision: Int? = null): ReleaseScenario?
    fun scenarios(projectId: String): List<ReleaseScenario>

    fun promoteBaseline(projectId: String, baseline: BaselineRevision)
    fun activeBaseline(projectId: String, scenarioId: String): BaselineRevision?

    fun upsertRun(manifest: RunManifest, manifestPath: String)
    fun run(runId: String): IndexedRun?
    fun runs(projectId: String? = null): List<IndexedRun>
    fun saveEvaluation(runId: String, evaluation: ReleaseEvaluation)
    fun evaluation(runId: String): ReleaseEvaluation?

    fun rebuildFromArtifacts(store: ArtifactStore): Int
}

