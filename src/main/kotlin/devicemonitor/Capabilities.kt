package devicemonitor

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.RingPlot
import org.jfree.data.general.DefaultPieDataset
import java.awt.Color
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One-shot "device snapshot" of an STB's capabilities and memory footprint. */
object Capabilities {

    data class ProcEntry(val name: String, val pssMb: Double, val category: String)

    data class Snapshot(
        val summary: List<Pair<String, String>>,
        val memTotalMb: Double,
        val memFreeMb: Double,
        val memAvailableMb: Double,
        val androidMb: Double,
        val thirdPartyMb: Double,
        val launcherMb: Double,
        val launcherPkg: String?,
        val processes: List<ProcEntry>
    )

    private val LAUNCHER_PATTERNS = listOf("onetv", "telekom", "magenta", "launcher")
    private val ANDROID_PREFIXES = listOf("android.", "com.android.", "com.google.android.", "com.qualcomm", "vendor.")
    private val ANDROID_NATIVE = setOf(
        "system_server", "surfaceflinger", "zygote", "zygote64", "init", "logd", "servicemanager",
        "media.codec", "mediaserver", "audioserver", "drmserver", "netd", "installd"
    )

    /** Runs the capture over adb. [progress] is called with 0..100 as it advances. */
    fun capture(adb: Adb, target: String, progress: (Int) -> Unit = {}): Snapshot {
        progress(5)
        // --- Hardware / OS summary (one shell round-trip) ---
        val sumCmd = buildString {
            for (k in listOf(
                "ro.product.manufacturer", "ro.product.brand", "ro.product.model", "ro.product.device",
                "ro.board.platform", "ro.product.board", "ro.build.version.release", "ro.build.version.sdk",
                "ro.build.type", "ro.hardware"
            )) append("echo \"$k=\$(getprop $k)\"; ")
            append("echo \"uname=\$(uname -r 2>/dev/null)\"; ")
            append("echo \"cores=\$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)\"; ")
            append("echo \"gov=\$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)\"; ")
        }
        val props = parseKv(safeShell(adb, target, sumCmd))
        progress(25)

        // --- /proc/meminfo ---
        val mem = parseMeminfo(safeShell(adb, target, "cat /proc/meminfo 2>/dev/null"))
        val memTotalMb = kbToMb(mem["MemTotal"])
        val memFreeMb = kbToMb(mem["MemFree"])
        val memAvailMb = kbToMb(mem["MemAvailable"] ?: mem["MemFree"])
        val swapTotalMb = kbToMb(mem["SwapTotal"])
        progress(40)

        // --- Launcher (default HOME) package ---
        val launcherPkg = safeShell(
            adb, target,
            "home=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME 2>/dev/null | tail -n1); echo \${home%%/*}"
        ).trim().ifBlank { null }
        progress(55)

        // --- dumpsys meminfo: Total PSS by process ---
        val pssBlock = safeShell(
            adb, target,
            "dumpsys meminfo 2>/dev/null | sed -n '/Total PSS by process:/,/Total PSS by OOM/p' | head -n 250"
        )
        val procs = parsePssByProcess(pssBlock, launcherPkg)
        progress(80)

        val androidMb = procs.filter { it.category == "android" }.sumOf { it.pssMb }
        val thirdMb = procs.filter { it.category == "third_party" }.sumOf { it.pssMb }
        val launcherMb = procs.filter { it.category == "launcher" }.sumOf { it.pssMb }

        val summary = buildList {
            fun add(k: String, v: String?) { if (!v.isNullOrBlank()) add(k to v) }
            add("Manufacturer", props["ro.product.manufacturer"])
            add("Brand", props["ro.product.brand"])
            add("Model", props["ro.product.model"])
            add("Device", props["ro.product.device"])
            add("SoC / platform", listOfNotNull(props["ro.board.platform"], props["ro.product.board"], props["ro.hardware"]).distinct().joinToString(" · "))
            add("Android", listOfNotNull(props["ro.build.version.release"]?.let { "Android $it" }, props["ro.build.version.sdk"]?.let { "SDK $it" }, props["ro.build.type"]).joinToString("  ·  "))
            add("Kernel", props["uname"])
            add("CPU cores", props["cores"])
            add("CPU governor", props["gov"])
            add("Total RAM", "%.0f MB  (~%.2f GiB)".format(memTotalMb, memTotalMb / 1024.0))
            add("Swap total", if (swapTotalMb > 0) "%.0f MB".format(swapTotalMb) else "none")
            add("MemFree / MemAvailable", "%.0f MB  /  %.0f MB".format(memFreeMb, memAvailMb))
            launcherPkg?.let { add("Launcher package", it) }
        }
        progress(90)

        return Snapshot(
            summary, memTotalMb, memFreeMb, memAvailMb,
            androidMb, thirdMb, launcherMb, launcherPkg,
            procs.sortedByDescending { it.pssMb }
        )
    }

