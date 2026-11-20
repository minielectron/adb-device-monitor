package devicemonitor.data.logcat

import java.io.File

/** Generic Android tunnel-mode inference from MediaCodec/ACodec/CCodec logcat lines. */
object Playback {

    // Tunnel — explicit MediaCodec/ACodec/CCodec configuration only.
    private val TUNNELED = Regex("(?i)(?:MediaCodec|ACodec|CCodec)\\s*:\\s*Configuring\\s+TUNNELED\\s+video\\s+playback")
    private val NON_TUNNELED = Regex("(?i)(?:MediaCodec|ACodec|CCodec)\\s*:\\s*Configuring\\s+NON-?TUNNELED\\s+video\\s+playback")
    private val TUNNEL_CONFIG = Regex("(?i)isTunneledPlaybackEnabled\\s*[=:]\\s*(true|false)")

    // Build/config-level tunnel flag — the app's requested setting, when logged.
    private val BUILD_TUNNEL = Regex("isTunneledPlaybackEnabled\\s*=\\s*(true|false)")

    /** Result of replaying logcat lines: the final inferred tunnel state. */
    data class State(
        var tunnelMode: String? = null,
        var buildTunnel: Boolean? = null
    )

    fun tunnelModeFromLine(line: String): String? = when {
        TUNNELED.containsMatchIn(line) -> "tunneled"
        NON_TUNNELED.containsMatchIn(line) -> "non_tunneled"
        else -> TUNNEL_CONFIG.find(line)?.let {
            if (it.groupValues[1].lowercase() == "true") "tunneled" else "non_tunneled"
        }
    }

    /** Chronological replay of logcat lines → final playback/tunnel state. */
    fun scan(lines: Iterable<String>): State {
        val st = State()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("---")) continue

            // Build/config tunnel flag — latch the first occurrence.
            if (st.buildTunnel == null) {
                BUILD_TUNNEL.find(line)?.let { st.buildTunnel = it.groupValues[1].lowercase() == "true" }
            }

            // Tunnel negotiation (actual): MediaCodec/ACodec/CCodec Configuring TUNNELED/NON-TUNNELED.
            tunnelModeFromLine(line)?.let { st.tunnelMode = it }
        }
        return st
    }

    /** Tracks state from repeated log-tail replays during a live run. */
    class Tracker {
        var tunnelMode: String? = null
            private set
        var buildTunnel: Boolean? = null   // latched once seen — the config line scrolls out of the tail
            private set

        fun update(lines: List<String>) {
            val st = scan(lines)
            tunnelMode = st.tunnelMode
            if (buildTunnel == null && st.buildTunnel != null) buildTunnel = st.buildTunnel
        }

        /** Latch values found only in newly appended lines; absent values keep their prior state. */
        fun updateIncremental(lines: List<String>) {
            val st = scan(lines)
            if (st.tunnelMode != null) tunnelMode = st.tunnelMode
            if (buildTunnel == null && st.buildTunnel != null) buildTunnel = st.buildTunnel
        }

        fun snapshot(): String = tunnelMode ?: "unknown"
    }

    /** Summary of a post-hoc scan over a complete logcat file. */
    data class Analysis(
        val buildTunnel: Boolean?,
        val tunneledEvents: Int,
        val nonTunneledEvents: Int
    )

    /**
     * Scan an existing/last-run logcat file end-to-end (no device needed). Returns the build
     * tunnel flag and actual tunnel-config event counts.
     */
    fun analyzeFile(file: File): Analysis {
        var buildTunnel: Boolean? = null
        var tunneled = 0
        var nonTunneled = 0
        if (!file.isFile) return Analysis(null, 0, 0)
        file.useLines { seq ->
            for (raw in seq) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (buildTunnel == null) BUILD_TUNNEL.find(line)?.let { buildTunnel = it.groupValues[1].lowercase() == "true" }
                when {
                    TUNNELED.containsMatchIn(line) -> tunneled++
                    NON_TUNNELED.containsMatchIn(line) -> nonTunneled++
                }
            }
        }
        return Analysis(buildTunnel, tunneled, nonTunneled)
    }

    /** Read trailing lines of the background logcat file (no extra adb round-trip). */
    fun readLogTail(file: File, maxBytes: Long = 512_000): List<String> {
        if (!file.isFile) return emptyList()
        return try {
            val size = file.length()
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (size > maxBytes) raf.seek(size - maxBytes)
                val bytes = ByteArray((minOf(size, maxBytes)).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).lineSequence().drop(if (size > maxBytes) 1 else 0).toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
