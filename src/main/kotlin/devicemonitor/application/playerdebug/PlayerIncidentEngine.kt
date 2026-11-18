package devicemonitor.application.playerdebug

import devicemonitor.domain.playerdebug.*

class PlayerIncidentEngine(private val profile: PlayerRuleProfile = PlayerRuleProfile()) {
    fun analyze(graph: PlayerSessionGraph, captureEndNs: Long? = null): List<PlayerIncident> {
        val end = captureEndNs ?: graph.events.maxOfOrNull { it.timeNs }
            ?: graph.viewingSessions.maxOfOrNull { it.endedAtNs ?: it.startedAtNs } ?: 0L
        val incidents = mutableListOf<PlayerIncident>()
        var sequence = 0

        fun add(
            rule: String,
            title: String,
            severity: IncidentSeverity,
            at: Long,
            session: String? = null,
            instances: Set<String> = emptySet(),
            handoff: String? = null,
            confidence: Double,
            events: List<PlaybackEvent>,
            explanation: String,
            checks: List<String>,
            fingerprint: String? = null,
            correlationOnly: Boolean = false,
            endedAtNs: Long? = null
        ) {
            sequence++
            incidents += PlayerIncident(
                id = "$rule:$at:$sequence",
                ruleId = rule,
                ruleVersion = 1,
                title = title,
                severity = severity,
                startedAtNs = at,
                endedAtNs = endedAtNs,
                viewingSessionId = session,
                instanceIds = instances,
                handoffId = handoff,
                confidence = confidence,
                evidence = IncidentEvidence(
                    eventSequences = events.map { it.sequence }.distinct(),
                    artifacts = events.flatMap { it.evidence }.distinct(),
                    explanation = explanation
                ),
                suggestedChecks = checks,
                fingerprint = fingerprint,
                correlationOnly = correlationOnly
            )
        }

        val errors = graph.events.filter { event ->
            event.type == "error.reported" || event.type.endsWith(".error") ||
                event.type in setOf("app.anr", "app.crash", "app.native_crash")
        }
        for (event in errors) {
            val fatal = event.type in setOf("app.anr", "app.crash", "app.native_crash")
            add(
                "playback_error", if (fatal) "Application failure during playback" else "Playback error reported",
                if (fatal) IncidentSeverity.CRITICAL else IncidentSeverity.WARNING,
                event.timeNs, event.viewingSessionId, setOfNotNull(event.instanceId), confidence = event.confidence,
                events = listOf(event), explanation = "${event.type} was ${sourceLabel(event.source)}.",
                checks = listOf("Inspect the linked error and surrounding player state", "Compare the same fingerprint with the last good capture"),
                fingerprint = event.attributes["fingerprint"]
            )
        }

        for (session in graph.viewingSessions) {
            val players = session.playerInstances.associateBy { it.id }
            for (player in session.playerInstances) {
                val thresholds = profile.forDelivery(player.delivery)
                val playerEnd = player.endedAtNs ?: session.endedAtNs ?: end
                val firstFrame = graph.events.firstOrNull {
                    it.instanceId == player.id && it.type in VIDEO_AVAILABLE && it.timeNs >= player.startedAtNs
                }
                if (firstFrame == null && playerEnd - player.startedAtNs >= thresholds.startupTimeoutNs) {
                    add(
                        "startup_timeout", "No first frame before startup deadline", IncidentSeverity.CRITICAL,
                        player.startedAtNs + thresholds.startupTimeoutNs, session.id, setOf(player.id), confidence = if (player.synthetic) 0.65 else 0.95,
                        events = eventsFor(graph, player.id, player.startedAtNs, playerEnd),
                        explanation = "No video-available or first-frame evidence appeared within ${seconds(thresholds.startupTimeoutNs)} seconds.",
                        checks = listOf("Check source load, DRM, decoder and surface events", "Verify whether audio started without video")
                    )
                }

                val playing = session.stateIntervals.filter { it.instanceId == player.id && it.state.equals("playing", true) }
                for (interval in playing) {
                    val intervalEnd = interval.endedAtNs ?: playerEnd
                    val video = graph.events.any { it.instanceId == player.id && it.type in VIDEO_AVAILABLE && it.timeNs in player.startedAtNs..intervalEnd }
                    if (!video && intervalEnd - interval.startedAtNs >= thresholds.startupTimeoutNs) {
                        add(
                            "black_screen_candidate", "Playback reports playing without video evidence", IncidentSeverity.CRITICAL,
                            interval.startedAtNs, session.id, setOf(player.id), confidence = minOf(interval.confidence, 0.8),
                            events = eventsFor(graph, player.id, interval.startedAtNs, intervalEnd),
                            explanation = "The player remained in playing state but no first-frame or video-available evidence was observed.",
                            checks = listOf("Inspect surface attachment and decoder output", "Check whether audio continued during the interval"),
                            endedAtNs = intervalEnd
                        )
                    }
                }

                for (interval in session.stateIntervals.filter { it.instanceId == player.id && it.state.equals("buffering", true) }) {
                    val intervalEnd = interval.endedAtNs ?: playerEnd
                    if (intervalEnd - interval.startedAtNs >= thresholds.bufferTimeoutNs) {
                        add(
                            "prolonged_buffering", "Prolonged buffering", IncidentSeverity.WARNING,
                            interval.startedAtNs, session.id, setOf(player.id), confidence = interval.confidence,
                            events = eventsFor(graph, player.id, interval.startedAtNs, intervalEnd),
                            explanation = "Buffering lasted ${seconds(intervalEnd - interval.startedAtNs)} seconds; the ${player.delivery ?: "generic"} threshold is ${seconds(thresholds.bufferTimeoutNs)} seconds.",
                            checks = listOf("Inspect throughput and bitrate changes", "Check decoder stalls and memory pressure"), endedAtNs = intervalEnd
                        )
                    }
                }

                detectAudioVideoAvailability(graph, session, player, playerEnd, ::add)
            }

            for (handoff in session.handoffs) {
                val target = handoff.targetInstanceId?.let(players::get)
                val thresholds = profile.forDelivery(target?.delivery)
                val activation = handoff.activatedAtNs
                if (handoff.result == HandoffResult.FAILED || (activation == null && end - handoff.requestedAtNs >= thresholds.slowHandoffNs)) {
                    add(
                        "failed_handoff", "Player handoff failed", IncidentSeverity.CRITICAL,
                        handoff.requestedAtNs, session.id, setOfNotNull(handoff.sourceInstanceId, handoff.targetInstanceId), handoff.id,
                        handoff.confidence, handoffEvents(graph, handoff),
                        "The requested player handoff did not produce target activation within ${seconds(thresholds.slowHandoffNs)} seconds.",
                        listOf("Inspect the target player's initialization and first error", "Verify source release did not occur before target readiness")
                    )
                } else if (activation != null && activation - handoff.requestedAtNs > thresholds.slowHandoffNs) {
                    add(
                        "slow_handoff", "Player handoff was slow", IncidentSeverity.WARNING,
                        handoff.requestedAtNs, session.id, setOfNotNull(handoff.sourceInstanceId, handoff.targetInstanceId), handoff.id,
                        handoff.confidence, handoffEvents(graph, handoff),
                        "Target activation took ${seconds(activation - handoff.requestedAtNs)} seconds.",
                        listOf("Compare source load, DRM and decoder preparation phases")
                    )
                }
                if (activation != null && handoff.sourceInstanceId != null && handoff.sourceReleasedAtNs == null && end - activation > thresholds.sourceReleaseGraceNs) {
                    add(
                        "zombie_player", "Replaced player remained active", IncidentSeverity.WARNING,
                        activation, session.id, setOfNotNull(handoff.sourceInstanceId, handoff.targetInstanceId), handoff.id,
                        handoff.confidence, handoffEvents(graph, handoff),
                        "The target activated, but source release was not observed within ${seconds(thresholds.sourceReleaseGraceNs)} seconds.",
                        listOf("Verify stop/release is called on the source", "Check decoder and surface ownership after handoff")
                    )
                }
            }
            detectFallbackLoop(graph, session, players, ::add)
        }

        detectDecoderStress(graph, ::add)
        correlatePressure(graph, errors, ::add)
        return incidents.sortedWith(compareByDescending<PlayerIncident> { it.severity.ordinal }.thenBy { it.startedAtNs }.thenBy { it.ruleId })
    }

