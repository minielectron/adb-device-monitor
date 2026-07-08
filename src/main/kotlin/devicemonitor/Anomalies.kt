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

    // Precompiled once (was recompiled per key per line — O(lines × keys) regex builds).
    private val patterns: Map<String, Regex> = keys.associateWith { Regex("\"$it\"\\s*:\\s*\"([^\"]*)\"") }

    /**
     * Extracts app/device metadata from analytics events. The block appears early (app startup),
     * so we stop once every key is found and cap the scan at [maxBytes] — otherwise a multi-MB
     * logcat with no such events would be walked in full for nothing.
     */
    fun extract(logcat: File, maxBytes: Long = 16L * 1024 * 1024): Map<String, String> {
        if (!logcat.exists()) return emptyMap()
        val found = mutableMapOf<String, String>()
        var read = 0L
        logcat.bufferedReader().use { br ->
            var line = br.readLine()
            while (line != null) {
                read += line.length + 1
                for (k in keys) {
                    if (found.containsKey(k)) continue
                    patterns[k]!!.find(line)?.let {
                        val v = it.groupValues[1].trim()
                        if (v.isNotBlank()) found[k] = v
                    }
                }
                if (found.size == keys.size || read >= maxBytes) break
                line = br.readLine()
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

    /** Parse the run's anchor "MM-DD HH:MM:SS" into a LocalDateTime (for elapsed mapping). */
    fun parseAnchor(anchorDeviceTime: String?): LocalDateTime? =
        anchorDeviceTime?.trim()
            ?.split(Regex("\\s+"))
            ?.takeIf { it.size >= 2 }
            ?.let { parseDeviceTime(it[0], it[1]) }

    /** Match a single logcat line against the anomaly rules; null if it isn't an anomaly. */
    fun matchLine(line: String, anchor: LocalDateTime?): Anomaly? {
        val rule = rules.firstOrNull { r -> r.re.find(line)?.let(r.accept) == true } ?: return null
        val tm = TIME_RE.find(line)
        val ts = tm?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: "?"
        val elapsed = if (tm != null && anchor != null) {
            parseDeviceTime(tm.groupValues[1], tm.groupValues[2])
                ?.let { Duration.between(anchor, it).toMillis() / 1000.0 }
                ?.takeIf { it >= -1.0 }
        } else null
        return Anomaly(ts, elapsed, rule.type, line.trim().take(500))
    }

    fun scan(logcat: File, anchorDeviceTime: String?): List<Anomaly> {
        if (!logcat.exists()) return emptyList()
        val anchor = parseAnchor(anchorDeviceTime)
        val out = mutableListOf<Anomaly>()
        logcat.useLines { lines -> for (line in lines) matchLine(line, anchor)?.let(out::add) }
        return out
    }

    /**
     * Incremental anomaly scanner: remembers a byte offset into the growing logcat and, on each
     * call, matches only the lines appended since last time. This keeps live polling O(new bytes)
     * instead of re-scanning the whole (multi-MB) file every second, and lets the final report
     * reuse the already-accumulated list instead of a fresh full scan.
     */
    class Incremental(anchorDeviceTime: String?) {
        private val anchor = parseAnchor(anchorDeviceTime)
        private var offset = 0L
        private var carry = ""   // partial trailing line not yet terminated by a newline
        private val list = java.util.Collections.synchronizedList(mutableListOf<Anomaly>())

        fun snapshot(): List<Anomaly> = synchronized(list) { list.toList() }

        /** Scan newly-appended bytes; returns the accumulated anomaly list. */
        fun scanNew(file: File): List<Anomaly> {
            if (!file.isFile) return snapshot()
            val len = try { file.length() } catch (e: Exception) { return snapshot() }
            if (len < offset) { offset = 0; carry = "" }   // rotated/truncated
            if (len <= offset) return snapshot()
            try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray((len - offset).toInt())
                    raf.readFully(buf)
                    // Consume only up to the last newline; keep the remainder for next time.
                    var lastNl = -1
                    for (i in buf.indices.reversed()) { if (buf[i] == '\n'.code.toByte()) { lastNl = i; break } }
                    val consume = if (lastNl >= 0) lastNl + 1 else 0
                    offset += consume.toLong()
                    if (consume == 0) return snapshot()
                    val text = carry + String(buf, 0, consume, Charsets.UTF_8)
                    carry = ""
                    for (line in text.split('\n')) matchLine(line, anchor)?.let { list.add(it) }
                }
            } catch (e: Exception) { /* keep what we have */ }
            return snapshot()
        }
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
