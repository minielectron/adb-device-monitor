package devicemonitor

import devicemonitor.application.SessionConfig
import devicemonitor.application.releaseguard.*
import devicemonitor.data.device.*
import devicemonitor.data.storage.*
import devicemonitor.domain.*
import devicemonitor.domain.releaseguard.*
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ScenarioRunnerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T14:00:00Z"), ZoneOffset.UTC)

    @Test fun executesScenarioCapturesSectionsAndPassesBudgets() {
        fixture().use { f ->
            val scenario = scenario(
                steps = listOf(
                    ScenarioStep.BeginSection("browse"),
                    ScenarioStep.LaunchApp(),
                    ScenarioStep.EndSection("browse")
                ),
                budgets = listOf(
                    PerformanceBudget(
                        "cpu", "CPU", MetricDefinition("cpu_util_pct", "%", true),
                        Aggregation.MAXIMUM, section = "browse", absoluteLimit = 80.0
                    )
                )
            )
            val driver = FakeDriver(allCapabilities())
            val manifest = f.runner.run(project(), scenario, driver)

            assertEquals(ExecutionState.PASSED, manifest.state)
            assertEquals(Verdict.PASS, manifest.evaluation?.verdict)
            assertEquals(1, manifest.attempts.size)
            assertTrue(manifest.attempts.single().successful)
            assertTrue(manifest.markers.any { it.type == ScenarioMarkerType.SECTION_START && it.section == "browse" })
            assertTrue(manifest.markers.any { it.type == ScenarioMarkerType.SECTION_END && it.section == "browse" })
            assertEquals(BudgetStatus.PASS, manifest.evaluation?.results?.single()?.status)
            assertEquals(manifest.runId, f.repository.run(manifest.runId)?.manifest?.runId)
        }
    }

    @Test fun missingCapabilityFailsBeforeStartingCapture() {
        var captures = 0
        fixture(factory = CaptureSessionFactory { captures++; FakeCapture() }).use { f ->
            val scenario = scenario(steps = listOf(ScenarioStep.TapAt(.5, .5)))
            val driver = FakeDriver(setOf(DeviceCapability.SHELL))
            val manifest = f.runner.run(project(), scenario, driver)

            assertEquals(ExecutionState.FAILED, manifest.state)
            assertTrue("TOUCH_INPUT" in (manifest.error ?: ""))
            assertEquals(0, captures)
            assertTrue(manifest.attempts.isEmpty())
        }
    }

    @Test fun selectedDiagnosticsShareOneCaptureSessionAtTheConfiguredInterval() {
        lateinit var received: SessionConfig
        fixture(CaptureSessionFactory { config ->
            received = config
            FakeCapture(config.runDirectoryOverride)
        }).use { f ->
            val capture = CaptureSettings(
                metrics = setOf(
                    MetricDefinition("cpu_util_pct"),
                    MetricDefinition("per_core_cpu_pct"),
                    MetricDefinition("top_process_cpu_pct"),
                    MetricDefinition("memory_used_mb"),
                    MetricDefinition("psi_cpu"),
                    MetricDefinition("top_process_memory_mb")
                ),
                intervalSec = 0.5,
                configured = true
            )
            val manifest = f.runner.run(
                project(),
                scenario().copy(capture = capture),
                FakeDriver(allCapabilities())
            )

            assertEquals(ExecutionState.EVIDENCE_ONLY, manifest.state)
            assertEquals(0.5, received.intervalSec)
            assertTrue(received.cpuUtilEnabled)
            assertTrue(received.perCoreEnabled)
            assertTrue(received.topProcsEnabled)
            assertTrue(received.memoryMbEnabled)
            assertTrue(received.psiEnabled)
            assertTrue(received.topMemEnabled)
            assertTrue(received.logcatEnabled)
        }
    }

    @Test fun failedAttemptKeepsEvidenceAndCannotPass() {
        fixture().use { f ->
            val driver = FakeDriver(allCapabilities(), failOn = ScenarioStep.LaunchApp::class.java)
            val manifest = f.runner.run(
                project(),
                scenario(
                    steps = listOf(ScenarioStep.LaunchApp()),
                    budgets = listOf(
                        PerformanceBudget(
                            "cpu", "CPU", MetricDefinition("cpu_util_pct"),
                            Aggregation.MAXIMUM, absoluteLimit = 90.0
                        )
                    )
                ),
                driver
            )

            assertEquals(ExecutionState.FAILED, manifest.state)
            assertFalse(manifest.attempts.single().successful)
            assertEquals(Verdict.FAIL, manifest.evaluation?.verdict)
            assertTrue(manifest.markers.any { it.outcome == ScenarioMarkerOutcome.FAIL })
            assertTrue(manifest.evaluation?.executionErrors?.single()?.contains("synthetic step failure") == true)
        }
    }

    @Test fun zeroAnrsIsAValidCountInsteadOfMissingData() {
        fixture().use { f ->
            val manifest = f.runner.run(
                project(),
                scenario(
                    budgets = listOf(
                        PerformanceBudget(
                            "anr", "ANRs", MetricDefinition("anr_count"),
                            Aggregation.COUNT, absoluteLimit = 0.0
                        )
                    )
                ),
                FakeDriver(allCapabilities())
            )
            val result = manifest.evaluation?.results?.single()
            assertEquals(BudgetStatus.PASS, result?.status)
            assertEquals(0.0, result?.value)
        }
    }

    @Test fun zeroBudgetRunIsEvidenceOnlyAndCombinesEveryMeasuredIterationLog() {
        var captureNumber = 0
        val factory = CaptureSessionFactory { config ->
            captureNumber += 1
            FakeCapture(config.runDirectoryOverride, "raw log iteration $captureNumber\n")
        }
        fixture(factory).use { f ->
            val manifest = f.runner.run(
                project(),
                scenario(repetitions = 2),
                FakeDriver(allCapabilities())
            )

            assertEquals(ExecutionState.EVIDENCE_ONLY, manifest.state)
            assertEquals(Verdict.NOT_EVALUATED, manifest.evaluation?.verdict)
            val combined = f.root.resolve(manifest.artifacts.getValue("logcat.log")).readText()
            assertTrue(combined.indexOf("raw log iteration 1") < combined.indexOf("raw log iteration 2"))
            assertContains(combined, "===== ITERATION 1 OF 2 | PASS =====")
            assertContains(combined, "===== END ITERATION 1 =====")
            assertContains(combined, "===== ITERATION 2 OF 2 | PASS =====")
            assertTrue("attempt-01/logcat.log" in manifest.artifacts)
            assertTrue("attempt-02/logcat.log" in manifest.artifacts)
        }
    }

    @Test fun missingIterationLogFailsRunAndLeavesExplicitCombinedEvidence() {
        fixture(CaptureSessionFactory { FakeCapture() }).use { f ->
            val manifest = f.runner.run(project(), scenario(), FakeDriver(allCapabilities()))

            assertEquals(ExecutionState.FAILED, manifest.state)
            assertEquals(Verdict.FAIL, manifest.evaluation?.verdict)
            assertContains(manifest.attempts.single().error.orEmpty(), "logcat evidence was not created")
            val combined = f.root.resolve(manifest.artifacts.getValue("logcat.log")).readText()
            assertContains(combined, "LOGCAT UNAVAILABLE")
            assertContains(combined, "| FAIL | Required logcat evidence was not created =====")
        }
    }

    @Test fun combinedLogNormalizesMultilineFailureBoundary() {
        fixture().use { f ->
            val manifest = f.runner.run(
                project(),
                scenario(),
                FakeDriver(allCapabilities(), ScenarioStep.LaunchApp::class.java, "first line\nsecond line")
            )
            val combined = f.root.resolve(manifest.artifacts.getValue("logcat.log")).readText()
            assertContains(combined, "| FAIL | first line second line =====")
        }
    }

    private fun fixture(factory: CaptureSessionFactory? = null): Fixture {
        val root = Files.createTempDirectory("release-guard-runner").toFile()
        val store = ArtifactStore(root, clock, { "run" })
        val repository = SqliteRunRepository(root.resolve("guard.db"), clock)
        val activeFactory = factory ?: CaptureSessionFactory { config -> FakeCapture(config.runDirectoryOverride) }
        return Fixture(root, repository, ScenarioRunner(repository, store, activeFactory, clock))
    }

    private fun project() = ProjectRecord(
        id = "player",
        name = "Player",
        packageName = "com.example.player",
        createdAt = "2026-11-16T13:00:00Z",
        updatedAt = "2026-11-16T13:00:00Z"
    )

    private fun scenario(
        steps: List<ScenarioStep> = listOf(ScenarioStep.LaunchApp()),
        budgets: List<PerformanceBudget> = emptyList(),
        repetitions: Int = 1
    ) = ReleaseScenario(
        id = "browse",
        name = "Browse",
        packageName = "com.example.player",
        repetitions = repetitions,
        steps = steps,
        budgets = budgets
    )

    private fun allCapabilities() = DeviceCapability.entries.toSet()

    private data class Fixture(val root: File, val repository: SqliteRunRepository, val runner: ScenarioRunner) : AutoCloseable {
        override fun close() = repository.close()
    }

    private class FakeDriver(
        private val capabilities: Set<DeviceCapability>,
        private val failOn: Class<out ScenarioStep>? = null,
        private val failureMessage: String = "synthetic step failure"
    ) : DeviceDriver {
        override val serial = "fake-device"
        override fun target(refresh: Boolean) = TargetDevice(
            id = serial,
            formFactor = FormFactor.TV,
            properties = mapOf("model" to "QA Device", "android" to "14", "firmware" to "build-1"),
            capabilities = capabilities
        )
        override fun isConnected() = true
        override fun perform(
            step: ScenarioStep,
            packageName: String,
            artifactDirectory: File,
            cancelled: () -> Boolean
        ): StepOutput {
            if (failOn?.isInstance(step) == true) error(failureMessage)
            return StepOutput("ok")
        }
    }

    private class FakeCapture(
        private val directory: File? = null,
        private val logText: String = "synthetic logcat\n"
    ) : CaptureSession {
        override var status = CaptureStatus.STARTING
        override val error: String? = null
        private val markers = mutableListOf<ScenarioMarker>()
        private var markerTime = 0.0

        override fun start() { status = CaptureStatus.RUNNING }
        override fun stop() {
            directory?.resolve("logcat.log")?.writeText(logText)
            status = CaptureStatus.DONE
        }
        override fun samples() = listOf(sample(0.0, 40.0), sample(1.0, 60.0), sample(2.0, 55.0))
        override fun anomalies(): List<Anomaly> = emptyList()
        override fun markers(): List<ScenarioMarker> = markers.toList()
        override fun addMarker(
            attempt: Int,
            stepIndex: Int,
            label: String,
            section: String?,
            type: ScenarioMarkerType,
            outcome: ScenarioMarkerOutcome,
            detail: String?
        ) {
            markerTime += .5
            markers += ScenarioMarker(markerTime, attempt, stepIndex, label, section, type, outcome, detail)
        }

        private fun sample(t: Double, cpu: Double) = Sample(
            elapsedSec = t,
            cpuUtilPct = cpu,
            cpuIdlePct = 100.0 - cpu,
            cpuPsiSomeAvg10 = 0.0,
            memPsiSomeAvg10 = 0.0,
            memPsiFullAvg10 = 0.0,
            ioPsiSomeAvg10 = 0.0,
            memTotalMb = 4096.0,
            memUsedMb = 2048.0,
            memUsedPct = 50.0,
            memAvailableMb = 2048.0,
            cachedMb = 512.0,
            swapUsedMb = 0.0,
            load1 = 1.0,
            perCoreUtilPct = null,
            netRxKbps = 0.0,
            netTxKbps = 0.0,
            cpuTempC = 40.0,
            cpuFreqMhz = 1200.0,
            majorFaultsPerSec = 0.0
        )
    }
}
