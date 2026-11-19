package devicemonitor

import devicemonitor.data.storage.ProjectRecord
import devicemonitor.data.storage.RunManifest
import devicemonitor.domain.releaseguard.*
import devicemonitor.presentation.report.JunitExporter
import devicemonitor.presentation.report.ReleaseGuardReport
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
            setOf("evaluation.json", "result.junit.xml", "summary.csv", "report.html", "report.pdf"),
            outputs.keys
        )
        outputs.values.forEach { assertTrue(it.isFile && it.length() > 0, "missing output ${it.name}") }

        val html = outputs.getValue("report.html").readText()
        assertContains(html, "Release Guard")
        assertContains(html, "CPU &amp; frame")
        assertContains(html, "<svg")
        assertFalse(Regex("(?:src|href)=[\"']https?://", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("<script" in html.lowercase())

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
}
