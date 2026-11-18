package devicemonitor.data.playerdebug

import devicemonitor.domain.playerdebug.*
import java.io.File
import java.nio.file.*
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.stream.Collectors

data class PlayerCaptureLocation(val captureId: String, val directory: File, val manifestFile: File)
enum class PlayerCaptureRecoveryStatus { COMPLETE, PARTIAL, CORRUPT }
data class RecoveredPlayerCapture(
    val directory: File,
    val manifest: PlayerDebugManifest?,
    val status: PlayerCaptureRecoveryStatus,
    val error: String? = null
)
data class PlayerEventPage(val offset: Long, val events: List<PlaybackEvent>, val nextOffset: Long?, val totalRead: Long)

class PlayerDebugArtifactStore(
    root: File,
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: () -> String = { UUID.randomUUID().toString().take(8) }
) {
    val root: File
    private val rootPath: Path
    private val rootRealPath: Path

    init {
        rootPath = root.toPath().toAbsolutePath().normalize()
        Files.createDirectories(rootPath)
        rootRealPath = rootPath.toRealPath()
        this.root = rootPath.toFile()
    }

    fun createCapture(packageOrProject: String): PlayerCaptureLocation {
        val captureId = "${STAMP.format(clock.instant())}_${sanitize(idFactory()).take(24)}"
        val directory = ensureInside(rootPath.resolve(sanitize(packageOrProject)).resolve(captureId))
        Files.createDirectories(directory)
        return PlayerCaptureLocation(captureId, directory.toFile(), directory.resolve("manifest.json").toFile())
    }

    fun writeManifest(location: PlayerCaptureLocation, manifest: PlayerDebugManifest) {
        require(location.captureId == manifest.captureId) { "Manifest capture id does not match its folder" }
        manifest.validate()
        atomicWrite(location.manifestFile, PlayerDebugJson.manifest(manifest))
    }

    fun writeEvents(location: PlayerCaptureLocation, events: Iterable<PlaybackEvent>): File {
        val target = ensureInside(location.directory.toPath().resolve("events.ndjson"))
        atomicWrite(target.toFile(), events.joinToString("\n", postfix = "\n") { PlayerDebugJson.event(it) })
        return target.toFile()
    }

    fun writeGraph(location: PlayerCaptureLocation, graph: PlayerSessionGraph): File =
        writeNamed(location, "session-graph.json", PlayerDebugJson.graph(graph))

    fun writeIncidents(location: PlayerCaptureLocation, incidents: List<PlayerIncident>): File =
        writeNamed(location, "incidents.json", PlayerDebugJson.incidents(incidents))

    fun writeParserDiagnostics(location: PlayerCaptureLocation, diagnostics: List<OptdParseDiagnostic>): File =
        writeNamed(location, "parser-diagnostics.json", com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(diagnostics))

    fun readEventPage(file: File, offset: Long, limit: Int): PlayerEventPage {
        require(offset >= 0) { "Timeline offset cannot be negative" }
        require(limit in 1..500) { "Timeline limit must be between 1 and 500" }
        val target = ensureInside(file.toPath())
        require(Files.isRegularFile(target)) { "Event artifact does not exist" }
        val events = ArrayList<PlaybackEvent>(limit)
        var index = 0L
        var hasMore = false
        Files.newBufferedReader(target).useLines { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                val line = iterator.next()
                if (index++ < offset || line.isBlank()) continue
                if (events.size == limit) { hasMore = true; break }
                events += PlayerDebugJson.parseEvent(line)
            }
        }
        return PlayerEventPage(offset, events, if (hasMore) offset + events.size else null, index)
    }

    fun readEventWindow(file: File, anchorSequence: Long, limit: Int): PlayerEventPage {
        require(anchorSequence >= 0) { "Timeline sequence cannot be negative" }
        require(limit in 1..500) { "Timeline limit must be between 1 and 500" }
        val target = ensureInside(file.toPath())
        require(Files.isRegularFile(target)) { "Event artifact does not exist" }
        val before = ArrayDeque<Pair<Long, PlaybackEvent>>()
        val events = ArrayList<PlaybackEvent>(limit)
        val preceding = limit / 2
        var index = 0L
        var startOffset = 0L
        var found = false
        var hasMore = false
        Files.newBufferedReader(target).useLines { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                val line = iterator.next()
                val lineOffset = index++
                if (line.isBlank()) continue
                val event = PlayerDebugJson.parseEvent(line)
                if (!found) {
                    if (event.sequence == anchorSequence) {
                        startOffset = before.firstOrNull()?.first ?: lineOffset
                        events += before.map { it.second }
                        events += event
                        found = true
                    } else if (preceding > 0) {
                        before.addLast(lineOffset to event)
                        if (before.size > preceding) before.removeFirst()
                    }
                } else if (events.size == limit) {
                    hasMore = true
                    break
                } else {
                    events += event
                }
            }
        }
        require(found) { "Timeline event sequence was not found" }
        return PlayerEventPage(startOffset, events, if (hasMore) startOffset + events.size else null, index)
    }

    fun register(location: PlayerCaptureLocation, name: String, source: File): File {
        require(source.isFile) { "Artifact does not exist: ${source.absolutePath}" }
        require(name.isNotBlank() && name == File(name).name) { "Artifact name must not contain a path" }
        val target = ensureInside(location.directory.toPath().resolve(sanitize(name)))
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        return target.toFile()
    }

    fun resolve(relativePath: String): File {
        val relative = Paths.get(relativePath)
        require(!relative.isAbsolute) { "Artifact path must be relative" }
        return ensureInside(rootPath.resolve(relative)).toFile()
    }

    fun relative(file: File): String = rootPath.relativize(ensureInside(file.toPath())).toString()

    fun scan(): List<RecoveredPlayerCapture> = Files.walk(rootPath).use { paths ->
        paths.filter { it.fileName.toString() == "manifest.json" && Files.isRegularFile(it) }
            .map { path ->
                try {
                    val manifest = PlayerDebugJson.parseManifest(Files.readString(path))
                    val complete = manifest.state == PlayerCaptureState.COMPLETE
                    RecoveredPlayerCapture(path.parent.toFile(), manifest, if (complete) PlayerCaptureRecoveryStatus.COMPLETE else PlayerCaptureRecoveryStatus.PARTIAL)
                } catch (e: Exception) {
                    RecoveredPlayerCapture(path.parent.toFile(), null, PlayerCaptureRecoveryStatus.CORRUPT, e.message ?: e.toString())
                }
            }.sorted(compareByDescending { it.directory.name }).collect(Collectors.toList())
    }

    fun atomicWrite(file: File, content: String) {
        val target = ensureInside(file.toPath())
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.writeString(temp, content, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun writeNamed(location: PlayerCaptureLocation, name: String, content: String): File {
        val file = ensureInside(location.directory.toPath().resolve(name)).toFile()
        atomicWrite(file, content)
        return file
    }

    internal fun sanitize(value: String): String {
        val safe = value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(100)
        return safe.ifBlank { "untitled" }
    }

    private fun ensureInside(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(rootPath)) { "Artifact path escapes the Player Debugger root" }
        var existing: Path? = normalized
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing != null) require(existing.toRealPath().startsWith(rootRealPath)) {
            "Artifact path escapes the Player Debugger root through a symbolic link"
        }
        return normalized
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)
    }
}