    // ---------- parsing ----------

    private fun safeShell(adb: Adb, target: String, cmd: String): String =
        try { adb.shell(target, cmd) } catch (e: Exception) { "" }

    private fun parseKv(text: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return m
    }

    private fun parseMeminfo(text: String): Map<String, Long> {
        val m = mutableMapOf<String, Long>()
        val re = Regex("^(\\w+):\\s+(\\d+)\\s*kB")
        for (line in text.lines()) re.find(line.trim())?.let { m[it.groupValues[1]] = it.groupValues[2].toLong() }
        return m
    }

    private fun kbToMb(kb: Long?): Double = (kb ?: 0L) / 1024.0

    /** Lines like "  230,096K: com.netflix.ninja (pid 1234 / activities)". */
    private fun parsePssByProcess(text: String, launcherPkg: String?): List<ProcEntry> {
        val re = Regex("^\\s*([\\d,]+)K:\\s+(\\S+)")
        val out = mutableListOf<ProcEntry>()
        for (line in text.lines()) {
            val m = re.find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val name = m.groupValues[2]
            out += ProcEntry(name, kb / 1024.0, classify(name, launcherPkg))
        }
        return out
    }

    private fun classify(name: String, launcherPkg: String?): String {
        val low = name.lowercase()
        val base = name.substringBefore(':')
        if (launcherPkg != null && base == launcherPkg) return "launcher"
        if (LAUNCHER_PATTERNS.any { it.isNotEmpty() && low.contains(it) }) return "launcher"
        if (base in ANDROID_NATIVE || !base.contains('.')) return "android"
        if (ANDROID_PREFIXES.any { base.lowercase().startsWith(it) }) return "android"
        return "third_party"
    }

    // ---------- interactive HTML ----------

    private fun escHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun catColor(cat: String) = when (cat) {
        "android" -> "#2ca02c"; "launcher" -> "#1f77b4"; else -> "#d62728"
    }
    private fun catLabel(cat: String) = when (cat) {
        "android" -> "Android"; "launcher" -> "Launcher"; else -> "3rd-party"
    }

