package devicemonitor

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

/** Parsed result of one combined snapshot. */
class Snapshot(raw: String) {
    val cpuTicks: CpuTicks?
    val memTotalKb: Long
    val memFreeKb: Long
    val memAvailableKb: Long
    val cachedKb: Long
    val swapTotalKb: Long
    val swapFreeKb: Long
    val load1: Double?
    var psiCpuSome: Double?
    var psiMemSome: Double?
    var psiMemFull: Double?
    var psiIoSome: Double?
    val perCoreTicks: List<CpuTicks>
    val netRxBytes: Long?
    val netTxBytes: Long?
    val cpuTempC: Double?
    val cpuFreqMhz: Double?
    val pgMajFault: Long?
    // /proc/zoneinfo — memory zone watermarks (in pages)
    val zoneActiveCount: Int?
    val zoneFreePages: Long?
    val zoneWmMinPages: Long?
    val zoneWmLowPages: Long?
    val zoneWmHighPages: Long?

    init {
        val sections = splitSections(raw)
        cpuTicks = parseCpuTicks(sections["STAT"] ?: "")
        perCoreTicks = parsePerCoreTicks(sections["STAT"] ?: "")

        val net = parseNetDev(sections["NET"])
        netRxBytes = net?.first
        netTxBytes = net?.second
        cpuTempC = parseThermal(sections["THERMAL"])
        cpuFreqMhz = parseFreq(sections["FREQ"])
        pgMajFault = parseVmstat(sections["VMSTAT"])

        val mem = parseMeminfo(sections["MEM"] ?: "")
        memTotalKb = mem["MemTotal"] ?: 0L
        memFreeKb = mem["MemFree"] ?: 0L
        memAvailableKb = mem["MemAvailable"] ?: memFreeKb
        cachedKb = mem["Cached"] ?: 0L
        swapTotalKb = mem["SwapTotal"] ?: 0L
        swapFreeKb = mem["SwapFree"] ?: 0L

        load1 = (sections["LOAD"] ?: "").trim()
            .split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()

        psiCpuSome = parsePsi(sections["PSI_CPU"], "some")
        psiMemSome = parsePsi(sections["PSI_MEM"], "some")
        psiMemFull = parsePsi(sections["PSI_MEM"], "full")
        psiIoSome = parsePsi(sections["PSI_IO"], "some")

        val zone = parseZoneinfo(sections["ZONEINFO"])
        zoneActiveCount = zone?.active
        zoneFreePages = zone?.freePages
        zoneWmMinPages = zone?.minPages
        zoneWmLowPages = zone?.lowPages
        zoneWmHighPages = zone?.highPages
    }

