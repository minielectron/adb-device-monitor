package devicemonitor.presentation.report

import devicemonitor.data.storage.ProjectRecord
import devicemonitor.data.storage.ReleaseGuardJson
import devicemonitor.data.storage.RunManifest
import devicemonitor.domain.releaseguard.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.Locale

object ReleaseGuardReport {
    fun write(
        directory: File,
        project: ProjectRecord,
        scenario: ReleaseScenario,
        manifest: RunManifest,
        baseline: BaselineRevision?
    ): Map<String, File> {
        val evaluation = requireNotNull(manifest.evaluation) { "Release evaluation is required before export" }
        directory.mkdirs()
        val outputs = linkedMapOf(
            "evaluation.json" to directory.resolve("evaluation.json"),
            "result.junit.xml" to directory.resolve("result.junit.xml"),
            "summary.csv" to directory.resolve("summary.csv"),
            "report.html" to directory.resolve("report.html"),
            "report.pdf" to directory.resolve("report.pdf")
        )
        outputs.getValue("evaluation.json").writeText(ReleaseGuardJson.evaluation(evaluation))
        outputs.getValue("result.junit.xml").writeText(JunitExporter.export("${project.name} / ${scenario.name}", evaluation))
        outputs.getValue("summary.csv").writeText(csv(manifest))
        outputs.getValue("report.html").writeText(html(project, scenario, manifest, baseline))
        writePdf(outputs.getValue("report.pdf"), project, scenario, manifest, baseline)
        return outputs
    }

    private fun csv(manifest: RunManifest): String = buildString {
        append("run_id,verdict,budget_id,budget,status,value,absolute_limit,baseline_value,regression_pct,samples,message\n")
        manifest.evaluation?.results?.forEach { result ->
            append(listOf(
                manifest.runId,
                manifest.evaluation.verdict.name,
                result.budgetId,
                result.name,
                result.status.name,
                result.value?.let(::number).orEmpty(),
                result.absoluteLimit?.let(::number).orEmpty(),
                result.baselineValue?.let(::number).orEmpty(),
                result.relativeRegressionPct?.let(::number).orEmpty(),
                result.sampleCount.toString(),
                result.message
            ).joinToString(",", transform = ::csvCell)).append('\n')
        }
    }

