package devicemonitor

import devicemonitor.application.releaseguard.EvaluationEngine
import devicemonitor.domain.releaseguard.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseGuardEvaluationTest {
    private val identity = BaselineIdentity("Pixel 9", "16", "build-a", "browse", 1)

    @Test fun aggregatesPercentilesAndGrowthDeterministically() {
        assertEquals(2.5, EvaluationEngine.percentile(listOf(1.0, 2.0, 3.0, 4.0), 50.0))
        assertEquals(3.7, EvaluationEngine.percentile(listOf(1.0, 2.0, 3.0, 4.0), 90.0))
        assertEquals(
            15.0,
            EvaluationEngine.aggregate(
                Aggregation.GROWTH_PERCENT,
                listOf(
                    MetricSeries("memory", values = listOf(100.0, 110.0)),
                    MetricSeries("memory", values = listOf(200.0, 240.0))
                )
            )
        )
        assertNull(EvaluationEngine.aggregate(Aggregation.RATE, listOf(MetricSeries("events", values = listOf(1.0)))))
    }

    @Test fun absoluteBudgetsProducePassWarningAndFail() {
        val attempts = listOf(attempt(1, "cpu", listOf(40.0, 80.0)))
        val result = EvaluationEngine.evaluate(
            listOf(
                budget("pass", 80.0, Severity.CRITICAL),
                budget("warn", 70.0, Severity.WARNING),
                budget("fail", 70.0, Severity.CRITICAL)
            ),
            attempts,
            identity
        )

        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(listOf(BudgetStatus.PASS, BudgetStatus.WARNING, BudgetStatus.FAIL), result.results.map { it.status })
        assertEquals(2, result.results.first().sampleCount)
        assertEquals(listOf(1), result.results.first().contributingAttempts)
    }

    @Test fun zeroBudgetsProduceNeutralVerdictUnlessExecutionFailed() {
        val evidenceOnly = EvaluationEngine.evaluate(emptyList(), listOf(attempt(1, "cpu", listOf(40.0))), identity)
        assertEquals(Verdict.NOT_EVALUATED, evidenceOnly.verdict)
        assertTrue(evidenceOnly.results.isEmpty())
        assertTrue(evidenceOnly.executionErrors.isEmpty())

        val failed = EvaluationEngine.evaluate(
            emptyList(),
            listOf(AttemptSummary(1, false, error = "device disconnected")),
            identity
        )
        assertEquals(Verdict.FAIL, failed.verdict)
        assertEquals(listOf("Attempt 1 failed: device disconnected"), failed.executionErrors)
    }

    @Test fun relativeBaselineHonorsIdentityAndNormalizedOptIn() {
        val baseline = BaselineRevision(
            "base-1",
            identity.copy(model = "Other Device"),
            listOf(attempt(1, "startup", listOf(1000.0)))
        )
        val normalized = MetricDefinition("startup", "ms", normalizedAcrossDevices = true)
        val budget = PerformanceBudget(
            "startup", "Startup", normalized, Aggregation.MEDIAN,
            relativeRegressionLimitPct = 10.0, required = true
        )
        val attempts = listOf(AttemptSummary(1, true, listOf(MetricSeries("startup", values = listOf(1200.0)))))

        val strict = EvaluationEngine.evaluate(listOf(budget), attempts, identity, baseline)
        assertEquals(BudgetStatus.FAIL, strict.results.single().status)
        assertTrue("does not match" in strict.results.single().message)

        val compatible = EvaluationEngine.evaluate(listOf(budget), attempts, identity, baseline, allowCompatibleDeviceBaseline = true)
        assertEquals(BudgetStatus.FAIL, compatible.results.single().status)
        assertEquals(20.0, compatible.results.single().relativeRegressionPct)
        assertEquals("Baseline regression limit exceeded", compatible.results.single().message)
    }

    @Test fun nonNormalizedMetricRefusesCrossDeviceBaseline() {
        val baseline = BaselineRevision(
            "base-1", identity.copy(model = "Other Device"),
            listOf(attempt(1, "pss", listOf(200.0)))
        )
        val budget = PerformanceBudget(
            "pss", "PSS", MetricDefinition("pss", "MB"), Aggregation.MEDIAN,
            relativeRegressionLimitPct = 10.0, required = false
        )
        val result = EvaluationEngine.evaluate(
            listOf(budget), listOf(attempt(1, "pss", listOf(250.0))), identity, baseline,
            allowCompatibleDeviceBaseline = true
        )
        assertEquals(BudgetStatus.NOT_EVALUATED, result.results.single().status)
        assertTrue("not normalized" in result.results.single().message)
    }

    @Test fun missingMetricsAndFailedAttemptsCannotPass() {
        val attempts = listOf(
            attempt(1, "cpu", listOf(20.0)),
            AttemptSummary(2, false, error = "device disconnected")
        )
        val result = EvaluationEngine.evaluate(
            listOf(
                PerformanceBudget(
                    "required", "Required", MetricDefinition("missing"), Aggregation.MEAN,
                    absoluteLimit = 1.0, required = true
                ),
                PerformanceBudget(
                    "optional", "Optional", MetricDefinition("optional"), Aggregation.MEAN,
                    absoluteLimit = 1.0, required = false
                )
            ),
            attempts,
            identity
        )

        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(BudgetStatus.FAIL, result.results[0].status)
        assertEquals(BudgetStatus.NOT_EVALUATED, result.results[1].status)
        assertEquals(listOf("Attempt 2 failed: device disconnected"), result.executionErrors)
    }

    @Test fun minimumDirectionTreatsLowerCurrentValueAsRegression() {
        val baseline = BaselineRevision("base", identity, listOf(attempt(1, "fps", listOf(60.0))))
        val budget = PerformanceBudget(
            "fps", "FPS", MetricDefinition("fps", normalizedAcrossDevices = true), Aggregation.MEDIAN,
            direction = LimitDirection.MINIMUM, relativeRegressionLimitPct = 5.0
        )
        val result = EvaluationEngine.evaluate(listOf(budget), listOf(attempt(1, "fps", listOf(54.0))), identity, baseline)
        assertEquals(10.0, result.results.single().relativeRegressionPct)
        assertEquals(BudgetStatus.FAIL, result.results.single().status)
    }

    private fun attempt(number: Int, metric: String, values: List<Double>) =
        AttemptSummary(number, true, listOf(MetricSeries(metric, values = values)))

    private fun budget(id: String, limit: Double, severity: Severity) = PerformanceBudget(
        id, id, MetricDefinition("cpu", "%", normalizedAcrossDevices = true),
        Aggregation.MAXIMUM, absoluteLimit = limit, severity = severity
    )
}
