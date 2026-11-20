package devicemonitor.domain.releaseguard

const val RELEASE_GUARD_SCHEMA_VERSION = 1

enum class Platform { ANDROID }
enum class Transport { USB, TCP_IP, EMULATOR, UNKNOWN }
enum class FormFactor { PHONE, TABLET, TV, STB, EMULATOR, UNKNOWN }

enum class DeviceCapability {
    SHELL,
    PACKAGE_INSTALL,
    UI_HIERARCHY,
    TOUCH_INPUT,
    DPAD_INPUT,
    GFXINFO,
    PERFETTO,
    PSI,
    THERMAL,
    ROOT,
    PROCRANK,
    SCREEN_CAPTURE,
    BATTERY_STATS,
    LOGCAT,
    PLAYBACK_DIAGNOSTICS,
    MEDIA_CODEC_DIAGNOSTICS,
    AUDIO_DIAGNOSTICS,
    SURFACE_DIAGNOSTICS
}

data class TargetDevice(
    val id: String,
    val platform: Platform = Platform.ANDROID,
    val transport: Transport = Transport.UNKNOWN,
    val formFactor: FormFactor? = null,
    val properties: Map<String, String> = emptyMap(),
    val capabilities: Set<DeviceCapability> = emptySet()
)

data class UiSelector(
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val fallbackXPct: Double? = null,
    val fallbackYPct: Double? = null
) {
    fun isUsable(): Boolean =
        !resourceId.isNullOrBlank() || !contentDescription.isNullOrBlank() || !text.isNullOrBlank() ||
            (fallbackXPct != null && fallbackYPct != null && fallbackXPct in 0.0..1.0 && fallbackYPct in 0.0..1.0)
}

enum class LogicalKey {
    BACK,
    HOME,
    ENTER,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_CENTER,
    CHANNEL_UP,
    CHANNEL_DOWN,
    MEDIA_PLAY_PAUSE
}

sealed class ScenarioStep {
    abstract val name: String?

    data class InstallApk(val path: String, override val name: String? = null) : ScenarioStep()
    data class UninstallPackage(override val name: String? = null) : ScenarioStep()
    data class ClearAppData(override val name: String? = null) : ScenarioStep()
    data class LaunchApp(override val name: String? = null) : ScenarioStep()
    data class LaunchDeepLink(val uri: String, override val name: String? = null) : ScenarioStep()
    data class ForceStopApp(override val name: String? = null) : ScenarioStep()
    data class GrantPermission(val permission: String, override val name: String? = null) : ScenarioStep()
    data class Wait(val durationMs: Long, override val name: String? = null) : ScenarioStep()
    data class BeginSection(val section: String, override val name: String? = null) : ScenarioStep()
    data class EndSection(val section: String, override val name: String? = null) : ScenarioStep()
    data class PressKey(val key: LogicalKey, override val name: String? = null) : ScenarioStep()
    data class TapElement(val selector: UiSelector, override val name: String? = null) : ScenarioStep()
    data class TapAt(val xPct: Double, val yPct: Double, override val name: String? = null) : ScenarioStep()
    data class Swipe(
        val fromXPct: Double,
        val fromYPct: Double,
        val toXPct: Double,
        val toYPct: Double,
        val durationMs: Long = 300,
        override val name: String? = null
    ) : ScenarioStep()
    data class EnterText(val text: String, override val name: String? = null) : ScenarioStep()
    data class WaitForElement(
        val selector: UiSelector,
        val timeoutMs: Long = 15_000,
        override val name: String? = null
    ) : ScenarioStep()
    data class WaitForActivity(
        val activity: String,
        val timeoutMs: Long = 15_000,
        override val name: String? = null
    ) : ScenarioStep()
    data class WaitForLog(
        val pattern: String,
        val timeoutMs: Long = 15_000,
        override val name: String? = null
    ) : ScenarioStep()
    data class WaitForProcess(val timeoutMs: Long = 15_000, override val name: String? = null) : ScenarioStep()
    data class CaptureScreenshot(val fileName: String, override val name: String? = null) : ScenarioStep()
    data class Shell(
        val command: String,
        val timeoutMs: Long = 20_000,
        override val name: String? = null
    ) : ScenarioStep()

