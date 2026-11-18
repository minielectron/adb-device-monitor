package devicemonitor.domain.playerdebug

const val PLAYER_DEBUG_SCHEMA_VERSION = 1
const val OPTD_SUPPORTED_MAJOR = 1

enum class EvidenceSource { APP_REPORTED, DEVICE_MEASURED, LOG_PARSED, ENGINE_INFERRED }
enum class EvidenceAvailability { REPORTED, MEASURED, INFERRED, UNAVAILABLE }
enum class ConfidenceClass { HIGH, MEDIUM, LOW }
enum class PlayerRole { PRIMARY, CANDIDATE, FALLBACK, UNKNOWN }
enum class HandoffResult { SUCCEEDED, FAILED, CANCELLED, LOOPED, UNKNOWN }
enum class PlayerCaptureState {
    SELECT,
    PROBE,
    READY,
    CAPTURING,
    FINALIZING,
    ANALYZING,
    COMPLETE,
    PARTIAL,
    CANCELLED,
    FAILED;

    fun isTerminal(): Boolean = this in setOf(COMPLETE, PARTIAL, CANCELLED, FAILED)
}

data class EvidenceReference(
    val artifact: String,
    val offset: Long? = null,
    val line: Long? = null,
    val description: String? = null
) {
    init {
        require(artifact.isNotBlank()) { "Evidence artifact is required" }
        require(offset == null || offset >= 0) { "Evidence offset cannot be negative" }
        require(line == null || line >= 1) { "Evidence line must be positive" }
    }
}

data class PlaybackEvent(
    val sequence: Long,
    val schema: String,
    val type: String,
    val timeNs: Long,
    val deviceTime: String? = null,
    val packageName: String? = null,
    val pid: Int? = null,
    val tid: Int? = null,
    val processEpochId: String? = null,
    val viewingSessionId: String? = null,
    val instanceId: String? = null,
    val eventId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val measurements: Map<String, Double> = emptyMap(),
    val source: EvidenceSource,
    val confidence: Double,
    val evidence: List<EvidenceReference> = emptyList(),
    val rawJson: String? = null,
    val late: Boolean = false,
    val redacted: Boolean = false
) {
    init {
        require(sequence >= 0) { "Event sequence cannot be negative" }
        require(schema.isNotBlank()) { "Event schema is required" }
        require(type.isNotBlank()) { "Event type is required" }
        require(timeNs >= 0) { "Event time cannot be negative" }
        require(confidence in 0.0..1.0) { "Event confidence must be between 0 and 1" }
        require(pid == null || pid >= 0) { "PID cannot be negative" }
        require(tid == null || tid >= 0) { "TID cannot be negative" }
    }

    val confidenceClass: ConfidenceClass
        get() = when {
            confidence >= 0.8 -> ConfidenceClass.HIGH
            confidence >= 0.5 -> ConfidenceClass.MEDIUM
            else -> ConfidenceClass.LOW
        }
}

data class ProcessEpoch(
    val id: String,
    val packageName: String,
    val pid: Int?,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val adapterName: String? = null,
    val adapterVersion: String? = null,
    val capabilities: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "Process epoch id is required" }
        require(packageName.isNotBlank()) { "Package name is required" }
        require(pid == null || pid >= 0) { "PID cannot be negative" }
        require(startedAtNs >= 0) { "Process epoch start cannot be negative" }
        require(endedAtNs == null || endedAtNs >= startedAtNs) { "Process epoch cannot end before it starts" }
    }
}

data class PlayerInstance(
    val id: String,
    val viewingSessionId: String,
    val family: String? = null,
    val implementation: String? = null,
    val delivery: String? = null,
    val role: PlayerRole = PlayerRole.UNKNOWN,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val capabilities: Set<String> = emptySet(),
    val synthetic: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Player instance id is required" }
        require(viewingSessionId.isNotBlank()) { "Viewing session id is required" }
        require(startedAtNs >= 0) { "Player start cannot be negative" }
        require(endedAtNs == null || endedAtNs >= startedAtNs) { "Player cannot end before it starts" }
    }
}

data class PlayerStateInterval(
    val instanceId: String,
    val state: String,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val source: EvidenceSource,
    val confidence: Double
) {
    init {
        require(instanceId.isNotBlank()) { "State interval player id is required" }
        require(state.isNotBlank()) { "State is required" }
        require(startedAtNs >= 0) { "State start cannot be negative" }
        require(endedAtNs == null || endedAtNs >= startedAtNs) { "State cannot end before it starts" }
        require(confidence in 0.0..1.0) { "State confidence must be between 0 and 1" }
    }
}

