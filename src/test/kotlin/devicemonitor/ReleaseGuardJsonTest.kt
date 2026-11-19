package devicemonitor

import com.google.gson.JsonParser
import devicemonitor.data.storage.*
import devicemonitor.domain.releaseguard.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReleaseGuardJsonTest {
    @Test fun scenarioRoundTripsEveryStepType() {
        val scenario = ReleaseScenario(
            id = "tv browse",
            name = "TV Browse ✓",
            packageName = "com.example.tv",
            repetitions = 3,
            warmups = 1,
            steps = listOf(
                ScenarioStep.InstallApk("/tmp/Build 1.apk"),
                ScenarioStep.UninstallPackage(),
                ScenarioStep.ClearAppData(),
                ScenarioStep.LaunchApp(),
                ScenarioStep.LaunchDeepLink("myapp://content/123?source=release-guard"),
                ScenarioStep.ForceStopApp(),
                ScenarioStep.GrantPermission("android.permission.POST_NOTIFICATIONS"),
                ScenarioStep.Wait(100),
                ScenarioStep.BeginSection("browse"),
                ScenarioStep.PressKey(LogicalKey.DPAD_CENTER),
                ScenarioStep.TapElement(UiSelector(resourceId = "id/browse", fallbackXPct = .5, fallbackYPct = .5)),
                ScenarioStep.TapAt(.5, .5),
                ScenarioStep.Swipe(.5, .8, .5, .2),
                ScenarioStep.EnterText("hello world"),
                ScenarioStep.WaitForElement(UiSelector(text = "Ready")),
                ScenarioStep.WaitForActivity(".HomeActivity"),
                ScenarioStep.WaitForLog("READY"),
                ScenarioStep.WaitForProcess(),
                ScenarioStep.CaptureScreenshot("browse 1.png"),
                ScenarioStep.Shell("getprop ro.product.model"),
                ScenarioStep.EndSection("browse")
            ),
            budgets = listOf(
                PerformanceBudget(
                    "cpu", "CPU p95", MetricDefinition("cpu", "%", true), Aggregation.P95,
                    absoluteLimit = 80.0, relativeRegressionLimitPct = 10.0
                )
            )
        )

        val encoded = ReleaseGuardJson.scenario(scenario)
        val decoded = ReleaseGuardJson.parseScenario(encoded)
        assertEquals(scenario, decoded)
        assertTrue("\"type\": \"installApk\"" in encoded)
        assertTrue("\"type\": \"launchDeepLink\"" in encoded)
        assertIs<ScenarioStep.Shell>(decoded.steps[19])
    }

    @Test fun manifestsPreserveMetadataAndUnicodePaths() {
        val metadata = mapOf("future" to JsonParser.parseString("{\"enabled\":true}"))
        val manifest = RunManifest(
            runId = "run-1",
            projectId = "player",
            scenarioId = "browse",
            scenarioRevision = 2,
            state = ExecutionState.EXECUTE_AND_CAPTURE,
            startedAt = "2026-11-16T10:00:00Z",
            updatedAt = "2026-11-16T10:01:00Z",
            artifacts = mapOf("screenshot" to "attempt-01/स्क्रीन शॉट.png"),
            metadata = metadata
        )
        assertEquals(manifest, ReleaseGuardJson.parseManifest(ReleaseGuardJson.manifest(manifest)))

        val evidenceOnly = manifest.copy(
            state = ExecutionState.EVIDENCE_ONLY,
            evaluation = ReleaseEvaluation(Verdict.NOT_EVALUATED, emptyList())
        )
        assertEquals(evidenceOnly, ReleaseGuardJson.parseManifest(ReleaseGuardJson.manifest(evidenceOnly)))
    }

    @Test fun unsupportedSchemaAndUnknownStepFailClearly() {
        val unsupported = ReleaseGuardJson.scenario(
            ReleaseScenario(id = "x", name = "X", packageName = "x", steps = listOf(ScenarioStep.LaunchApp()))
        ).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val versionError = assertFailsWith<IllegalArgumentException> { ReleaseGuardJson.parseScenario(unsupported) }
        assertTrue("supports 1" in (versionError.message ?: ""))

        val unknown = unsupported
            .replace("\"schemaVersion\": 99", "\"schemaVersion\": 1")
            .replace("\"launchApp\"", "\"futureStep\"")
        val stepError = assertFailsWith<RuntimeException> { ReleaseGuardJson.parseScenario(unknown) }
        assertTrue("futureStep" in generateSequence<Throwable>(stepError) { it.cause }.mapNotNull { it.message }.joinToString())
    }
}
