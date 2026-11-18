package devicemonitor.data.playerdebug

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devicemonitor.domain.playerdebug.*

data class OptdLogMetadata(
    val deviceTime: String?,
    val pid: Int?,
    val tid: Int?,
    val priority: String?,
    val tag: String,
    val packageName: String? = null,
    val artifact: String = "logcat.log",
    val byteOffset: Long? = null,
    val lineNumber: Long? = null
)

data class OptdParseDiagnostic(val code: String, val message: String)

sealed class OptdParseResult {
    data class Accepted(val event: PlaybackEvent, val knownType: Boolean) : OptdParseResult()
    data class Rejected(val diagnostic: OptdParseDiagnostic) : OptdParseResult()
    object Ignored : OptdParseResult()
}

class OptdParser(private val selectedPackage: String? = null) {
    private val gson = Gson()

    fun parseLine(line: String, sequence: Long, lineNumber: Long? = null, byteOffset: Long? = null): OptdParseResult {
        val match = THREADTIME.matchEntire(line) ?: return OptdParseResult.Ignored
        val tag = match.groupValues[5].trim()
        if (tag != PlayerTelemetryProtocol.TAG) return OptdParseResult.Ignored
        val metadata = OptdLogMetadata(
            deviceTime = match.groupValues[1],
            pid = match.groupValues[2].toIntOrNull(),
            tid = match.groupValues[3].toIntOrNull(),
            priority = match.groupValues[4],
            tag = tag,
            packageName = selectedPackage,
            lineNumber = lineNumber,
            byteOffset = byteOffset
        )
        return parsePayload(match.groupValues[6], metadata, sequence)
    }