data class PlayerHandoff(
    val id: String,
    val viewingSessionId: String,
    val sourceInstanceId: String? = null,
    val targetInstanceId: String? = null,
    val requestedAtNs: Long,
    val activatedAtNs: Long? = null,
    val sourceReleasedAtNs: Long? = null,
    val reason: String? = null,
    val result: HandoffResult = HandoffResult.UNKNOWN,
    val source: EvidenceSource,
    val confidence: Double
) {
    init {
        require(id.isNotBlank()) { "Handoff id is required" }
        require(viewingSessionId.isNotBlank()) { "Viewing session id is required" }
        require(sourceInstanceId != null || targetInstanceId != null) { "Handoff requires a source or target player" }
        require(requestedAtNs >= 0) { "Handoff request cannot be negative" }
        require(activatedAtNs == null || activatedAtNs >= requestedAtNs) { "Handoff activation cannot precede its request" }
        require(sourceReleasedAtNs == null || sourceReleasedAtNs >= requestedAtNs) { "Source release cannot precede handoff request" }
        require(confidence in 0.0..1.0) { "Handoff confidence must be between 0 and 1" }
    }

    val overlapNs: Long?
        get() = if (activatedAtNs != null && sourceReleasedAtNs != null) {
            (sourceReleasedAtNs - activatedAtNs).coerceAtLeast(0)
        } else null
}

data class ViewingSession(
    val id: String,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val contentLabel: String? = null,
    val playerInstances: List<PlayerInstance> = emptyList(),
    val stateIntervals: List<PlayerStateInterval> = emptyList(),
    val handoffs: List<PlayerHandoff> = emptyList(),
    val synthetic: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Viewing session id is required" }
        require(startedAtNs >= 0) { "Viewing session start cannot be negative" }
        require(endedAtNs == null || endedAtNs >= startedAtNs) { "Viewing session cannot end before it starts" }
        require(playerInstances.all { it.viewingSessionId == id }) { "Player instance belongs to another viewing session" }
        val playerIds = playerInstances.map { it.id }.toSet()
        require(stateIntervals.all { it.instanceId in playerIds }) { "State interval references an unknown player" }
        require(handoffs.all {
            it.viewingSessionId == id &&
                (it.sourceInstanceId == null || it.sourceInstanceId in playerIds) &&
                (it.targetInstanceId == null || it.targetInstanceId in playerIds)
        }) { "Handoff references another session or an unknown player" }
    }
}

data class AdapterCapabilities(
    val schema: String,
    val adapterName: String,
    val adapterVersion: String? = null,
    val eventTypes: Set<String> = emptySet(),
    val metricNames: Set<String> = emptySet(),
    val playerFamilies: Set<String> = emptySet()
) {
    init {
        require(schema.isNotBlank()) { "Capability schema is required" }
        require(adapterName.isNotBlank()) { "Adapter name is required" }
    }
}

data class PlayerDebugCapture(
    val schemaVersion: Int = PLAYER_DEBUG_SCHEMA_VERSION,
    val id: String,
    val deviceId: String,
    val packageName: String,
    val state: PlayerCaptureState,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val processEpochs: List<ProcessEpoch> = emptyList(),
    val viewingSessions: List<ViewingSession> = emptyList()
) {
    init {
        require(schemaVersion == PLAYER_DEBUG_SCHEMA_VERSION) { "Unsupported Player Debugger schema version $schemaVersion" }
        require(id.isNotBlank()) { "Capture id is required" }
        require(deviceId.isNotBlank()) { "Device id is required" }
        require(packageName.isNotBlank()) { "Package name is required" }
        require(startedAtNs >= 0) { "Capture start cannot be negative" }
        require(endedAtNs == null || endedAtNs >= startedAtNs) { "Capture cannot end before it starts" }
        require(!state.isTerminal() || endedAtNs != null) { "Terminal capture requires an end time" }
    }
}

data class PlayerSessionGraph(
    val processEpochs: List<ProcessEpoch>,
    val viewingSessions: List<ViewingSession>,
    val events: List<PlaybackEvent>,
    val duplicateEvents: Int = 0,
    val lateEvents: Int = 0
)