    private fun html(
        project: ProjectRecord,
        scenario: ReleaseScenario,
        manifest: RunManifest,
        baseline: BaselineRevision?
    ): String {
        val evaluation = requireNotNull(manifest.evaluation)
        val verdictClass = evaluation.verdict.name.lowercase(Locale.US)
        val findings = evaluation.results.sortedBy { statusRank(it.status) }.joinToString("\n") { result ->
            """<tr><td><strong>${html(result.name)}</strong><small>${html(result.message)}</small></td><td><span class="status ${result.status.name.lowercase(Locale.US)}">${html(result.status.name)}</span></td><td>${html(result.value?.let(::number) ?: "-")}</td><td>${html(result.absoluteLimit?.let(::number) ?: "-")}</td><td>${html(result.relativeRegressionPct?.let { number(it) + "%" } ?: "-")}</td></tr>"""
        }
        val attempts = manifest.attempts.joinToString("\n") { attempt ->
            """<tr><td>${attempt.attempt}</td><td>${if (attempt.successful) "PASS" else "FAILED"}</td><td>${attempt.metrics.size}</td><td>${html(attempt.error ?: "-")}</td></tr>"""
        }
        val evidence = manifest.artifacts.entries.sortedBy { it.key }.joinToString("\n") { (key, path) ->
            "<li><strong>${html(key)}</strong><span>${html(path)}</span></li>"
        }
        val bars = evaluation.results.mapIndexed { index, result ->
            val width = when (result.status) { BudgetStatus.PASS -> 100; BudgetStatus.WARNING -> 68; BudgetStatus.FAIL -> 34; BudgetStatus.NOT_EVALUATED -> 12 }
            val y = 10 + index * 20
            "<rect x=\"0\" y=\"$y\" width=\"$width%\" height=\"10\" rx=\"5\" class=\"${result.status.name.lowercase(Locale.US)}\"/>"
        }.joinToString("")
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Release Guard · ${html(project.name)}</title>
<style>*{box-sizing:border-box}body{margin:0;background:#090909;color:#f5f1e8;font:14px/1.55 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}.shell{width:min(1080px,calc(100% - 36px));margin:auto;padding:48px 0 72px}.eyebrow{color:#e8b66d;font-size:11px;font-weight:800;letter-spacing:.16em;text-transform:uppercase}h1{margin:8px 0;font-size:clamp(34px,6vw,66px);line-height:1}.meta{color:#8f8b83}.hero,.card{border:1px solid #2b2a28;border-radius:20px;background:linear-gradient(145deg,#1b1b19,#111);box-shadow:0 24px 70px #0008}.hero{padding:32px}.verdict{color:#61d89c}.verdict.warning{color:#e8b66d}.verdict.fail,.verdict.cancelled{color:#ff7676}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}.card{padding:22px;overflow:auto}h2{margin:0 0 14px;font-size:16px}table{width:100%;border-collapse:collapse;min-width:620px}th,td{padding:11px 9px;border-bottom:1px solid #2b2a28;text-align:left;vertical-align:top}th{color:#8f8b83;font-size:10px;text-transform:uppercase}small{display:block;color:#8f8b83}.status{font:800 10px ui-monospace,monospace}.pass{color:#61d89c}.warning{color:#e8b66d}.fail{color:#ff7676}.not_evaluated{color:#8f8b83}svg{width:100%;height:${maxOf(40, evaluation.results.size * 20 + 20)}px}.pass{fill:#61d89c}.warning{fill:#e8b66d}.fail{fill:#ff7676}.not_evaluated{fill:#555}ul{margin:0;padding:0;list-style:none}li{display:flex;justify-content:space-between;gap:16px;padding:9px 0;border-bottom:1px solid #2b2a28}li span{color:#8f8b83;font:10px ui-monospace,monospace;overflow-wrap:anywhere}@media(max-width:760px){.grid{grid-template-columns:1fr}.hero{padding:23px}}</style></head>
<body><main class="shell"><section class="hero"><div class="eyebrow">Offline Performance Release Guard</div><h1 class="verdict $verdictClass">${html(evaluation.verdict.name)}</h1><p>${html(project.name)} · ${html(scenario.name)} · revision ${scenario.revision}</p><div class="meta">Run ${html(manifest.runId)} · ${html(manifest.updatedAt)} · ${manifest.attempts.size} measured attempt(s)</div></section>
<div class="grid"><section class="card"><h2>Target identity</h2><p><strong>${html(manifest.device?.model ?: "Unknown device")}</strong><br>Android ${html(manifest.device?.androidVersion ?: "unknown")}<br>Firmware ${html(manifest.device?.firmware ?: "unknown")}</p><p class="meta">Baseline: ${html(baseline?.id ?: "No matching baseline")}</p></section><section class="card"><h2>Budget signal</h2><svg viewBox="0 0 100 ${maxOf(40, evaluation.results.size * 20 + 20)}" preserveAspectRatio="none" aria-label="Budget status bars">$bars</svg></section></div>
<section class="card" style="margin-top:16px"><h2>Budget findings</h2><table><thead><tr><th>Budget</th><th>Status</th><th>Value</th><th>Limit</th><th>Regression</th></tr></thead><tbody>$findings</tbody></table></section>
<div class="grid"><section class="card"><h2>Attempts</h2><table><thead><tr><th>#</th><th>Result</th><th>Metrics</th><th>Note</th></tr></thead><tbody>$attempts</tbody></table></section><section class="card"><h2>Registered evidence</h2><ul>${evidence.ifBlank { "<li><span>No evidence files registered</span></li>" }}</ul></section></div>
</main></body></html>"""
    }

    private fun writePdf(
        file: File,
        project: ProjectRecord,
        scenario: ReleaseScenario,
        manifest: RunManifest,
        baseline: BaselineRevision?
    ) {
        val evaluation = requireNotNull(manifest.evaluation)
        PDDocument().use { document ->
            var page = PDPage(PDRectangle.A4).also(document::addPage)
            var stream = PDPageContentStream(document, page)
            var y = 790f
            fun nextPage() {
                stream.close()
                page = PDPage(PDRectangle.A4).also(document::addPage)
                stream = PDPageContentStream(document, page)
                y = 790f
            }
            fun line(text: String, bold: Boolean = false, size: Float = 10f, gap: Float = 16f) {
                if (y < 55f) nextPage()
                stream.beginText()
                stream.setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, size)
                stream.newLineAtOffset(48f, y)
                stream.showText(pdfSafe(text).take(110))
                stream.endText()
                y -= gap
            }
            line("ADB PERFORMANCE STUDIO", bold = true, size = 10f, gap = 24f)
            line("Release Guard: ${evaluation.verdict}", bold = true, size = 24f, gap = 34f)
            line("${project.name} / ${scenario.name} / revision ${scenario.revision}", bold = true, size = 13f, gap = 23f)
            line("Run: ${manifest.runId}")
            line("Device: ${manifest.device?.model ?: "Unknown"} / Android ${manifest.device?.androidVersion ?: "unknown"}")
            line("Firmware: ${manifest.device?.firmware ?: "unknown"}")
            line("Baseline: ${baseline?.id ?: "No matching baseline"}", gap = 25f)
            line("BUDGET FINDINGS", bold = true, size = 12f, gap = 22f)
            evaluation.results.sortedBy { statusRank(it.status) }.forEach { result ->
                line("${result.status}  ${result.name}", bold = true)
                line("Value ${result.value?.let(::number) ?: "-"} / limit ${result.absoluteLimit?.let(::number) ?: "-"} / samples ${result.sampleCount}")
                wrap(result.message, 90).forEach { line(it, size = 9f, gap = 13f) }
                y -= 5f
            }
            if (evaluation.executionErrors.isNotEmpty()) {
                line("EXECUTION ERRORS", bold = true, size = 12f, gap = 22f)
                evaluation.executionErrors.forEach { error -> wrap(error, 90).forEach { line(it) } }
            }
            line("EVIDENCE", bold = true, size = 12f, gap = 22f)
            line("${manifest.attempts.size} attempt(s), ${manifest.markers.size} scenario marker(s), ${manifest.artifacts.size} registered file(s)")
            stream.close()
            document.save(file)
        }
    }

    private fun statusRank(status: BudgetStatus): Int = when (status) {
        BudgetStatus.FAIL -> 0
        BudgetStatus.WARNING -> 1
        BudgetStatus.NOT_EVALUATED -> 2
        BudgetStatus.PASS -> 3
    }

    private fun number(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun pdfSafe(value: String): String = value.replace(Regex("[^\\x20-\\x7E]"), "?")
    private fun wrap(value: String, width: Int): List<String> = value.chunked(width).ifEmpty { listOf("") }
}