    /** Self-contained interactive HTML snapshot (doughnut with %, summary, process table). */
    fun writeHtml(s: Snapshot, target: String, out: File) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val total = s.androidMb + s.thirdPartyMb + s.launcherMb + s.memFreeMb + s.memAvailableMb
        val sb = StringBuilder(16384)
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>STB Device Snapshot — ").append(escHtml(target)).append("</title>")
        sb.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js\"></script>")
        sb.append("<script src=\"https://cdn.jsdelivr.net/npm/chartjs-plugin-datalabels@2.2.0/dist/chartjs-plugin-datalabels.min.js\"></script>")
        sb.append("<style>")
        sb.append("body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;margin:0;background:#f4f5f7;color:#1f2430}")
        sb.append("header{background:linear-gradient(135deg,#111827,#1e3a5f);color:#fff;padding:16px 24px}")
        sb.append("header h1{margin:0;font-size:20px} header .m{font-size:12px;color:#cbd5e1;margin-top:4px}")
        sb.append(".wrap{padding:18px 24px;max-width:1100px}")
        sb.append(".grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}")
        sb.append(".card{background:#fff;border:1px solid #e2e4e8;border-radius:10px;padding:14px 16px;box-shadow:0 1px 3px rgba(0,0,0,.05)}")
        sb.append(".card h2{font-size:14px;margin:0 0 10px;color:#374151}")
        sb.append("table{width:100%;border-collapse:collapse;font-size:12.5px} td,th{text-align:left;padding:4px 8px;border-bottom:1px solid #eef0f3}")
        sb.append(".sumk{font-weight:700;color:#374151;width:150px} .piebox{position:relative;height:300px}")
        sb.append(".center{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;pointer-events:none}")
        sb.append(".center>div{text-align:center;max-width:150px;line-height:1.4}")
        sb.append(".dot{display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:6px;vertical-align:middle}")
        sb.append(".tag{font-size:10px;color:#fff;border-radius:8px;padding:1px 7px}")
        sb.append("#proc td:last-child,#proc th:last-child{text-align:right;font-family:ui-monospace,Menlo,monospace}")
        sb.append("</style></head><body>")

        sb.append("<header><h1>&#128247; STB Device Snapshot</h1><div class=\"m\">Target: ")
            .append(escHtml(target)).append("  &middot;  Generated: ").append(ts)
            .append("  &middot;  ").append(escHtml("ADB Performance Studio"))
            .append("  &middot;  ").append(escHtml(buildTag())).append("</div></header>")

        sb.append("<div class=\"wrap\"><div class=\"grid\">")

        // Summary card
        sb.append("<div class=\"card\"><h2>Device &amp; OS</h2><table>")
        for ((k, v) in s.summary) {
            sb.append("<tr><td class=\"sumk\">").append(escHtml(k)).append("</td><td>").append(escHtml(v)).append("</td></tr>")
        }
        sb.append("</table></div>")

        // Pie card
        sb.append("<div class=\"card\"><h2>Memory footprint (PSS by category vs free/available RAM)</h2>")
        sb.append("<div class=\"piebox\"><canvas id=\"pie\"></canvas></div>")
        sb.append("</div></div>")  // close grid

        // Process table
        sb.append("<div class=\"card\" style=\"margin-top:18px\"><h2>Top processes / services by PSS</h2>")
        sb.append("<table id=\"proc\"><thead><tr><th>Process</th><th>Category</th><th>PSS</th></tr></thead><tbody>")
        for (p in s.processes.take(150)) {
            val pct = if (s.memTotalMb > 0) 100.0 * p.pssMb / s.memTotalMb else 0.0
            sb.append("<tr><td><span class=\"dot\" style=\"background:").append(catColor(p.category)).append("\"></span>")
                .append(escHtml(p.name)).append("</td><td><span class=\"tag\" style=\"background:").append(catColor(p.category))
                .append("\">").append(catLabel(p.category)).append("</span></td><td>")
                .append("%,.0f MiB  (%.1f%%)".format(p.pssMb, pct)).append("</td></tr>")
        }
        sb.append("</tbody></table></div>")

        // Data + render script (no '$' / template literals — Kotlin string safety)
        sb.append("<script>")
        // Locale.US is REQUIRED: a comma-decimal locale would break the JS number literals below.
        fun js1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
        sb.append("var D={android:").append(js1(s.androidMb))
            .append(",third:").append(js1(s.thirdPartyMb))
            .append(",launcher:").append(js1(s.launcherMb))
            .append(",memFree:").append(js1(s.memFreeMb))
            .append(",memAvail:").append(js1(s.memAvailableMb))
            .append(",memTotal:").append(js1(s.memTotalMb))
            .append(",total:").append(js1(total)).append("};")
        sb.append(RENDER_JS)
        sb.append("</script></body></html>")

        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
    }

    private const val RENDER_JS = """
function fmt(v){return Math.round(v).toLocaleString();}
var labels=['Android','3rd-party apps','Launcher','MemFree','MemAvailable'];
var vals=[D.android,D.third,D.launcher,D.memFree,D.memAvail];
var colors=['#2ca02c','#d62728','#1f77b4','#ff7f0e','#9467bd'];
var tot=vals.reduce(function(a,b){return a+b;},0)||1;
// Draw "Total RAM" at the doughnut's TRUE arc center (a right-side legend shifts the ring
// left, so an HTML overlay centered on the whole box would overlap the ring — this can't).
var centerText={id:'centerText',afterDraw:function(chart){
  var meta=chart.getDatasetMeta(0); if(!meta||!meta.data||!meta.data.length) return;
  var arc=meta.data[0]; var ctx=chart.ctx;
  var cx=arc.x, cy=arc.y;
  ctx.save(); ctx.textAlign='center'; ctx.textBaseline='middle';
  ctx.fillStyle='#6b7280'; ctx.font='11px -apple-system,Segoe UI,Roboto,sans-serif';
  ctx.fillText('Total RAM', cx, cy-9);
  ctx.fillStyle='#374151'; ctx.font='600 18px -apple-system,Segoe UI,Roboto,sans-serif';
  ctx.fillText(fmt(D.memTotal)+' MiB', cx, cy+9);
  ctx.restore();
}};
new Chart(document.getElementById('pie'),{
  type:'doughnut',
  data:{labels:labels,datasets:[{data:vals,backgroundColor:colors,borderColor:'#fff',borderWidth:2}]},
  options:{responsive:true,maintainAspectRatio:false,cutout:'56%',
    plugins:{
      legend:{position:'right',labels:{boxWidth:12,font:{size:11},
        generateLabels:function(ch){return labels.map(function(l,i){
          return {text:l+'  '+fmt(vals[i])+' MiB ('+(100*vals[i]/tot).toFixed(1)+'%)',
            fillStyle:colors[i],strokeStyle:colors[i],index:i};});}}},
      datalabels:{color:'#fff',font:{weight:'bold',size:12},
        formatter:function(v){return v>0?(100*v/tot).toFixed(1)+'%':'';}},
      tooltip:{callbacks:{label:function(c){return c.label+': '+fmt(c.raw)+' MiB ('+(100*c.raw/tot).toFixed(1)+'%)';}}}
    }},
  plugins:[ChartDataLabels, centerText]
});
"""

    // ---------- procrank snapshot (per-process VSS/RSS/PSS/USS) ----------

    data class ProcrankEntry(
        val pid: Int, val name: String,
        val vssMb: Double?, val rssMb: Double?, val pssMb: Double?, val ussMb: Double?
    )

    data class ProcrankSnapshot(
        val entries: List<ProcrankEntry>, val source: String,   // "procrank" or "proc-rss"
        val totalPssMb: Double, val totalRssMb: Double,
        val memTotalMb: Double, val memFreeMb: Double, val memAvailableMb: Double
    )

    /** Sidecar debug log written next to the procrank HTML — captures exactly what the device returned. */
    @Volatile var lastProcrankDebug: String = ""
        private set

    /** One-shot procrank capture (falls back to /proc RSS when procrank/root isn't available). */
    fun captureProcrank(adb: Adb, target: String, progress: (Int) -> Unit = {}): ProcrankSnapshot {
        val dbg = StringBuilder()
        dbg.append("=== procrank capture debug — ").append(LocalDateTime.now()).append(" — target=").append(target).append(" ===\n")
        progress(10)
        val memRaw = safeShell(adb, target, "cat /proc/meminfo 2>/dev/null")
        val mem = parseMeminfo(memRaw)
        val memTotal = kbToMb(mem["MemTotal"]); val memFree = kbToMb(mem["MemFree"])
        val memAvail = kbToMb(mem["MemAvailable"] ?: mem["MemFree"])
        dbg.append("meminfo lines: ").append(memRaw.lines().size)
            .append("  MemTotal=").append(memTotal).append("MiB  MemFree=").append(memFree)
            .append("  MemAvailable=").append(memAvail).append("\n")
        progress(35)

        // Try procrank variants. Accept only if it actually yields data ROWS — on a non-rooted
        // device procrank often prints just the header ("...Pss...Uss...") with no per-process
        // rows, which previously slipped through and produced an empty chart.
        var parsed: List<ProcrankEntry> = emptyList()
        for (cmd in listOf("procrank", "su 0 procrank", "su -c procrank")) {
            val o = safeShell(adb, target, cmd)
            val rows = if (o.contains("Pss", ignoreCase = true)) parseProcrankFull(o) else emptyList()
            dbg.append("\n--- `").append(cmd).append("` -> ").append(o.lines().size)
                .append(" lines, hasPss=").append(o.contains("Pss", ignoreCase = true))
                .append(", parsedRows=").append(rows.size).append("\n")
            dbg.append("    first 6 lines of output:\n")
            o.lines().take(6).forEach { dbg.append("    | ").append(it.take(160)).append("\n") }
            if (rows.size >= 3) { parsed = rows; break }
        }
        progress(80)

        val entries: List<ProcrankEntry>
        val source: String
        if (parsed.isNotEmpty()) { entries = parsed; source = "procrank" }
        else {
            entries = procFromStat(adb, target)
            source = "proc-rss"
            dbg.append("\n--- fallback ps (VSZ,RSS) -> parsedRows=").append(entries.size).append("\n")
            entries.take(5).forEach { dbg.append("    | pid=").append(it.pid).append(" ").append(it.name)
                .append(" rss=").append(it.rssMb).append("MiB vss=").append(it.vssMb).append("MiB\n") }
        }

        val sorted = entries.sortedByDescending { it.pssMb ?: it.rssMb ?: 0.0 }
        val totalPss = entries.mapNotNull { it.pssMb }.sum()
        val totalRss = entries.mapNotNull { it.rssMb }.sum()
        dbg.append("\n=== RESULT: source=").append(source).append("  entries=").append(sorted.size)
            .append("  totalPss=").append(totalPss).append("MiB  totalRss=").append(totalRss).append("MiB ===\n")
        lastProcrankDebug = dbg.toString()
        println(lastProcrankDebug)
        progress(90)
        return ProcrankSnapshot(sorted, source, totalPss, totalRss, memTotal, memFree, memAvail)
    }

    /** procrank row: PID Vss Rss Pss Uss (Swap) cmdline — values suffixed with K. */
    private fun parseProcrankFull(out: String): List<ProcrankEntry> {
        val list = mutableListOf<ProcrankEntry>()
        for (raw in out.lines()) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("PID") || t.startsWith("-") || t.startsWith("RAM") || t.startsWith("TOTAL")) continue
            val toks = t.split(Regex("\\s+"))
            val pid = toks.getOrNull(0)?.toIntOrNull() ?: continue
            val kIdx = toks.indices.filter { toks[it].endsWith("K") && toks[it].dropLast(1).all(Char::isDigit) }
            if (kIdx.size < 4) continue
            fun mb(i: Int) = toks[kIdx[i]].dropLast(1).toLongOrNull()?.div(1024.0)
            val name = toks.subList(kIdx.last() + 1, toks.size).joinToString(" ").ifBlank { toks.last() }.take(60)
            list += ProcrankEntry(pid, name, mb(0), mb(1), mb(2), mb(3))   // Vss,Rss,Pss,Uss
        }
        return list
    }

    /**
     * Fallback (no procrank/root): RSS + VSS from a single `ps` call (VSZ & RSS columns are KiB).
     * Uses `ps` rather than a `cat /proc/[0-9]#/stat` glob so it can't hit ARG_MAX or stall on a
     * busy device — PSS/USS aren't available without procrank, so those stay null.
     */
    private fun procFromStat(adb: Adb, target: String): List<ProcrankEntry> {
        val out = try {
            adb.shell(target, "ps -A -o PID,VSZ,RSS,NAME 2>/dev/null || ps -o PID,VSZ,RSS,NAME 2>/dev/null")
        } catch (e: Exception) { return emptyList() }
        val list = mutableListOf<ProcrankEntry>()
        for (line in out.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("PID", ignoreCase = true)) continue
            val cols = t.split(Regex("\\s+"))
            if (cols.size < 4) continue
            val pid = cols[0].toIntOrNull() ?: continue
            val vszKb = cols[1].toLongOrNull()
            val rssKb = cols[2].toLongOrNull() ?: continue
            val name = cols.subList(3, cols.size).joinToString(" ").take(60)
            val rssMb = rssKb / 1024.0
            if (rssMb < 0.5) continue
            list += ProcrankEntry(pid, name, vszKb?.div(1024.0), rssMb, null, null)
        }
        return list
    }

    private fun n0(v: Double): String = String.format(java.util.Locale.US, "%,.0f", v)
    private fun n0OrDash(v: Double?): String = v?.let { n0(it) } ?: "—"

    /**
     * Self-contained procrank report. The chart is rendered SERVER-SIDE as inline SVG and the table
     * as static HTML — deliberately NO Chart.js, NO CDN, and NO embedded JS data array. Earlier
     * versions injected a JS `DATA` array and drew with Chart.js, which failed silently whenever a
     * process name or a locale-formatted number broke the JS literal. Server-side rendering removes
     * that entire failure surface: if the capture has rows, the graph shows. A tiny optional script
     * only adds filter/sort on top of the already-rendered table (it can't blank the page).
     */
    fun writeProcrankHtml(s: ProcrankSnapshot, target: String, out: File) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val metric = if (s.source == "procrank") "PSS" else "RSS"
        fun metricVal(e: ProcrankEntry): Double = (if (metric == "PSS") e.pssMb else e.rssMb) ?: e.rssMb ?: 0.0

        val sb = StringBuilder(32768)
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Procrank Snapshot — ").append(escHtml(target)).append("</title>")
        sb.append("<style>")
        sb.append("body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;margin:0;background:#f4f5f7;color:#1f2430}")
        sb.append("header{background:linear-gradient(135deg,#111827,#1e3a5f);color:#fff;padding:16px 24px}")
        sb.append("header h1{margin:0;font-size:20px} header .m{font-size:12px;color:#cbd5e1;margin-top:4px}")
        sb.append(".wrap{padding:18px 24px;max-width:1100px}")
        sb.append(".card{background:#fff;border:1px solid #e2e4e8;border-radius:10px;padding:14px 16px;margin-bottom:18px;box-shadow:0 1px 3px rgba(0,0,0,.05)}")
        sb.append(".card h2{font-size:14px;margin:0 0 10px;color:#374151}")
        sb.append(".kpis{display:flex;gap:22px;flex-wrap:wrap;font-size:13px;margin-bottom:6px}")
        sb.append(".kpis b{font-size:17px;display:block;color:#111827}")
        sb.append("input#q{width:260px;padding:7px 10px;border:1px solid #cdd2da;border-radius:7px;font-size:13px;margin-bottom:10px}")
        sb.append("table{width:100%;border-collapse:collapse;font-size:12.5px} th,td{padding:5px 9px;border-bottom:1px solid #eef0f3;text-align:right}")
        sb.append("th:nth-child(2),td:nth-child(2){text-align:left} th{cursor:pointer;background:#f3f4f6;user-select:none;position:sticky;top:0}")
        sb.append("td.mono,th{font-family:ui-monospace,Menlo,monospace}")
        sb.append(".note{background:#fef3c7;border:1px solid #fde68a;color:#92400e;padding:8px 12px;border-radius:8px;font-size:12.5px;margin-bottom:12px}")
        sb.append(".svgbar{width:100%;height:auto}")
        sb.append("</style></head><body>")
        sb.append("<header><h1>&#128202; Procrank Snapshot</h1><div class=\"m\">Target: ").append(escHtml(target))
            .append("  &middot;  ").append(ts).append("  &middot;  source: ")
            .append(if (s.source == "procrank") "procrank (PSS/USS)" else "ps RSS/VSS (procrank/root unavailable)")
            .append("  &middot;  ").append(escHtml(buildTag()))
            .append("  &middot;  ").append(s.entries.size).append(" processes")
            .append("</div></header>")
        sb.append("<div class=\"wrap\">")
        if (s.entries.isEmpty())
            sb.append("<div class=\"note\">No process memory data was returned by the device. Neither <code>procrank</code> nor <code>ps</code> produced rows — see the <code>.debug.txt</code> file saved next to this report for the raw device output.</div>")
        else if (s.source != "procrank")
            sb.append("<div class=\"note\">procrank was not available (needs root). Showing RSS + VSS from <code>ps</code> instead — PSS/USS columns are blank.</div>")

        sb.append("<div class=\"card\"><div class=\"kpis\">")
        sb.append("<div>Total RAM<b>").append(n0(s.memTotalMb)).append(" MiB</b></div>")
        sb.append("<div>MemFree<b>").append(n0(s.memFreeMb)).append(" MiB</b></div>")
        sb.append("<div>MemAvailable<b>").append(n0(s.memAvailableMb)).append(" MiB</b></div>")
        sb.append("<div>Total ").append(metric).append("<b>").append(n0(if (metric == "PSS") s.totalPssMb else s.totalRssMb)).append(" MiB</b></div>")
        sb.append("<div>Processes<b>").append(s.entries.size).append("</b></div>")
        sb.append("</div></div>")

        // ---- Top-15 horizontal bar chart, rendered as inline SVG (no JS, no CDN) ----
        val top = s.entries.take(15)
        if (top.isNotEmpty()) {
            val maxV = (top.maxOfOrNull { metricVal(it) } ?: 1.0).coerceAtLeast(0.0001)
            val rowH = 26; val padTop = 12; val labelW = 250; val barMax = 540; val valW = 78
            val svgW = labelW + barMax + valW + 16
            val svgH = top.size * rowH + padTop * 2
            sb.append("<div class=\"card\"><h2>Top ").append(top.size).append(" processes by ").append(metric).append(" (MiB)</h2>")
            sb.append("<svg class=\"svgbar\" viewBox=\"0 0 ").append(svgW).append(' ').append(svgH)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\" font-family=\"-apple-system,Segoe UI,Roboto,sans-serif\">")
            top.forEachIndexed { i, e ->
                val v = metricVal(e)
                val barW = (v / maxV * barMax).coerceIn(1.0, barMax.toDouble())
                val y = padTop + i * rowH
                val cy = y + rowH / 2
                val label = e.name.let { if (it.length > 34) it.take(33) + "…" else it }
                sb.append("<text x=\"").append(labelW - 8).append("\" y=\"").append(cy + 4)
                    .append("\" text-anchor=\"end\" font-size=\"11\" fill=\"#374151\">").append(escHtml(label)).append("</text>")
                sb.append("<rect x=\"").append(labelW).append("\" y=\"").append(y + 4)
                    .append("\" width=\"").append("%.1f".let { String.format(java.util.Locale.US, it, barW) })
                    .append("\" height=\"").append(rowH - 8).append("\" rx=\"3\" fill=\"#1d4ed8\"></rect>")
                sb.append("<text x=\"").append(String.format(java.util.Locale.US, "%.1f", labelW + barW + 6))
                    .append("\" y=\"").append(cy + 4).append("\" font-size=\"11\" fill=\"#111827\">").append(n0(v)).append("</text>")
            }
            sb.append("</svg></div>")
        }

        // ---- Full process table, rendered statically (no JS needed to see it) ----
        sb.append("<div class=\"card\"><h2>All processes</h2>")
        sb.append("<input id=\"q\" placeholder=\"filter by process name…\">")
        sb.append("<div style=\"max-height:520px;overflow:auto\"><table id=\"tbl\"><thead><tr>")
        sb.append("<th data-k=\"p\">PID</th><th data-k=\"n\">Process</th>")
        sb.append("<th data-k=\"pss\">PSS</th><th data-k=\"rss\">RSS</th>")
        sb.append("<th data-k=\"uss\">USS</th><th data-k=\"vss\">VSS</th>")
        sb.append("</tr></thead><tbody id=\"tb\">")
        for (e in s.entries) {
            fun d(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "-1"
            sb.append("<tr data-p=\"").append(e.pid).append("\" data-n=\"").append(escHtml(e.name.lowercase()))
                .append("\" data-pss=\"").append(d(e.pssMb)).append("\" data-rss=\"").append(d(e.rssMb))
                .append("\" data-uss=\"").append(d(e.ussMb)).append("\" data-vss=\"").append(d(e.vssMb)).append("\">")
                .append("<td class=\"mono\">").append(e.pid).append("</td>")
                .append("<td>").append(escHtml(e.name)).append("</td>")
                .append("<td>").append(n0OrDash(e.pssMb)).append("</td>")
                .append("<td>").append(n0OrDash(e.rssMb)).append("</td>")
                .append("<td>").append(n0OrDash(e.ussMb)).append("</td>")
                .append("<td>").append(n0OrDash(e.vssMb)).append("</td></tr>")
        }
        sb.append("</tbody></table></div></div>")
        // Optional progressive-enhancement filter/sort over the ALREADY-rendered rows.
        sb.append("<script>").append(PROC_TABLE_JS).append("</script>")
        sb.append("</div></body></html>")

        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
    }

    // Operates only on existing DOM rows via their data-* attributes; if it throws, the fully
    // server-rendered table and SVG chart are already visible — nothing can blank the report.
    private const val PROC_TABLE_JS = """
try{
(function(){
  var tb=document.getElementById('tb'), q=document.getElementById('q');
  var rows=[].slice.call(tb.querySelectorAll('tr'));
  function num(r,k){ var v=parseFloat(r.getAttribute('data-'+k)); return isNaN(v)?-1:v; }
  q.addEventListener('input',function(){
    var f=(q.value||'').toLowerCase();
    rows.forEach(function(r){ r.style.display=(!f||r.getAttribute('data-n').indexOf(f)>=0)?'':'none'; });
  });
  var dir={};
  [].slice.call(document.querySelectorAll('th[data-k]')).forEach(function(th){
    th.addEventListener('click',function(){
      var k=th.getAttribute('data-k'); dir[k]=-(dir[k]||1);
      var vis=rows.filter(function(r){return r.style.display!=='none';});
      vis.sort(function(a,b){
        if(k==='n'){ var x=a.getAttribute('data-n'),y=b.getAttribute('data-n'); return x<y?dir[k]:x>y?-dir[k]:0; }
        return (num(a,k)-num(b,k))*dir[k];
      });
      vis.forEach(function(r){ tb.appendChild(r); });
    });
  });
})();
}catch(e){}
"""

    // ---------- PDF (kept as an alternative output) ----------

    fun writePdf(s: Snapshot, target: String, out: File) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val w = PDRectangle.A4.width
            val h = PDRectangle.A4.height
            PDPageContentStream(doc, page).use { cs ->
                var y = h - 50f
                fun text(t: String, font: PDType1Font, size: Float, x: Float = 40f, dy: Float = size + 7f) {
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(t); cs.endText(); y -= dy
                }
                text("STB Device Snapshot", PDType1Font.HELVETICA_BOLD, 20f)
                text("Target: $target   ·   Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}",
                    PDType1Font.HELVETICA, 9f); y -= 6f

                // Summary block (left column)
                text("Device & OS", PDType1Font.HELVETICA_BOLD, 12f); y -= 2f
                for ((k, v) in s.summary) {
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 9f); cs.newLineAtOffset(40f, y); cs.showText("$k:"); cs.endText()
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 9f); cs.newLineAtOffset(165f, y); cs.showText(v.take(90)); cs.endText()
                    y -= 15f
                }

                // Memory footprint donut (right of the summary won't fit — place below)
                y -= 8f
                text("Memory footprint (PSS by category vs free/available RAM)", PDType1Font.HELVETICA_BOLD, 12f)
                val img = ringChartImage(s, 520, 380)
                val pd = LosslessFactory.createFromImage(doc, img)
                val iw = 320f; val ih = iw * 380f / 520f
                val imgY = y - ih
                cs.drawImage(pd, 40f, imgY, iw, ih)

                // Totals text beside the chart
                var ty = y - 18f
                fun stat(label: String, mb: Double, col: Triple<Int, Int, Int>) {
                    cs.setNonStrokingColor(Color(col.first, col.second, col.third))
                    cs.addRect(375f, ty - 1f, 9f, 9f); cs.fill()
                    cs.setNonStrokingColor(Color(30, 30, 30))
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 10f); cs.newLineAtOffset(390f, ty)
                    cs.showText("%-16s %,.0f MiB".format(label, mb)); cs.endText()
                    ty -= 18f
                }
                stat("Android", s.androidMb, Triple(44, 160, 44))
                stat("3rd-party apps", s.thirdPartyMb, Triple(214, 39, 40))
                stat("Launcher", s.launcherMb, Triple(31, 119, 180))
                stat("MemFree", s.memFreeMb, Triple(255, 127, 14))
                stat("MemAvailable", s.memAvailableMb, Triple(148, 103, 189))
                ty -= 6f
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 9f); cs.newLineAtOffset(375f, ty)
                cs.showText("Total RAM %,.0f MiB".format(s.memTotalMb)); cs.endText()

                y = imgY - 24f
                cs.setNonStrokingColor(Color(0, 0, 0))
                text("Top processes / services by PSS", PDType1Font.HELVETICA_BOLD, 12f)
                // Two-column process list
                writeProcessList(cs, s.processes, startY = y, bottom = 50f)
            }
            out.parentFile?.mkdirs()
            doc.save(out)
        }
    }

    private fun writeProcessList(cs: PDPageContentStream, procs: List<ProcEntry>, startY: Float, bottom: Float) {
        val colX = floatArrayOf(40f, 300f)
        var col = 0
        var y = startY
        val rowH = 12f
        for (p in procs.take(80)) {
            if (y < bottom) {
                if (col == 0) { col = 1; y = startY } else break
            }
            val tag = when (p.category) { "android" -> "[A]"; "launcher" -> "[L]"; else -> "[3]" }
            cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 8f); cs.newLineAtOffset(colX[col], y)
            cs.showText("$tag ${p.name.take(34)}"); cs.endText()
            cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 8f); cs.newLineAtOffset(colX[col] + 210f, y)
            cs.showText("%,.0f MiB".format(p.pssMb)); cs.endText()
            y -= rowH
        }
    }

    private fun ringChartImage(s: Snapshot, w: Int, h: Int): java.awt.image.BufferedImage {
        val ds = DefaultPieDataset<String>()
        ds.setValue("Android", s.androidMb)
        ds.setValue("3rd-party apps", s.thirdPartyMb)
        ds.setValue("Launcher", s.launcherMb)
        ds.setValue("MemFree", s.memFreeMb)
        ds.setValue("MemAvailable", s.memAvailableMb)
        val chart = ChartFactory.createRingChart("", ds, false, false, false)
        chart.backgroundPaint = Color.WHITE
        val plot = chart.plot as RingPlot
        plot.backgroundPaint = Color.WHITE
        plot.setSectionOutlinesVisible(false)
        plot.setSectionPaint("Android", Color(44, 160, 44))
        plot.setSectionPaint("3rd-party apps", Color(214, 39, 40))
        plot.setSectionPaint("Launcher", Color(31, 119, 180))
        plot.setSectionPaint("MemFree", Color(255, 127, 14))
        plot.setSectionPaint("MemAvailable", Color(148, 103, 189))
        plot.labelFont = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11)
        plot.sectionDepth = 0.42
        return chart.createBufferedImage(w, h)
    }
}