    companion object {
        /**
         * Build a single shell command that fetches all enabled metric sections in one
         * round-trip. Disabled sections are omitted entirely to reduce device CPU and
         * shell overhead.
         *
         * @param psi          include /proc/pressure/{cpu,memory,io}
         * @param network      include /proc/net/dev (RX/TX bytes)
         * @param thermal      include /sys/class/thermal (CPU temperature)
         * @param cpuFreq      include scaling_cur_freq (CPU clock speed)
         * @param pageFaults   include pgmajfault from /proc/vmstat
         * @param perCore      always parsed from ===STAT=== (no separate section needed)
         */
        fun buildCommand(
            psi: Boolean = true,
            network: Boolean = true,
            thermal: Boolean = true,
            cpuFreq: Boolean = true,
            pageFaults: Boolean = true,
            load: Boolean = true,
            zoneinfo: Boolean = true
        ): String = buildString {
            // Always included — CPU ticks + memory are the irreducible core of every sample
            append("echo ===STAT===; cat /proc/stat 2>/dev/null; ")
            append("echo ===MEM===; cat /proc/meminfo 2>/dev/null; ")
            if (zoneinfo) append("echo ===ZONEINFO===; cat /proc/zoneinfo 2>/dev/null; ")
            if (load) append("echo ===LOAD===; cat /proc/loadavg 2>/dev/null; ")
            if (psi) {
                append("echo ===PSI_CPU===; cat /proc/pressure/cpu 2>/dev/null; ")
                append("echo ===PSI_MEM===; cat /proc/pressure/memory 2>/dev/null; ")
                append("echo ===PSI_IO===; cat /proc/pressure/io 2>/dev/null; ")
            }
            if (network) append("echo ===NET===; cat /proc/net/dev 2>/dev/null; ")
            if (thermal) append(
                "echo ===THERMAL===; for z in /sys/class/thermal/thermal_zone*/; do " +
                "echo \"\$(cat \${z}type 2>/dev/null) \$(cat \${z}temp 2>/dev/null)\"; done 2>/dev/null; "
            )
            if (cpuFreq) append("echo ===FREQ===; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null; ")
            if (pageFaults) append("echo ===VMSTAT===; grep pgmajfault /proc/vmstat 2>/dev/null")
        }.trimEnd(';', ' ')

        /** Full command with every section enabled (used when no config is available). */
        val COMMAND: String get() = buildCommand()

        private fun splitSections(raw: String): Map<String, String> {
            val sections = mutableMapOf<String, String>()
            var current: String? = null
            val sb = StringBuilder()
            for (line in raw.lines()) {
                val m = Regex("^===(\\w+)===$").find(line.trim())
                if (m != null) {
                    current?.let { sections[it] = sb.toString() }
                    sb.setLength(0)
                    current = m.groupValues[1]
                } else if (current != null) {
                    sb.appendLine(line)
                }
            }
            current?.let { sections[it] = sb.toString() }
            return sections
        }

        /**
         * Aggregate "cpu" line: user nice system idle iowait irq softirq steal ...
         * idle time = idle + iowait.
         */
        private fun parseCpuTicks(stat: String): CpuTicks? {
            val line = stat.lines().firstOrNull { it.startsWith("cpu ") } ?: return null
            val f = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (f.size < 5) return null
            val total = f.take(8).sum()
            val idle = f[3] + f[4]
            return CpuTicks(total, idle)
        }

        /** Per-core lines "cpu0 ... cpuN", ordered by core index. */
        private fun parsePerCoreTicks(stat: String): List<CpuTicks> =
            stat.lines()
                .mapNotNull { line ->
                    val m = Regex("^cpu(\\d+)\\s").find(line) ?: return@mapNotNull null
                    val f = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (f.size < 5) return@mapNotNull null
                    m.groupValues[1].toInt() to CpuTicks(f.take(8).sum(), f[3] + f[4])
                }
                .sortedBy { it.first }
                .map { it.second }

        /** /proc/net/dev: sum rx/tx bytes over all interfaces except loopback. */
        private fun parseNetDev(section: String?): Pair<Long, Long>? {
            if (section == null) return null
            var rx = 0L; var tx = 0L; var found = false
            for (line in section.lines()) {
                val m = Regex("^\\s*([\\w.\\-@]+):\\s*(.*)$").find(line) ?: continue
                val iface = m.groupValues[1]
                if (iface == "lo") continue
                val f = m.groupValues[2].trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                if (f.size < 9) continue
                rx += f[0]; tx += f[8]; found = true
            }
            return if (found) rx to tx else null
        }

        /** Thermal zones "type temp"; pick the hottest CPU/SoC-ish zone, fall back to hottest overall. */
        private fun parseThermal(section: String?): Double? {
            if (section == null) return null
            val zones = section.lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val raw = parts.last().toDoubleOrNull() ?: return@mapNotNull null
                val temp = if (raw > 1000) raw / 1000.0 else raw   // milli-degrees on most kernels
                if (temp <= 0.0 || temp > 150.0) return@mapNotNull null
                parts.dropLast(1).joinToString(" ").lowercase() to temp
            }
            if (zones.isEmpty()) return null
            val cpuish = zones.filter { (type, _) ->
                listOf("cpu", "soc", "tsens", "cluster", "core").any { type.contains(it) }
            }
            return (cpuish.ifEmpty { zones }).maxOf { it.second }
        }

        /** scaling_cur_freq values are kHz; report the mean across cores in MHz. */
        private fun parseFreq(section: String?): Double? {
            val khz = section?.lines()?.mapNotNull { it.trim().toLongOrNull() } ?: return null
            if (khz.isEmpty()) return null
            return khz.average() / 1000.0
        }

