package devicemonitor.presentation.report

import devicemonitor.data.storage.ProjectRecord
import devicemonitor.data.storage.ReleaseGuardJson
import devicemonitor.data.storage.RunManifest
import devicemonitor.domain.Anomaly
import devicemonitor.domain.Sample
import devicemonitor.domain.releaseguard.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.Locale

data class ScenarioRunEvidence(
    val samples: List<Sample> = emptyList(),
    val anomalies: List<Anomaly> = emptyList(),
    val markers: List<ScenarioMarker> = emptyList()
)

object ReleaseGuardReport {
    private data class AttemptWindow(val attempt: Int, val start: Double, val end: Double)
    private data class GraphMetric(
        val ids: Set<String>,
        val label: String,
        val unit: String,
        val value: (Sample) -> Double?
    )
    private data class GraphPoint(val bucket: Int, val median: Double, val low: Double, val high: Double)

    fun write(
        directory: File,
        project: ProjectRecord,
        scenario: ReleaseScenario,
        manifest: RunManifest,
        baseline: BaselineRevision?,
        evidence: ScenarioRunEvidence = ScenarioRunEvidence()
    ): Map<String, File> {
        val evaluation = requireNotNull(manifest.evaluation) { "Release evaluation is required before export" }
        directory.mkdirs()
        val outputs = linkedMapOf(
            "evaluation.json" to directory.resolve("evaluation.json"),
            "result.junit.xml" to directory.resolve("result.junit.xml"),
            "summary.csv" to directory.resolve("summary.csv"),
            "report.html" to directory.resolve("report.html"),
            "interactive-performance-report.html" to directory.resolve("interactive-performance-report.html"),
            "report.pdf" to directory.resolve("report.pdf")
        )
        outputs.getValue("evaluation.json").writeText(ReleaseGuardJson.evaluation(evaluation))
        outputs.getValue("result.junit.xml").writeText(JunitExporter.export("${project.name} / ${scenario.name}", evaluation))
        outputs.getValue("summary.csv").writeText(csv(manifest))
        outputs.getValue("report.html").writeText(html(project, scenario, manifest, baseline, evidence))
        InteractivePerformanceReport.write(
            outputs.getValue("interactive-performance-report.html"),
            project,
            scenario,
            manifest,
            evidence
        )
        writePdf(outputs.getValue("report.pdf"), project, scenario, manifest, baseline, evidence)
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
        baseline: BaselineRevision?,
        evidence: ScenarioRunEvidence
    ): String {
        val evaluation = requireNotNull(manifest.evaluation)
        val verdictClass = evaluation.verdict.name.lowercase(Locale.US)
        val evidenceOnly = evaluation.verdict == Verdict.NOT_EVALUATED
        val verdictLabel = evaluation.verdict.name.replace('_', ' ')
        val findings = evaluation.results.sortedBy { statusRank(it.status) }.joinToString("\n") { result ->
            """<tr><td><strong>${html(result.name)}</strong><small>${html(result.message)}</small></td><td><span class="status ${result.status.name.lowercase(Locale.US)}">${html(result.status.name)}</span></td><td>${html(result.value?.let(::number) ?: "-")}</td><td>${html(result.absoluteLimit?.let(::number) ?: "-")}</td><td>${html(result.relativeRegressionPct?.let { number(it) + "%" } ?: "-")}</td></tr>"""
        }.ifBlank {
            if (evidenceOnly) """<tr><td><strong>No performance budgets configured</strong><small>Evidence was collected without a release evaluation.</small></td><td><span class="status not_evaluated">NOT EVALUATED</span></td><td>-</td><td>-</td><td>-</td></tr>"""
            else """<tr><td colspan="5">No budget findings</td></tr>"""
        }
        val registeredEvidence = manifest.artifacts.entries.sortedBy { it.key }.joinToString("\n") { (key, path) ->
            "<li><strong>${html(key)}</strong><span>${html(path)}</span></li>"
        }
        val successfulAttempts = manifest.attempts.count { it.successful }
        val failedAttempts = manifest.attempts.size - successfulAttempts
        val graphs = normalizedGraphs(scenario, manifest, evidence)
        val appMemory = appMemoryBreakdown(scenario, evidence)
        val anomalySummary = evidence.anomalies.groupingBy { it.type }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString("") { "<li><strong>${html(it.key)}</strong><span>${it.value}</span></li>" }
            .ifBlank { "<li><strong>No anomalies detected</strong><span>0</span></li>" }
        val bars = evaluation.results.mapIndexed { index, result ->
            val width = when (result.status) { BudgetStatus.PASS -> 100; BudgetStatus.WARNING -> 68; BudgetStatus.FAIL -> 34; BudgetStatus.NOT_EVALUATED -> 12 }
            val y = 10 + index * 20
            "<rect x=\"0\" y=\"$y\" width=\"$width%\" height=\"10\" rx=\"5\" class=\"${result.status.name.lowercase(Locale.US)}\"/>"
        }.joinToString("")
        val budgetSignal = if (evidenceOnly) {
            "<p class=\"meta\">No performance budgets were configured. This run contains evidence only.</p>"
        } else {
            """<svg class="budget-bars" viewBox="0 0 100 ${maxOf(40, evaluation.results.size * 20 + 20)}" preserveAspectRatio="none" aria-label="Budget status bars">$bars</svg>"""
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Release Guard · ${html(project.name)}</title>
<style>*{box-sizing:border-box}body{margin:0;background:#090909;color:#f5f1e8;font:14px/1.55 Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}.shell{width:min(1180px,calc(100% - 36px));margin:auto;padding:48px 0 72px}.eyebrow{color:#e8b66d;font-size:11px;font-weight:800;letter-spacing:.16em;text-transform:uppercase}h1{margin:8px 0;font-size:clamp(34px,6vw,66px);line-height:1}.meta{color:#8f8b83}.hero,.card{border:1px solid #2b2a28;border-radius:20px;background:linear-gradient(145deg,#1b1b19,#111);box-shadow:0 24px 70px #0008}.hero{padding:32px}.verdict{color:#61d89c}.verdict.warning{color:#e8b66d}.verdict.not_evaluated{color:#aaa49a}.verdict.fail,.verdict.cancelled{color:#ff7676}.grid,.charts{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}.card{padding:22px;overflow:auto}h2{margin:0 0 14px;font-size:16px}h3{margin:0;font-size:14px}.chart-meta{display:flex;justify-content:space-between;gap:12px;margin:5px 0 12px;color:#8f8b83;font:10px ui-monospace,monospace}.metric-chart{display:block;width:100%;height:auto;min-height:190px}.chart-grid{stroke:#2d2d2b;stroke-width:1}.chart-axis{fill:#77736c;font:10px ui-monospace,monospace}.chart-band{fill:#58d6c7;opacity:.13}.chart-line{fill:none;stroke:#58d6c7;stroke-width:3;stroke-linecap:round;stroke-linejoin:round}.budget-bars{width:100%;height:${maxOf(40, evaluation.results.size * 20 + 20)}px}table{width:100%;border-collapse:collapse;min-width:620px}th,td{padding:11px 9px;border-bottom:1px solid #2b2a28;text-align:left;vertical-align:top}th{color:#8f8b83;font-size:10px;text-transform:uppercase}small{display:block;color:#8f8b83}.status{font:800 10px ui-monospace,monospace}.pass{color:#61d89c}.warning{color:#e8b66d}.fail{color:#ff7676}.not_evaluated{color:#8f8b83}.pass{fill:#61d89c}.warning{fill:#e8b66d}.fail{fill:#ff7676}.not_evaluated{fill:#555}ul{margin:0;padding:0;list-style:none}li{display:flex;justify-content:space-between;gap:16px;padding:9px 0;border-bottom:1px solid #2b2a28}li span{color:#8f8b83;font:10px ui-monospace,monospace;overflow-wrap:anywhere}.coverage{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.coverage div{padding:13px;border:1px solid #2b2a28;border-radius:13px;background:#0e0f0e}.coverage strong{display:block;font-size:24px}.coverage span{color:#8f8b83;font-size:9px;text-transform:uppercase;letter-spacing:.08em}@media(max-width:760px){.grid,.charts{grid-template-columns:1fr}.hero{padding:23px}.coverage{grid-template-columns:1fr 1fr}}</style></head>
<body><main class="shell"><section class="hero"><div class="eyebrow">Offline Performance Release Guard</div><h1 class="verdict $verdictClass">${html(verdictLabel)}</h1><p>${html(project.name)} · ${html(scenario.name)} · revision ${scenario.revision}</p><div class="meta">Run ${html(manifest.runId)} · ${html(manifest.updatedAt)} · ${manifest.attempts.size} measured repetition(s)</div></section>
<div class="grid"><section class="card"><h2>Target identity</h2><p><strong>${html(manifest.device?.model ?: "Unknown device")}</strong><br>Android ${html(manifest.device?.androidVersion ?: "unknown")}<br>Firmware ${html(manifest.device?.firmware ?: "unknown")}</p><p class="meta">Baseline: ${html(baseline?.id ?: "No matching baseline")}</p></section><section class="card"><h2>Budget signal</h2>$budgetSignal</section></div>
<section class="card" style="margin-top:16px"><h2>Budget findings</h2><table><thead><tr><th>Budget</th><th>Status</th><th>Value</th><th>Limit</th><th>Regression</th></tr></thead><tbody>$findings</tbody></table></section>
<section class="card" style="margin-top:16px"><h2>Complete scenario coverage</h2><div class="coverage"><div><strong>${manifest.attempts.size}</strong><span>Measured repetitions</span></div><div><strong>$successfulAttempts</strong><span>Successful</span></div><div><strong>$failedAttempts</strong><span>Failed</span></div><div><strong>${evidence.samples.size}</strong><span>Samples</span></div></div></section>
<section style="margin-top:16px"><h2>Normalized performance profile</h2><p class="meta">Median trend with P10–P90 variability across successful measured repetitions, normalized to scenario progress.</p><div class="charts">${graphs.ifBlank { "<section class=\"card\"><h3>Performance graph unavailable</h3><p class=\"meta\">No complete measured repetition contained enough samples to normalize.</p></section>" }}</div></section>
$appMemory
<section class="card" style="margin-top:16px"><h2>Run anomalies</h2><p class="meta">Iteration and device issues are consolidated in the single scenario anomalies file.</p><ul>$anomalySummary</ul></section>
<section class="card" style="margin-top:16px"><h2>Registered scenario evidence</h2><ul>${registeredEvidence.ifBlank { "<li><span>No evidence files registered</span></li>" }}</ul></section>
</main></body></html>"""
    }

    private fun writePdf(
        file: File,
        project: ProjectRecord,
        scenario: ReleaseScenario,
        manifest: RunManifest,
        baseline: BaselineRevision?,
        evidence: ScenarioRunEvidence
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
            if (evaluation.verdict == Verdict.NOT_EVALUATED) {
                line("No performance budgets were configured. Evidence only; no release decision was made.", gap = 24f)
            }
            line("${project.name} / ${scenario.name} / revision ${scenario.revision}", bold = true, size = 13f, gap = 23f)
            line("Run: ${manifest.runId}")
            line("Device: ${manifest.device?.model ?: "Unknown"} / Android ${manifest.device?.androidVersion ?: "unknown"}")
            line("Firmware: ${manifest.device?.firmware ?: "unknown"}")
            line("Baseline: ${baseline?.id ?: "No matching baseline"}", gap = 25f)
            line("Coverage: ${evidence.samples.size} samples / ${evidence.anomalies.size} anomalies", gap = 25f)
            line("BUDGET FINDINGS", bold = true, size = 12f, gap = 22f)
            evaluation.results.sortedBy { statusRank(it.status) }.forEach { result ->
                line("${result.status}  ${result.name}", bold = true)
                line("Value ${result.value?.let(::number) ?: "-"} / limit ${result.absoluteLimit?.let(::number) ?: "-"} / samples ${result.sampleCount}")
                wrap(result.message, 90).forEach { line(it, size = 9f, gap = 13f) }
                y -= 5f
            }
            line("EVIDENCE", bold = true, size = 12f, gap = 22f)
            line("${manifest.attempts.size} measured repetition(s), ${manifest.markers.size} scenario marker(s), ${manifest.artifacts.size} registered file(s)")
            stream.close()
            document.save(file)
        }
    }

    private fun normalizedGraphs(
        scenario: ReleaseScenario,
        manifest: RunManifest,
        evidence: ScenarioRunEvidence
    ): String {
        val successful = manifest.attempts.filter { it.successful }.map { it.attempt }.toSet()
        val windows = evidence.markers.groupBy { it.attempt }.mapNotNull { (attempt, markers) ->
            if (attempt !in successful) return@mapNotNull null
            val start = markers.firstOrNull { it.type == ScenarioMarkerType.ATTEMPT_START }?.elapsedSec
            val end = markers.lastOrNull { it.type == ScenarioMarkerType.ATTEMPT_END }?.elapsedSec
            if (start != null && end != null && end > start) AttemptWindow(attempt, start, end) else null
        }.sortedBy { it.attempt }
        if (windows.isEmpty() || evidence.samples.isEmpty()) return ""

        val selected = selectedMetricIds(scenario)
        return graphMetrics().filter { metric -> metric.ids.any { it in selected } }.mapNotNull { metric ->
            val perAttempt = windows.mapNotNull { window ->
                val values = evidence.samples.asSequence()
                    .filter { it.elapsedSec in window.start..window.end }
                    .mapNotNull { sample -> metric.value(sample)?.let { sample.elapsedSec to it } }
                    .groupBy(
                        keySelector = { ((it.first - window.start) / (window.end - window.start) * (GRAPH_BUCKETS - 1)).toInt().coerceIn(0, GRAPH_BUCKETS - 1) },
                        valueTransform = { it.second }
                    )
                    .mapValues { (_, bucket) -> bucket.average() }
                values.takeIf { it.isNotEmpty() }
            }
            if (perAttempt.isEmpty()) return@mapNotNull null
            val points = (0 until GRAPH_BUCKETS).mapNotNull { bucket ->
                val values = perAttempt.mapNotNull { it[bucket] }.sorted()
                if (values.isEmpty()) null else GraphPoint(
                    bucket,
                    percentile(values, 50.0),
                    percentile(values, 10.0),
                    percentile(values, 90.0)
                )
            }
            if (points.size < 2) return@mapNotNull null
            graphCard(metric, points, perAttempt.size)
        }.joinToString("")
    }

    private fun graphCard(metric: GraphMetric, points: List<GraphPoint>, repetitions: Int): String {
        var min = points.minOf { it.low }
        var max = points.maxOf { it.high }
        if (max <= min) {
            val padding = maxOf(1.0, kotlin.math.abs(max) * .05)
            min -= padding
            max += padding
        }
        fun x(bucket: Int) = 48.0 + bucket.toDouble() / (GRAPH_BUCKETS - 1) * 724.0
        fun y(value: Double) = 178.0 - (value - min) / (max - min) * 148.0
        val band = (points.map { "${svgNumber(x(it.bucket))},${svgNumber(y(it.low))}" } +
            points.asReversed().map { "${svgNumber(x(it.bucket))},${svgNumber(y(it.high))}" }).joinToString(" ")
        val line = points.joinToString(" ") { "${svgNumber(x(it.bucket))},${svgNumber(y(it.median))}" }
        val grid = listOf(0, 25, 50, 75, 100).joinToString("") { progress ->
            val gridX = 48.0 + progress / 100.0 * 724.0
            "<line x1=\"${svgNumber(gridX)}\" y1=\"30\" x2=\"${svgNumber(gridX)}\" y2=\"178\" class=\"chart-grid\"/><text x=\"${svgNumber(gridX)}\" y=\"202\" text-anchor=\"middle\" class=\"chart-axis\">$progress%</text>"
        }
        return """<section class="card"><h3>${html(metric.label)}</h3><div class="chart-meta"><span>${number(min)}–${number(max)} ${html(metric.unit)}</span><span>$repetitions repetition(s)</span></div><svg class="metric-chart" viewBox="0 0 820 214" role="img" aria-label="${html(metric.label)} normalized scenario graph">$grid<text x="8" y="34" class="chart-axis">${html(metric.unit)}</text><polygon points="$band" class="chart-band"/><polyline points="$line" class="chart-line"/></svg></section>"""
    }

    private fun appMemoryBreakdown(scenario: ReleaseScenario, evidence: ScenarioRunEvidence): String {
        val selected = selectedMetricIds(scenario).any { it == "app_pss_mb" || it == "pss" }
        if (!selected) return ""
        val categories = listOf<Pair<String, (Sample) -> Double?>>(
            "Total PSS" to { it.pss?.totalMb },
            "Java heap" to { it.pss?.javaHeapMb },
            "Native heap" to { it.pss?.nativeHeapMb },
            "Code" to { it.pss?.codeMb },
            "Stack" to { it.pss?.stackMb },
            "Graphics" to { it.pss?.graphicsMb },
            "Private other" to { it.pss?.privateOtherMb },
            "System" to { it.pss?.systemMb }
        )
        val rows = categories.mapNotNull { (label, value) ->
            val values = evidence.samples.mapNotNull(value).filter(Double::isFinite).sorted()
            values.takeIf { it.isNotEmpty() }?.let {
                "<tr><td><strong>${html(label)}</strong></td><td>${number(percentile(it, 50.0))} MB</td><td>${number(it.last())} MB</td></tr>"
            }
        }.joinToString("")
        val body = rows.ifBlank { "<tr><td colspan=\"3\"><strong>App PSS data unavailable</strong><small>The package was not running or the device did not expose memory details.</small></td></tr>" }
        return """<section class="card" style="margin-top:16px"><h2>App memory breakdown</h2><p class="meta">Scenario-wide memory from dumpsys meminfo for ${html(scenario.packageName)}.</p><table><thead><tr><th>Category</th><th>Median</th><th>Peak</th></tr></thead><tbody>$body</tbody></table></section>"""
    }

    private fun graphMetrics(): List<GraphMetric> = listOf(
        GraphMetric(setOf("cpu", "cpu_util_pct"), "CPU utilization", "%") { it.cpuUtilPct },
        GraphMetric(setOf("per_core_cpu_pct"), "Per-core CPU average", "%") { sample -> sample.perCoreUtilPct?.mapNotNull { it }?.takeIf { it.isNotEmpty() }?.average() },
        GraphMetric(setOf("top_process_cpu_pct"), "Top process CPU", "%") { sample -> sample.topProcs.maxOfOrNull { it.cpuPct } },
        GraphMetric(setOf("memory", "memory_used_mb", "memory_available_mb"), "Memory used", "MB") { it.memUsedMb },
        GraphMetric(setOf("memory_used_pct"), "Memory used", "%") { it.memUsedPct },
        GraphMetric(setOf("psi_cpu"), "CPU PSI pressure", "%") { it.cpuPsiSomeAvg10 },
        GraphMetric(setOf("psi_memory"), "Memory PSI pressure", "%") { it.memPsiSomeAvg10 },
        GraphMetric(setOf("psi_io"), "I/O PSI pressure", "%") { it.ioPsiSomeAvg10 },
        GraphMetric(setOf("top_process_memory_mb"), "Top process memory", "MB") { sample -> sample.topMemProcs.maxOfOrNull { it.pssMb } },
        GraphMetric(setOf("app_pss_mb", "pss"), "Application PSS", "MB") { it.pss?.totalMb },
        GraphMetric(setOf("app_cpu_pct"), "Application CPU", "%") { it.appCpuPct },
        GraphMetric(setOf("main_thread_cpu_pct"), "Main-thread CPU", "%") { it.mainThreadCpuPct },
        GraphMetric(setOf("screen_fps"), "Screen FPS", "fps") { it.screenFps },
        GraphMetric(setOf("video_fps"), "Video FPS", "fps") { it.videoFps }
    )

    private fun selectedMetricIds(scenario: ReleaseScenario): Set<String> =
        (scenario.capture.metrics.map { it.id } + scenario.budgets.map { it.metric.id } +
            if (scenario.capture.configured) emptySet() else setOf("app_pss_mb"))
            .map { it.lowercase(Locale.US) }.toSet()

    private fun percentile(sorted: List<Double>, percentile: Double): Double {
        if (sorted.size == 1) return sorted.first()
        val position = percentile / 100.0 * (sorted.size - 1)
        val lower = position.toInt()
        val upper = kotlin.math.ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun svgNumber(value: Double): String = String.format(Locale.US, "%.2f", value)

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

    private const val GRAPH_BUCKETS = 40
}
