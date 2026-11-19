package devicemonitor.application.releaseguard

import devicemonitor.application.MonitorSession
import devicemonitor.application.SessionConfig
import devicemonitor.data.adb.Adb
import devicemonitor.data.device.DeviceDriver
import devicemonitor.data.logcat.AnomalyScanner
import devicemonitor.data.storage.*
import devicemonitor.domain.*
import devicemonitor.domain.releaseguard.*
import devicemonitor.presentation.report.ReleaseGuardReport
import devicemonitor.presentation.report.ScenarioRunEvidence
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

enum class CaptureStatus { STARTING, RUNNING, DONE, FAILED }

interface CaptureSession {
    val status: CaptureStatus
    val error: String?
    fun start()
    fun stop()
    fun samples(): List<Sample>
    fun anomalies(): List<Anomaly>
    fun markers(): List<ScenarioMarker>
    fun addMarker(
        attempt: Int,
        stepIndex: Int,
        label: String,
        section: String?,
        type: ScenarioMarkerType,
        outcome: ScenarioMarkerOutcome = ScenarioMarkerOutcome.RUNNING,
        detail: String? = null
    )
}

fun interface CaptureSessionFactory {
    fun create(config: SessionConfig): CaptureSession
}

class MonitorCaptureSession(private val session: MonitorSession) : CaptureSession {
    override val status: CaptureStatus get() = when (session.status) {
        MonitorSession.Status.STARTING -> CaptureStatus.STARTING
        MonitorSession.Status.RUNNING -> CaptureStatus.RUNNING
        MonitorSession.Status.DONE -> CaptureStatus.DONE
        MonitorSession.Status.FAILED -> CaptureStatus.FAILED
    }
    override val error: String? get() = session.error
    override fun start() = session.startAsync()
    override fun stop() = session.requestStop()
    override fun samples(): List<Sample> = synchronized(session.samples) { session.samples.toList() }
    override fun anomalies(): List<Anomaly> = session.liveAnomalies()
    override fun markers(): List<ScenarioMarker> = synchronized(session.scenarioMarkers) { session.scenarioMarkers.toList() }
    override fun addMarker(
        attempt: Int,
        stepIndex: Int,
        label: String,
        section: String?,
        type: ScenarioMarkerType,
        outcome: ScenarioMarkerOutcome,
        detail: String?
    ) {
        session.addScenarioMarker(attempt, stepIndex, label, section, type, outcome, detail)
    }
}

class MonitorCaptureSessionFactory(private val adb: Adb) : CaptureSessionFactory {
    override fun create(config: SessionConfig): CaptureSession = MonitorCaptureSession(MonitorSession(adb, config))
}

data class ReleaseGuardProgress(
    val runId: String? = null,
    val state: ExecutionState = ExecutionState.DISCOVER,
    val attempt: Int = 0,
    val totalAttempts: Int = 0,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val message: String = "Waiting",
    val error: String? = null
)

