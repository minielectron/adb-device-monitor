package devicemonitor.data.playerdebug

import devicemonitor.data.logcat.Playback
import devicemonitor.domain.playerdebug.*
import java.io.File
import java.io.RandomAccessFile

data class PassiveProbeResult(
    val events: List<PlaybackEvent>,
    val videoState: String?,
    val tunnelMode: String,
    val buildTunnelEnabled: Boolean?,
    val diagnostics: List<OptdParseDiagnostic>,
    val evidenceGap: Boolean
)

class PassivePlaybackProbe(
    packageName: String?,
    private val captureStartNs: Long,
    private val maxReadBytes: Int = 2 * 1024 * 1024
) {
    private val optd = OptdParser(packageName)
    private val playback = Playback.Tracker()
    private var offset = 0L
    private var lineNumber = 0L
    private var sequence = 0L
    private var firstDeviceMs: Long? = null
    private var firstOptdDeviceNs: Long? = null
    private var firstOptdCaptureNs: Long? = null

    init {
        require(captureStartNs >= 0) { "Capture start cannot be negative" }
        require(maxReadBytes > 0) { "Read size must be positive" }
    }

    fun scanNew(file: File): PassiveProbeResult {
        if (!file.isFile) return result(emptyList(), emptyList(), false)
        val length = try { file.length() } catch (_: Exception) { return result(emptyList(), emptyList(), false) }
        var gap = false
        if (length < offset) {
            offset = 0
            lineNumber = 0
            gap = true
        }
        if (length <= offset) return result(emptyList(), emptyList(), gap)

        val startOffset = offset
        val bytes = try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val size = minOf(length - offset, maxReadBytes.toLong()).toInt()
                ByteArray(size).also { raf.readFully(it) }
            }
        } catch (_: Exception) {
            return result(emptyList(), emptyList(), gap)
        }
        val lastNewline = bytes.indexOfLast { it == '\n'.code.toByte() }
        if (lastNewline < 0) return result(emptyList(), emptyList(), gap)
        val consumed = lastNewline + 1
        val text = String(bytes, 0, consumed, Charsets.UTF_8)
        offset += consumed

        val lines = text.split('\n').dropLast(1)
        val events = mutableListOf<PlaybackEvent>()
        val diagnostics = mutableListOf<OptdParseDiagnostic>()
        var relativeByteOffset = 0L
        val oldState = playback.videoState
        val oldTunnel = playback.tunnelMode
        playback.updateIncremental(lines)

        for (line in lines) {
            lineNumber++
            val absoluteOffset = startOffset + relativeByteOffset
            relativeByteOffset += line.toByteArray(Charsets.UTF_8).size + 1L
            val parsed = optd.parseLine(line, sequence, lineNumber, absoluteOffset)
            when (parsed) {
                is OptdParseResult.Accepted -> {
                    val deviceNs = parsed.event.timeNs
                    val firstDeviceNs = firstOptdDeviceNs ?: deviceNs.also { firstOptdDeviceNs = it }
                    val firstCaptureNs = firstOptdCaptureNs ?: monotonicTime(line).also { firstOptdCaptureNs = it }
                    events += parsed.event.copy(timeNs = firstCaptureNs + (deviceNs - firstDeviceNs).coerceAtLeast(0))
                    sequence++
                }
                is OptdParseResult.Rejected -> diagnostics += parsed.diagnostic
                OptdParseResult.Ignored -> platformEvent(line, absoluteOffset)?.let { events += it }
            }
        }
        if (oldState != playback.videoState && playback.videoState != null && events.none { it.type == "state.changed" && it.source == EvidenceSource.APP_REPORTED }) {
            events += inferredEvent("state.changed", lines.lastOrNull().orEmpty(), startOffset, mapOf("to" to playback.videoState!!))
        }
        if (oldTunnel != playback.tunnelMode && playback.tunnelMode != null) {
            events += inferredEvent("tunnel.changed", lines.lastOrNull().orEmpty(), startOffset, mapOf("mode" to playback.tunnelMode!!))
        }
        if (gap) events += inferredEvent("evidence.gap", "logcat truncated or rotated", 0, mapOf("reason" to "logcat_truncated"), 0.9)
        return result(events.sortedWith(compareBy<PlaybackEvent> { it.timeNs }.thenBy { it.sequence }), diagnostics, gap)
    }

    private fun platformEvent(line: String, byteOffset: Long): PlaybackEvent? {
        val type = when {
            ANR.containsMatchIn(line) -> "app.anr"
            JAVA_CRASH.containsMatchIn(line) -> "app.crash"
            NATIVE_CRASH.containsMatchIn(line) -> "app.native_crash"
            CODEC_ERROR.containsMatchIn(line) -> "codec.error"
            DRM_ERROR.containsMatchIn(line) -> "drm.error"
            AUDIO_ERROR.containsMatchIn(line) -> "audio.error"
            else -> return null
        }
        return inferredEvent(type, line, byteOffset, mapOf("summary" to line.trim().take(500)), 0.85)
    }

    private fun inferredEvent(
        type: String,
        line: String,
        byteOffset: Long,
        attributes: Map<String, String>,
        confidence: Double = 0.75
    ): PlaybackEvent {
        val event = PlaybackEvent(
            sequence = sequence++,
            schema = "passive/1",
            type = type,
            timeNs = monotonicTime(line),
            deviceTime = DEVICE_TIME.find(line)?.groupValues?.get(1),
            attributes = attributes,
            source = EvidenceSource.LOG_PARSED,
            confidence = confidence,
            evidence = listOf(EvidenceReference("logcat.log", byteOffset, lineNumber.coerceAtLeast(1), "Passive logcat evidence"))
        )
        return event
    }

    private fun monotonicTime(line: String): Long {
        val deviceMs = DEVICE_TIME.find(line)?.groupValues?.get(1)?.let(::deviceMillis)
        if (deviceMs == null) return captureStartNs + sequence * 1_000
        val first = firstDeviceMs ?: deviceMs.also { firstDeviceMs = it }
        return captureStartNs + (deviceMs - first).coerceAtLeast(0) * 1_000_000
    }

    private fun deviceMillis(value: String): Long? = try {
        val parts = value.split(Regex("[- :.]+"))
        val month = parts[0].toLong(); val day = parts[1].toLong(); val hour = parts[2].toLong()
        val minute = parts[3].toLong(); val second = parts[4].toLong(); val millis = parts.getOrElse(5) { "0" }.padEnd(3, '0').take(3).toLong()
        (((((month * 31 + day) * 24 + hour) * 60 + minute) * 60 + second) * 1000) + millis
    } catch (_: Exception) { null }

    private fun result(events: List<PlaybackEvent>, diagnostics: List<OptdParseDiagnostic>, gap: Boolean) =
        PassiveProbeResult(events, playback.videoState, playback.snapshot().second, playback.buildTunnel, diagnostics, gap)

    companion object {
        private val DEVICE_TIME = Regex("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)")
        private val ANR = Regex("ActivityManager:\\s+ANR in\\s+\\S+")
        private val JAVA_CRASH = Regex("FATAL EXCEPTION")
        private val NATIVE_CRASH = Regex("Fatal signal\\s+\\d+")
        private val CODEC_ERROR = Regex("(?i)(?:MediaCodec|ACodec|CCodec).*?(?:error|failed|fatal)")
        private val DRM_ERROR = Regex("(?i)(?:drm|widevine).*?(?:error|failed|denied)")
        private val AUDIO_ERROR = Regex("(?i)(?:AudioTrack|AudioFlinger).*?(?:error|failed|dead)")
    }
}
