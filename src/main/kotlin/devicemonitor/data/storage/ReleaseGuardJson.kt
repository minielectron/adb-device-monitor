package devicemonitor.data.storage

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import devicemonitor.domain.releaseguard.*
import java.lang.reflect.Type

data class ProjectRecord(
    val schemaVersion: Int = RELEASE_GUARD_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val packageName: String,
    val createdAt: String,
    val updatedAt: String,
    val metadata: Map<String, JsonElement> = emptyMap()
)

data class RunManifest(
    val schemaVersion: Int = RELEASE_GUARD_SCHEMA_VERSION,
    val runId: String,
    val projectId: String,
    val scenarioId: String,
    val scenarioRevision: Int,
    val state: ExecutionState,
    val startedAt: String,
    val updatedAt: String,
    val device: BaselineIdentity? = null,
    val attempts: List<AttemptSummary> = emptyList(),
    val evaluation: ReleaseEvaluation? = null,
    val artifacts: Map<String, String> = emptyMap(),
    val error: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap()
)

object ReleaseGuardJson {
    private val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(ScenarioStep::class.java, ScenarioStepAdapter())
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun scenario(value: ReleaseScenario): String = gson.toJson(value)
    fun parseScenario(json: String): ReleaseScenario = checked(gson.fromJson(json, ReleaseScenario::class.java))

    fun project(value: ProjectRecord): String = gson.toJson(value)
    fun parseProject(json: String): ProjectRecord = checked(gson.fromJson(json, ProjectRecord::class.java))

    fun manifest(value: RunManifest): String = gson.toJson(value)
    fun parseManifest(json: String): RunManifest = checked(gson.fromJson(json, RunManifest::class.java))

    fun evaluation(value: ReleaseEvaluation): String = gson.toJson(value)
    fun parseEvaluation(json: String): ReleaseEvaluation = gson.fromJson(json, ReleaseEvaluation::class.java)

    fun baseline(value: BaselineRevision): String = gson.toJson(value)
    fun parseBaseline(json: String): BaselineRevision = gson.fromJson(json, BaselineRevision::class.java)

    fun budget(value: PerformanceBudget): String = gson.toJson(value)
    fun parseBudget(json: String): PerformanceBudget = gson.fromJson(json, PerformanceBudget::class.java)

    fun attempts(value: List<AttemptSummary>): String = gson.toJson(value)
    fun parseAttempts(json: String): List<AttemptSummary> = gson.fromJson(
        json,
        object : TypeToken<List<AttemptSummary>>() {}.type
    )

    private fun <T> checked(value: T): T {
        val version = when (value) {
            is ReleaseScenario -> value.schemaVersion
            is ProjectRecord -> value.schemaVersion
            is RunManifest -> value.schemaVersion
            else -> RELEASE_GUARD_SCHEMA_VERSION
        }
        require(version == RELEASE_GUARD_SCHEMA_VERSION) {
            "Unsupported Release Guard schema version $version; this build supports $RELEASE_GUARD_SCHEMA_VERSION"
        }
        return value
    }

    private class ScenarioStepAdapter : JsonSerializer<ScenarioStep>, JsonDeserializer<ScenarioStep> {
        override fun serialize(src: ScenarioStep, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val json = PLAIN_GSON.toJsonTree(src, src.javaClass).asJsonObject
            json.addProperty("type", stepType(src))
            return json
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ScenarioStep {
            val obj = json.asJsonObject
            val type = obj.get("type")?.asString ?: throw JsonParseException("Scenario step type is required")
            val target = STEP_TYPES[type] ?: throw JsonParseException("Unsupported scenario step type '$type'")
            return PLAIN_GSON.fromJson(json, target)
        }

        private fun stepType(step: ScenarioStep): String = STEP_TYPES.entries
            .firstOrNull { it.value == step.javaClass }?.key
            ?: error("Unsupported scenario step ${step.javaClass.name}")

        companion object {
            private val PLAIN_GSON = GsonBuilder().disableHtmlEscaping().create()
            private val STEP_TYPES: Map<String, Class<out ScenarioStep>> = linkedMapOf(
                "installApk" to ScenarioStep.InstallApk::class.java,
                "uninstallPackage" to ScenarioStep.UninstallPackage::class.java,
                "clearAppData" to ScenarioStep.ClearAppData::class.java,
                "launchApp" to ScenarioStep.LaunchApp::class.java,
                "forceStopApp" to ScenarioStep.ForceStopApp::class.java,
                "grantPermission" to ScenarioStep.GrantPermission::class.java,
                "wait" to ScenarioStep.Wait::class.java,
                "beginSection" to ScenarioStep.BeginSection::class.java,
                "endSection" to ScenarioStep.EndSection::class.java,
                "pressKey" to ScenarioStep.PressKey::class.java,
                "tapElement" to ScenarioStep.TapElement::class.java,
                "tapAt" to ScenarioStep.TapAt::class.java,
                "swipe" to ScenarioStep.Swipe::class.java,
                "enterText" to ScenarioStep.EnterText::class.java,
                "waitForElement" to ScenarioStep.WaitForElement::class.java,
                "waitForActivity" to ScenarioStep.WaitForActivity::class.java,
                "waitForLog" to ScenarioStep.WaitForLog::class.java,
                "waitForProcess" to ScenarioStep.WaitForProcess::class.java,
                "captureScreenshot" to ScenarioStep.CaptureScreenshot::class.java,
                "shell" to ScenarioStep.Shell::class.java
            )
        }
    }
}
