package devicemonitor

import devicemonitor.data.storage.*
import devicemonitor.domain.releaseguard.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class SqliteRunRepositoryTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC)

    @Test fun storesProjectsAndImmutableScenarioRevisionsAcrossReopen() {
        val dir = Files.createTempDirectory("release-guard-db").toFile()
        val db = dir.resolve("guard.db")
        SqliteRunRepository(db, fixedClock).use { repository ->
            repository.saveProject(project())
            repository.saveScenario("player", scenario(1, "Launch"))
            repository.saveScenario("player", scenario(2, "Launch and browse"))
            assertEquals(2, repository.scenario("launch")?.revision)
            assertEquals(listOf("launch"), repository.scenarios("player").map { it.id })

            val mutation = scenario(1, "Changed old revision")
            assertFailsWith<IllegalArgumentException> { repository.saveScenario("player", mutation) }
            assertEquals("Launch", repository.scenario("launch", 1)?.name)
        }
        SqliteRunRepository(db, fixedClock).use { repository ->
            assertEquals("Player", repository.project("player")?.name)
            assertEquals(2, repository.scenario("launch")?.revision)
        }
    }

    @Test fun promotesOneActiveImmutableBaseline() {
        val repository = repository()
        repository.use {
            it.saveProject(project())
            it.saveScenario("player", scenario())
            val first = baseline("base-1", 100.0)
            val second = baseline("base-2", 90.0)
            it.promoteBaseline("player", first)
            assertEquals("base-1", it.activeBaseline("player", "launch")?.id)
            it.promoteBaseline("player", second)
            assertEquals("base-2", it.activeBaseline("player", "launch")?.id)
            assertFailsWith<IllegalArgumentException> {
                it.promoteBaseline("player", first.copy(attempts = baseline("x", 120.0).attempts))
            }
            assertEquals("base-2", it.activeBaseline("player", "launch")?.id)
        }
    }

    @Test fun indexesRunsEvaluationsAndRebuildsFromArtifacts() {
        val dir = Files.createTempDirectory("release-guard-rebuild").toFile()
        val store = ArtifactStore(dir, fixedClock, { "run" })
        val location = store.createRun("Player", "Launch")
        val manifest = manifest(location.runId)
        store.writeManifest(location, manifest)

        SqliteRunRepository(dir.resolve("guard.db"), fixedClock).use { repository ->
            repository.saveProject(project())
            repository.saveScenario("player", scenario())
            assertEquals(1, repository.rebuildFromArtifacts(store))
            assertEquals(location.runId, repository.runs("player").single().manifest.runId)
            val evaluation = ReleaseEvaluation(Verdict.WARNING, emptyList())
            repository.saveEvaluation(location.runId, evaluation)
            assertEquals(evaluation, repository.evaluation(location.runId))
        }
    }

    private fun repository(): SqliteRunRepository {
        val dir = Files.createTempDirectory("release-guard-repository").toFile()
        return SqliteRunRepository(dir.resolve("guard.db"), fixedClock)
    }

    private fun project() = ProjectRecord(
        id = "player",
        name = "Player",
        packageName = "com.example.player",
        createdAt = "2026-11-16T10:00:00Z",
        updatedAt = "2026-11-16T10:00:00Z"
    )

    private fun scenario(revision: Int = 1, name: String = "Launch") = ReleaseScenario(
        id = "launch",
        revision = revision,
        name = name,
        packageName = "com.example.player",
        steps = listOf(ScenarioStep.LaunchApp()),
        budgets = listOf(
            PerformanceBudget(
                "cpu", "CPU", MetricDefinition("cpu", "%", true),
                Aggregation.P95, absoluteLimit = 80.0
            )
        )
    )

    private fun baseline(id: String, value: Double) = BaselineRevision(
        id,
        BaselineIdentity("Device", "16", "firmware", "launch", 1),
        listOf(AttemptSummary(1, true, listOf(MetricSeries("cpu", values = listOf(value)))))
    )

    private fun manifest(runId: String) = RunManifest(
        runId = runId,
        projectId = "player",
        scenarioId = "launch",
        scenarioRevision = 1,
        state = ExecutionState.PASSED,
        startedAt = "2026-11-16T11:00:00Z",
        updatedAt = "2026-11-16T11:01:00Z",
        evaluation = ReleaseEvaluation(Verdict.PASS, emptyList())
    )
}
