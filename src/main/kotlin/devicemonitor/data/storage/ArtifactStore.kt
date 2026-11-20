package devicemonitor.data.storage

import devicemonitor.domain.releaseguard.ExecutionState
import java.io.File
import java.nio.file.*
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.stream.Collectors

data class RunLocation(val runId: String, val directory: File, val manifestFile: File)
enum class RecoveryStatus { COMPLETE, INCOMPLETE, CORRUPT }
data class RecoveredRun(
    val directory: File,
    val manifest: RunManifest?,
    val status: RecoveryStatus,
    val error: String? = null
)

class ArtifactStore(
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

    fun createRun(projectName: String, scenarioName: String): RunLocation {
        val stamp = RUN_STAMP.format(clock.instant())
        val nonce = sanitizeSegment(idFactory()).take(24)
        val scenarioSegment = sanitizeSegment(scenarioName)
        val runId = "${scenarioSegment}_${stamp}_$nonce"
        val directory = inside(sanitizeSegment(projectName), scenarioSegment, runId)
        Files.createDirectories(directory)
        return RunLocation(runId, directory.toFile(), directory.resolve("run.json").toFile())
    }

    fun attemptPath(run: RunLocation, attempt: Int): File {
        require(attempt > 0) { "Attempt number must be positive" }
        val path = ensureInside(run.directory.toPath().resolve("attempt-%02d".format(attempt)))
        return path.toFile()
    }

    fun writeManifest(run: RunLocation, manifest: RunManifest) {
        require(run.runId == manifest.runId) { "Manifest run id '${manifest.runId}' does not match '${run.runId}'" }
        atomicWrite(run.manifestFile, ReleaseGuardJson.manifest(manifest))
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

    fun registerOutput(attemptDirectory: File, name: String, source: File): File {
        require(source.isFile) { "Output does not exist: ${source.absolutePath}" }
        val safeName = sanitizeFileName(name)
        val target = ensureInside(attemptDirectory.toPath().resolve(safeName))
        Files.createDirectories(target.parent)
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        return target.toFile()
    }

    fun resolve(relativePath: String): File {
        val relative = Paths.get(relativePath)
        require(!relative.isAbsolute) { "Artifact path must be relative" }
        return ensureInside(rootPath.resolve(relative)).toFile()
    }

    fun relative(file: File): String = rootPath.relativize(ensureInside(file.toPath())).toString()

    fun scan(): List<RecoveredRun> {
        if (!Files.exists(rootPath)) return emptyList()
        return Files.walk(rootPath).use { paths ->
            paths.filter { it.fileName.toString() == "run.json" && Files.isRegularFile(it) }
                .map { manifestPath ->
                    try {
                        val manifest = ReleaseGuardJson.parseManifest(Files.readString(manifestPath))
                        RecoveredRun(
                            manifestPath.parent.toFile(),
                            manifest,
                            if (manifest.state.isTerminal()) RecoveryStatus.COMPLETE else RecoveryStatus.INCOMPLETE
                        )
                    } catch (e: Exception) {
                        RecoveredRun(manifestPath.parent.toFile(), null, RecoveryStatus.CORRUPT, e.message ?: e.toString())
                    }
                }
                .sorted(compareByDescending { it.directory.name })
                .collect(Collectors.toList())
        }
    }

    internal fun sanitizeSegment(value: String): String {
        val sanitized = value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_')
            .take(80)
        return sanitized.ifBlank { "untitled" }
    }

    private fun sanitizeFileName(value: String): String {
        require(value.isNotBlank()) { "Artifact name is required" }
        require(value == File(value).name) { "Artifact name must not contain a path" }
        return sanitizeSegment(value).ifBlank { "artifact" }
    }

    private fun inside(vararg segments: String): Path = ensureInside(segments.fold(rootPath) { path, segment -> path.resolve(segment) })

    private fun ensureInside(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(rootPath)) { "Artifact path escapes the Release Guard root" }

        var existing: Path? = normalized
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing != null) {
            val real = existing.toRealPath()
            require(real.startsWith(rootRealPath)) { "Artifact path escapes the Release Guard root through a symbolic link" }
        }
        return normalized
    }

    companion object {
        private val RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)
    }
}
