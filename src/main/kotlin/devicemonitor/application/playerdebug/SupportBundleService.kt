package devicemonitor.application.playerdebug

import devicemonitor.data.playerdebug.*
import devicemonitor.domain.playerdebug.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class SupportBundlePreview(
    val includedFiles: List<String>,
    val excludedFiles: List<String>,
    val estimatedBytes: Long,
    val redactions: Map<RedactionCategory, Int>
)

data class ImportedSupportBundle(val directory: File, val manifest: PlayerDebugManifest)

class SupportBundleService(
    private val store: PlayerDebugArtifactStore,
    private val saltFactory: () -> String = { UUID.randomUUID().toString() }
) {
    fun preview(location: PlayerCaptureLocation, manifest: PlayerDebugManifest): SupportBundlePreview {
        val selected = selectedFiles(location, manifest)
        val counts = linkedMapOf<RedactionCategory, Int>()
        var bytes = 0L
        for ((_, file) in selected.included) {
            bytes += file.length().coerceAtMost(MAX_FILE_BYTES)
            if (isText(file)) PlayerDebugRedactor.redactText(file.readText().take(MAX_PREVIEW_CHARS)).counts.forEach { (category, count) ->
                counts[category] = (counts[category] ?: 0) + count
            }
        }
        return SupportBundlePreview(selected.included.map { it.first }, selected.excluded, bytes, counts)
    }

    fun export(location: PlayerCaptureLocation, manifest: PlayerDebugManifest, output: File): File {
        manifest.validate()
        output.absoluteFile.parentFile?.mkdirs()
        val selected = selectedFiles(location, manifest)
        val sanitizer = BundleSanitizer(saltFactory())
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            for ((name, file) in selected.included) {
                require(file.length() <= MAX_FILE_BYTES) { "Support-bundle file '$name' exceeds ${MAX_FILE_BYTES / 1024 / 1024} MB" }
                zip.putNextEntry(ZipEntry(name))
                val bytes = when (name) {
                    "manifest.json" -> PlayerDebugJson.manifest(
                        sanitizer.manifest(PlayerDebugJson.parseManifest(file.readText()), selected.artifacts)
                    ).toByteArray()
                    "events.ndjson" -> file.useLines { lines -> lines.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n") {
                        PlayerDebugJson.event(sanitizer.event(PlayerDebugJson.parseEvent(it)))
                    } }.toByteArray()
                    "session-graph.json" -> PlayerDebugJson.graph(sanitizer.graph(PlayerDebugJson.parseGraph(file.readText()))).toByteArray()
                    "incidents.json" -> PlayerDebugJson.incidents(sanitizer.incidents(PlayerDebugJson.parseIncidents(file.readText()))).toByteArray()
                    else -> if (isText(file)) PlayerDebugRedactor.redactText(file.readText()).value.toByteArray() else file.readBytes()
                }
                require(bytes.size.toLong() <= MAX_FILE_BYTES) { "Support-bundle file '$name' exceeds ${MAX_FILE_BYTES / 1024 / 1024} MB" }
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output
    }

    fun importBundle(bundle: File, destinationRoot: File): ImportedSupportBundle {
        require(bundle.isFile) { "Support bundle does not exist" }
        val destination = File(destinationRoot, "import_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
        require(destination.mkdirs()) { "Cannot create support-bundle import folder" }
        val destinationPath = destination.toPath().toAbsolutePath().normalize()
        var total = 0L
        var entries = 0
        try {
            ZipFile(bundle).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    entries++
                    require(entries <= MAX_ENTRIES) { "Support bundle contains too many files" }
                    require(!entry.isDirectory) { "Support bundle directories are not supported" }
                    val target = destinationPath.resolve(entry.name).normalize()
                    require(target.startsWith(destinationPath) && !File(entry.name).isAbsolute) { "Support bundle entry escapes its destination" }
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        target.toFile().outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= MAX_BUNDLE_BYTES) { "Support bundle expands beyond ${MAX_BUNDLE_BYTES / 1024 / 1024} MB" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val manifestFile = File(destination, "manifest.json")
            require(manifestFile.isFile) { "Support bundle is missing manifest.json" }
            val manifest = PlayerDebugJson.parseManifest(manifestFile.readText())
            require(manifest.artifacts.values.all { relative ->
                destinationPath.resolve(relative).normalize().let { it.startsWith(destinationPath) && Files.isRegularFile(it) }
            }) { "Support bundle manifest references a missing artifact" }
            return ImportedSupportBundle(destination, manifest)
        } catch (e: Exception) {
            destination.walkBottomUp().forEach { runCatching { it.delete() } }
            throw e
        }
    }

    private data class Selected(
        val included: List<Pair<String, File>>,
        val excluded: List<String>,
        val artifacts: Map<String, String>
    )

    private fun selectedFiles(location: PlayerCaptureLocation, manifest: PlayerDebugManifest): Selected {
        val candidates = linkedMapOf<String, File>()
        STANDARD_FILES.forEach { name -> File(location.directory, name).takeIf(File::isFile)?.let { candidates[name] = it } }
        val artifactEntries = linkedMapOf<String, String>()
        manifest.artifacts.forEach { (name, relative) ->
            val file = store.resolve(relative)
            if (file.isFile && file.toPath().toAbsolutePath().normalize().startsWith(location.directory.toPath().toAbsolutePath().normalize())) {
                val path = file.toPath().toAbsolutePath().normalize()
                val existing = candidates.entries.firstOrNull { it.value.toPath().toAbsolutePath().normalize() == path }?.key
                val entryName = existing ?: uniqueEntryName(name, candidates).also { candidates[it] = file }
                artifactEntries[name] = entryName
            }
        }
        val included = mutableListOf<Pair<String, File>>()
        val excluded = mutableListOf<String>()
        candidates.forEach { (name, file) ->
            if (excludedByPolicy(name, file)) {
                excluded += name
                artifactEntries.entries.removeIf { it.value == name }
            } else included += name to file
        }
        return Selected(included.sortedBy { it.first }, excluded.sorted(), artifactEntries)
    }

    private fun uniqueEntryName(name: String, candidates: Map<String, File>): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "artifact" }
        if (safe !in candidates) return safe
        val base = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 2
        while ("${base}_$index$extension" in candidates) index++
        return "${base}_$index$extension"
    }

    private fun excludedByPolicy(name: String, file: File): Boolean {
        val lower = (name + " " + file.name).lowercase()
        return EXCLUDED_EXTENSIONS.any { lower.endsWith(it) } || EXCLUDED_WORDS.any { it in lower }
    }

    private fun isText(file: File): Boolean = file.extension.lowercase() in TEXT_EXTENSIONS || file.name in STANDARD_FILES

    companion object {
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_BUNDLE_BYTES = 100L * 1024 * 1024
        private const val MAX_ENTRIES = 500
        private const val MAX_PREVIEW_CHARS = 2 * 1024 * 1024
        private val STANDARD_FILES = setOf("manifest.json", "events.ndjson", "session-graph.json", "incidents.json", "parser-diagnostics.json", "metrics.csv", "logcat.log", "report.html")
        private val TEXT_EXTENSIONS = setOf("json", "ndjson", "csv", "log", "txt", "html")
        private val EXCLUDED_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".mp4", ".ts", ".trace", ".bin")
        private val EXCLUDED_WORDS = setOf("screenshot", "protected", "segment", "license_challenge", "drm_payload")
    }
}

