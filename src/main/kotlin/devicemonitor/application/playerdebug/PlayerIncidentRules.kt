package devicemonitor.application.playerdebug

data class PlayerRuleThresholds(
    val startupTimeoutNs: Long = 8_000_000_000L,
    val bufferTimeoutNs: Long = 5_000_000_000L,
    val slowHandoffNs: Long = 4_000_000_000L,
    val sourceReleaseGraceNs: Long = 3_000_000_000L,
    val fallbackLoopWindowNs: Long = 60_000_000_000L,
    val droppedFrames: Double = 30.0,
    val decoderStalls: Double = 3.0,
    val pressureCorrelationWindowNs: Long = 5_000_000_000L
) {
    init {
        require(startupTimeoutNs > 0 && bufferTimeoutNs > 0 && slowHandoffNs > 0) { "Playback thresholds must be positive" }
        require(sourceReleaseGraceNs > 0 && fallbackLoopWindowNs > 0) { "Handoff thresholds must be positive" }
        require(droppedFrames >= 0 && decoderStalls >= 0) { "Decoder thresholds cannot be negative" }
        require(pressureCorrelationWindowNs > 0) { "Correlation window must be positive" }
    }
}

data class PlayerRuleProfile(
    val generic: PlayerRuleThresholds = PlayerRuleThresholds(),
    val byDelivery: Map<String, PlayerRuleThresholds> = mapOf(
        "ott" to PlayerRuleThresholds(startupTimeoutNs = 10_000_000_000L, bufferTimeoutNs = 6_000_000_000L),
        "multicast" to PlayerRuleThresholds(startupTimeoutNs = 6_000_000_000L, bufferTimeoutNs = 3_000_000_000L),
        "dvb-c" to PlayerRuleThresholds(startupTimeoutNs = 5_000_000_000L, bufferTimeoutNs = 3_000_000_000L),
        "dvb-t" to PlayerRuleThresholds(startupTimeoutNs = 5_000_000_000L, bufferTimeoutNs = 3_000_000_000L)
    )
) {
    fun forDelivery(delivery: String?): PlayerRuleThresholds =
        delivery?.lowercase()?.let(byDelivery::get) ?: generic
}