    private fun detectAudioVideoAvailability(
        graph: PlayerSessionGraph,
        session: ViewingSession,
        player: PlayerInstance,
        playerEnd: Long,
        add: IncidentAdder
    ) {
        val events = graph.events.filter { it.instanceId == player.id && it.timeNs in player.startedAtNs..playerEnd }
        val audio = events.firstOrNull { it.type == "audio.available" }
        val videoUnavailable = events.firstOrNull { it.type == "render.video_unavailable" }
        val video = events.firstOrNull { it.type in VIDEO_AVAILABLE }
        val audioUnavailable = events.firstOrNull { it.type == "audio.unavailable" }
        if (audio != null && videoUnavailable != null && (video == null || video.timeNs < videoUnavailable.timeNs)) {
            add("audio_only", "Audio available while video is unavailable", IncidentSeverity.WARNING, videoUnavailable.timeNs,
                session.id, setOf(player.id), null, minOf(audio.confidence, videoUnavailable.confidence), listOf(audio, videoUnavailable),
                "Audio availability and explicit video-unavailable evidence overlap.", listOf("Inspect video decoder and surface state"), null, false, null)
        }
        if (video != null && audioUnavailable != null && audioUnavailable.timeNs >= video.timeNs) {
            add("silent_video", "Video available while audio is unavailable", IncidentSeverity.WARNING, audioUnavailable.timeNs,
                session.id, setOf(player.id), null, minOf(video.confidence, audioUnavailable.confidence), listOf(video, audioUnavailable),
                "Video availability is followed by explicit audio-unavailable evidence.", listOf("Inspect audio track selection, focus and AudioTrack errors"), null, false, null)
        }
    }