    fun requiredCapabilities(): Set<DeviceCapability> = when (this) {
        is InstallApk, is UninstallPackage -> setOf(DeviceCapability.SHELL, DeviceCapability.PACKAGE_INSTALL)
        is TapElement -> setOf(DeviceCapability.SHELL, DeviceCapability.UI_HIERARCHY, DeviceCapability.TOUCH_INPUT)
        is WaitForElement -> setOf(DeviceCapability.SHELL, DeviceCapability.UI_HIERARCHY)
        is TapAt, is Swipe, is EnterText -> setOf(DeviceCapability.SHELL, DeviceCapability.TOUCH_INPUT)
        is PressKey -> if (key in DPAD_KEYS) {
            setOf(DeviceCapability.SHELL, DeviceCapability.DPAD_INPUT)
        } else {
            setOf(DeviceCapability.SHELL)
        }
        is CaptureScreenshot -> setOf(DeviceCapability.SHELL, DeviceCapability.SCREEN_CAPTURE)
        is WaitForLog -> setOf(DeviceCapability.SHELL, DeviceCapability.LOGCAT)
        else -> setOf(DeviceCapability.SHELL)
    }

    companion object {
        private val DPAD_KEYS = setOf(
            LogicalKey.DPAD_UP,
            LogicalKey.DPAD_DOWN,
            LogicalKey.DPAD_LEFT,
            LogicalKey.DPAD_RIGHT,
            LogicalKey.DPAD_CENTER,
            LogicalKey.CHANNEL_UP,
            LogicalKey.CHANNEL_DOWN,
            LogicalKey.MEDIA_PLAY_PAUSE
        )
    }
}

data class MetricDefinition(
    val id: String,
    val unit: String? = null,
    val normalizedAcrossDevices: Boolean = false
)

enum class Aggregation { MINIMUM, MAXIMUM, MEAN, MEDIAN, P90, P95, COUNT, RATE, GROWTH_PERCENT }
enum class LimitDirection { MAXIMUM, MINIMUM }
enum class Severity { CRITICAL, WARNING }
enum class BudgetStatus { PASS, WARNING, FAIL, NOT_EVALUATED }
enum class Verdict { PASS, WARNING, FAIL, NOT_EVALUATED, CANCELLED }
enum class ExecutionState {
    DISCOVER,
    PREFLIGHT,
    PREPARE,
    WARM_UP,
    EXECUTE_AND_CAPTURE,
    FINALIZE_ARTIFACTS,
    EVALUATE,
    EXPORT,
    PASSED,
    WARNING,
    EVIDENCE_ONLY,
    FAILED,
    CANCELLED;

    fun isTerminal(): Boolean = this in setOf(PASSED, WARNING, EVIDENCE_ONLY, FAILED, CANCELLED)
}

enum class ScenarioMarkerType { ATTEMPT_START, STEP_START, STEP_END, SECTION_START, SECTION_END, ATTEMPT_END }
enum class ScenarioMarkerOutcome { RUNNING, PASS, FAIL, SKIP }

data class ScenarioMarker(
    val elapsedSec: Double,
    val attempt: Int,
    val stepIndex: Int,
    val label: String,
    val section: String? = null,
    val type: ScenarioMarkerType,
    val outcome: ScenarioMarkerOutcome = ScenarioMarkerOutcome.RUNNING,
    val detail: String? = null
)

data class PerformanceBudget(
    val id: String,
    val name: String,
    val metric: MetricDefinition,
    val aggregation: Aggregation,
    val direction: LimitDirection = LimitDirection.MAXIMUM,
    val section: String? = null,
    val absoluteLimit: Double? = null,
    val relativeRegressionLimitPct: Double? = null,
    val severity: Severity = Severity.CRITICAL,
    val required: Boolean = true
)

data class CaptureSettings(
    val metrics: Set<MetricDefinition> = emptySet(),
    val requiredCapabilities: Set<DeviceCapability> = emptySet(),
    val intervalSec: Double = 1.0,
    val configured: Boolean = false
)

