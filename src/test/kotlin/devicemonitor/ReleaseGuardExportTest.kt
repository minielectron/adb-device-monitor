package devicemonitor

import devicemonitor.data.storage.ProjectRecord
import devicemonitor.data.storage.RunManifest
import devicemonitor.domain.Anomaly
import devicemonitor.domain.PssSample
import devicemonitor.domain.Sample
import devicemonitor.domain.releaseguard.*
import devicemonitor.presentation.report.JunitExporter
import devicemonitor.presentation.report.ReleaseGuardReport
import devicemonitor.presentation.report.ScenarioRunEvidence
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseGuardExportTest {
    @Test fun writesStandaloneHtmlJsonCsvJunitAndReadablePdf() {
        val dir = Files.createTempDirectory("release-guard-export").toFile()
        val evaluation = evaluation()
        val manifest = manifest(evaluation)
        val outputs = ReleaseGuardReport.write(dir, project(), scenario(), manifest, baseline())

        assertEquals(
            setOf("evaluation.json", "result.junit.xml", "summary.csv", "report.html", "interactive-performance-report.html", "report.pdf"),
            outputs.keys
        )
        outputs.values.forEach { assertTrue(it.isFile && it.length() > 0, "missing output ${it.name}") }

        val html = outputs.getValue("report.html").readText()
        assertContains(html, "Release Guard")
        assertContains(html, "CPU &amp; frame")
        assertContains(html, "<svg")
        assertFalse(Regex("(?:src|href)=[\"']https?://", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("<script" in html.lowercase())

        val interactive = outputs.getValue("interactive-performance-report.html").readText()
        assertContains(interactive, "Interactive performance")
        assertContains(interactive, "Scenario events &amp; anomalies")
        assertContains(interactive, "CPU Utilization (%)")
        assertContains(interactive, "App PSS breakdown (MB)")
        assertContains(interactive, "var report = JSON.parse")
        assertContains(interactive, "run-1")
        assertFalse(Regex("(?:src|href)=[\"']https?://", RegexOption.IGNORE_CASE).containsMatchIn(interactive))
        assertFalse(Regex("<script[^>]+src=", RegexOption.IGNORE_CASE).containsMatchIn(interactive))
        assertFalse("fetch(" in interactive)

        val json = outputs.getValue("evaluation.json").readText()
        assertContains(json, "\"verdict\": \"FAIL\"")
        val csv = outputs.getValue("summary.csv").readText()
        assertContains(csv, "\"cpu\"")
        assertContains(csv, "\"FAIL\"")

        val junit = outputs.getValue("result.junit.xml").readText()
        assertContains(junit, "failures=\"2\"")
        assertContains(junit, "CPU &amp; frame")
        assertContains(junit, "device &lt;offline&gt;")

        val pdfText = PDDocument.load(outputs.getValue("report.pdf")).use(PDFTextStripper()::getText)
        assertContains(pdfText, "Release Guard: FAIL")
        assertContains(pdfText, "QA Device")
        assertContains(pdfText, "baseline-1")
        assertContains(pdfText, "CPU & frame")
    }

    @Test fun junitEscapesNamesAndTreatsWarningsAsNonFailingSkips() {
        val evaluation = ReleaseEvaluation(
            Verdict.WARNING,
            listOf(BudgetResult("memory", "Memory <warning>", BudgetStatus.WARNING, message = "Review & accept"))
        )
        val xml = JunitExporter.export("Player \"QA\"", evaluation)
        assertContains(xml, "name=\"Player &quot;QA&quot;\"")
        assertContains(xml, "failures=\"0\"")
        assertContains(xml, "skipped=\"1\"")
        assertContains(xml, "Memory &lt;warning&gt;")
        assertContains(xml, "Review &amp; accept")
    }

    @Test fun evidenceOnlyExportsUseNeutralVerdictAndSkippedJunitCase() {
        val dir = Files.createTempDirectory("release-guard-evidence-only").toFile()
        val evaluation = ReleaseEvaluation(Verdict.NOT_EVALUATED, emptyList())
        val manifest = manifest(evaluation).copy(state = ExecutionState.EVIDENCE_ONLY)
        val outputs = ReleaseGuardReport.write(dir, project(), scenario().copy(budgets = emptyList()), manifest, null)

        val html = outputs.getValue("report.html").readText()
        assertContains(html, "NOT EVALUATED")
        assertContains(html, "No performance budgets were configured")
        val junit = outputs.getValue("result.junit.xml").readText()
        assertContains(junit, "tests=\"1\"")
        assertContains(junit, "skipped=\"1\"")
        assertContains(junit, "No performance budgets configured")
        val pdfText = PDDocument.load(outputs.getValue("report.pdf")).use(PDFTextStripper()::getText)
        assertContains(pdfText, "Release Guard: NOT_EVALUATED")
        assertContains(pdfText, "No performance budgets were configured")
    }

    @Test fun scenarioReportAggregatesRepetitionsIntoNormalizedGraphs() {
        val dir = Files.createTempDirectory("release-guard-aggregate").toFile()
        val scenario = scenario().copy(
            repetitions = 2,
            capture = CaptureSettings(
                metrics = setOf(MetricDefinition("cpu_util_pct", "%", true)),
                configured = true
            )
        )
        val manifest = manifest(evaluation()).copy(
            attempts = listOf(
                AttemptSummary(1, true, listOf(MetricSeries("cpu_util_pct", values = listOf(40.0, 60.0)))),
                AttemptSummary(2, true, listOf(MetricSeries("cpu_util_pct", values = listOf(50.0, 70.0))))
            )
        )
        val markers = listOf(
            ScenarioMarker(0.0, 1, 0, "Measured iteration 1", type = ScenarioMarkerType.ATTEMPT_START),
            ScenarioMarker(10.0, 1, 2, "Measured iteration 1", type = ScenarioMarkerType.ATTEMPT_END, outcome = ScenarioMarkerOutcome.PASS),
            ScenarioMarker(12.0, 2, 0, "Measured iteration 2", type = ScenarioMarkerType.ATTEMPT_START),
            ScenarioMarker(22.0, 2, 2, "Measured iteration 2", type = ScenarioMarkerType.ATTEMPT_END, outcome = ScenarioMarkerOutcome.PASS)
        )
        val samples = (0..10).map { sample(it.toDouble(), 40.0 + it) } +
            (12..22).map { sample(it.toDouble(), 50.0 + it - 12) }
        val evidence = ScenarioRunEvidence(
            samples,
            listOf(Anomaly("11-19 10:00:00.000", 8.0, "ANR", "synthetic anomaly")),
            markers
        )

        val html = ReleaseGuardReport.write(dir, project(), scenario, manifest, baseline(), evidence)
            .getValue("report.html").readText()

        assertContains(html, "Normalized performance profile")
        assertContains(html, "CPU utilization")
        assertContains(html, "P10–P90 variability")
        assertContains(html, "ANR")
        assertContains(html, "2 repetition(s)")
        assertFalse("<h2>Attempts</h2>" in html)
        assertFalse("Execution health" in html)
        assertContains(html, "class=\"chart-band\"")
        assertContains(html, "class=\"chart-line\"")
    }

    @Test fun scenarioReportSummarizesAppPssHeapBreakdownAndHandlesMissingData() {
        val scenario = scenario().copy(
            budgets = emptyList(),
            capture = CaptureSettings(
                metrics = setOf(MetricDefinition("app_pss_mb", "MB", true)),
                configured = true
            )
        )
        val manifest = manifest(ReleaseEvaluation(Verdict.NOT_EVALUATED, emptyList())).copy(
            state = ExecutionState.EVIDENCE_ONLY
        )
        val markers = listOf(
            ScenarioMarker(0.0, 1, 0, "Measured iteration 1", type = ScenarioMarkerType.ATTEMPT_START),
            ScenarioMarker(1.0, 1, 2, "Measured iteration 1", type = ScenarioMarkerType.ATTEMPT_END, outcome = ScenarioMarkerOutcome.PASS)
        )
        val pssSamples = listOf(
            sample(0.0, 40.0, PssSample(100.0, 40.0, 20.0, 10.0, 2.0, 8.0, 12.0, 8.0)),
            sample(1.0, 45.0, PssSample(120.0, 60.0, 30.0, 12.0, 3.0, 14.0, 14.0, 9.0))
        )

        val html = ReleaseGuardReport.write(
            Files.createTempDirectory("release-guard-pss").toFile(),
            project(), scenario, manifest, null, ScenarioRunEvidence(pssSamples, markers = markers)
        ).getValue("report.html").readText()
        assertContains(html, "Application PSS")
        assertContains(html, "App memory breakdown")
        assertContains(html, "Java heap")
        assertContains(html, "<td>50 MB</td><td>60 MB</td>")

        val unavailable = ReleaseGuardReport.write(
            Files.createTempDirectory("release-guard-pss-unavailable").toFile(),
            project(), scenario, manifest, null, ScenarioRunEvidence(listOf(sample(0.0, 40.0)), markers = markers)
        ).getValue("report.html").readText()
        assertContains(unavailable, "App PSS data unavailable")
    }

    @Test fun interactiveReportEmbedsCompleteScenarioEvidenceAndHandlesNoSamples() {
        val markers = listOf(
            ScenarioMarker(0.0, 1, 1, "Launch", type = ScenarioMarkerType.STEP_START),
            ScenarioMarker(2.0, 1, 1, "Launch", type = ScenarioMarkerType.STEP_END, outcome = ScenarioMarkerOutcome.FAIL, detail = "Timeout")
        )
        val evidence = ScenarioRunEvidence(
            samples = listOf(sample(1.0, 42.0, PssSample(100.0, 40.0, 20.0, 10.0, 2.0, 8.0, 12.0, 8.0))),
            anomalies = listOf(Anomaly("11-20 10:00:00.000", 1.5, "ANR", "App did not respond")),
            markers = markers
        )
        val report = ReleaseGuardReport.write(
            Files.createTempDirectory("release-guard-interactive").toFile(),
            project(), scenario(), manifest(evaluation()), baseline(), evidence
        ).getValue("interactive-performance-report.html").readText()

        assertContains(report, "App did not respond")
        assertContains(report, "Timeout")
        assertContains(report, "Java heap")
        assertContains(report, "Visible time range")
        assertContains(report, "decimation")
        assertContains(report, "scenarioEvidence")

        val unavailable = ReleaseGuardReport.write(
            Files.createTempDirectory("release-guard-interactive-empty").toFile(),
            project(), scenario(), manifest(evaluation()), baseline()
        ).getValue("interactive-performance-report.html").readText()
        assertContains(unavailable, "No performance samples were captured for this scenario")
    }

    private fun project() = ProjectRecord(
        id = "player",
        name = "Player QA",
        packageName = "com.example.player",
        createdAt = "2026-11-16T10:00:00Z",
        updatedAt = "2026-11-16T10:00:00Z"
    )

    private fun scenario() = ReleaseScenario(
        id = "browse",
        revision = 2,
        name = "Browse",
        packageName = "com.example.player",
        steps = listOf(ScenarioStep.LaunchApp()),
        budgets = listOf(
            PerformanceBudget("cpu", "CPU & frame", MetricDefinition("cpu_util_pct"), Aggregation.P95, absoluteLimit = 70.0)
        )
    )

    private fun evaluation() = ReleaseEvaluation(
        Verdict.FAIL,
        listOf(
            BudgetResult("cpu", "CPU & frame", BudgetStatus.FAIL, 82.5, 70.0, 60.0, 37.5, 12, listOf(1), "Limit exceeded")
        ),
        listOf("device <offline>")
    )

    private fun manifest(evaluation: ReleaseEvaluation) = RunManifest(
        runId = "run-1",
        projectId = "player",
        scenarioId = "browse",
        scenarioRevision = 2,
        state = ExecutionState.FAILED,
        startedAt = "2026-11-16T10:00:00Z",
        updatedAt = "2026-11-16T10:01:00Z",
        device = BaselineIdentity("QA Device", "14", "build-1", "browse", 2),
        attempts = listOf(AttemptSummary(1, true, listOf(MetricSeries("cpu_util_pct", values = listOf(82.5))))),
        evaluation = evaluation,
        artifacts = mapOf("attempt-01/report.pdf" to "Player/Browse/run-1/attempt-01/report.pdf")
    )

    private fun baseline() = BaselineRevision(
        "baseline-1",
        BaselineIdentity("QA Device", "14", "build-1", "browse", 2),
        listOf(AttemptSummary(1, true))
    )

    private fun sample(t: Double, cpu: Double, pss: PssSample? = null) = Sample(
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
        perCoreUtilPct = listOf(cpu, cpu - 5),
        netRxKbps = 0.0,
        netTxKbps = 0.0,
        cpuTempC = 40.0,
        cpuFreqMhz = 1200.0,
        majorFaultsPerSec = 0.0,
        pss = pss
    )
}