class ScenarioRunner(
    private val repository: RunRepository,
    private val artifacts: ArtifactStore,
    private val captureFactory: CaptureSessionFactory,
    private val clock: Clock = Clock.systemUTC()
) {
    private val cancelled = AtomicBoolean(false)
    @Volatile var progress: ReleaseGuardProgress = ReleaseGuardProgress(); private set
    @Volatile var latestManifest: RunManifest? = null; private set

    fun cancel() {
        cancelled.set(true)
    }

    fun run(
        project: ProjectRecord,
        scenario: ReleaseScenario,
        driver: DeviceDriver,
        baseline: BaselineRevision? = null,
        allowCompatibleDeviceBaseline: Boolean = false
    ): RunManifest {
        cancelled.set(false)
        repository.saveProject(project)
        repository.saveScenario(project.id, scenario)
        val location = artifacts.createRun(project.name, scenario.name)
        val startedAt = now()
        var manifest = RunManifest(
            runId = location.runId,
            projectId = project.id,
            scenarioId = scenario.id,
            scenarioRevision = scenario.revision,
            state = ExecutionState.DISCOVER,
            startedAt = startedAt,
            updatedAt = startedAt
        )
        val attempts = mutableListOf<AttemptSummary>()
        val allMarkers = mutableListOf<ScenarioMarker>()
        val executionAnomalies = mutableListOf<Anomaly>()
        val artifactMap = linkedMapOf<String, String>()

        fun persist(
            state: ExecutionState = manifest.state,
            attempts: List<AttemptSummary> = manifest.attempts,
            markers: List<ScenarioMarker> = manifest.markers,
            evaluation: ReleaseEvaluation? = manifest.evaluation,
            error: String? = manifest.error,
            device: BaselineIdentity? = manifest.device,
            artifactsMap: Map<String, String> = manifest.artifacts,
            message: String,
            attempt: Int = progress.attempt,
            step: Int = progress.step
        ) {
            manifest = manifest.copy(
                state = state,
                updatedAt = now(),
                attempts = attempts,
                markers = markers,
                evaluation = evaluation,
                error = error,
                device = device,
                artifacts = artifactsMap
            )
            artifacts.writeManifest(location, manifest)
            repository.upsertRun(manifest, artifacts.relative(location.manifestFile))
            progress = ReleaseGuardProgress(
                location.runId, state, attempt, scenario.repetitions, step, scenario.steps.size, message, error
            )
            latestManifest = manifest
        }

        try {
            persist(message = "Discovering device")
            val target = driver.target(refresh = true)
            val identity = BaselineIdentity(
                target.properties["model"].orEmpty().ifBlank { target.id },
                target.properties["android"].orEmpty().ifBlank { "unknown" },
                target.properties["firmware"].orEmpty().ifBlank { "unknown" },
                scenario.id,
                scenario.revision
            )

            persist(ExecutionState.PREFLIGHT, device = identity, message = "Checking scenario and device capabilities")
            val validation = scenario.validate()
            require(validation.isEmpty()) { validation.joinToString("; ") }
            require(driver.isConnected()) { "Device '${driver.serial}' is not connected and authorized" }
            val missing = scenario.requiredCapabilities() - target.capabilities
            require(missing.isEmpty()) { "Device is missing required capabilities: ${missing.joinToString()}" }
            checkCancelled()

            persist(ExecutionState.PREPARE, device = identity, message = "Preparing scenario")
            if (scenario.warmups > 0) {
                persist(ExecutionState.WARM_UP, device = identity, message = "Running ${scenario.warmups} warm-up attempt(s)")
                repeat(scenario.warmups) { warmup ->
                    val warmupDir = File(location.directory, "warmup-%02d".format(warmup + 1)).apply { mkdirs() }
                    executeSteps(scenario, driver, warmupDir, null, warmup + 1, measured = false)
                }
            }

            val capture = captureFactory.create(sessionConfig(scenario, driver.serial, location.directory, target.capabilities))
            var measurementFailure: Exception? = null
            try {
                capture.start()
                awaitCaptureStart(capture)
                for (attempt in 1..scenario.repetitions) {
                    checkCancelled()
                    require(capture.status == CaptureStatus.RUNNING) { capture.error ?: "Performance capture stopped unexpectedly" }
                    val attemptDir = artifacts.attemptDirectory(location, attempt)
                    persist(
                        ExecutionState.EXECUTE_AND_CAPTURE,
                        attempts = attempts,
                        markers = capture.markers(),
                        artifactsMap = artifactMap,
                        device = identity,
                        message = "Running measured attempt $attempt of ${scenario.repetitions}",
                        attempt = attempt,
                        step = 0
                    )
                    val sampleStart = capture.samples().size
                    capture.addMarker(
                        attempt, 0, "Measured iteration $attempt", null,
                        ScenarioMarkerType.ATTEMPT_START
                    )
                    var stepError: String? = null
                    try {
                        executeSteps(scenario, driver, attemptDir, capture, attempt, measured = true)
                    } catch (e: Exception) {
                        if (e is InterruptedException) throw e
                        stepError = e.message ?: e.toString()
                    }
                    if (capture.status == CaptureStatus.RUNNING) {
                        awaitMinimumSamples(capture, sampleStart + 2, 10_000)
                    } else if (stepError == null) {
                        stepError = capture.error ?: "Performance capture stopped unexpectedly"
                    }
                    capture.addMarker(
                        attempt, scenario.steps.size + 1, "Measured iteration $attempt", null,
                        ScenarioMarkerType.ATTEMPT_END,
                        if (stepError == null) ScenarioMarkerOutcome.PASS else ScenarioMarkerOutcome.FAIL,
                        stepError
                    )
                    val markers = capture.markers().filter { it.attempt == attempt }
                    val range = attemptRange(markers)
                    val samples = range?.let { window -> capture.samples().filter { it.elapsedSec in window } }.orEmpty()
                    val anomalies = range?.let { window -> filteredAnomalies(capture.anomalies(), window) }.orEmpty()
                    val attemptError = stepError ?: if (range == null) "Measured iteration boundary is incomplete" else null
                    val successful = attemptError == null && capture.status == CaptureStatus.RUNNING
                    attempts += AttemptSummary(
                        attempt,
                        successful,
                        extractMetrics(scenario.budgets, samples, anomalies, markers),
                        attemptError
                    )
                    attemptError?.let { error ->
                        executionAnomalies += Anomaly(
                            now(),
                            markers.lastOrNull { it.type == ScenarioMarkerType.ATTEMPT_END }?.elapsedSec,
                            "Scenario iteration failure",
                            "Measured repetition $attempt: ${error.replace(Regex("\\s+"), " ").trim()}"
                        )
                    }
                    attemptDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val key = "attempt-%02d/%s".format(attempt, file.name)
                        artifactMap[key] = artifacts.relative(file)
                    }
                    persist(
                        ExecutionState.EXECUTE_AND_CAPTURE,
                        attempts = attempts,
                        markers = capture.markers(),
                        artifactsMap = artifactMap,
                        device = identity,
                        message = "Completed measured attempt $attempt",
                        attempt = attempt,
                        step = scenario.steps.size
                    )
                    checkCancelled()
                }
            } catch (e: Exception) {
                measurementFailure = e
            } finally {
                progress = progress.copy(state = ExecutionState.FINALIZE_ARTIFACTS, message = "Finalizing scenario evidence")
                try {
                    capture.stop()
                    awaitCaptureEnd(capture, 120_000)
                } catch (e: Exception) {
                    if (measurementFailure == null) measurementFailure = e
                }
            }

            allMarkers += capture.markers()
            val logFile = File(location.directory, "logcat.log")
            val logError = when {
                !logFile.isFile -> "Required scenario logcat evidence was not created"
                logFile.length() == 0L -> "Required scenario logcat evidence is empty"
                else -> null
            }
            val captureError = capture.error ?: logError
            if (captureError != null) {
                if (attempts.isEmpty()) attempts += AttemptSummary(1, false, error = captureError)
                else attempts[attempts.lastIndex] = attempts.last().copy(successful = false, error = captureError)
                executionAnomalies += Anomaly(now(), capture.samples().lastOrNull()?.elapsedSec, "Capture failure", captureError)
            }
            measurementFailure?.takeUnless { it is InterruptedException }?.let { failure ->
                executionAnomalies += Anomaly(
                    now(), capture.samples().lastOrNull()?.elapsedSec, "Scenario execution failure",
                    (failure.message ?: failure.toString()).replace(Regex("\\s+"), " ").trim()
                )
            }
            val scenarioAnomalies = (capture.anomalies() + executionAnomalies).distinctBy { it.type to it.detail }
            AnomalyScanner.writeReport(scenarioAnomalies, File(location.directory, "anomalies.log"))
            registerRunArtifacts(location, artifactMap)
            persist(
                ExecutionState.FINALIZE_ARTIFACTS,
                attempts = attempts,
                markers = allMarkers,
                artifactsMap = artifactMap,
                device = identity,
                message = "Finalized complete scenario capture",
                attempt = attempts.size,
                step = scenario.steps.size
            )
            measurementFailure?.let { throw it }

            checkCancelled()
            persist(
                ExecutionState.EVALUATE,
                attempts = attempts,
                markers = allMarkers,
                artifactsMap = artifactMap,
                device = identity,
                message = "Evaluating performance budgets"
            )
            val selectedBaseline = baseline ?: repository.activeBaseline(project.id, scenario.id)
            val evaluation = EvaluationEngine.evaluate(
                scenario.budgets,
                attempts,
                identity,
                selectedBaseline,
                allowCompatibleDeviceBaseline
            )
            persist(
                ExecutionState.EXPORT,
                attempts = attempts,
                markers = allMarkers,
                evaluation = evaluation,
                artifactsMap = artifactMap,
                device = identity,
                message = "Writing result artifacts"
            )
            val exportManifest = manifest.copy(artifacts = artifactMap.toMap())
            ReleaseGuardReport.write(
                location.directory,
                project,
                scenario,
                exportManifest,
                selectedBaseline,
                ScenarioRunEvidence(capture.samples(), scenarioAnomalies, allMarkers)
            )
                .forEach { (name, file) -> artifactMap[name] = artifacts.relative(file) }
            val terminal = when (evaluation.verdict) {
                Verdict.PASS -> ExecutionState.PASSED
                Verdict.WARNING -> ExecutionState.WARNING
                Verdict.NOT_EVALUATED -> ExecutionState.EVIDENCE_ONLY
                Verdict.FAIL -> ExecutionState.FAILED
                Verdict.CANCELLED -> ExecutionState.CANCELLED
            }
            persist(
                terminal,
                attempts,
                allMarkers,
                evaluation,
                artifactsMap = artifactMap,
                device = identity,
                message = "Release Guard ${evaluation.verdict.name.lowercase()}"
            )
            return manifest
        } catch (e: Exception) {
            val wasCancelled = cancelled.get() || e is InterruptedException
            val state = if (wasCancelled) ExecutionState.CANCELLED else ExecutionState.FAILED
            val verdict = if (wasCancelled) Verdict.CANCELLED else Verdict.FAIL
            val message = if (wasCancelled) "Release Guard cancelled" else "Release Guard failed"
            val evaluation = manifest.evaluation ?: ReleaseEvaluation(verdict, emptyList(), listOf(e.message ?: e.toString()))
            persist(state, evaluation = evaluation, error = e.message ?: e.toString(), message = message)
            return manifest
        }
    }

    private fun executeSteps(
        scenario: ReleaseScenario,
        driver: DeviceDriver,
        directory: File,
        capture: CaptureSession?,
        attempt: Int,
        measured: Boolean
    ) {
        var currentSection: String? = null
        scenario.steps.forEachIndexed { index, step ->
            checkCancelled()
            val label = step.name ?: step.javaClass.simpleName
            val startType = if (step is ScenarioStep.BeginSection) ScenarioMarkerType.SECTION_START else ScenarioMarkerType.STEP_START
            val section = when (step) {
                is ScenarioStep.BeginSection -> step.section
                is ScenarioStep.EndSection -> step.section
                else -> currentSection
            }
            progress = progress.copy(
                step = index + 1,
                message = "${if (measured) "Attempt" else "Warm-up"} $attempt · Running $label"
            )
            if (measured) {
                capture?.addMarker(attempt, index + 1, label, section, startType)
            }
            try {
                driver.perform(step, scenario.packageName, directory, cancelled::get)
                if (step is ScenarioStep.BeginSection) currentSection = step.section
                if (measured) {
                    val endType = if (step is ScenarioStep.EndSection) ScenarioMarkerType.SECTION_END else ScenarioMarkerType.STEP_END
                    capture?.addMarker(attempt, index + 1, label, section, endType, ScenarioMarkerOutcome.PASS)
                }
                if (step is ScenarioStep.EndSection) currentSection = null
            } catch (e: Exception) {
                if (measured) {
                    capture?.addMarker(
                        attempt, index + 1, label, section, ScenarioMarkerType.STEP_END,
                        ScenarioMarkerOutcome.FAIL, e.message
                    )
                }
                throw e
            }
        }
    }

    private fun registerRunArtifacts(location: RunLocation, artifactMap: MutableMap<String, String>) {
        listOf(
            "logcat.log", "device-info.log", "anomalies.log", "samples.csv",
            "performance-report.pdf", "run.log"
        ).forEach { name ->
            location.directory.resolve(name).takeIf(File::isFile)?.let { artifactMap[name] = artifacts.relative(it) }
        }
    }

    private fun sessionConfig(
        scenario: ReleaseScenario,
        serial: String,
        runDirectory: File,
        capabilities: Set<DeviceCapability>
    ): SessionConfig {
        val metrics = (scenario.capture.metrics.map { it.id } + scenario.budgets.map { it.metric.id })
            .map { it.lowercase() }.toSet()
        fun enabled(vararg ids: String) = ids.any { it in metrics }
        return SessionConfig(
            target = serial,
            durationSec = 86_400.0,
            intervalSec = scenario.capture.intervalSec,
            packageName = scenario.packageName,
            logcatEnabled = DeviceCapability.LOGCAT in capabilities,
            jankEnabled = enabled("jank_pct", "frame_input_ms", "frame_anim_ms", "frame_layout_ms", "frame_draw_ms") && DeviceCapability.GFXINFO in capabilities,
            perfettoEnabled = enabled("perfetto") && DeviceCapability.PERFETTO in capabilities,
            label = "release-guard",
            outputDir = runDirectory.parentFile.absolutePath,
            outputOverride = File(runDirectory, "performance-report.pdf"),
            csvOverride = File(runDirectory, "samples.csv"),
            runDirectoryOverride = runDirectory,
            cpuUtilEnabled = enabled("cpu", "cpu_util_pct"),
            perCoreEnabled = enabled("per_core_cpu_pct"),
            topProcsEnabled = enabled("top_process_cpu_pct"),
            memoryMbEnabled = enabled("memory", "memory_used_mb", "memory_available_mb"),
            memoryPctEnabled = enabled("memory_used_pct"),
            swapEnabled = enabled("swap_used_mb"),
            psiEnabled = enabled("psi_cpu", "psi_memory", "psi_io"),
            thermalEnabled = enabled("cpu_temp_c") && DeviceCapability.THERMAL in capabilities,
            cpuFreqEnabled = enabled("cpu_freq_mhz"),
            networkEnabled = enabled("network_rx_kbps", "network_tx_kbps"),
            pageFaultsEnabled = enabled("major_faults_per_sec"),
            directReclaimEnabled = enabled("direct_reclaim_per_sec"),
            fpsEnabled = enabled("screen_fps", "video_fps"),
            playbackEnabled = enabled("playback_state", "tunnel_mode") && DeviceCapability.PLAYBACK_DIAGNOSTICS in capabilities,
            appPssEnabled = enabled("pss", "app_pss_mb"),
            appProcCpuEnabled = enabled("app_cpu_pct"),
            mainThreadCpuEnabled = enabled("main_thread_cpu_pct"),
            topMemEnabled = enabled("top_process_memory_mb")
        )
    }

    private fun extractMetrics(
        budgets: List<PerformanceBudget>,
        samples: List<Sample>,
        anomalies: List<Anomaly>,
        markers: List<ScenarioMarker>
    ): List<MetricSeries> = budgets.distinctBy { it.metric.id to it.section }.map { budget ->
        val range = budget.section?.let { sectionRange(it, markers) }
        val selected = if (range == null) samples else samples.filter { it.elapsedSec in range }
        val duration = when {
            range != null -> range.endInclusive - range.start
            selected.size >= 2 -> selected.last().elapsedSec - selected.first().elapsedSec
            else -> null
        }
        val values = when (budget.metric.id.lowercase()) {
            "section_duration_ms" -> range?.let { listOf((it.endInclusive - it.start) * 1000.0) } ?: emptyList()
            "anr_count" -> filteredAnomalies(anomalies, range).filter { it.type.equals("ANR", true) }.map { 1.0 }
            "crash_count" -> filteredAnomalies(anomalies, range).filter {
                it.type.contains("fatal", true) || it.type.contains("crash", true)
            }.map { 1.0 }
            else -> selected.mapNotNull { sampleValue(it, budget.metric.id) }
        }
        MetricSeries(budget.metric.id, budget.section, values, duration?.takeIf { it > 0.0 })
    }

    private fun filteredAnomalies(anomalies: List<Anomaly>, range: ClosedFloatingPointRange<Double>?): List<Anomaly> =
        if (range == null) anomalies else anomalies.filter { it.elapsedSec != null && it.elapsedSec in range }

    private fun sectionRange(section: String, markers: List<ScenarioMarker>): ClosedFloatingPointRange<Double>? {
        val start = markers.firstOrNull { it.type == ScenarioMarkerType.SECTION_START && it.section == section }?.elapsedSec
        val end = markers.lastOrNull { it.type == ScenarioMarkerType.SECTION_END && it.section == section }?.elapsedSec
        return if (start != null && end != null && end >= start) start..end else null
    }

    private fun attemptRange(markers: List<ScenarioMarker>): ClosedFloatingPointRange<Double>? {
        val start = markers.firstOrNull { it.type == ScenarioMarkerType.ATTEMPT_START }?.elapsedSec
        val end = markers.lastOrNull { it.type == ScenarioMarkerType.ATTEMPT_END }?.elapsedSec
        return if (start != null && end != null && end >= start) start..end else null
    }

    private fun sampleValue(sample: Sample, id: String): Double? = when (id.lowercase()) {
        "cpu", "cpu_util_pct" -> sample.cpuUtilPct
        "cpu_idle_pct" -> sample.cpuIdlePct
        "memory", "memory_used_mb" -> sample.memUsedMb
        "memory_used_pct" -> sample.memUsedPct
        "memory_available_mb" -> sample.memAvailableMb
        "swap_used_mb" -> sample.swapUsedMb
        "psi_cpu" -> sample.cpuPsiSomeAvg10
        "psi_memory" -> sample.memPsiSomeAvg10
        "psi_io" -> sample.ioPsiSomeAvg10
        "network_rx_kbps" -> sample.netRxKbps
        "network_tx_kbps" -> sample.netTxKbps
        "cpu_temp_c" -> sample.cpuTempC
        "cpu_freq_mhz" -> sample.cpuFreqMhz
        "major_faults_per_sec" -> sample.majorFaultsPerSec
        "direct_reclaim_per_sec" -> sample.directReclaimPerSec
        "pss", "app_pss_mb" -> sample.pss?.totalMb
        "jank_pct" -> sample.jankPct
        "main_thread_cpu_pct" -> sample.mainThreadCpuPct
        "app_cpu_pct" -> sample.appCpuPct
        "frame_input_ms" -> sample.frameInputMs
        "frame_anim_ms" -> sample.frameAnimMs
        "frame_layout_ms" -> sample.frameLayoutMs
        "frame_draw_ms" -> sample.frameDrawMs
        "screen_fps" -> sample.screenFps
        "video_fps" -> sample.videoFps
        else -> null
    }

    private fun awaitCaptureStart(capture: CaptureSession) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (capture.status == CaptureStatus.STARTING && System.nanoTime() < deadline) {
            checkCancelled()
            Thread.sleep(50)
        }
        require(capture.status == CaptureStatus.RUNNING) { capture.error ?: "Performance capture did not start" }
    }

    private fun awaitMinimumSamples(capture: CaptureSession, minimum: Int, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (capture.samples().size < minimum && capture.status == CaptureStatus.RUNNING && System.nanoTime() < deadline) {
            checkCancelled()
            Thread.sleep(100)
        }
    }

    private fun awaitCaptureEnd(capture: CaptureSession, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (capture.status in setOf(CaptureStatus.STARTING, CaptureStatus.RUNNING) && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw InterruptedException("Release Guard run cancelled")
    }

    private fun now(): String = Instant.now(clock).toString()
}