    fun parsePayload(payload: String, metadata: OptdLogMetadata, sequence: Long): OptdParseResult {
        if (metadata.tag != PlayerTelemetryProtocol.TAG) return OptdParseResult.Ignored
        if (sequence < 0) return rejected("invalid_sequence", "Event sequence cannot be negative")
        val byteCount = payload.toByteArray(Charsets.UTF_8).size
        if (byteCount > PlayerTelemetryProtocol.MAX_EVENT_BYTES) {
            return rejected("oversized", "OPTD event is $byteCount bytes; maximum is ${PlayerTelemetryProtocol.MAX_EVENT_BYTES}")
        }

        val root = try {
            JsonParser.parseString(payload)
        } catch (e: Exception) {
            return rejected("malformed_json", e.message ?: "Malformed OPTD JSON")
        }
        if (!root.isJsonObject) return rejected("invalid_envelope", "OPTD event must be a JSON object")
        validateShape(root)?.let { return rejected(it.code, it.message) }
        val obj = root.asJsonObject

        val schema = obj.string("schema") ?: return rejected("missing_schema", "OPTD schema is required")
        val major = SCHEMA.matchEntire(schema)?.groupValues?.get(1)?.toIntOrNull()
            ?: return rejected("invalid_schema", "Invalid OPTD schema '$schema'")
        if (major != OPTD_SUPPORTED_MAJOR) {
            return rejected("unsupported_schema", "Unsupported OPTD major version $major")
        }

        val type = obj.string("type") ?: return rejected("missing_type", "OPTD event type is required")
        if (type.isBlank() || type.length > PlayerTelemetryProtocol.MAX_TYPE_CHARS || !TYPE.matches(type)) {
            return rejected("invalid_type", "Invalid OPTD event type")
        }
        val timeNs = obj.long("timeNs") ?: return rejected("missing_time", "OPTD timeNs must be an integer")
        if (timeNs < 0) return rejected("invalid_time", "OPTD timeNs cannot be negative")
        val data = obj.get("data")
        if (data == null || !data.isJsonObject) return rejected("invalid_data", "OPTD data must be a JSON object")

        val viewingSessionId = obj.optionalId("viewSessionId") ?: if (obj.has("viewSessionId")) {
            return rejected("invalid_id", "Invalid viewSessionId")
        } else null
        val instanceId = obj.optionalId("instanceId") ?: if (obj.has("instanceId")) {
            return rejected("invalid_id", "Invalid instanceId")
        } else null
        val eventId = obj.optionalId("eventId") ?: if (obj.has("eventId")) {
            return rejected("invalid_id", "Invalid eventId")
        } else null

        val attributes = linkedMapOf<String, String>()
        val measurements = linkedMapOf<String, Double>()
        for ((key, value) in data.asJsonObject.entrySet()) {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                val number = try { value.asDouble } catch (_: Exception) { Double.NaN }
                if (!number.isFinite()) return rejected("invalid_number", "Non-finite number in data.$key")
                measurements[key] = number
            } else if (!value.isJsonNull) {
                val text = when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                    value.isJsonPrimitive -> value.asJsonPrimitive.toString()
                    else -> gson.toJson(value)
                }
                if (text.length > PlayerTelemetryProtocol.MAX_ATTRIBUTE_CHARS) {
                    return rejected("attribute_too_large", "data.$key exceeds ${PlayerTelemetryProtocol.MAX_ATTRIBUTE_CHARS} characters")
                }
                attributes[key] = text
            }
        }

        val evidence = if (metadata.artifact.isNotBlank()) listOf(
            EvidenceReference(metadata.artifact, metadata.byteOffset, metadata.lineNumber, "OPTD logcat event")
        ) else emptyList()
        val event = PlaybackEvent(
            sequence = sequence,
            schema = schema,
            type = type,
            timeNs = timeNs,
            deviceTime = metadata.deviceTime,
            packageName = metadata.packageName ?: selectedPackage,
            pid = metadata.pid,
            tid = metadata.tid,
            viewingSessionId = viewingSessionId,
            instanceId = instanceId,
            eventId = eventId,
            attributes = attributes,
            measurements = measurements,
            source = EvidenceSource.APP_REPORTED,
            confidence = 1.0,
            evidence = evidence,
            rawJson = gson.toJson(root)
        )
        return OptdParseResult.Accepted(event, type in PlayerTelemetryProtocol.V1_EVENT_TYPES)
    }

    private fun validateShape(root: JsonElement): OptdParseDiagnostic? {
        var values = 0
        fun visit(value: JsonElement, depth: Int): OptdParseDiagnostic? {
            if (depth > PlayerTelemetryProtocol.MAX_JSON_DEPTH) {
                return OptdParseDiagnostic("too_deep", "OPTD JSON exceeds depth ${PlayerTelemetryProtocol.MAX_JSON_DEPTH}")
            }
            values++
            if (values > PlayerTelemetryProtocol.MAX_JSON_VALUES) {
                return OptdParseDiagnostic("too_many_values", "OPTD JSON exceeds ${PlayerTelemetryProtocol.MAX_JSON_VALUES} values")
            }
            if (value.isJsonObject) for ((_, child) in value.asJsonObject.entrySet()) visit(child, depth + 1)?.let { return it }
            if (value.isJsonArray) for (child in value.asJsonArray) visit(child, depth + 1)?.let { return it }
            return null
        }
        return visit(root, 1)
    }

    private fun JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.long(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return try {
            val raw = value.asJsonPrimitive.toString()
            if (!INTEGER.matches(raw)) null else raw.toLong()
        } catch (_: Exception) { null }
    }

    private fun JsonObject.optionalId(name: String): String? {
        val value = string(name) ?: return null
        return value.takeIf { it.isNotBlank() && it.length <= PlayerTelemetryProtocol.MAX_ID_CHARS }
    }

    private fun rejected(code: String, message: String) =
        OptdParseResult.Rejected(OptdParseDiagnostic(code, message))

    companion object {
        private val THREADTIME = Regex("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEAFS])\\s+([^:]+):\\s?(.*)$")
        private val SCHEMA = Regex("optd/(\\d+)")
        private val TYPE = Regex("[a-z][a-z0-9_.-]*")
        private val INTEGER = Regex("-?\\d+")
    }
}
