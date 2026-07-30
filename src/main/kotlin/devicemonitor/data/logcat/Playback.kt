package devicemonitor.data.logcat

import java.io.File

/**
 * STB video playback + tunnel-mode inference from logcat (Set-Top-Box / Fire TV / Android TV).
 *
 *  - Video player state is the RAW MediaKind player lifecycle event (`mk-player-lifecycle: onXxx`).
 *    Every recognized lifecycle state is surfaced as-is (no collapsing into READY/BUFFERING/…).
 *  - Tunnel mode comes from explicit MediaCodec/ACodec/CCodec "Configuring TUNNELED/NON-TUNNELED
 *    video playback" lines (OMX vendor extension names are ignored), with the build/config flag
 *    `isTunneledPlaybackEnabled=true/false` as a separate signal.
 *
 * The captured logcat tail is replayed chronologically each tick, so the tracker always reflects
 * the latest known state without issuing extra adb calls.
 */
object Playback {

    // Tunnel — explicit MediaCodec/ACodec/CCodec configuration only.
    private val TUNNELED = Regex("(?i)(?:MediaCodec|ACodec|CCodec)\\s*:\\s*Configuring\\s+TUNNELED\\s+video\\s+playback")
    private val NON_TUNNELED = Regex("(?i)(?:MediaCodec|ACodec|CCodec)\\s*:\\s*Configuring\\s+NON-?TUNNELED\\s+video\\s+playback")
    private val TUNNEL_CONFIG = Regex("(?i)isTunneledPlaybackEnabled\\s*[=:]\\s*(true|false)")

    // Build/config-level tunnel flag — the app's requested setting, printed once when the
    // Bitmovin/MediaKind player is created (PlayerConfig … isTunneledPlaybackEnabled=true/false).
    private val BUILD_TUNNEL = Regex("isTunneledPlaybackEnabled\\s*=\\s*(true|false)")

    // MediaKind player lifecycle (real player on these STBs): `mk-player-lifecycle: onXxx`.
    private val MK_LIFECYCLE = Regex("mk-player-lifecycle:\\s*(on[A-Za-z]+)")

    /**
     * Recognized lifecycle states, ordered from terminal/idle (low) to actively-playing (high).
     * The chart uses the index as the y value and the name as the tick label. Lifecycle events not
     * in this list (onDownloadFinished, onDroppedVideoFrames, onProgramRestrictions, audio/quality
     * change callbacks, …) are not player-state transitions and are ignored.
     */
    val LIFECYCLE_STATES: List<String> = listOf(
        "onError",
        "onSourceUnloaded",
        "onPlaybackFinished",
        "onStop",
        "onPaused",
        "onSourceLoad",
        "onSourceLoaded",
        "onSeek",
        "onStallStarted",
        "onPlay",
        "onStallEnded",
        "onSeeked",
        "onRenderFirstFrame",
        "onReady",
        "onPlaying"
    )
    private val STATE_SET = LIFECYCLE_STATES.toSet()

    // States that represent an active playback session (used to decide when tunnel mode applies).
    private val NOT_PLAYING = setOf("onError", "onSourceUnloaded", "onPlaybackFinished", "onStop", "onPaused")

    /** True when the (raw lifecycle) state represents an active playback session. */
    fun isPlayingState(state: String?): Boolean = state != null && state in STATE_SET && state !in NOT_PLAYING

    /** Result of replaying logcat lines: the final inferred state. */
    data class State(
        var videoState: String? = null,    // raw lifecycle event, e.g. "onPlaying"
        var tunnelMode: String? = null,
        var buildTunnel: Boolean? = null    // isTunneledPlaybackEnabled from player config (build-level)
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

            // Video state = raw MediaKind lifecycle event (only recognized state transitions).
            val mk = MK_LIFECYCLE.find(line)
            if (mk != null) {
                val ev = mk.groupValues[1]
                if (ev in STATE_SET) st.videoState = ev
                continue
            }

            // Tunnel negotiation (actual): MediaCodec/ACodec/CCodec Configuring TUNNELED/NON-TUNNELED.
            tunnelModeFromLine(line)?.let { st.tunnelMode = it }
        }
        return st
    }

    /** Tracks state from repeated log-tail replays during a live run. */
    class Tracker {
        var videoState: String? = null
            private set
        var tunnelMode: String? = null
            private set
        var buildTunnel: Boolean? = null   // latched once seen — the config line scrolls out of the tail
            private set

        fun update(lines: List<String>) {
            val st = scan(lines)
            videoState = st.videoState
            tunnelMode = st.tunnelMode
            if (buildTunnel == null && st.buildTunnel != null) buildTunnel = st.buildTunnel
        }

        val isPlaying: Boolean get() = isPlayingState(videoState)

        /** (videoState, tunnelMode-for-this-tick). Tunnel is "n/a" while not in an active session. */
        fun snapshot(): Pair<String?, String> =
            if (!isPlaying) videoState to "n/a" else videoState to (tunnelMode ?: "unknown")
    }

    /** Summary of a post-hoc scan over a complete logcat file. */
    data class Analysis(
        val buildTunnel: Boolean?,
        val finalVideoState: String?,
        val tunneledEvents: Int,
        val nonTunneledEvents: Int,
        val stateTransitions: List<Pair<Double?, String>>  // (elapsed seconds if derivable, raw lifecycle event)
    )

    private val LOG_TIME = Regex("^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d+)")

    /**
     * Scan an existing/last-run logcat file end-to-end (no device needed). Returns the build
     * tunnel flag, actual tunnel-config event counts, and the raw lifecycle-state timeline.
     */
    fun analyzeFile(file: File): Analysis {
        var buildTunnel: Boolean? = null
        var finalState: String? = null
        var tunneled = 0
        var nonTunneled = 0
        val transitions = mutableListOf<Pair<Double?, String>>()
        var t0Ms: Long? = null
        if (!file.isFile) return Analysis(null, null, 0, 0, emptyList())
        file.useLines { seq ->
            for (raw in seq) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (buildTunnel == null) BUILD_TUNNEL.find(line)?.let { buildTunnel = it.groupValues[1].lowercase() == "true" }
                val mk = MK_LIFECYCLE.find(line)
                if (mk != null) {
                    val ev = mk.groupValues[1]
                    if (ev in STATE_SET) {
                        finalState = ev
                        val ms = lineEpochMs(line)
                        if (ms != null && t0Ms == null) t0Ms = ms
                        val el = if (ms != null && t0Ms != null) (ms - t0Ms!!) / 1000.0 else null
                        transitions += el to ev
                    }
                    continue
                }
                when {
                    TUNNELED.containsMatchIn(line) -> tunneled++
                    NON_TUNNELED.containsMatchIn(line) -> nonTunneled++
                }
            }
        }
        return Analysis(buildTunnel, finalState, tunneled, nonTunneled, transitions)
    }

    private fun lineEpochMs(line: String): Long? {
        val m = LOG_TIME.find(line) ?: return null
        return try {
            val (mo, d) = m.groupValues[1].split("-").map { it.toInt() }
            val hms = m.groupValues[2]
            val (h, mi, s) = hms.substringBefore('.').split(":").map { it.toInt() }
            val ms = hms.substringAfter('.', "0").padEnd(3, '0').take(3).toInt()
            // Year-agnostic relative timestamp (only deltas are used).
            (((((mo.toLong() * 31 + d) * 24 + h) * 60 + mi) * 60 + s) * 1000) + ms
        } catch (e: Exception) {
            null
        }
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
