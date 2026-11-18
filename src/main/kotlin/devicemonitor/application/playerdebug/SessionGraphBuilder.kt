package devicemonitor.application.playerdebug

import devicemonitor.domain.playerdebug.*

class SessionGraphBuilder {
    fun build(replay: EventReplayResult): PlayerSessionGraph {
        val sessions = linkedMapOf<String, MutableSession>()
        val instanceSessions = mutableMapOf<String, String>()
        val currentSessionByPackage = mutableMapOf<String, String>()
        var syntheticSessionNumber = 0

        fun sessionFor(event: PlaybackEvent): MutableSession {
            val packageKey = event.packageName ?: "unknown"
            val explicit = event.viewingSessionId
                ?: event.instanceId?.let { instanceSessions[it] }
            val id = explicit ?: currentSessionByPackage[packageKey] ?: run {
                syntheticSessionNumber++
                "passive-view-$syntheticSessionNumber"
            }
            val session = sessions.getOrPut(id) {
                MutableSession(id, event.timeNs, synthetic = explicit == null)
            }
            session.startedAtNs = minOf(session.startedAtNs, event.timeNs)
            currentSessionByPackage[packageKey] = id
            return session
        }

        for (event in replay.events) {
            val session = sessionFor(event)
            when (event.type) {
                "session.started" -> Unit
                "session.content_changed" -> session.contentLabel = event.attributes["contentLabel"]
                    ?: event.attributes["content"] ?: event.attributes["channel"]
                "session.ended" -> session.endedAtNs = event.timeNs
                "player.created" -> {
                    val id = event.instanceId ?: syntheticPlayerId(event)
                    session.ensurePlayer(id, event, synthetic = event.instanceId == null)
                    instanceSessions[id] = session.id
                }
                "player.activated" -> {
                    val player = session.ensureEventPlayer(event, instanceSessions)
                    player.role = PlayerRole.PRIMARY
                    session.latestHandoffTo(player.id)?.let {
                        if (it.activatedAtNs == null) it.activatedAtNs = event.timeNs
                    }
                }
                "player.released" -> {
                    val player = session.ensureEventPlayer(event, instanceSessions)
                    player.endedAtNs = event.timeNs
                    session.closeState(player.id, event.timeNs)
                    session.handoffs.values.filter { it.sourceInstanceId == player.id && it.sourceReleasedAtNs == null }
                        .forEach { it.sourceReleasedAtNs = event.timeNs }
                }
                "state.changed" -> {
                    val player = session.ensureEventPlayer(event, instanceSessions)
                    val state = event.attributes["to"] ?: event.attributes["state"]
                    if (!state.isNullOrBlank()) session.changeState(player.id, state, event)
                }
                "buffer.started" -> {
                    val player = session.ensureEventPlayer(event, instanceSessions)
                    session.changeState(player.id, "buffering", event)
                }
                "buffer.ended" -> {
                    val player = session.ensureEventPlayer(event, instanceSessions)
                    session.changeState(player.id, event.attributes["to"] ?: "playing", event)
                }
                "render.first_frame", "render.video_available" -> {
                    val player = session.ensureEventPlayer(event, instanceSessions)
                    session.latestHandoffTo(player.id)?.let {
                        if (it.activatedAtNs == null) it.activatedAtNs = event.timeNs
                    }
                }
                "handoff.requested" -> session.requestHandoff(event, instanceSessions)
                "handoff.completed" -> session.completeHandoff(event, instanceSessions)
            }
        }

        return PlayerSessionGraph(
            processEpochs = replay.processEpochs,
            viewingSessions = sessions.values.map { it.toDomain() },
            events = replay.events,
            duplicateEvents = replay.duplicateEvents,
            lateEvents = replay.lateEvents
        )
    }

    private fun syntheticPlayerId(event: PlaybackEvent): String =
        "passive-player-${event.pid ?: "unknown"}"

    private data class MutablePlayer(
        val id: String,
        val viewingSessionId: String,
        var family: String?,
        var implementation: String?,
        var delivery: String?,
        var role: PlayerRole,
        var startedAtNs: Long,
        var endedAtNs: Long? = null,
        val capabilities: MutableSet<String> = linkedSetOf(),
        val synthetic: Boolean
    ) {
        fun toDomain() = PlayerInstance(
            id, viewingSessionId, family, implementation, delivery, role,
            startedAtNs, endedAtNs, capabilities, synthetic
        )
    }

    private data class MutableHandoff(
        val id: String,
        val viewingSessionId: String,
        var sourceInstanceId: String?,
        var targetInstanceId: String?,
        var requestedAtNs: Long,
        var activatedAtNs: Long? = null,
        var sourceReleasedAtNs: Long? = null,
        var reason: String? = null,
        var result: HandoffResult = HandoffResult.UNKNOWN,
        var source: EvidenceSource,
        var confidence: Double
    ) {
        fun toDomain() = PlayerHandoff(
            id, viewingSessionId, sourceInstanceId, targetInstanceId, requestedAtNs,
            activatedAtNs, sourceReleasedAtNs, reason, result, source, confidence
        )
    }