private class BundleSanitizer(private val salt: String) {
    private val ids = mutableMapOf<String, String>()
    private fun id(value: String?): String? = value?.let { ids.getOrPut(it) { PlayerDebugRedactor.saltIdentifier(it, salt) } }

    fun manifest(value: PlayerDebugManifest, artifacts: Map<String, String>) = value.copy(
        captureId = id(value.captureId)!!,
        deviceId = id(value.deviceId)!!,
        artifacts = artifacts,
        error = value.error?.let { PlayerDebugRedactor.redactText(it).value }
    )

    fun event(value: PlaybackEvent) = value.copy(
        processEpochId = id(value.processEpochId),
        viewingSessionId = id(value.viewingSessionId),
        instanceId = id(value.instanceId),
        eventId = id(value.eventId),
        attributes = PlayerDebugRedactor.redactAttributes(value.attributes).value,
        evidence = value.evidence.map { it.copy(description = it.description?.let { text -> PlayerDebugRedactor.redactText(text).value }) },
        rawJson = null,
        redacted = true
    )

    fun graph(value: PlayerSessionGraph) = value.copy(
        processEpochs = value.processEpochs.map { epoch -> epoch.copy(id = id(epoch.id)!!, packageName = epoch.packageName) },
        viewingSessions = value.viewingSessions.map(::session),
        events = value.events.map(::event)
    )

    fun incidents(values: List<PlayerIncident>) = values.map { incident -> incident.copy(
        id = id(incident.id)!!,
        viewingSessionId = id(incident.viewingSessionId),
        instanceIds = incident.instanceIds.mapNotNull(::id).toSet(),
        handoffId = id(incident.handoffId),
        evidence = incident.evidence.copy(explanation = PlayerDebugRedactor.redactText(incident.evidence.explanation).value)
    ) }

    private fun session(value: ViewingSession): ViewingSession {
        val sessionId = id(value.id)!!
        return value.copy(
            id = sessionId,
            playerInstances = value.playerInstances.map { player -> player.copy(id = id(player.id)!!, viewingSessionId = sessionId) },
            stateIntervals = value.stateIntervals.map { interval -> interval.copy(instanceId = id(interval.instanceId)!!) },
            handoffs = value.handoffs.map { handoff -> handoff.copy(
                id = id(handoff.id)!!,
                viewingSessionId = sessionId,
                sourceInstanceId = id(handoff.sourceInstanceId),
                targetInstanceId = id(handoff.targetInstanceId)
            ) }
        )
    }
}
