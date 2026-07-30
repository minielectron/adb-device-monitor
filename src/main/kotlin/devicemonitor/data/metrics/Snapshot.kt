package devicemonitor.data.metrics

import devicemonitor.domain.*

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
