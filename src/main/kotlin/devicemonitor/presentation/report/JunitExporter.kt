package devicemonitor.presentation.report

import devicemonitor.domain.releaseguard.BudgetStatus
import devicemonitor.domain.releaseguard.ReleaseEvaluation
import devicemonitor.domain.releaseguard.Verdict

object JunitExporter {
    fun export(suiteName: String, evaluation: ReleaseEvaluation): String {
        val failures = evaluation.results.count { it.status == BudgetStatus.FAIL } + evaluation.executionErrors.size
        val evidenceOnly = evaluation.verdict == Verdict.NOT_EVALUATED && evaluation.results.isEmpty()
        val skipped = evaluation.results.count { it.status in setOf(BudgetStatus.WARNING, BudgetStatus.NOT_EVALUATED) } +
            if (evidenceOnly) 1 else 0
        val tests = evaluation.results.size + evaluation.executionErrors.size + if (evidenceOnly) 1 else 0
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<testsuite name=\"").append(xml(suiteName)).append("\" tests=\"").append(tests)
                .append("\" failures=\"").append(failures).append("\" skipped=\"").append(skipped).append("\">\n")
            evaluation.results.forEach { result ->
                append("  <testcase classname=\"release-guard.budget\" name=\"").append(xml(result.name)).append("\">\n")
                when (result.status) {
                    BudgetStatus.FAIL -> append("    <failure message=\"").append(xml(result.message)).append("\"/>\n")
                    BudgetStatus.WARNING -> append("    <skipped message=\"warning: ").append(xml(result.message)).append("\"/>\n")
                    BudgetStatus.NOT_EVALUATED -> append("    <skipped message=\"").append(xml(result.message)).append("\"/>\n")
                    BudgetStatus.PASS -> Unit
                }
                append("  </testcase>\n")
            }
            evaluation.executionErrors.forEachIndexed { index, error ->
                append("  <testcase classname=\"release-guard.infrastructure\" name=\"execution-").append(index + 1).append("\">\n")
                append("    <failure message=\"").append(xml(error)).append("\"/>\n")
                append("  </testcase>\n")
            }
            if (evidenceOnly) {
                append("  <testcase classname=\"release-guard.evidence\" name=\"No performance budgets configured\">\n")
                append("    <skipped message=\"Evidence collected without a release evaluation\"/>\n")
                append("  </testcase>\n")
            }
            append("</testsuite>\n")
        }
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
