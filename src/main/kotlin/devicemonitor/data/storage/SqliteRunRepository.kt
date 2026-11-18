package devicemonitor.data.storage

import devicemonitor.domain.releaseguard.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

class SqliteRunRepository(database: File, private val clock: Clock = Clock.systemUTC()) : RunRepository {
    private val connection: Connection

    init {
        database.absoluteFile.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
        migrate()
    }

    @Synchronized
    override fun saveProject(project: ProjectRecord) {
        require(project.schemaVersion == RELEASE_GUARD_SCHEMA_VERSION) { "Unsupported project schema ${project.schemaVersion}" }
        require(project.id.isNotBlank() && project.name.isNotBlank() && project.packageName.isNotBlank()) { "Project id, name and package are required" }
        connection.prepareStatement(
            """INSERT INTO projects(id, json, created_at, updated_at) VALUES(?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET json=excluded.json, updated_at=excluded.updated_at"""
        ).use { statement ->
            statement.setString(1, project.id)
            statement.setString(2, ReleaseGuardJson.project(project))
            statement.setString(3, project.createdAt)
            statement.setString(4, project.updatedAt)
            statement.executeUpdate()
        }
    }

    @Synchronized
    override fun project(id: String): ProjectRecord? = connection.prepareStatement(
        "SELECT json FROM projects WHERE id=?"
    ).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) ReleaseGuardJson.parseProject(rows.getString(1)) else null }
    }

    @Synchronized
    override fun projects(): List<ProjectRecord> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT json FROM projects ORDER BY updated_at DESC, id").use { rows ->
            rows.map { ReleaseGuardJson.parseProject(it.getString(1)) }
        }
    }

    @Synchronized
    override fun saveScenario(projectId: String, scenario: ReleaseScenario) {
        require(project(projectId) != null) { "Unknown project '$projectId'" }
        val errors = scenario.validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        transaction {
            val json = ReleaseGuardJson.scenario(scenario)
            val existing = connection.prepareStatement(
                "SELECT json FROM scenario_revisions WHERE scenario_id=? AND revision=?"
            ).use { statement ->
                statement.setString(1, scenario.id)
                statement.setInt(2, scenario.revision)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            require(existing == null || existing == json) {
                "Scenario '${scenario.id}' revision ${scenario.revision} is immutable"
            }
            if (existing == null) {
                connection.prepareStatement(
                    "INSERT INTO scenario_revisions(scenario_id, revision, project_id, json, created_at) VALUES(?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, scenario.id)
                    statement.setInt(2, scenario.revision)
                    statement.setString(3, projectId)
                    statement.setString(4, json)
                    statement.setString(5, now())
                    statement.executeUpdate()
                }
                scenario.budgets.forEach { budget ->
                    connection.prepareStatement(
                        "INSERT INTO budgets(scenario_id, scenario_revision, budget_id, json) VALUES(?, ?, ?, ?)"
                    ).use { statement ->
                        statement.setString(1, scenario.id)
                        statement.setInt(2, scenario.revision)
                        statement.setString(3, budget.id)
                        statement.setString(4, ReleaseGuardJson.budget(budget))
                        statement.executeUpdate()
                    }
                }
            }
            connection.prepareStatement(
                """INSERT INTO scenarios(id, project_id, name, active_revision, updated_at) VALUES(?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET name=excluded.name,
                       active_revision=MAX(scenarios.active_revision, excluded.active_revision), updated_at=excluded.updated_at"""
            ).use { statement ->
                statement.setString(1, scenario.id)
                statement.setString(2, projectId)
                statement.setString(3, scenario.name)
                statement.setInt(4, scenario.revision)
                statement.setString(5, now())
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    override fun scenario(id: String, revision: Int?): ReleaseScenario? {
        val sql = if (revision == null) {
            """SELECT r.json FROM scenario_revisions r JOIN scenarios s ON s.id=r.scenario_id
               WHERE r.scenario_id=? AND r.revision=s.active_revision"""
        } else {
            "SELECT json FROM scenario_revisions WHERE scenario_id=? AND revision=?"
        }
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, id)
            if (revision != null) statement.setInt(2, revision)
            statement.executeQuery().use { rows -> if (rows.next()) ReleaseGuardJson.parseScenario(rows.getString(1)) else null }
        }
    }

    @Synchronized
    override fun scenarios(projectId: String): List<ReleaseScenario> = connection.prepareStatement(
        """SELECT r.json FROM scenario_revisions r JOIN scenarios s ON s.id=r.scenario_id
           WHERE s.project_id=? AND r.revision=s.active_revision ORDER BY s.updated_at DESC, s.id"""
    ).use { statement ->
        statement.setString(1, projectId)
        statement.executeQuery().use { rows -> rows.map { ReleaseGuardJson.parseScenario(it.getString(1)) } }
    }

    @Synchronized
    override fun promoteBaseline(projectId: String, baseline: BaselineRevision) {
        require(project(projectId) != null) { "Unknown project '$projectId'" }
        require(scenario(baseline.identity.scenarioId, baseline.identity.scenarioRevision) != null) {
            "Unknown scenario '${baseline.identity.scenarioId}' revision ${baseline.identity.scenarioRevision}"
        }
        transaction {
            val json = ReleaseGuardJson.baseline(baseline)
            val existing = connection.prepareStatement("SELECT json FROM baselines WHERE id=?").use { statement ->
                statement.setString(1, baseline.id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            require(existing == null || existing == json) { "Baseline '${baseline.id}' is immutable" }
            connection.prepareStatement(
                "UPDATE baselines SET active=0 WHERE project_id=? AND scenario_id=?"
            ).use { statement ->
                statement.setString(1, projectId)
                statement.setString(2, baseline.identity.scenarioId)
                statement.executeUpdate()
            }
            if (existing == null) {
                connection.prepareStatement(
                    """INSERT INTO baselines(id, project_id, scenario_id, scenario_revision, active, json, created_at)
                       VALUES(?, ?, ?, ?, 1, ?, ?)"""
                ).use { statement ->
                    statement.setString(1, baseline.id)
                    statement.setString(2, projectId)
                    statement.setString(3, baseline.identity.scenarioId)
                    statement.setInt(4, baseline.identity.scenarioRevision)
                    statement.setString(5, json)
                    statement.setString(6, now())
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement("UPDATE baselines SET active=1 WHERE id=?").use { statement ->
                    statement.setString(1, baseline.id)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Synchronized
    override fun activeBaseline(projectId: String, scenarioId: String): BaselineRevision? = connection.prepareStatement(
        "SELECT json FROM baselines WHERE project_id=? AND scenario_id=? AND active=1"
    ).use { statement ->
        statement.setString(1, projectId)
        statement.setString(2, scenarioId)
        statement.executeQuery().use { rows -> if (rows.next()) ReleaseGuardJson.parseBaseline(rows.getString(1)) else null }
    }

    @Synchronized
    override fun upsertRun(manifest: RunManifest, manifestPath: String) {
        require(manifestPath.isNotBlank() && !File(manifestPath).isAbsolute) { "Manifest path must be relative" }
        transaction {
            manifest.device?.let { identity ->
                val deviceId = deviceId(identity)
                connection.prepareStatement(
                    "INSERT OR IGNORE INTO devices(id, json, updated_at) VALUES(?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, deviceId)
                    statement.setString(2, ReleaseGuardJson.baseline(BaselineRevision("device", identity, emptyList())))
                    statement.setString(3, now())
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                """INSERT INTO runs(id, project_id, scenario_id, scenario_revision, state, verdict, manifest_path, started_at, updated_at)
                   VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET state=excluded.state, verdict=excluded.verdict,
                       manifest_path=excluded.manifest_path, updated_at=excluded.updated_at"""
            ).use { statement ->
                statement.setString(1, manifest.runId)
                statement.setString(2, manifest.projectId)
                statement.setString(3, manifest.scenarioId)
                statement.setInt(4, manifest.scenarioRevision)
                statement.setString(5, manifest.state.name)
                statement.setString(6, manifest.evaluation?.verdict?.name)
                statement.setString(7, manifestPath)
                statement.setString(8, manifest.startedAt)
                statement.setString(9, manifest.updatedAt)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM attempts WHERE run_id=?").use { statement ->
                statement.setString(1, manifest.runId)
                statement.executeUpdate()
            }
            manifest.attempts.forEach { attempt ->
                connection.prepareStatement(
                    "INSERT INTO attempts(run_id, attempt, successful, json) VALUES(?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, manifest.runId)
                    statement.setInt(2, attempt.attempt)
                    statement.setInt(3, if (attempt.successful) 1 else 0)
                    statement.setString(4, ReleaseGuardJson.attempts(listOf(attempt)))
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement("DELETE FROM artifacts WHERE run_id=?").use { statement ->
                statement.setString(1, manifest.runId)
                statement.executeUpdate()
            }
            manifest.artifacts.forEach { (name, path) ->
                connection.prepareStatement("INSERT INTO artifacts(run_id, name, path) VALUES(?, ?, ?)").use { statement ->
                    statement.setString(1, manifest.runId)
                    statement.setString(2, name)
                    statement.setString(3, path)
                    statement.executeUpdate()
                }
            }
            manifest.evaluation?.let { saveEvaluationInTransaction(manifest.runId, it) }
        }
    }

    @Synchronized
    override fun run(runId: String): IndexedRun? = connection.prepareStatement(
        "SELECT manifest_path FROM runs WHERE id=?"
    ).use { statement ->
        statement.setString(1, runId)
        statement.executeQuery().use { rows -> if (rows.next()) loadIndexedRun(rows.getString(1)) else null }
    }

    @Synchronized
    override fun runs(projectId: String?): List<IndexedRun> {
        val sql = if (projectId == null) {
            "SELECT manifest_path FROM runs ORDER BY updated_at DESC, id DESC"
        } else {
            "SELECT manifest_path FROM runs WHERE project_id=? ORDER BY updated_at DESC, id DESC"
        }
        return connection.prepareStatement(sql).use { statement ->
            if (projectId != null) statement.setString(1, projectId)
            statement.executeQuery().use { rows -> rows.mapNotNull { loadIndexedRun(it.getString(1)) } }
        }
    }

    @Synchronized
    override fun saveEvaluation(runId: String, evaluation: ReleaseEvaluation) {
        transaction { saveEvaluationInTransaction(runId, evaluation) }
    }

    @Synchronized
    override fun evaluation(runId: String): ReleaseEvaluation? = connection.prepareStatement(
        "SELECT json FROM evaluations WHERE run_id=?"
    ).use { statement ->
        statement.setString(1, runId)
        statement.executeQuery().use { rows -> if (rows.next()) ReleaseGuardJson.parseEvaluation(rows.getString(1)) else null }
    }

    @Synchronized
    override fun rebuildFromArtifacts(store: ArtifactStore): Int {
        var count = 0
        store.scan().forEach { recovered ->
            recovered.manifest?.let { manifest ->
                upsertRun(manifest, store.relative(File(recovered.directory, "run.json")))
                count++
            }
        }
        return count
    }

    @Synchronized
    override fun close() {
        connection.close()
    }

    private fun saveEvaluationInTransaction(runId: String, evaluation: ReleaseEvaluation) {
        require(connection.prepareStatement("SELECT 1 FROM runs WHERE id=?").use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { it.next() }
        }) { "Unknown run '$runId'" }
        connection.prepareStatement(
            """INSERT INTO evaluations(run_id, json) VALUES(?, ?)
               ON CONFLICT(run_id) DO UPDATE SET json=excluded.json"""
        ).use { statement ->
            statement.setString(1, runId)
            statement.setString(2, ReleaseGuardJson.evaluation(evaluation))
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE runs SET verdict=?, updated_at=? WHERE id=?").use { statement ->
            statement.setString(1, evaluation.verdict.name)
            statement.setString(2, now())
            statement.setString(3, runId)
            statement.executeUpdate()
        }
    }

    private fun loadIndexedRun(path: String): IndexedRun? = try {
        val file = databaseRoot().resolve(path).normalize().toFile()
        IndexedRun(ReleaseGuardJson.parseManifest(file.readText()), path)
    } catch (_: Exception) {
        null
    }

    private fun databaseRoot(): java.nio.file.Path {
        val url = connection.metaData.url.removePrefix("jdbc:sqlite:")
        return File(url).absoluteFile.parentFile.toPath()
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_meta(version INTEGER NOT NULL)")
            if (statement.executeQuery("SELECT COUNT(*) FROM schema_meta").use { it.next(); it.getInt(1) } == 0) {
                statement.executeUpdate("INSERT INTO schema_meta(version) VALUES(1)")
            }
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY, json TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS scenarios(id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), name TEXT NOT NULL, active_revision INTEGER NOT NULL, updated_at TEXT NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS scenario_revisions(scenario_id TEXT NOT NULL REFERENCES scenarios(id) DEFERRABLE INITIALLY DEFERRED, revision INTEGER NOT NULL, project_id TEXT NOT NULL REFERENCES projects(id), json TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(scenario_id, revision))")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at TEXT NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, project_id TEXT NOT NULL, scenario_id TEXT NOT NULL, scenario_revision INTEGER NOT NULL, state TEXT NOT NULL, verdict TEXT, manifest_path TEXT NOT NULL, started_at TEXT NOT NULL, updated_at TEXT NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS attempts(run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, attempt INTEGER NOT NULL, successful INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(run_id, attempt))")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS budgets(scenario_id TEXT NOT NULL, scenario_revision INTEGER NOT NULL, budget_id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(scenario_id, scenario_revision, budget_id))")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS baselines(id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), scenario_id TEXT NOT NULL, scenario_revision INTEGER NOT NULL, active INTEGER NOT NULL, json TEXT NOT NULL, created_at TEXT NOT NULL)")
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS active_baseline ON baselines(project_id, scenario_id) WHERE active=1")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS evaluations(run_id TEXT PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE, json TEXT NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS artifacts(run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, name TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(run_id, name))")
        }
    }

    private fun <T> transaction(block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previous
        }
    }

    private fun now(): String = Instant.now(clock).toString()
    private fun deviceId(identity: BaselineIdentity): String =
        listOf(identity.model, identity.androidVersion, identity.firmware).joinToString("|")

    private inline fun <T> ResultSet.map(transform: (ResultSet) -> T): List<T> {
        val result = mutableListOf<T>()
        while (next()) result += transform(this)
        return result
    }

    private inline fun <T : Any> ResultSet.mapNotNull(transform: (ResultSet) -> T?): List<T> {
        val result = mutableListOf<T>()
        while (next()) transform(this)?.let { result += it }
        return result
    }
}

