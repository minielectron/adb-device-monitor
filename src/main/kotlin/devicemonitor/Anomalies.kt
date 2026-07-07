package devicemonitor

import java.io.File
import java.time.Duration
import java.time.LocalDateTime

/** One flagged logcat event. [elapsedSec] is relative to run start (null if not derivable). */
data class Anomaly(
    val deviceTime: String,
    val elapsedSec: Double?,
    val type: String,
    val detail: String
)

/**
 * Pulls app metadata out of analytics "Event parameters: {...}" blocks that the
 * app prints to logcat. The JSON is spread over multiple logcat lines, so fields
 * are matched per line; the latest occurrence wins.
 */
object EventInfo {
    private val keys = listOf(
        "app_version", "device_model", "os_name",
        "firmware_version", "account_id", "screen_resolution", "screen_size"
    )

    fun extract(logcat: File): Map<String, String> {
        if (!logcat.exists()) return emptyMap()
        val found = mutableMapOf<String, String>()
        logcat.useLines { lines ->
            for (line in lines) {
                for (k in keys) {
                    Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(line)?.let {
                        val v = it.groupValues[1].trim()
                        if (v.isNotBlank()) found[k] = v
                    }
                }
            }
        }
        return found
    }
}

/**
 * Scans the captured logcat (threadtime format) for events worth flagging:
 * ANRs, fatal exceptions, native crashes, low-memory kills, big Choreographer
 * frame skips and Davey janks. Patterns follow scripts/SystemPulseV4.py.
 */
object AnomalyScanner {

    private const val SKIPPED_FRAMES_THRESHOLD = 30

    private val TIME_RE = Regex("^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d+)")

    private class Rule(
        val type: String,
        val re: Regex,
        val accept: (MatchResult) -> Boolean = { true }
    )

    private val rules = listOf(
        Rule("ANR", Regex("ActivityManager:\\s+ANR in\\s+\\S+")),
        Rule("Fatal exception", Regex("FATAL EXCEPTION")),
        Rule("Native crash", Regex("Fatal signal\\s+\\d+")),
        Rule("LMK kill", Regex("(lowmemorykiller|lmkd).*[Kk]ill")),
        Rule("Skipped frames", Regex("Skipped\\s+(\\d+)\\s+frames!"), { m ->
            (m.groupValues[1].toIntOrNull() ?: 0) >= SKIPPED_FRAMES_THRESHOLD
        }),
        Rule("Jank (Davey)", Regex("Davey!\\s+duration=(\\d+)ms"))
    )

    fun scan(logcat: File, anchorDeviceTime: String?): List<Anomaly> {
        if (!logcat.exists()) return emptyList()
        val anchor = anchorDeviceTime?.trim()
            ?.split(Regex("\\s+"))
            ?.takeIf { it.size >= 2 }
            ?.let { parseDeviceTime(it[0], it[1]) }

        val out = mutableListOf<Anomaly>()
        logcat.useLines { lines ->
            for (line in lines) {
                val rule = rules.firstOrNull { r ->
                    r.re.find(line)?.let(r.accept) == true
                } ?: continue
                val tm = TIME_RE.find(line)
                val ts = tm?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: "?"
                val elapsed = if (tm != null && anchor != null) {
                    parseDeviceTime(tm.groupValues[1], tm.groupValues[2])
                        ?.let { Duration.between(anchor, it).toMillis() / 1000.0 }
                        ?.takeIf { it >= -1.0 }
                } else null
                out += Anomaly(ts, elapsed, rule.type, line.trim().take(500))
            }
        }
        return out
    }

    fun writeReport(anomalies: List<Anomaly>, file: File) {
        file.bufferedWriter().use { w ->
            if (anomalies.isEmpty()) {
                w.appendLine("No anomalies detected.")
                return
            }
            w.appendLine("# device time | time since run start | type | logcat line")
            for (a in anomalies) {
                val t = a.elapsedSec?.let { "t=%.1fs".format(it) } ?: "t=?"
                w.appendLine("${a.deviceTime} | $t | ${a.type} | ${a.detail}")
            }
        }
    }

    fun summary(anomalies: List<Anomaly>): String =
        if (anomalies.isEmpty()) "none detected"
        else anomalies.groupingBy { it.type }.eachCount()
            .entries.joinToString(", ") { "${it.value} ${it.key}" }

    /** "06-11" + "13:45:01.123" → LocalDateTime in the current year. */
    private fun parseDeviceTime(md: String, hms: String): LocalDateTime? = try {
        val (mo, d) = md.split("-").map { it.toInt() }
        val t = hms.substringBefore('.').split(":").map { it.toInt() }
        val ms = hms.substringAfter('.', "0").padEnd(3, '0').take(3).toInt()
        LocalDateTime.of(LocalDateTime.now().year, mo, d, t[0], t[1], t[2], ms * 1_000_000)
    } catch (e: Exception) {
        null
    }
}
