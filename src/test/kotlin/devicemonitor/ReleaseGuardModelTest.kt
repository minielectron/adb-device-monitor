package devicemonitor

import devicemonitor.domain.releaseguard.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseGuardModelTest {
    @Test fun validTouchScenarioDerivesCapabilities() {
        val scenario = scenario(
            steps = listOf(
                ScenarioStep.BeginSection("browse"),
                ScenarioStep.TapElement(UiSelector(resourceId = "browse_button")),
                ScenarioStep.Swipe(.5, .8, .5, .2),
                ScenarioStep.CaptureScreenshot("browse.png"),
                ScenarioStep.EndSection("browse")
            )
        )

        assertTrue(scenario.validate().isEmpty())
        assertEquals(
            setOf(
                DeviceCapability.SHELL,
                DeviceCapability.UI_HIERARCHY,
                DeviceCapability.TOUCH_INPUT,
                DeviceCapability.SCREEN_CAPTURE
            ),
            scenario.requiredCapabilities()
        )
    }

    @Test fun dpadScenarioDoesNotRequireTouch() {
        val scenario = scenario(steps = listOf(ScenarioStep.PressKey(LogicalKey.DPAD_CENTER)))
        assertEquals(setOf(DeviceCapability.SHELL, DeviceCapability.DPAD_INPUT), scenario.requiredCapabilities())
    }

    @Test fun validationReportsStructureCoordinatesTimeoutsAndBudgets() {
        val scenario = scenario(
            schemaVersion = 99,
            repetitions = 0,
            steps = listOf(
                ScenarioStep.BeginSection("launch"),
                ScenarioStep.BeginSection("nested"),
                ScenarioStep.TapAt(2.0, .5),
                ScenarioStep.WaitForElement(UiSelector(), timeoutMs = 0),
                ScenarioStep.EndSection("wrong")
            ),
            budgets = listOf(
                PerformanceBudget("", "", MetricDefinition(""), Aggregation.P95),
                PerformanceBudget("duplicate", "one", MetricDefinition("cpu"), Aggregation.MEAN, absoluteLimit = 1.0),
                PerformanceBudget("duplicate", "two", MetricDefinition("cpu"), Aggregation.MEAN, relativeRegressionLimitPct = -1.0)
            )
        )

        val errors = scenario.validate()
        assertContains(errors, "Unsupported schema version 99")
        assertContains(errors, "Repetitions must be between 1 and 100")
        assertTrue(errors.any { "cannot begin 'nested'" in it })
        assertTrue(errors.any { "coordinates must be between 0 and 1" in it })
        assertTrue(errors.any { "requires a resource id" in it })
        assertTrue(errors.any { "timeout must be" in it })
        assertTrue(errors.any { "requires an absolute or relative limit" in it })
        assertTrue(errors.any { "duplicates id 'duplicate'" in it })
        assertTrue(errors.any { "relative regression limit" in it })
        assertTrue(errors.any { "is not closed" in it })
    }

    @Test fun selectorCoordinateFallbackMustBeCompleteAndNormalized() {
        val errors = scenario(
            steps = listOf(ScenarioStep.TapElement(UiSelector(fallbackXPct = .5)))
        ).validate()
        assertTrue(errors.any { "requires both x and y" in it })
        assertTrue(errors.any { "requires a resource id" in it })
    }

    private fun scenario(
        schemaVersion: Int = RELEASE_GUARD_SCHEMA_VERSION,
        repetitions: Int = 1,
        steps: List<ScenarioStep>,
        budgets: List<PerformanceBudget> = emptyList()
    ) = ReleaseScenario(
        schemaVersion = schemaVersion,
        id = "browse",
        name = "Browse",
        packageName = "com.example.app",
        repetitions = repetitions,
        steps = steps,
        budgets = budgets
    )
}

