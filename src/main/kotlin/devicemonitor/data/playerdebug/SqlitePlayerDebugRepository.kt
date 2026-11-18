package devicemonitor.data.playerdebug

import devicemonitor.domain.playerdebug.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class SqlitePlayerDebugRepository(
    database: File,
    private val store: PlayerDebugArtifactStore
) : PlayerDebugRepository {
    private val connection: Connection

    init {
        database.absoluteFile.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")
        connection.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("PRAGMA busy_timeout = 5000")
        }
        migrate()
    }

    @Synchronized override fun upsert(
        manifest: PlayerDebugManifest,
        manifestPath: String,
        graph: PlayerSessionGraph?,
        incidents: List<PlayerIncident>
    ) {
        manifest.validate()
        require(manifestPath.isNotBlank() && !File(manifestPath).isAbsolute && manifestPath.split(File.separatorChar).none { it == ".." }) {
            "Manifest path must be relative and contained"
        }
        transaction {
            connection.prepareStatement(
                """INSERT INTO player_captures(id, package_name, device_id, state, manifest_path, started_at, updated_at, event_count, session_count, incident_count)
                   VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET state=excluded.state, manifest_path=excluded.manifest_path,
                     updated_at=excluded.updated_at, event_count=excluded.event_count,
                     session_count=excluded.session_count, incident_count=excluded.incident_count"""
            ).use { statement ->
                statement.setString(1, manifest.captureId); statement.setString(2, manifest.packageName)
                statement.setString(3, manifest.deviceId); statement.setString(4, manifest.state.name)
                statement.setString(5, manifestPath); statement.setString(6, manifest.startedAt)
                statement.setString(7, manifest.updatedAt); statement.setInt(8, manifest.eventCount)
                statement.setInt(9, manifest.sessionCount); statement.setInt(10, manifest.incidentCount)
                statement.executeUpdate()
            }
            listOf("player_sessions", "player_instances", "player_handoffs", "player_incidents", "player_artifacts").forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE capture_id=?").use { it.setString(1, manifest.captureId); it.executeUpdate() }
            }
            graph?.viewingSessions?.forEach { session ->
                connection.prepareStatement("INSERT INTO player_sessions(capture_id, session_id, started_ns, ended_ns, synthetic) VALUES(?, ?, ?, ?, ?)").use {
                    it.setString(1, manifest.captureId); it.setString(2, session.id); it.setLong(3, session.startedAtNs)
                    if (session.endedAtNs == null) it.setNull(4, java.sql.Types.BIGINT) else it.setLong(4, session.endedAtNs)
                    it.setInt(5, if (session.synthetic) 1 else 0); it.executeUpdate()
                }
                session.playerInstances.forEach { player ->
                    connection.prepareStatement("INSERT INTO player_instances(capture_id, instance_id, session_id, family, delivery, started_ns, ended_ns) VALUES(?, ?, ?, ?, ?, ?, ?)").use {
                        it.setString(1, manifest.captureId); it.setString(2, player.id); it.setString(3, session.id)
                        it.setString(4, player.family); it.setString(5, player.delivery); it.setLong(6, player.startedAtNs)
                        if (player.endedAtNs == null) it.setNull(7, java.sql.Types.BIGINT) else it.setLong(7, player.endedAtNs)
                        it.executeUpdate()
                    }
                }
                session.handoffs.forEach { handoff ->
                    connection.prepareStatement("INSERT INTO player_handoffs(capture_id, handoff_id, session_id, source_id, target_id, result, requested_ns) VALUES(?, ?, ?, ?, ?, ?, ?)").use {
                        it.setString(1, manifest.captureId); it.setString(2, handoff.id); it.setString(3, session.id)
                        it.setString(4, handoff.sourceInstanceId); it.setString(5, handoff.targetInstanceId)
                        it.setString(6, handoff.result.name); it.setLong(7, handoff.requestedAtNs); it.executeUpdate()
                    }
                }
            }
            incidents.forEach { incident ->
                connection.prepareStatement("INSERT INTO player_incidents(capture_id, incident_id, rule_id, severity, started_ns, json) VALUES(?, ?, ?, ?, ?, ?)").use {
                    it.setString(1, manifest.captureId); it.setString(2, incident.id); it.setString(3, incident.ruleId)
                    it.setString(4, incident.severity.name); it.setLong(5, incident.startedAtNs)
                    it.setString(6, PlayerDebugJson.incidents(listOf(incident))); it.executeUpdate()
                }
            }
            manifest.artifacts.forEach { (name, path) ->
                connection.prepareStatement("INSERT INTO player_artifacts(capture_id, name, path) VALUES(?, ?, ?)").use {
                    it.setString(1, manifest.captureId); it.setString(2, name); it.setString(3, path); it.executeUpdate()
                }
            }
        }
    }

    @Synchronized override fun capture(captureId: String): IndexedPlayerCapture? = connection.prepareStatement(
        "SELECT manifest_path FROM player_captures WHERE id=?"
    ).use { statement ->
        statement.setString(1, captureId)
        statement.executeQuery().use { rows -> if (rows.next()) load(rows.getString(1)) else null }
    }

    @Synchronized override fun captures(packageName: String?): List<IndexedPlayerCapture> {
        val sql = if (packageName == null) "SELECT manifest_path FROM player_captures ORDER BY updated_at DESC, id DESC"
        else "SELECT manifest_path FROM player_captures WHERE package_name=? ORDER BY updated_at DESC, id DESC"
        return connection.prepareStatement(sql).use { statement ->
            if (packageName != null) statement.setString(1, packageName)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) load(rows.getString(1))?.let(::add) } }
        }
    }

    @Synchronized override fun rebuildFromArtifacts(): Int {
        var count = 0
        store.scan().forEach { recovered -> recovered.manifest?.let {
            upsert(it, store.relative(File(recovered.directory, "manifest.json"))); count++
        } }
        return count
    }

    @Synchronized override fun close() = connection.close()

    private fun load(path: String): IndexedPlayerCapture? = try {
        val file = store.resolve(path)
        IndexedPlayerCapture(PlayerDebugJson.parseManifest(file.readText()), path)
    } catch (_: Exception) { null }

    private fun migrate() = connection.createStatement().use { statement ->
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_schema(version INTEGER NOT NULL)")
        if (statement.executeQuery("SELECT COUNT(*) FROM player_schema").use { it.next(); it.getInt(1) } == 0) statement.executeUpdate("INSERT INTO player_schema(version) VALUES(1)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_captures(id TEXT PRIMARY KEY, package_name TEXT NOT NULL, device_id TEXT NOT NULL, state TEXT NOT NULL, manifest_path TEXT NOT NULL, started_at TEXT NOT NULL, updated_at TEXT NOT NULL, event_count INTEGER NOT NULL, session_count INTEGER NOT NULL, incident_count INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_sessions(capture_id TEXT NOT NULL REFERENCES player_captures(id) ON DELETE CASCADE, session_id TEXT NOT NULL, started_ns INTEGER NOT NULL, ended_ns INTEGER, synthetic INTEGER NOT NULL, PRIMARY KEY(capture_id, session_id))")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_instances(capture_id TEXT NOT NULL REFERENCES player_captures(id) ON DELETE CASCADE, instance_id TEXT NOT NULL, session_id TEXT NOT NULL, family TEXT, delivery TEXT, started_ns INTEGER NOT NULL, ended_ns INTEGER, PRIMARY KEY(capture_id, instance_id))")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_handoffs(capture_id TEXT NOT NULL REFERENCES player_captures(id) ON DELETE CASCADE, handoff_id TEXT NOT NULL, session_id TEXT NOT NULL, source_id TEXT, target_id TEXT, result TEXT NOT NULL, requested_ns INTEGER NOT NULL, PRIMARY KEY(capture_id, handoff_id))")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_incidents(capture_id TEXT NOT NULL REFERENCES player_captures(id) ON DELETE CASCADE, incident_id TEXT NOT NULL, rule_id TEXT NOT NULL, severity TEXT NOT NULL, started_ns INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(capture_id, incident_id))")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS player_artifacts(capture_id TEXT NOT NULL REFERENCES player_captures(id) ON DELETE CASCADE, name TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(capture_id, name))")
    }

    private fun <T> transaction(block: () -> T): T {
        val prior = connection.autoCommit
        connection.autoCommit = false
        try { val value = block(); connection.commit(); return value }
        catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = prior }
    }
}