    private fun detectFallbackLoop(graph: PlayerSessionGraph, session: ViewingSession, players: Map<String, PlayerInstance>, add: IncidentAdder) {
        val ordered = session.handoffs.sortedBy { it.requestedAtNs }
        if (ordered.size < 3) return
        for (window in ordered.windowed(3)) {
            val first = window.first(); val last = window.last()
            if (last.requestedAtNs - first.requestedAtNs > profile.generic.fallbackLoopWindowNs) continue
            val deliveries = window.flatMap { h -> listOfNotNull(h.sourceInstanceId?.let(players::get)?.delivery, h.targetInstanceId?.let(players::get)?.delivery) }.toSet()
            if (deliveries.size == 2) {
                add("fallback_loop", "Repeated player fallback loop", IncidentSeverity.CRITICAL, first.requestedAtNs,
                    session.id, window.flatMap { listOfNotNull(it.sourceInstanceId, it.targetInstanceId) }.toSet(), last.id,
                    window.minOf { it.confidence }, window.flatMap { handoffEvents(graph, it) },
                    "Three handoffs cycled between ${deliveries.joinToString(" and ")} within ${seconds(last.requestedAtNs - first.requestedAtNs)} seconds.",
                    listOf("Inspect the first failure in the loop", "Apply a retry/fallback circuit breaker"), null, false, last.requestedAtNs)
                return
            }
        }
    }

    private fun detectDecoderStress(graph: PlayerSessionGraph, add: IncidentAdder) {
        for (event in graph.events.filter { it.type == "metric.sample" }) {
            val dropped = event.measurements["droppedFrames"] ?: event.measurements["videoFramesDropped"]
            val stalls = event.measurements["decoderStalls"] ?: event.measurements["decoderStallCount"]
            if ((dropped ?: 0.0) >= profile.generic.droppedFrames || (stalls ?: 0.0) >= profile.generic.decoderStalls) {
                add("decoder_stress", "Decoder stress exceeded threshold", IncidentSeverity.WARNING, event.timeNs,
                    event.viewingSessionId, setOfNotNull(event.instanceId), null, event.confidence, listOf(event),
                    "Reported dropped frames or decoder stalls exceeded the local rule profile.",
                    listOf("Correlate with CPU, thermal state and bitrate", "Inspect decoder overlap during handoff"), null, false, null)
            }
        }
    }

    private fun correlatePressure(graph: PlayerSessionGraph, errors: List<PlaybackEvent>, add: IncidentAdder) {
        val pressure = graph.events.filter { it.type == "resource.pressure" || (it.measurements["psiMemory"] ?: 0.0) >= 1.0 }
        for (error in errors) {
            val nearby = pressure.minByOrNull { kotlin.math.abs(it.timeNs - error.timeNs) } ?: continue
            if (kotlin.math.abs(nearby.timeNs - error.timeNs) <= profile.generic.pressureCorrelationWindowNs) {
                add("resource_pressure_correlation", "Resource pressure near playback failure", IncidentSeverity.INFO,
                    minOf(nearby.timeNs, error.timeNs), error.viewingSessionId, setOfNotNull(error.instanceId), null,
                    minOf(nearby.confidence, error.confidence), listOf(nearby, error),
                    "Memory/CPU pressure occurred near the failure; this is correlation, not proof of root cause.",
                    listOf("Compare with a capture without pressure", "Inspect process memory and PSI trends"), null, true, maxOf(nearby.timeNs, error.timeNs))
            }
        }
    }

    private fun eventsFor(graph: PlayerSessionGraph, instance: String, start: Long, end: Long) =
        graph.events.filter { it.instanceId == instance && it.timeNs in start..end }

    private fun handoffEvents(graph: PlayerSessionGraph, handoff: PlayerHandoff) = graph.events.filter {
        it.attributes["handoffId"] == handoff.id || it.eventId == handoff.id ||
            it.instanceId == handoff.sourceInstanceId || it.instanceId == handoff.targetInstanceId
    }

    private fun sourceLabel(source: EvidenceSource) = when (source) {
        EvidenceSource.APP_REPORTED -> "reported by the application"
        EvidenceSource.DEVICE_MEASURED -> "measured on the device"
        EvidenceSource.LOG_PARSED -> "parsed from device logs"
        EvidenceSource.ENGINE_INFERRED -> "inferred by the incident engine"
    }

    private fun seconds(ns: Long): String = "%.1f".format(ns / 1_000_000_000.0)

    companion object {
        private val VIDEO_AVAILABLE = setOf("render.first_frame", "render.video_available")
    }
}

private typealias IncidentAdder = (
    rule: String,
    title: String,
    severity: IncidentSeverity,
    at: Long,
    session: String?,
    instances: Set<String>,
    handoff: String?,
    confidence: Double,
    events: List<PlaybackEvent>,
    explanation: String,
    checks: List<String>,
    fingerprint: String?,
    correlationOnly: Boolean,
    endedAtNs: Long?
) -> Unit

private fun <T : Any> setOfNotNull(vararg values: T?): Set<T> = values.filterNotNull().toSet()
