package devicemonitor.application.playerdebug

import devicemonitor.domain.playerdebug.PlaybackEvent
import devicemonitor.domain.playerdebug.ProcessEpoch

data class EventReplayResult(
    val events: List<PlaybackEvent>,
    val processEpochs: List<ProcessEpoch>,
    val duplicateEvents: Int,
    val lateEvents: Int
)

class EventReplay(private val latenessWindowNs: Long = 500_000_000L) {
    init { require(latenessWindowNs >= 0) { "Lateness window cannot be negative" } }

    fun replay(input: Iterable<PlaybackEvent>): EventReplayResult {
        val arrivals = input.sortedBy { it.sequence }
        val epochs = mutableListOf<MutableEpoch>()
        val active = mutableMapOf<String, MutableEpoch>()
        val seenIds = mutableSetOf<Pair<String, String>>()
        val kept = mutableListOf<PlaybackEvent>()
        var duplicates = 0
        var late = 0
        var maxTime = Long.MIN_VALUE

        for (original in arrivals) {
            val key = processKey(original)
            var epoch = active[key]
            val startsEpoch = original.type == "capabilities" && epoch?.sawCapabilities == true
            if (epoch == null || startsEpoch || (original.pid != null && epoch.pid != original.pid)) {
                epoch?.let { it.endedAtNs = maxOf(it.startedAtNs, original.timeNs) }
                epoch = MutableEpoch(
                    id = "epoch-${epochs.size + 1}",
                    packageName = original.packageName ?: "unknown",
                    pid = original.pid,
                    startedAtNs = original.timeNs
                )
                epochs += epoch
                active[key] = epoch
            }
            val currentEpoch = requireNotNull(epoch)
            if (original.type == "capabilities") {
                currentEpoch.sawCapabilities = true
                currentEpoch.adapterName = original.attributes["adapterName"] ?: original.attributes["name"]
                currentEpoch.adapterVersion = original.attributes["adapterVersion"] ?: original.attributes["version"]
                currentEpoch.capabilities += parseSet(original.attributes["eventTypes"])
                currentEpoch.capabilities += parseSet(original.attributes["metrics"])
            }

            val event = original.copy(processEpochId = original.processEpochId ?: currentEpoch.id)
            val dedupeKey = event.eventId?.let { event.processEpochId.orEmpty() to it }
            if (dedupeKey != null && !seenIds.add(dedupeKey)) {
                duplicates++
                continue
            }
            val isLate = maxTime != Long.MIN_VALUE && event.timeNs < maxTime - latenessWindowNs
            if (isLate) late++
            maxTime = maxOf(maxTime, event.timeNs)
            currentEpoch.lastAtNs = maxOf(currentEpoch.lastAtNs, event.timeNs)
            kept += if (isLate) event.copy(late = true) else event
        }

        val ordered = kept.sortedWith(compareBy<PlaybackEvent> { it.timeNs }.thenBy { it.sequence })
        return EventReplayResult(
            events = ordered,
            processEpochs = epochs.map { it.toDomain() },
            duplicateEvents = duplicates,
            lateEvents = late
        )
    }

    private fun processKey(event: PlaybackEvent): String = event.packageName ?: "unknown"

    private fun parseSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.trim().removePrefix("[").removeSuffix("]")
            .split(',').map { it.trim().trim('"') }.filter { it.isNotBlank() }.toSet()
    }

    private data class MutableEpoch(
        val id: String,
        val packageName: String,
        val pid: Int?,
        val startedAtNs: Long,
        var endedAtNs: Long? = null,
        var lastAtNs: Long = startedAtNs,
        var adapterName: String? = null,
        var adapterVersion: String? = null,
        val capabilities: MutableSet<String> = linkedSetOf(),
        var sawCapabilities: Boolean = false
    ) {
        fun toDomain() = ProcessEpoch(
            id = id,
            packageName = packageName,
            pid = pid,
            startedAtNs = startedAtNs,
            endedAtNs = endedAtNs,
            adapterName = adapterName,
            adapterVersion = adapterVersion,
            capabilities = capabilities
        )
    }
}
