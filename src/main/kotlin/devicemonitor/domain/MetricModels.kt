package devicemonitor.domain

/** Cumulative CPU tick counters from /proc/stat (aggregate "cpu " line). */
data class CpuTicks(val total: Long, val idle: Long)

/** One sampled data point. Null = metric unavailable on this device/kernel. */
data class Sample(
    val elapsedSec: Double,
    val cpuUtilPct: Double?,        // derived from /proc/stat deltas
    val cpuIdlePct: Double?,        // 100 - cpuUtilPct (idle share of CPU this tick)
    val cpuPsiSomeAvg10: Double?,   // /proc/pressure/cpu
    val memPsiSomeAvg10: Double?,   // /proc/pressure/memory
    val memPsiFullAvg10: Double?,
    val ioPsiSomeAvg10: Double?,    // /proc/pressure/io
    val memTotalMb: Double,
    val memUsedMb: Double,          // MemTotal - MemAvailable
    val memUsedPct: Double,         // 100 * memUsedMb / memTotalMb
    val memAvailableMb: Double,
    val cachedMb: Double,
    val swapUsedMb: Double?,        // null when SwapTotal == 0
    val load1: Double?,             // 1-min load average
    val perCoreUtilPct: List<Double?>?, // per-core CPU % from /proc/stat cpuN lines
    val netRxKbps: Double?,         // /proc/net/dev deltas, all non-lo interfaces, KB/s
    val netTxKbps: Double?,
    val cpuTempC: Double?,          // hottest CPU/SoC thermal zone, °C
    val cpuFreqMhz: Double?,        // mean of online cores' scaling_cur_freq, MHz
    val majorFaultsPerSec: Double?, // /proc/vmstat pgmajfault delta rate (memory thrashing)
    val directReclaimPerSec: Double? = null, // kernel direct-reclaim events/s from logcat (memory pressure)
    val zoneActiveCount: Int? = null,     // /proc/zoneinfo — number of active memory zones
    val zoneFreePages: Double? = null,    // total free pages across active zones
    val zoneWmMinPages: Double? = null,   // sum of per-zone "min" watermark (pages)
    val zoneWmLowPages: Double? = null,   // sum of per-zone "low" watermark (pages)
    val zoneWmHighPages: Double? = null,  // sum of per-zone "high" watermark (pages)
    val pss: PssSample? = null,     // per-app PSS (dumpsys meminfo), only with --package
    val jankPct: Double? = null,    // % janky frames this tick (dumpsys gfxinfo deltas)
    val mainThreadCpuPct: Double? = null, // app main thread CPU% (/proc/<pid>/task/<pid>/stat deltas)
    val frameInputMs: Double? = null,     // avg ms per frame phase this tick (gfxinfo framestats)
    val frameAnimMs: Double? = null,
    val frameLayoutMs: Double? = null,
    val frameDrawMs: Double? = null,
    val appCpuPct: Double? = null,        // whole-process CPU% (all threads summed) — only with --package
    val mediaProcs: Map<String, Double?> = emptyMap(), // media/HAL process CPU% keyed by proc name
    val topProcs: List<ProcessSample> = emptyList(),   // top-10 processes by CPU% this tick (only when topProcsEnabled)
    val videoState: String? = null,                    // ExoPlayer/Bitmovin state: READY/BUFFERING/PAUSED/IDLE (logcat, STB)
    val tunnelMode: String? = null,                    // "tunneled" | "non_tunneled" | "unknown" | "n/a" (logcat, STB)
    val topActivity: String? = null,                   // current foreground activity component (dumpsys)
    val topMemProcs: List<ProcMemSample> = emptyList() // top processes by RSS this tick (/proc/*/stat)
)

/** One process entry in the top-10 CPU list. */
data class ProcessSample(val pid: Int, val name: String, val cpuPct: Double)

/** One process entry in the top memory (PSS) list. */
data class ProcMemSample(val pid: Int, val name: String, val pssMb: Double)

/** Per-frame phase durations parsed from `dumpsys gfxinfo <pkg> framestats`. */
data class FramePhases(
    val vsync: Long,
    val inputMs: Double,
    val animMs: Double,
    val layoutMs: Double,
    val drawMs: Double
)

data class GfxData(
    val totalFrames: Long,
    val jankyFrames: Long,
    val frames: List<FramePhases>
)

/** App Summary PSS breakdown from `dumpsys meminfo <package>`, in MB. */
data class PssSample(
    val totalMb: Double?,
    val javaHeapMb: Double?,
    val nativeHeapMb: Double?,
    val codeMb: Double?,
    val stackMb: Double?,
    val graphicsMb: Double?,
    val privateOtherMb: Double?,
    val systemMb: Double?
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: String,
    val cpuCores: Int,
    val totalMemMb: Double
)