        private fun parseVmstat(section: String?): Long? =
            section?.lines()
                ?.firstOrNull { it.trim().startsWith("pgmajfault") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()

        private fun parseMeminfo(mem: String): Map<String, Long> {
            val map = mutableMapOf<String, Long>()
            val re = Regex("^(\\w+):\\s+(\\d+)\\s*kB?")
            for (line in mem.lines()) {
                re.find(line.trim())?.let { map[it.groupValues[1]] = it.groupValues[2].toLong() }
            }
            return map
        }

        private data class ZoneStats(
            val active: Int, val freePages: Long,
            val minPages: Long, val lowPages: Long, val highPages: Long
        )

        /**
         * Parse /proc/zoneinfo. Each "Node N, zone NAME" block carries "pages free" plus the
         * min / low / high reclaim watermarks (all in pages). We sum free pages and each
         * watermark across the *active* zones (managed pages > 0) and count how many are active.
         *
         * When free pages fall toward the low watermark the kernel wakes kswapd to reclaim;
         * below min it enters costly direct reclaim — so charting free against the three
         * watermarks shows exactly how close the device is to memory trouble.
         */
        private fun parseZoneinfo(text: String?): ZoneStats? {
            if (text.isNullOrBlank()) return null
            var active = 0
            var free = 0L; var min = 0L; var low = 0L; var high = 0L
            var inZone = false
            var cFree = 0L; var cMin = 0L; var cLow = 0L; var cHigh = 0L; var cManaged = 0L
            fun flush() {
                if (inZone && cManaged > 0) { active++; free += cFree; min += cMin; low += cLow; high += cHigh }
            }
            fun num(line: String, key: String) = line.removePrefix(key).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("Node ") && line.contains("zone")) {
                    flush()
                    inZone = true; cFree = 0; cMin = 0; cLow = 0; cHigh = 0; cManaged = 0
                    continue
                }
                if (!inZone) continue
                when {
                    line.startsWith("pages free") -> cFree = num(line, "pages free")
                    line.startsWith("min ")       -> cMin = num(line, "min")
                    line.startsWith("low ")       -> cLow = num(line, "low")
                    line.startsWith("high ")      -> cHigh = num(line, "high")
                    line.startsWith("managed ")   -> cManaged = num(line, "managed")
                }
            }
            flush()
            return ZoneStats(active, free, min, low, high)
        }

        /** PSI line: "some avg10=1.23 avg60=0.50 avg300=0.10 total=12345" */
        fun parsePsi(section: String?, kind: String): Double? {
            val line = section?.lines()?.firstOrNull { it.trim().startsWith(kind) } ?: return null
            return Regex("avg10=([0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        /**
         * App Summary PSS from `dumpsys meminfo <package>` (values in KB → returned in MB).
         * Handles the common line shapes: "Java Heap:  10052", "TOTAL PSS: 111545",
         * "TOTAL: 111545" and "111545 TOTAL".
         */
        fun parsePss(dump: String): PssSample? {
            val marker = dump.indexOf("App Summary")
            val window = if (marker >= 0) dump.substring(marker) else dump

            fun category(label: String): Double? =
                Regex("^\\s*${Regex.escape(label)}:\\s+(\\d+)", RegexOption.MULTILINE)
                    .find(window)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1024.0)

            val total =
                Regex("^\\s*TOTAL(?:\\s+PSS)?:\\s*(\\d+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
                    .find(window)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1024.0)
                    ?: Regex("^\\s*(\\d+)\\s+TOTAL\\s*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
                        .find(window)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1024.0)

            val pss = PssSample(
                totalMb = total,
                javaHeapMb = category("Java Heap"),
                nativeHeapMb = category("Native Heap"),
                codeMb = category("Code"),
                stackMb = category("Stack"),
                graphicsMb = category("Graphics"),
                privateOtherMb = category("Private Other"),
                systemMb = category("System")
            )
            return if (pss.totalMb == null && pss.javaHeapMb == null && pss.nativeHeapMb == null) null else pss
        }
    }
}