    private class MutableSession(
        val id: String,
        var startedAtNs: Long,
        val synthetic: Boolean
    ) {
        var endedAtNs: Long? = null
        var contentLabel: String? = null
        val players = linkedMapOf<String, MutablePlayer>()
        val states = mutableListOf<PlayerStateInterval>()
        val openState = mutableMapOf<String, Int>()
        val handoffs = linkedMapOf<String, MutableHandoff>()

        fun ensureEventPlayer(event: PlaybackEvent, instanceSessions: MutableMap<String, String>): MutablePlayer {
            val id = event.instanceId ?: players.values.lastOrNull { it.endedAtNs == null }?.id
                ?: "passive-player-${event.pid ?: "unknown"}"
            instanceSessions[id] = this.id
            return ensurePlayer(id, event, synthetic = event.instanceId == null)
        }

        fun ensurePlayer(id: String, event: PlaybackEvent, synthetic: Boolean): MutablePlayer {
            val player = players.getOrPut(id) {
                MutablePlayer(
                    id = id,
                    viewingSessionId = this.id,
                    family = event.attributes["family"],
                    implementation = event.attributes["implementation"],
                    delivery = event.attributes["delivery"],
                    role = parseRole(event.attributes["role"]),
                    startedAtNs = event.timeNs,
                    synthetic = synthetic
                )
            }
            player.startedAtNs = minOf(player.startedAtNs, event.timeNs)
            player.family = player.family ?: event.attributes["family"]
            player.implementation = player.implementation ?: event.attributes["implementation"]
            player.delivery = player.delivery ?: event.attributes["delivery"]
            player.capabilities += parseSet(event.attributes["capabilities"])
            return player
        }

        fun changeState(playerId: String, state: String, event: PlaybackEvent) {
            closeState(playerId, event.timeNs)
            states += PlayerStateInterval(playerId, state, event.timeNs, source = event.source, confidence = event.confidence)
            openState[playerId] = states.lastIndex
        }

        fun closeState(playerId: String, timeNs: Long) {
            val index = openState.remove(playerId) ?: return
            val previous = states[index]
            states[index] = previous.copy(endedAtNs = maxOf(previous.startedAtNs, timeNs))
        }

        fun requestHandoff(event: PlaybackEvent, instanceSessions: MutableMap<String, String>) {
            val handoffId = event.attributes["handoffId"] ?: event.eventId ?: "handoff-${event.sequence}"
            val from = event.attributes["fromInstanceId"] ?: event.attributes["sourceInstanceId"] ?: event.instanceId
            val to = event.attributes["toInstanceId"] ?: event.attributes["targetInstanceId"]
            from?.let { ensurePlayer(it, event, synthetic = false); instanceSessions[it] = id }
            to?.let { ensurePlayer(it, event, synthetic = false); instanceSessions[it] = id }
            handoffs[handoffId] = MutableHandoff(
                id = handoffId,
                viewingSessionId = id,
                sourceInstanceId = from,
                targetInstanceId = to,
                requestedAtNs = event.timeNs,
                reason = event.attributes["reason"],
                source = event.source,
                confidence = event.confidence
            )
        }

        fun completeHandoff(event: PlaybackEvent, instanceSessions: MutableMap<String, String>) {
            val handoffId = event.attributes["handoffId"] ?: event.eventId ?: "handoff-${event.sequence}"
            val from = event.attributes["fromInstanceId"] ?: event.attributes["sourceInstanceId"]
            val to = event.attributes["toInstanceId"] ?: event.attributes["targetInstanceId"] ?: event.instanceId
            val handoff = handoffs.getOrPut(handoffId) {
                MutableHandoff(
                    handoffId, id, from, to, event.timeNs,
                    source = event.source, confidence = event.confidence
                )
            }
            handoff.sourceInstanceId = handoff.sourceInstanceId ?: from
            handoff.targetInstanceId = handoff.targetInstanceId ?: to
            handoff.reason = handoff.reason ?: event.attributes["reason"]
            handoff.result = parseResult(event.attributes["result"])
            if (handoff.result == HandoffResult.SUCCEEDED && handoff.activatedAtNs == null) handoff.activatedAtNs = event.timeNs
            if (event.attributes["sourceReleased"]?.toBooleanStrictOrNull() == true) handoff.sourceReleasedAtNs = event.timeNs
            handoff.sourceInstanceId?.let { ensurePlayer(it, event, synthetic = false); instanceSessions[it] = id }
            handoff.targetInstanceId?.let { ensurePlayer(it, event, synthetic = false); instanceSessions[it] = id }
        }

        fun latestHandoffTo(instanceId: String): MutableHandoff? =
            handoffs.values.lastOrNull { it.targetInstanceId == instanceId && it.result == HandoffResult.UNKNOWN }

        fun toDomain(): ViewingSession = ViewingSession(
            id = id,
            startedAtNs = startedAtNs,
            endedAtNs = endedAtNs,
            contentLabel = contentLabel,
            playerInstances = players.values.map { it.toDomain() },
            stateIntervals = states.toList(),
            handoffs = handoffs.values.map { it.toDomain() },
            synthetic = synthetic
        )

        private fun parseRole(raw: String?): PlayerRole = when (raw?.lowercase()) {
            "primary" -> PlayerRole.PRIMARY
            "candidate" -> PlayerRole.CANDIDATE
            "fallback" -> PlayerRole.FALLBACK
            else -> PlayerRole.UNKNOWN
        }

        private fun parseResult(raw: String?): HandoffResult = when (raw?.lowercase()) {
            "succeeded", "success", "playing" -> HandoffResult.SUCCEEDED
            "failed", "failure" -> HandoffResult.FAILED
            "cancelled", "canceled" -> HandoffResult.CANCELLED
            "looped", "loop" -> HandoffResult.LOOPED
            else -> HandoffResult.UNKNOWN
        }

        private fun parseSet(raw: String?): Set<String> = raw?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }
}