data class ReleaseScenario(
    val schemaVersion: Int = RELEASE_GUARD_SCHEMA_VERSION,
    val id: String,
    val revision: Int = 1,
    val name: String,
    val packageName: String,
    val repetitions: Int = 1,
    val warmups: Int = 0,
    val steps: List<ScenarioStep>,
    val budgets: List<PerformanceBudget> = emptyList(),
    val capture: CaptureSettings = CaptureSettings()
) {
    fun requiredCapabilities(): Set<DeviceCapability> =
        buildSet {
            add(DeviceCapability.SHELL)
            add(DeviceCapability.LOGCAT)
            steps.forEach { addAll(it.requiredCapabilities()) }
            addAll(capture.requiredCapabilities)
            capture.metrics.forEach { addAll(it.requiredCapabilities()) }
            budgets.forEach { addAll(it.metric.requiredCapabilities()) }
        }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion != RELEASE_GUARD_SCHEMA_VERSION) errors += "Unsupported schema version $schemaVersion"
        if (id.isBlank()) errors += "Scenario id is required"
        if (revision < 1) errors += "Scenario revision must be at least 1"
        if (name.isBlank()) errors += "Scenario name is required"
        if (packageName.isBlank()) errors += "Package name is required"
        if (repetitions !in 1..1_000) errors += "Repetitions must be between 1 and 1000"
        if (warmups !in 0..20) errors += "Warmups must be between 0 and 20"
        if (capture.intervalSec !in 0.25..60.0) errors += "Capture interval must be between 0.25 and 60 seconds"
        if (steps.isEmpty()) errors += "Scenario must contain at least one step"

        var openSection: String? = null
        val sectionNames = mutableSetOf<String>()
        steps.forEachIndexed { index, step ->
            val prefix = "Step ${index + 1}"
            when (step) {
                is ScenarioStep.InstallApk -> if (step.path.isBlank()) errors += "$prefix APK path is required"
                is ScenarioStep.LaunchDeepLink -> validateDeepLink(step.uri, prefix, errors)
                is ScenarioStep.GrantPermission -> if (step.permission.isBlank()) errors += "$prefix permission is required"
                is ScenarioStep.Wait -> if (step.durationMs !in 1..MAX_TIMEOUT_MS) errors += "$prefix wait must be between 1 and $MAX_TIMEOUT_MS ms"
                is ScenarioStep.BeginSection -> {
                    if (step.section.isBlank()) errors += "$prefix section name is required"
                    else if (openSection != null) errors += "$prefix cannot begin '${step.section}' while '$openSection' is open"
                    else if (!sectionNames.add(step.section)) errors += "$prefix duplicates section '${step.section}'"
                    else openSection = step.section
                }
                is ScenarioStep.EndSection -> {
                    if (step.section.isBlank()) errors += "$prefix section name is required"
                    else if (openSection != step.section) errors += "$prefix ends '${step.section}' but '${openSection ?: "no section"}' is open"
                    else openSection = null
                }
                is ScenarioStep.TapElement -> validateSelector(step.selector, prefix, errors)
                is ScenarioStep.TapAt -> validatePoint(step.xPct, step.yPct, prefix, errors)
                is ScenarioStep.Swipe -> {
                    validatePoint(step.fromXPct, step.fromYPct, "$prefix start", errors)
                    validatePoint(step.toXPct, step.toYPct, "$prefix end", errors)
                    if (step.durationMs !in 1..MAX_TIMEOUT_MS) errors += "$prefix swipe duration must be between 1 and $MAX_TIMEOUT_MS ms"
                }
                is ScenarioStep.EnterText -> if (step.text.isEmpty()) errors += "$prefix text is required"
                is ScenarioStep.WaitForElement -> {
                    validateSelector(step.selector, prefix, errors)
                    validateTimeout(step.timeoutMs, prefix, errors)
                }
                is ScenarioStep.WaitForActivity -> {
                    if (step.activity.isBlank()) errors += "$prefix activity is required"
                    validateTimeout(step.timeoutMs, prefix, errors)
                }
                is ScenarioStep.WaitForLog -> {
                    if (step.pattern.isBlank()) errors += "$prefix log pattern is required"
                    validateTimeout(step.timeoutMs, prefix, errors)
                }
                is ScenarioStep.WaitForProcess -> validateTimeout(step.timeoutMs, prefix, errors)
                is ScenarioStep.CaptureScreenshot -> {
                    if (step.fileName.isBlank()) errors += "$prefix screenshot file name is required"
                    else if (java.io.File(step.fileName).name != step.fileName || step.fileName in setOf(".", "..")) {
                        errors += "$prefix screenshot file name must not contain a path"
                    }
                }
                is ScenarioStep.Shell -> {
                    if (step.command.isBlank()) errors += "$prefix shell command is required"
                    validateTimeout(step.timeoutMs, prefix, errors)
                }
                else -> Unit
            }
        }
        if (openSection != null) errors += "Section '$openSection' is not closed"

        val budgetIds = mutableSetOf<String>()
        budgets.forEachIndexed { index, budget ->
            val prefix = "Budget ${index + 1}"
            if (budget.id.isBlank()) errors += "$prefix id is required"
            else if (!budgetIds.add(budget.id)) errors += "$prefix duplicates id '${budget.id}'"
            if (budget.name.isBlank()) errors += "$prefix name is required"
            if (budget.metric.id.isBlank()) errors += "$prefix metric is required"
            if (budget.absoluteLimit == null && budget.relativeRegressionLimitPct == null) errors += "$prefix requires an absolute or relative limit"
            if (budget.absoluteLimit?.isFinite() == false) errors += "$prefix absolute limit must be finite"
            if (budget.relativeRegressionLimitPct?.let { !it.isFinite() || it < 0.0 } == true) {
                errors += "$prefix relative regression limit must be a finite non-negative number"
            }
        }
        return errors
    }

    private fun validateSelector(selector: UiSelector, prefix: String, errors: MutableList<String>) {
        if (!selector.isUsable()) errors += "$prefix requires a resource id, accessibility description, text, or valid coordinate fallback"
        val oneCoordinateOnly = (selector.fallbackXPct == null) != (selector.fallbackYPct == null)
        if (oneCoordinateOnly) errors += "$prefix coordinate fallback requires both x and y"
    }

    private fun validatePoint(x: Double, y: Double, prefix: String, errors: MutableList<String>) {
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) {
            errors += "$prefix coordinates must be between 0 and 1"
        }
    }

    private fun validateTimeout(timeoutMs: Long, prefix: String, errors: MutableList<String>) {
        if (timeoutMs !in 1..MAX_TIMEOUT_MS) errors += "$prefix timeout must be between 1 and $MAX_TIMEOUT_MS ms"
    }

    private fun validateDeepLink(value: String, prefix: String, errors: MutableList<String>) {
        if (value.isBlank()) {
            errors += "$prefix deep link URI is required"
            return
        }
        if (value.length > MAX_DEEP_LINK_LENGTH) {
            errors += "$prefix deep link URI must not exceed $MAX_DEEP_LINK_LENGTH characters"
            return
        }
        if (value.any(Char::isISOControl)) {
            errors += "$prefix deep link URI must not contain control characters"
            return
        }
        val uri = try {
            java.net.URI(value)
        } catch (_: java.net.URISyntaxException) {
            errors += "$prefix deep link URI is invalid; encode spaces and special characters"
            return
        }
        if (!uri.isAbsolute || uri.scheme.isNullOrBlank()) {
            errors += "$prefix deep link URI must be absolute and include a scheme"
        }
    }

    companion object {
        private const val MAX_TIMEOUT_MS = 600_000L
        private const val MAX_DEEP_LINK_LENGTH = 4_096
    }
}

