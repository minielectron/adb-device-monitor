package devicemonitor.optd

/**
 * Dependency-free OPTD v1 emitter. Android callers inject `{ tag, payload -> Log.i(tag, payload) }`.
 * Disabled instances return without validating or emitting, keeping production paths inert.
 */
class Optd(
    val enabled: Boolean,
    private val emit: (tag: String, payload: String) -> Unit,
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    private var lastTimeNs = -1L

    @Synchronized
    fun event(
        type: String,
        viewSessionId: String? = null,
        instanceId: String? = null,
        eventId: String? = null,
        data: Map<String, Any?> = emptyMap()
    ): Boolean {
        if (!enabled) return false
        require(type.length <= MAX_TYPE_CHARS && TYPE.matches(type)) { "Invalid OPTD event type" }
        validateId("viewSessionId", viewSessionId)
        validateId("instanceId", instanceId)
        validateId("eventId", eventId)
        require(data.size <= MAX_DATA_FIELDS) { "OPTD data has too many fields" }
        data.forEach { (key, value) -> if (value != null) validateData(key, value) }

        val timeNs = maxOf(0L, monotonicNanos(), lastTimeNs + 1)
        lastTimeNs = timeNs
        val json = buildString {
            append("{\"schema\":\"").append(SCHEMA).append("\",\"type\":\"").append(type).append("\",\"timeNs\":").append(timeNs)
            viewSessionId?.let { append(",\"viewSessionId\":").append(jsonString(it)) }
            instanceId?.let { append(",\"instanceId\":").append(jsonString(it)) }
            eventId?.let { append(",\"eventId\":").append(jsonString(it)) }
            append(",\"data\":{")
            var first = true
            data.forEach { (key, value) ->
                if (value == null) return@forEach
                if (!first) append(',')
                first = false
                append(jsonString(key)).append(':').append(jsonValue(value))
            }
            append("}}")
        }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) { "OPTD event exceeds 8 KiB" }
        emit(TAG, json)
        return true
    }

    fun capabilities(
        adapterName: String,
        adapterVersion: String? = null,
        eventTypes: Collection<String> = emptyList(),
        metricNames: Collection<String> = emptyList(),
        playerFamilies: Collection<String> = emptyList()
    ) = event("capabilities", data = linkedMapOf(
        "adapterName" to adapterName,
        "adapterVersion" to adapterVersion,
        "eventTypes" to eventTypes.toList(),
        "metrics" to metricNames.toList(),
        "playerFamilies" to playerFamilies.toList()
    ))

    fun sessionStarted(viewSessionId: String) = event("session.started", viewSessionId)
    fun sessionEnded(viewSessionId: String) = event("session.ended", viewSessionId)

    fun playerCreated(
        viewSessionId: String,
        instanceId: String,
        family: String? = null,
        implementation: String? = null,
        delivery: String? = null,
        role: String? = null
    ) = event("player.created", viewSessionId, instanceId, data = linkedMapOf(
        "family" to family,
        "implementation" to implementation,
        "delivery" to delivery,
        "role" to role
    ))

    fun playerActivated(viewSessionId: String, instanceId: String) = event("player.activated", viewSessionId, instanceId)
    fun playerReleased(viewSessionId: String, instanceId: String) = event("player.released", viewSessionId, instanceId)

    fun stateChanged(viewSessionId: String, instanceId: String, from: String?, to: String) =
        event("state.changed", viewSessionId, instanceId, data = mapOf("from" to from, "to" to to))

    fun bufferStarted(viewSessionId: String, instanceId: String, reason: String? = null) =
        event("buffer.started", viewSessionId, instanceId, data = mapOf("reason" to reason))

    fun bufferEnded(viewSessionId: String, instanceId: String, nextState: String = "playing") =
        event("buffer.ended", viewSessionId, instanceId, data = mapOf("to" to nextState))

    fun firstFrame(viewSessionId: String, instanceId: String) = event("render.first_frame", viewSessionId, instanceId)
    fun videoAvailable(viewSessionId: String, instanceId: String, available: Boolean) =
        event(if (available) "render.video_available" else "render.video_unavailable", viewSessionId, instanceId)

    fun audioAvailable(viewSessionId: String, instanceId: String, available: Boolean) =
        event(if (available) "audio.available" else "audio.unavailable", viewSessionId, instanceId)

    fun qualityChanged(viewSessionId: String, instanceId: String, values: Map<String, Any?>) =
        event("quality.changed", viewSessionId, instanceId, data = values)

    fun metricSample(viewSessionId: String?, instanceId: String?, measurements: Map<String, Number>) =
        event("metric.sample", viewSessionId, instanceId, data = measurements)

    fun errorReported(viewSessionId: String?, instanceId: String?, code: String, category: String? = null) =
        event("error.reported", viewSessionId, instanceId, data = mapOf("code" to code, "category" to category))

    fun handoffRequested(
        viewSessionId: String,
        handoffId: String,
        sourceInstanceId: String?,
        targetInstanceId: String?,
        reason: String? = null
    ) = event("handoff.requested", viewSessionId, sourceInstanceId, handoffId, mapOf(
        "handoffId" to handoffId,
        "sourceInstanceId" to sourceInstanceId,
        "targetInstanceId" to targetInstanceId,
        "reason" to reason
    ))

    fun handoffCompleted(
        viewSessionId: String,
        handoffId: String,
        sourceInstanceId: String?,
        targetInstanceId: String?,
        result: String,
        sourceReleased: Boolean = false
    ) = event("handoff.completed", viewSessionId, targetInstanceId, handoffId, mapOf(
        "handoffId" to handoffId,
        "sourceInstanceId" to sourceInstanceId,
        "targetInstanceId" to targetInstanceId,
        "result" to result,
        "sourceReleased" to sourceReleased
    ))

    private fun validateId(name: String, value: String?) {
        require(value == null || (value.isNotBlank() && value.length <= MAX_ID_CHARS)) { "Invalid OPTD $name" }
    }

    private fun validateData(key: String, value: Any) {
        require(DATA_KEY.matches(key)) { "Invalid OPTD data key" }
        require(!SENSITIVE_KEY.matches(key)) { "Sensitive OPTD data key is not allowed: $key" }
        when (value) {
            is String -> validateString(value)
            is Number -> require(value.toDouble().isFinite()) { "OPTD data number must be finite" }
            is Boolean -> Unit
            is Iterable<*> -> {
                val values = value.toList()
                require(values.size <= MAX_LIST_VALUES && values.all { it is String }) { "OPTD lists must contain at most $MAX_LIST_VALUES strings" }
                values.filterIsInstance<String>().forEach(::validateString)
            }
            else -> throw IllegalArgumentException("Unsupported OPTD data value for $key")
        }
    }

    private fun validateString(value: String) {
        require(value.length <= MAX_ATTRIBUTE_CHARS) { "OPTD attribute exceeds $MAX_ATTRIBUTE_CHARS characters" }
        require(!URL.containsMatchIn(value) && !SENSITIVE_TEXT.containsMatchIn(value)) { "Sensitive OPTD data is not allowed" }
    }

    private fun jsonValue(value: Any): String = when (value) {
        is String -> jsonString(value)
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonString(it as String) }
        else -> error("Unsupported OPTD value")
    }

    private fun jsonString(value: String) = buildString(value.length + 2) {
        append('"')
        value.forEach { character -> when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        } }
        append('"')
    }

    companion object {
        const val TAG = "OPTD"
        const val SCHEMA = "optd/1"
        const val MAX_EVENT_BYTES = 8 * 1024
        private const val MAX_ID_CHARS = 256
        private const val MAX_TYPE_CHARS = 96
        private const val MAX_ATTRIBUTE_CHARS = 2 * 1024
        private const val MAX_DATA_FIELDS = 64
        private const val MAX_LIST_VALUES = 64
        private val TYPE = Regex("[a-z][a-z0-9_.-]*")
        private val DATA_KEY = Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")
        private val SENSITIVE_KEY = Regex("(?i)(?:authorization|access[_-]?token|refresh[_-]?token|token|cookie|license(?:[_-]?challenge)?|subscriber[_-]?id|device[_-]?id|content[_-]?id|session[_-]?id)")
        private val SENSITIVE_TEXT = Regex("(?i)(?:authorization|access[_-]?token|refresh[_-]?token|token|cookie|license(?:[_-]?challenge)?|subscriber[_-]?id|device[_-]?id|content[_-]?id|session[_-]?id)\\s*[:=]")
        private val URL = Regex("https?://", RegexOption.IGNORE_CASE)
    }
}
