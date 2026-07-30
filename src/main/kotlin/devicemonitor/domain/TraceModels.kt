package devicemonitor.domain

/** One method on the app's main thread. Percentages are of BUSY time (idle polling excluded). */
data class MethodStat(
    val method: String,
    val selfMs: Double,        // time in the method itself, excluding callees
    val inclusiveMs: Double,   // method + callees
    val calls: Int,
    val pctBusy: Double
)

/** A package method's contribution to slow (janky) frames. */
data class JankMethodStat(
    val method: String,
    val msInSlowFrames: Double,
    val framesTouched: Int,
    val pctOfSlowTime: Double
)

data class TraceAnalysis(
    val topMethods: List<MethodStat>,    // hottest by SELF time, framework wrappers/idle filtered out naturally
    val appMethods: List<MethodStat>,    // only methods from the configured package, by inclusive time
    val jankMethods: List<JankMethodStat>,
    val totalFrames: Int,
    val slowFrames: Int,
    val slowThresholdMs: Double,
    val busyMs: Double,                  // main-thread time excluding idle waits
    val spanMs: Double
)

/**
 * Minimal parser for ART method traces (`am profile start --sampling ...`).
 *
 * Analyses (main thread only):
 *  1. Hot methods by SELF time. Inclusive time makes framework roots (ZygoteInit.main,
 *     Looper.loop, MessageQueue.next) show 100% and idle polling (nativePollOnce)
 *     dominate — self time shows where work actually happens, and idle waits are
 *     excluded from the busy-time base.
 *  2. App methods: only methods whose class is under the configured package.
 *  3. Jank attribution: each `Choreographer.doFrame` invocation is one frame; frames
 *     longer than [SLOW_FRAME_MS] are janky. Package methods overlapping those slow
 *     windows are ranked — "which of MY methods caused the skipped frames".
 */