fun MetricDefinition.requiredCapabilities(): Set<DeviceCapability> = when (id.lowercase()) {
    "jank_pct", "frame_input_ms", "frame_anim_ms", "frame_layout_ms", "frame_draw_ms" -> setOf(DeviceCapability.GFXINFO)
    "cpu_temp_c" -> setOf(DeviceCapability.THERMAL)
    "psi_cpu", "psi_memory", "psi_io" -> setOf(DeviceCapability.PSI)
    "anr_count", "crash_count", "direct_reclaim_per_sec" -> setOf(DeviceCapability.LOGCAT)
    "playback_state", "tunnel_mode" -> setOf(DeviceCapability.PLAYBACK_DIAGNOSTICS)
    else -> emptySet()
}

data class MetricSeries(
    val metricId: String,
    val section: String? = null,
    val values: List<Double>,
    val durationSec: Double? = null
)

data class AttemptSummary(
    val attempt: Int,
    val successful: Boolean,
    val metrics: List<MetricSeries> = emptyList(),
    val error: String? = null
)

data class BaselineIdentity(
    val model: String,
    val androidVersion: String,
    val firmware: String,
    val scenarioId: String,
    val scenarioRevision: Int
) {
    fun exactlyMatches(other: BaselineIdentity): Boolean = this == other
    fun sameScenario(other: BaselineIdentity): Boolean =
        scenarioId == other.scenarioId && scenarioRevision == other.scenarioRevision
}

data class BaselineRevision(
    val id: String,
    val identity: BaselineIdentity,
    val attempts: List<AttemptSummary>
)

data class BudgetResult(
    val budgetId: String,
    val name: String,
    val status: BudgetStatus,
    val value: Double? = null,
    val absoluteLimit: Double? = null,
    val baselineValue: Double? = null,
    val relativeRegressionPct: Double? = null,
    val sampleCount: Int = 0,
    val contributingAttempts: List<Int> = emptyList(),
    val message: String
)

data class ReleaseEvaluation(
    val verdict: Verdict,
    val results: List<BudgetResult>,
    val executionErrors: List<String> = emptyList()
)
