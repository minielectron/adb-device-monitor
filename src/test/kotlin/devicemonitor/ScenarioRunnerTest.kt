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
                    MetricDefinition("top_process_memory_mb"),
                    MetricDefinition("app_pss_mb")
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
            assertTrue(received.appPssEnabled)
            assertTrue(received.logcatEnabled)
        }
    }

    @Test fun appPssDefaultsOnButAnExplicitCaptureSelectionCanDisableIt() {
        var defaultEnabled = false
        fixture(CaptureSessionFactory { config ->
            defaultEnabled = config.appPssEnabled
            FakeCapture(config.runDirectoryOverride)
        }).use { f ->
            f.runner.run(project(), scenario(), FakeDriver(allCapabilities()))
        }
        var configuredEnabled = true
        fixture(CaptureSessionFactory { config ->
            configuredEnabled = config.appPssEnabled
            FakeCapture(config.runDirectoryOverride)
        }).use { f ->
            f.runner.run(
                project(),
                scenario().copy(capture = CaptureSettings(metrics = emptySet(), configured = true)),
                FakeDriver(allCapabilities())
            )
        }

        assertTrue(defaultEnabled)
        assertFalse(configuredEnabled)
    }

    @Test fun liveProgressTracksWarmUpAndMeasuredJourneySteps() {
        fixture().use { f ->
            val observed = mutableListOf<ReleaseGuardProgress>()
            val scenario = scenario(
                steps = listOf(
                    ScenarioStep.LaunchApp("Open player"),
                    ScenarioStep.Wait(1, "Settle")
                )
            ).copy(warmups = 1)

            f.runner.run(
                project(),
                scenario,
                FakeDriver(allCapabilities(), onPerform = { observed += f.runner.progress })
            )

            assertEquals(listOf(1, 2, 1, 2), observed.map { it.step })
            assertEquals(listOf(ExecutionState.WARM_UP, ExecutionState.WARM_UP), observed.take(2).map { it.state })
            assertEquals(
                listOf(ExecutionState.EXECUTE_AND_CAPTURE, ExecutionState.EXECUTE_AND_CAPTURE),
                observed.drop(2).map { it.state }
            )
            assertContains(observed.first().message, "Warm-up 1 · Running Open player")
            assertContains(observed.last().message, "Attempt 1 · Running Settle")
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
            assertEquals(listOf("Scenario execution failed; see anomalies.log"), manifest.evaluation?.executionErrors)
            assertContains(f.root.resolve(manifest.artifacts.getValue("anomalies.log")).readText(), "synthetic step failure")
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

    @Test fun zeroBudgetRunUsesOneCaptureAndOneScenarioEvidenceSet() {
        var captureNumber = 0
        val factory = CaptureSessionFactory { config ->
            captureNumber += 1
            FakeCapture(config.runDirectoryOverride, "raw full scenario log\n")
        }
        fixture(factory).use { f ->
            val manifest = f.runner.run(
                project(),
                scenario(repetitions = 2),
                FakeDriver(allCapabilities())
            )

            assertEquals(ExecutionState.EVIDENCE_ONLY, manifest.state)
            assertEquals(Verdict.NOT_EVALUATED, manifest.evaluation?.verdict)
            assertEquals(1, captureNumber)
            assertEquals("raw full scenario log\n", f.root.resolve(manifest.artifacts.getValue("logcat.log")).readText())
            listOf("device-info.log", "anomalies.log", "samples.csv", "performance-report.pdf").forEach {
                assertTrue(it in manifest.artifacts, "$it should be registered once at run level")
            }
            assertFalse(manifest.artifacts.keys.any { it.matches(Regex("attempt-\\d+/logcat\\.log")) })
            val runDirectory = f.root.resolve(manifest.artifacts.getValue("logcat.log")).parentFile
            assertFalse(runDirectory.listFiles().orEmpty().any { it.name.startsWith("attempt-") })
            assertEquals(2, manifest.markers.count { it.type == ScenarioMarkerType.ATTEMPT_START })
            assertEquals(2, manifest.markers.count { it.type == ScenarioMarkerType.ATTEMPT_END })
        }
    }

    @Test fun fileProducingStepCreatesAndRegistersItsAttemptDirectory() {
        fixture().use { f ->
            val manifest = f.runner.run(
                project(),
                scenario(steps = listOf(ScenarioStep.CaptureScreenshot("evidence.png"))),
                FakeDriver(allCapabilities())
            )

            val path = manifest.artifacts.getValue("attempt-01/evidence.png")
            assertTrue(f.root.resolve(path).isFile)
            assertTrue(f.root.resolve(path).parentFile.name == "attempt-01")
        }
    }

    @Test fun missingScenarioLogFailsRunWithoutInventingEvidence() {
        fixture(CaptureSessionFactory { FakeCapture() }).use { f ->
            val manifest = f.runner.run(project(), scenario(), FakeDriver(allCapabilities()))

            assertEquals(ExecutionState.FAILED, manifest.state)
            assertEquals(Verdict.FAIL, manifest.evaluation?.verdict)
            assertContains(manifest.attempts.single().error.orEmpty(), "scenario logcat evidence was not created")
            assertFalse("logcat.log" in manifest.artifacts)
        }
    }

    @Test fun failedIterationPreservesRawScenarioLogAndOriginalError() {
        fixture().use { f ->
            val manifest = f.runner.run(
                project(),
                scenario(),
                FakeDriver(allCapabilities(), ScenarioStep.LaunchApp::class.java, "first line\nsecond line")
            )
            assertEquals("synthetic logcat\n", f.root.resolve(manifest.artifacts.getValue("logcat.log")).readText())
            assertContains(manifest.attempts.single().error.orEmpty(), "first line\nsecond line")
            val anomalies = f.root.resolve(manifest.artifacts.getValue("anomalies.log")).readText()
            assertContains(anomalies, "Scenario iteration failure")
            assertContains(anomalies, "Measured repetition 1: first line second line")
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
        private val failureMessage: String = "synthetic step failure",
        private val onPerform: () -> Unit = {}
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
            onPerform()
            if (failOn?.isInstance(step) == true) error(failureMessage)
            if (step is ScenarioStep.CaptureScreenshot) {
                artifactDirectory.mkdirs()
                val file = artifactDirectory.resolve(step.fileName).apply { writeText("synthetic png") }
                return StepOutput("ok", file)
            }
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
            directory?.let { dir ->
                dir.resolve("logcat.log").writeText(logText)
                dir.resolve("device-info.log").writeText("QA Device\n")
                dir.resolve("anomalies.log").writeText("No anomalies\n")
                dir.resolve("samples.csv").writeText("elapsedSec,cpuUtilPct\n0,40\n")
                dir.resolve("performance-report.pdf").writeText("synthetic performance report")
            }
            status = CaptureStatus.DONE
        }
        override fun samples(): List<Sample> {
            val count = maxOf(3, (markerTime * 4).toInt() + 3)
            return (0 until count).map { index -> sample(index * .25, 40.0 + index % 20) }
        }
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
