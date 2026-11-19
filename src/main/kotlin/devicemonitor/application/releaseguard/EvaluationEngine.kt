package devicemonitor.application.releaseguard

import devicemonitor.domain.releaseguard.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

object EvaluationEngine {
    fun evaluate(
        budgets: List<PerformanceBudget>,
        attempts: List<AttemptSummary>,
        currentIdentity: BaselineIdentity,
        baseline: BaselineRevision? = null,
        allowCompatibleDeviceBaseline: Boolean = false
    ): ReleaseEvaluation {
        val successful = attempts.filter { it.successful }
        val executionErrors = attempts.filterNot { it.successful }.map {
            "Attempt ${it.attempt} failed${it.error?.let { message -> ": $message" } ?: ""}"
        }
        val results = budgets.map { budget ->
            evaluateBudget(budget, successful, currentIdentity, baseline, allowCompatibleDeviceBaseline)
        }
        val verdict = when {
            executionErrors.isNotEmpty() || results.any { it.status == BudgetStatus.FAIL } -> Verdict.FAIL
            budgets.isEmpty() -> Verdict.NOT_EVALUATED
            results.any { it.status == BudgetStatus.WARNING } -> Verdict.WARNING
            else -> Verdict.PASS
        }
        return ReleaseEvaluation(verdict, results, executionErrors)
    }

    private fun evaluateBudget(
        budget: PerformanceBudget,
        attempts: List<AttemptSummary>,
        currentIdentity: BaselineIdentity,
        baseline: BaselineRevision?,
        allowCompatibleDeviceBaseline: Boolean
    ): BudgetResult {
        val series = matchingSeries(attempts, budget)
        val value = aggregate(budget.aggregation, series)
        val attemptNumbers = attempts.filter { attempt ->
            attempt.metrics.any { it.metricId == budget.metric.id && it.section == budget.section }
        }.map { it.attempt }
        val sampleCount = series.sumOf { it.values.count(Double::isFinite) }

        if (value == null) {
            val status = if (budget.required) BudgetStatus.FAIL else BudgetStatus.NOT_EVALUATED
            return BudgetResult(
                budget.id, budget.name, status, sampleCount = sampleCount,
                contributingAttempts = attemptNumbers,
                message = "No usable data for ${budget.metric.id}${budget.section?.let { " in $it" } ?: ""}"
            )
        }

        val baselineAllowed = baseline != null && baseline.identity.sameScenario(currentIdentity) &&
            (baseline.identity.exactlyMatches(currentIdentity) ||
                (allowCompatibleDeviceBaseline && budget.metric.normalizedAcrossDevices))
        val baselineValue = if (baselineAllowed) {
            aggregate(budget.aggregation, matchingSeries(baseline!!.attempts.filter { it.successful }, budget))
        } else null
        val regression = baselineValue?.let { regressionPercent(value, it, budget.direction) }

        val absoluteFailed = budget.absoluteLimit?.let { limit -> violates(value, limit, budget.direction) } ?: false
        val relativeApplicable = budget.relativeRegressionLimitPct != null && regression != null
        val relativeFailed = relativeApplicable && regression!! > budget.relativeRegressionLimitPct!!
        val hasApplicableLimit = budget.absoluteLimit != null || relativeApplicable

        if (!hasApplicableLimit) {
            val reason = when {
                baseline == null -> "No baseline is selected"
                !baseline.identity.sameScenario(currentIdentity) -> "Baseline scenario revision does not match"
                !baseline.identity.exactlyMatches(currentIdentity) && !allowCompatibleDeviceBaseline -> "Baseline device does not match"
                !baseline.identity.exactlyMatches(currentIdentity) && !budget.metric.normalizedAcrossDevices -> "Metric is not normalized for cross-device comparison"
                baselineValue == null -> "Baseline has no usable data"
                else -> "Baseline value cannot be compared"
            }
            val status = if (budget.required) BudgetStatus.FAIL else BudgetStatus.NOT_EVALUATED
            return BudgetResult(
                budget.id, budget.name, status, value, budget.absoluteLimit, baselineValue, regression,
                sampleCount, attemptNumbers, reason
            )
        }

        val violated = absoluteFailed || relativeFailed
        val status = when {
            !violated -> BudgetStatus.PASS
            budget.severity == Severity.CRITICAL -> BudgetStatus.FAIL
            else -> BudgetStatus.WARNING
        }
        val message = when {
            absoluteFailed && relativeFailed -> "Absolute and baseline limits exceeded"
            absoluteFailed -> "Absolute limit exceeded"
            relativeFailed -> "Baseline regression limit exceeded"
            else -> "Within budget"
        }
        return BudgetResult(
            budget.id, budget.name, status, value, budget.absoluteLimit, baselineValue, regression,
            sampleCount, attemptNumbers, message
        )
    }

    private fun matchingSeries(attempts: List<AttemptSummary>, budget: PerformanceBudget): List<MetricSeries> =
        attempts.flatMap { it.metrics }.filter {
            it.metricId == budget.metric.id && it.section == budget.section
        }

    internal fun aggregate(aggregation: Aggregation, series: List<MetricSeries>): Double? {
        val values = series.flatMap { it.values }.filter(Double::isFinite)
        if (aggregation == Aggregation.COUNT) return if (series.isEmpty()) null else values.size.toDouble()
        if (values.isEmpty()) return null
        return when (aggregation) {
            Aggregation.MINIMUM -> values.minOrNull()
            Aggregation.MAXIMUM -> values.maxOrNull()
            Aggregation.MEAN -> values.average()
            Aggregation.MEDIAN -> percentile(values, 50.0)
            Aggregation.P90 -> percentile(values, 90.0)
            Aggregation.P95 -> percentile(values, 95.0)
            Aggregation.COUNT -> error("COUNT handled before empty-value validation")
            Aggregation.RATE -> {
                val duration = series.mapNotNull { it.durationSec?.takeIf { seconds -> seconds > 0.0 && seconds.isFinite() } }.sum()
                if (duration > 0.0) values.size / duration else null
            }
            Aggregation.GROWTH_PERCENT -> {
                val growth = series.mapNotNull { item ->
                    val finite = item.values.filter(Double::isFinite)
                    if (finite.size < 2 || finite.first() == 0.0) null
                    else ((finite.last() - finite.first()) / abs(finite.first())) * 100.0
                }
                growth.takeIf { it.isNotEmpty() }?.average()
            }
        }
    }

    internal fun percentile(values: List<Double>, percentile: Double): Double? {
        val sorted = values.filter(Double::isFinite).sorted()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted.first()
        val rank = (percentile.coerceIn(0.0, 100.0) / 100.0) * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower)
    }

    private fun violates(value: Double, limit: Double, direction: LimitDirection): Boolean = when (direction) {
        LimitDirection.MAXIMUM -> value > limit
        LimitDirection.MINIMUM -> value < limit
    }

    private fun regressionPercent(current: Double, baseline: Double, direction: LimitDirection): Double? {
        if (baseline == 0.0) return if (current == 0.0) 0.0 else null
        return when (direction) {
            LimitDirection.MAXIMUM -> ((current - baseline) / abs(baseline)) * 100.0
            LimitDirection.MINIMUM -> ((baseline - current) / abs(baseline)) * 100.0
        }
    }
}