class MetricsCollector(
    private val adb: Adb,
    private val target: String,
    private val thermalEnabled: Boolean = true,
    private val networkEnabled: Boolean = true,
    private val pageFaultsEnabled: Boolean = true,
    private val cpuFreqEnabled: Boolean = true,
    val perCoreEnabled: Boolean = true,   // read by Session to decide whether to emit per-core data
    private val psiEnabled: Boolean = true,
    private val loadEnabled: Boolean = true,
    private val zoneInfoEnabled: Boolean = true
) {
    /** Pre-built shell command for this collector's enabled metric set. */
    private val snapshotCommand = Snapshot.buildCommand(
        psi = psiEnabled,
        network = networkEnabled,
        thermal = thermalEnabled,
        cpuFreq = cpuFreqEnabled,
        pageFaults = pageFaultsEnabled,
        load = loadEnabled,
        zoneinfo = zoneInfoEnabled
    )

    /** When true, PSI files are read with one `cat` per file (some builds reject the combined command). */
    private var psiPerFileMode = false

    /**
     * Verify PSI availability up front. Some devices return nothing for the combined
     * marker command but answer a plain `cat /proc/pressure/<name>` (the approach used
     * by scripts/psi_pressure_monitor.py) — detect that and switch to per-file mode.
     * Returns a human-readable status line for the console.
     */
    fun probePsi(): String {
        if (!psiEnabled) return "PSI: disabled by user"
        val combined = snapshotRaw()
        if (combined.psiCpuSome != null || combined.psiMemSome != null || combined.psiIoSome != null) {
            return "PSI: available"
        }
        val raw = try {
            adb.shell(target, "cat /proc/pressure/cpu")
        } catch (e: Exception) {
            return "PSI: not readable (${e.message}) — pressure charts will be skipped"
        }
        return if (raw.contains("avg10")) {
            psiPerFileMode = true
            "PSI: available (per-file mode)"
        } else {
            "PSI: not available — device said: '${raw.take(120).ifBlank { "<empty>" }}'. " +
                "Pressure charts will be skipped (kernel may lack CONFIG_PSI)."
        }
    }

    /** App Summary PSS for one package; null if the app isn't running or parsing fails. */
    fun pss(packageName: String): PssSample? = try {
        Snapshot.parsePss(adb.shell(target, "dumpsys meminfo $packageName"))
    } catch (e: Exception) {
        null
    }

    fun deviceInfo(): DeviceInfo {
        val props = adb.shell(
            target,
            "getprop ro.product.manufacturer; getprop ro.product.model; " +
            "getprop ro.build.version.release; getprop ro.build.version.sdk; " +
            "grep -c ^processor /proc/cpuinfo"
        ).lines().map { it.trim() }
        val snap = snapshot()
        return DeviceInfo(
            manufacturer = props.getOrElse(0) { "?" },
            model = props.getOrElse(1) { "?" },
            androidVersion = props.getOrElse(2) { "?" },
            sdk = props.getOrElse(3) { "?" },
            cpuCores = props.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
            totalMemMb = snap.memTotalKb / 1024.0
        )
    }

    fun snapshot(): Snapshot {
        val snap = snapshotRaw()
        if (psiEnabled && psiPerFileMode) {
            snap.psiCpuSome = psiAvg10("cpu", "some")
            val mem = psiFile("memory")
            snap.psiMemSome = Snapshot.parsePsi(mem, "some")
            snap.psiMemFull = Snapshot.parsePsi(mem, "full")
            snap.psiIoSome = psiAvg10("io", "some")
        }
        return snap
    }

    private fun snapshotRaw(): Snapshot = Snapshot(adb.shell(target, snapshotCommand))

    private fun psiFile(name: String): String? = try {
        adb.shell(target, "cat /proc/pressure/$name")
    } catch (e: Exception) {
        null
    }

    private fun psiAvg10(name: String, kind: String): Double? = Snapshot.parsePsi(psiFile(name), kind)

    /**
     * The current foreground (resumed) activity component, e.g.
     * "com.telekom.onetv.tv.hr/com.telekom.atvretail.homeui.view.HomeActivity", or null.
     * Grep on-device so only the one relevant line is transferred (dumpsys output is large).
     */
    fun topActivity(): String? {
        val out = try {
            adb.shell(target, "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")
        } catch (e: Exception) { "" }
        val comp = out.lines().asSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                line.split(Regex("\\s+")).firstOrNull { it.contains("/") && !it.startsWith("{") }
            }
            .firstOrNull()
        if (comp != null) return comp.trimEnd('}', ',').take(160)
        // Fallback: focused window.
        return try {
            adb.shell(target, "dumpsys window windows | grep -m1 -E 'mCurrentFocus|mFocusedApp'")
                .lines().firstOrNull { it.isNotBlank() }
                ?.split(Regex("\\s+"))?.firstOrNull { it.contains("/") }?.trimEnd('}', ',')?.take(160)
        } catch (e: Exception) { null }
    }

    private var cachedPid: Int? = null

    /** Pid of the app's process (cached; call [invalidatePid] if the app may have restarted). */
    fun pid(packageName: String): Int? {
        cachedPid?.let { return it }
        val out = try { adb.shell(target, "pidof $packageName") } catch (e: Exception) { return null }
        val p = out.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
        cachedPid = p
        return p
    }

    fun invalidatePid() {
        cachedPid = null
    }

    /**
     * Cumulative CPU ticks (utime+stime) of the app's MAIN thread —
     * /proc/<pid>/task/<pid>/stat, since the main thread's tid == pid.
     * At the usual 100 Hz clock, delta-ticks / delta-seconds == CPU %.
     */
    fun mainThreadTicks(pid: Int): Long? {
        val out = try { adb.shell(target, "cat /proc/$pid/task/$pid/stat") } catch (e: Exception) { return null }
        // comm can contain spaces — fields restart after the last ')'.
        val rest = out.substringAfterLast(')').trim().split(Regex("\\s+"))
        val utime = rest.getOrNull(11)?.toLongOrNull()   // overall field 14
        val stime = rest.getOrNull(12)?.toLongOrNull()   // overall field 15
        return if (utime != null && stime != null) utime + stime else null
    }

    /**
     * Cumulative CPU ticks (utime+stime) summed across ALL threads of the app process.
     * Reads each thread's stat file under /proc/pid/task/ in one shell call.
     * A value >100 per second is normal for multi-threaded apps (100 ticks/s = one full core).
     */
    fun appProcessTicks(pid: Int): Long? {
        val out = try {
            adb.shell(target, "cat /proc/$pid/task/*/stat 2>/dev/null")
        } catch (e: Exception) { return null }
        var total = 0L; var found = false
        for (line in out.lines()) {
            if (line.isBlank()) continue
            val rest = line.substringAfterLast(')').trim().split(Regex("\\s+"))
            val u = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val s = rest.getOrNull(12)?.toLongOrNull() ?: continue
            total += u + s; found = true
        }
        return if (found) total else null
    }

    /**
     * Fallback whole-process CPU% from `dumpsys cpuinfo`, for non-rooted devices where the
     * per-thread stat files under /proc/<pid>/task aren't readable. Sums every row for the
     * package (main + ":sub" processes). dumpsys reports a snapshot % since its last internal
     * window, so it's coarser than the /proc tick delta, but it works without root.
     * Example row: "  12% 1234/com.pkg.name: 8% user + 4% kernel".
     */
    fun appCpuFromDumpsys(packageName: String): Double? {
        val out = try { adb.shell(target, "dumpsys cpuinfo") } catch (e: Exception) { return null }
        val re = Regex("([0-9.]+)%\\s+\\d+/" + Regex.escape(packageName) + "(?::[\\w.]+)?:")
        var total = 0.0; var found = false
        for (line in out.lines()) {
            val m = re.find(line) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            total += v; found = true
        }
        return if (found) total else null
    }

    private val mediaPidCache = mutableMapOf<String, Int?>()
    /** Used to embed literal $ in shell commands without triggering Kotlin string interpolation. */
    private val S = "\$"

    /**
     * Reads cumulative CPU ticks for known media/audio HAL processes in a single shell call.
     * Process names: mediaserver, media.codec, audioserver, vendor.mediaswcodec, OMX HAL.
     * PIDs are resolved via pidof (cached); stale entries are cleared on GONE reply.
     * Returns a map of process name -> total ticks (utime+stime).
     */
    fun mediaProcessTicks(): Map<String, Long?> {
        val names = listOf(
            "mediaserver", "media.codec", "audioserver",
            "vendor.mediaswcodec", "android.hardware.media.omx@1.0-service"
        )
        val cmdParts = names.map { name ->
            val cached = mediaPidCache[name]
            if (cached != null) {
                "echo '===PROC $name $cached==='; cat /proc/$cached/stat 2>/dev/null || echo GONE"
            } else {
                // Use awk to resolve PID & read stat without shell variable assignment
                // (shell vars like $pid would clash with Kotlin string templates)
                "pidof '$name' 2>/dev/null | awk 'NR==1{printf \"===PROC $name %s===\\n\",${S}1; system(\"cat /proc/\"${S}1\"/stat 2>/dev/null\")}'"
            }
        }
        val out = try {
            adb.shell(target, cmdParts.joinToString("; "))
        } catch (e: Exception) { return emptyMap() }

        val result = mutableMapOf<String, Long?>()
        var curName: String? = null
        for (line in out.lines()) {
            val m = Regex("^===PROC ([^ ]+) (\\d+)===$").find(line.trim())
            if (m != null) {
                curName = m.groupValues[1]
                mediaPidCache[curName!!] = m.groupValues[2].toIntOrNull()
                continue
            }
            val cn = curName ?: continue
            if (line.trim() == "GONE") { mediaPidCache[cn] = null; curName = null; continue }
            if (line.isBlank()) continue
            val rest = line.substringAfterLast(')').trim().split(Regex("\\s+"))
            val u = rest.getOrNull(11)?.toLongOrNull()
            val s = rest.getOrNull(12)?.toLongOrNull()
            if (u != null && s != null) { result[cn] = u + s }
            curName = null
        }
        return result
    }

    /**
     * Jank totals + per-frame phase timings from `dumpsys gfxinfo <pkg> framestats`.
     * Column positions are resolved from the PROFILEDATA header row (they vary by version).
     */
    fun gfxFramestats(packageName: String): GfxData? = try {
        val out = adb.shell(target, "dumpsys gfxinfo $packageName framestats")
        val total = Regex("Total frames rendered:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        val janky = Regex("Janky frames:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        if (total == null || janky == null) null
        else GfxData(total, janky, parseFramestats(out))
    } catch (e: Exception) {
        null
    }

    private fun parseFramestats(out: String): List<FramePhases> {
        val lines = out.lines()
        val headerIdx = lines.indexOfFirst { it.startsWith("Flags,") }
        if (headerIdx < 0) return emptyList()
        val cols = lines[headerIdx].split(",").map { it.trim() }
        fun col(name: String) = cols.indexOf(name)
        val cVsync = col("IntendedVsync")
        val cInput = col("HandleInputStart")
        val cAnim = col("AnimationStart")
        val cTrav = col("PerformTraversalsStart")
        val cDraw = col("DrawStart")
        val cSync = col("SyncQueued")
        if (listOf(cVsync, cInput, cAnim, cTrav, cDraw, cSync).any { it < 0 }) return emptyList()

        val frames = mutableListOf<FramePhases>()
        for (line in lines.drop(headerIdx + 1)) {
            if (!line.firstOrNull().let { it != null && (it.isDigit() || it == '-') }) break
            val f = line.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (f.size < cols.size - 1) continue
            fun ms(a: Int, b: Int): Double = ((f[b] - f[a]) / 1e6)
            val input = ms(cInput, cAnim)
            val anim = ms(cAnim, cTrav)
            val layout = ms(cTrav, cDraw)
            val draw = ms(cDraw, cSync)
            if (listOf(input, anim, layout, draw).any { it < 0 || it > 10_000 }) continue
            frames += FramePhases(f[cVsync], input, anim, layout, draw)
        }
        return frames
    }

    /** Raw head of dumpsys meminfo output, for diagnostics when parsing fails. */
    fun pssRaw(packageName: String): String = try {
        adb.shell(target, "dumpsys meminfo $packageName")
    } catch (e: Exception) {
        "<adb error: ${e.message}>"
    }

    /**
     * Reads all process stat files (cat /proc/[pid]/stat for all numeric dirs) in one shell call
     * and returns a map of pid -> (name, cumulativeTicks).
     * Used by Session to compute per-process CPU% deltas each tick.
     */
    fun allProcessTicks(): Map<Int, Pair<String, Long>> {
        val out = try {
            adb.shell(target, "cat /proc/[0-9]*/stat 2>/dev/null")
        } catch (e: Exception) { return emptyMap() }
        val result = mutableMapOf<Int, Pair<String, Long>>()
        for (line in out.lines()) {
            if (line.isBlank()) continue
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx < 0) continue
            val pid = line.substring(0, spaceIdx).toIntOrNull() ?: continue
            val openParen = line.indexOf('(')
            val closeParen = line.lastIndexOf(')')
            if (openParen < 0 || closeParen < 0 || closeParen <= openParen) continue
            val name = line.substring(openParen + 1, closeParen).take(20)
            val rest = line.substring(closeParen + 2).trim().split(Regex("\\s+"))
            val utime = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val stime = rest.getOrNull(12)?.toLongOrNull() ?: continue
            result[pid] = name to (utime + stime)
        }
        return result
    }

    /** The memory metric surfaced by topMemoryProcs(): "RSS" (ps/proc) or "PSS" (procrank). */
    @Volatile var lastMemSource: String = "RSS"
        private set
    @Volatile private var memStrategyLogged = false

    // Memory reads use a SHORT timeout (8 s, not the default 20 s): if procrank/su hangs it must
    // not hold an adb shell long enough to stall the main sampling loop or delay Stop.
    private fun sh(cmd: String): String = try { adb.shell(target, cmd, 8) } catch (e: Exception) { "" }

    /**
     * Top processes by memory (MB) — deliberately **RSS only, never procrank**. procrank walks every
     * process's pagemap to compute PSS and needs root/`su`; it's heavy and was stalling the sampling
     * loop, so it's used only for the on-demand Procrank Snapshot, never for the live chart.
     *
     * RSS is kernel-maintained and returned by a single `ps` call (sub-second, tiny output). Devices
     * differ in which `ps -o` column spelling they accept, so we try a few forms and use the first
     * that yields rows; a `/proc/<pid>/stat` glob is the last resort. The winning strategy + row
     * count is logged once so the device's behaviour is visible in the log file.
     */
    fun topMemoryProcs(topN: Int = 12): List<ProcMemSample> {
        lastMemSource = "RSS"
        for (cmd in listOf(
            "ps -A -o PID,RSS,NAME", "ps -A -o PID,RSS,COMM", "ps -A -o PID,RSS,ARGS",
            "ps -o PID,RSS,NAME", "ps -A -o pid,rss,comm", "ps -e -o pid,rss,comm", "ps"
        )) {
            val list = parsePsRss(sh("$cmd 2>/dev/null"))
            if (list.size >= 3) { logStrategy(cmd, list.size); return finalizeMem(list, topN) }
        }
        val stat = topMemoryFromStat()
        logStrategy(if (stat.isEmpty()) "NONE — all ps strategies empty" else "cat /proc/*/stat", stat.size)
        return finalizeMem(stat, topN)
    }

    private fun finalizeMem(list: List<ProcMemSample>, topN: Int): List<ProcMemSample> =
        list.sortedByDescending { it.pssMb }.take(topN)

    private fun logStrategy(src: String, n: Int) {
        if (memStrategyLogged) return
        memStrategyLogged = true
        println("[top-mem] strategy='$src'  processes=$n  (source=$lastMemSource)")
    }

    /**
     * Parse any `ps` output by reading the HEADER to locate the PID, RSS and name columns — so it
     * works for `-o PID,RSS,NAME` (header "PID RSS NAME") AND bare `ps -A`
     * (header "USER PID PPID VSZ RSS WCHAN ADDR S NAME"). RSS column is KiB. Name is taken from the
     * name column to end-of-line (handles cmdlines with spaces); the basename is kept.
     */
    private fun parsePsRss(out: String): List<ProcMemSample> {
        val lines = out.lines()
        // Find header: a line containing both PID and RSS (case-insensitive).
        val headerIdx = lines.indexOfFirst {
            val u = it.uppercase()
            u.contains("PID") && (u.contains("RSS") || u.contains("RSZ"))
        }
        var pidCol = 0; var rssCol = 1; var nameCol = 2
        if (headerIdx >= 0) {
            val h = lines[headerIdx].trim().split(Regex("\\s+")).map { it.uppercase() }
            h.indexOf("PID").let { if (it >= 0) pidCol = it }
            (if (h.contains("RSS")) h.indexOf("RSS") else h.indexOf("RSZ")).let { if (it >= 0) rssCol = it }
            val ni = listOf("NAME", "COMM", "CMD", "COMMAND", "ARGS").map { h.indexOf(it) }.firstOrNull { it >= 0 }
            nameCol = ni ?: (h.size - 1)
        }
        val list = mutableListOf<ProcMemSample>()
        for ((i, line) in lines.withIndex()) {
            if (i == headerIdx) continue
            val t = line.trim()
            if (t.isEmpty()) continue
            val cols = t.split(Regex("\\s+"))
            if (cols.size <= maxOf(pidCol, rssCol, nameCol)) continue
            val pid = cols[pidCol].toIntOrNull() ?: continue
            val rssKb = cols[rssCol].toLongOrNull() ?: continue
            val rawName = cols.subList(nameCol.coerceAtMost(cols.size - 1), cols.size).joinToString(" ")
            val name = rawName.substringAfterLast('/').ifBlank { rawName }.take(28)
            val mb = rssKb / 1024.0
            if (mb >= 1.0) list += ProcMemSample(pid, name, mb)
        }
        return list
    }

    /** Fallback: RSS (MB) per process from /proc/[pid]/stat field 24 (rss pages). */
    private fun topMemoryFromStat(): List<ProcMemSample> {
        val out = try {
            adb.shell(target, "cat /proc/[0-9]*/stat 2>/dev/null")
        } catch (e: Exception) { return emptyList() }
        val list = mutableListOf<ProcMemSample>()
        for (line in out.lines()) {
            if (line.isBlank()) continue
            val pid = line.substringBefore(' ').toIntOrNull() ?: continue
            val open = line.indexOf('('); val close = line.lastIndexOf(')')
            if (open < 0 || close < 0 || close <= open) continue
            val name = line.substring(open + 1, close).take(28)
            val rest = line.substring(close + 2).trim().split(Regex("\\s+"))
            val rssPages = rest.getOrNull(21)?.toLongOrNull() ?: continue     // overall field 24 = rss
            val mb = rssPages * 4.0 / 1024.0                                  // assume 4 KiB pages
            if (mb >= 1.0) list += ProcMemSample(pid, name, mb)
        }
        return list
    }

    fun toSample(
        prev: Snapshot?, dtSec: Double,
        snap: Snapshot, elapsedSec: Double,
        pss: PssSample? = null, jankPct: Double? = null,
        mainThreadCpuPct: Double? = null, phases: FramePhases? = null,
        appCpuPct: Double? = null,
        mediaProcs: Map<String, Double?> = emptyMap(),
        topProcs: List<ProcessSample> = emptyList(),
        videoState: String? = null,
        tunnelMode: String? = null,
        directReclaimPerSec: Double? = null,
        topActivity: String? = null,
        topMemProcs: List<ProcMemSample> = emptyList()
    ): Sample {
        fun util(p: CpuTicks?, c: CpuTicks?): Double? {
            if (p == null || c == null) return null
            val dTotal = c.total - p.total
            val dIdle = c.idle - p.idle
            return if (dTotal > 0) (100.0 * (dTotal - dIdle) / dTotal).coerceIn(0.0, 100.0) else null
        }

        val cpuUtil = util(prev?.cpuTicks, snap.cpuTicks)
        val memTotalMb = snap.memTotalKb / 1024.0
        val memUsedMb = (snap.memTotalKb - snap.memAvailableKb) / 1024.0

        val perCore = if (perCoreEnabled && prev != null && snap.perCoreTicks.isNotEmpty() &&
            prev.perCoreTicks.size == snap.perCoreTicks.size
        ) {
            snap.perCoreTicks.mapIndexed { i, c -> util(prev.perCoreTicks[i], c) }
        } else null

        fun rate(prevV: Long?, curV: Long?): Double? {
            if (prevV == null || curV == null || dtSec <= 0) return null
            val d = curV - prevV
            return if (d >= 0) d / dtSec else null   // counter reset → skip point
        }

        val swapUsed = if (snap.swapTotalKb > 0)
            (snap.swapTotalKb - snap.swapFreeKb) / 1024.0 else null

        return Sample(
            elapsedSec = elapsedSec,
            cpuUtilPct = cpuUtil,
            cpuIdlePct = cpuUtil?.let { (100.0 - it).coerceIn(0.0, 100.0) },
            perCoreUtilPct = perCore,
            netRxKbps = rate(prev?.netRxBytes, snap.netRxBytes)?.div(1024.0),
            netTxKbps = rate(prev?.netTxBytes, snap.netTxBytes)?.div(1024.0),
            cpuTempC = snap.cpuTempC,
            cpuFreqMhz = snap.cpuFreqMhz,
            majorFaultsPerSec = rate(prev?.pgMajFault, snap.pgMajFault),
            directReclaimPerSec = directReclaimPerSec,
            zoneActiveCount = snap.zoneActiveCount,
            zoneFreePages = snap.zoneFreePages?.toDouble(),
            zoneWmMinPages = snap.zoneWmMinPages?.toDouble(),
            zoneWmLowPages = snap.zoneWmLowPages?.toDouble(),
            zoneWmHighPages = snap.zoneWmHighPages?.toDouble(),
            cpuPsiSomeAvg10 = snap.psiCpuSome,
            memPsiSomeAvg10 = snap.psiMemSome,
            memPsiFullAvg10 = snap.psiMemFull,
            ioPsiSomeAvg10 = snap.psiIoSome,
            memTotalMb = memTotalMb,
            memUsedMb = memUsedMb,
            memUsedPct = if (memTotalMb > 0) (100.0 * memUsedMb / memTotalMb).coerceIn(0.0, 100.0) else 0.0,
            memAvailableMb = snap.memAvailableKb / 1024.0,
            cachedMb = snap.cachedKb / 1024.0,
            swapUsedMb = swapUsed,
            load1 = snap.load1,
            pss = pss,
            jankPct = jankPct,
            mainThreadCpuPct = mainThreadCpuPct,
            frameInputMs = phases?.inputMs,
            frameAnimMs = phases?.animMs,
            frameLayoutMs = phases?.layoutMs,
            frameDrawMs = phases?.drawMs,
            appCpuPct = appCpuPct,
            mediaProcs = mediaProcs,
            topProcs = topProcs,
            videoState = videoState,
            tunnelMode = tunnelMode,
            topActivity = topActivity,
            topMemProcs = topMemProcs
        )
    }
}
