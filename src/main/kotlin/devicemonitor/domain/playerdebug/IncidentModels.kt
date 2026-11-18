package devicemonitor.domain.playerdebug

enum class IncidentSeverity { INFO, WARNING, CRITICAL }
enum class IncidentStatus { OPEN, RESOLVED, DISMISSED }

data class IncidentEvidence(
    val eventSequences: List<Long> = emptyList(),
    val artifacts: List<EvidenceReference> = emptyList(),
    val explanation: String
) {
    init {
        require(eventSequences.all { it >= 0 }) { "Incident event sequence cannot be negative" }
        require(explanation.isNotBlank()) { "Incident evidence explanation is required" }
    }
}

data class PlayerIncident(
    val id: String,
    val ruleId: String,
    val ruleVersion: Int,
    val title: String,
    val severity: IncidentSeverity,
    val status: IncidentStatus = IncidentStatus.OPEN,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val viewingSessionId: String? = null,
    val instanceIds: Set<String> = emptySet(),
    val handoffId: String? = null,
    val confidence: Double,
    val evidence: IncidentEvidence,
    val suggestedChecks: List<String> = emptyList(),
    val fingerprint: String? = null,
    val correlationOnly: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Incident id is required" }
        require(ruleId.isNotBlank()) { "Incident rule id is required" }
        require(ruleVersion >= 1) { "Incident rule version must be positive" }
        require(title.isNotBlank()) { "Incident title is required" }
        require(startedAtNs >= 0) { "Incident start cannot be negative" }
        require(endedAtNs == null || endedAtNs >= startedAtNs) { "Incident cannot end before it starts" }
        require(confidence in 0.0..1.0) { "Incident confidence must be between 0 and 1" }
    }
}
