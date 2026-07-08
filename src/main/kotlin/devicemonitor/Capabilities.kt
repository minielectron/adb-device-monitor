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
            .append("  &middot;  ").append(escHtml("ADB Performance Studio")).append("</div></header>")

        sb.append("<div class=\"wrap\"><div class=\"grid\">")

        // Summary card
        sb.append("<div class=\"card\"><h2>Device &amp; OS</h2><table>")
        for ((k, v) in s.summary) {
            sb.append("<tr><td class=\"sumk\">").append(escHtml(k)).append("</td><td>").append(escHtml(v)).append("</td></tr>")
        }
        sb.append("</table></div>")

        // Pie card
        sb.append("<div class=\"card\"><h2>Memory footprint (PSS by category vs free/available RAM)</h2>")
        sb.append("<div class=\"piebox\"><canvas id=\"pie\"></canvas><div class=\"center\"><div id=\"centerTxt\"></div></div></div>")
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
        sb.append("var D={android:").append("%.1f".format(s.androidMb))
            .append(",third:").append("%.1f".format(s.thirdPartyMb))
            .append(",launcher:").append("%.1f".format(s.launcherMb))
            .append(",memFree:").append("%.1f".format(s.memFreeMb))
            .append(",memAvail:").append("%.1f".format(s.memAvailableMb))
            .append(",memTotal:").append("%.1f".format(s.memTotalMb))
            .append(",total:").append("%.1f".format(total)).append("};")
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
document.getElementById('centerTxt').innerHTML =
  '<div style="font-size:11px;color:#6b7280">Total RAM</div>' +
  '<div style="font-size:18px;font-weight:600;color:#374151">' + fmt(D.memTotal) + ' MiB</div>';
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
  plugins:[ChartDataLabels]
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

    /** One-shot procrank capture (falls back to /proc RSS when procrank/root isn't available). */
    fun captureProcrank(adb: Adb, target: String, progress: (Int) -> Unit = {}): ProcrankSnapshot {
        progress(10)
        val mem = parseMeminfo(safeShell(adb, target, "cat /proc/meminfo 2>/dev/null"))
        val memTotal = kbToMb(mem["MemTotal"]); val memFree = kbToMb(mem["MemFree"])
        val memAvail = kbToMb(mem["MemAvailable"] ?: mem["MemFree"])
        progress(35)

        var out: String? = null
        for (cmd in listOf("procrank", "su 0 procrank", "su -c procrank")) {
            val o = safeShell(adb, target, cmd)
            if (o.contains("Pss", ignoreCase = true)) { out = o; break }
        }
        progress(80)

        val entries: List<ProcrankEntry>
        val source: String
        if (out != null) { entries = parseProcrankFull(out); source = "procrank" }
        else { entries = procFromStat(adb, target); source = "proc-rss" }

        val sorted = entries.sortedByDescending { it.pssMb ?: it.rssMb ?: 0.0 }
        val totalPss = entries.mapNotNull { it.pssMb }.sum()
        val totalRss = entries.mapNotNull { it.rssMb }.sum()
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

    /** Fallback (no procrank/root): RSS + VSS from /proc/[pid]/stat (fields 23 vsize, 24 rss). */
    private fun procFromStat(adb: Adb, target: String): List<ProcrankEntry> {
        val out = try { adb.shell(target, "cat /proc/[0-9]*/stat 2>/dev/null") } catch (e: Exception) { return emptyList() }
        val list = mutableListOf<ProcrankEntry>()
        for (line in out.lines()) {
            if (line.isBlank()) continue
            val pid = line.substringBefore(' ').toIntOrNull() ?: continue
            val open = line.indexOf('('); val close = line.lastIndexOf(')')
            if (open < 0 || close < 0 || close <= open) continue
            val name = line.substring(open + 1, close).take(60)
            val rest = line.substring(close + 2).trim().split(Regex("\\s+"))
            val vsize = rest.getOrNull(20)?.toLongOrNull()            // field 23 (bytes)
            val rssPages = rest.getOrNull(21)?.toLongOrNull() ?: continue  // field 24 (pages)
            val rssMb = rssPages * 4.0 / 1024.0
            if (rssMb < 0.5) continue
            list += ProcrankEntry(pid, name, vsize?.div(1024.0 * 1024.0), rssMb, null, null)
        }
        return list
    }

    /** Interactive HTML: top-15 by PSS/RSS bar + sortable, searchable full process table. */
    fun writeProcrankHtml(s: ProcrankSnapshot, target: String, out: File) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val metric = if (s.source == "procrank") "PSS" else "RSS"
        val rows = StringBuilder()
        for (e in s.entries) {
            fun c(v: Double?) = v?.let { "%.1f".format(it) } ?: "null"
            rows.append("{p:").append(e.pid)
                .append(",n:\"").append(escHtml(e.name)).append("\"")
                .append(",vss:").append(c(e.vssMb)).append(",rss:").append(c(e.rssMb))
                .append(",pss:").append(c(e.pssMb)).append(",uss:").append(c(e.ussMb)).append("},")
        }
        val sb = StringBuilder(16384)
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Procrank Snapshot — ").append(escHtml(target)).append("</title>")
        sb.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js\"></script>")
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
        sb.append("th.act{color:#0e7490} td.mono,th{font-family:ui-monospace,Menlo,monospace}")
        sb.append(".note{background:#fef3c7;border:1px solid #fde68a;color:#92400e;padding:8px 12px;border-radius:8px;font-size:12.5px;margin-bottom:12px}")
        sb.append("</style></head><body>")
        sb.append("<header><h1>&#128202; Procrank Snapshot</h1><div class=\"m\">Target: ").append(escHtml(target))
            .append("  &middot;  ").append(ts).append("  &middot;  source: ")
            .append(if (s.source == "procrank") "procrank (PSS/USS)" else "/proc RSS (procrank/root unavailable)")
            .append("</div></header>")
        sb.append("<div class=\"wrap\">")
        if (s.source != "procrank") sb.append("<div class=\"note\">procrank was not available (needs root). Showing RSS + VSS from /proc instead — PSS/USS columns are blank.</div>")
        sb.append("<div class=\"card\"><div class=\"kpis\">")
        sb.append("<div>Total RAM<b>%,.0f MiB</b></div>".format(s.memTotalMb))
        sb.append("<div>MemFree<b>%,.0f MiB</b></div>".format(s.memFreeMb))
        sb.append("<div>MemAvailable<b>%,.0f MiB</b></div>".format(s.memAvailableMb))
        sb.append("<div>Total ").append(metric).append("<b>%,.0f MiB</b></div>".format(if (metric == "PSS") s.totalPssMb else s.totalRssMb))
        sb.append("<div>Processes<b>").append(s.entries.size).append("</b></div>")
        sb.append("</div></div>")
        sb.append("<div class=\"card\"><h2>Top 15 processes by ").append(metric).append(" (MiB)</h2>")
        sb.append("<div style=\"position:relative;height:360px\"><canvas id=\"bar\"></canvas></div></div>")
        sb.append("<div class=\"card\"><h2>All processes</h2>")
        sb.append("<input id=\"q\" placeholder=\"filter by process name…\" oninput=\"render()\">")
        sb.append("<div style=\"max-height:520px;overflow:auto\"><table id=\"tbl\"><thead><tr>")
        sb.append("<th onclick=\"sortBy('p')\">PID</th><th onclick=\"sortBy('n')\">Process</th>")
        sb.append("<th onclick=\"sortBy('pss')\">PSS</th><th onclick=\"sortBy('rss')\">RSS</th>")
        sb.append("<th onclick=\"sortBy('uss')\">USS</th><th onclick=\"sortBy('vss')\">VSS</th>")
        sb.append("</tr></thead><tbody id=\"tb\"></tbody></table></div></div>")
        sb.append("<script>var DATA=[").append(rows).append("];var METRIC=\"").append(metric.lowercase()).append("\";")
        sb.append(PROC_RENDER_JS).append("</script></div></body></html>")

        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
    }

    private const val PROC_RENDER_JS = """
function fmt(v){return (v===null||v===undefined)?'—':Math.round(v).toLocaleString();}
var sortKey=METRIC, sortDir=-1;
function sortBy(k){ if(sortKey===k){sortDir=-sortDir;}else{sortKey=k;sortDir=(k==='n')?1:-1;} render(); }
function render(){
  var q=(document.getElementById('q').value||'').toLowerCase();
  var rows=DATA.filter(function(r){return !q || r.n.toLowerCase().indexOf(q)>=0;});
  rows.sort(function(a,b){
    var x=a[sortKey], y=b[sortKey];
    if(sortKey==='n'){ x=(''+x).toLowerCase(); y=(''+y).toLowerCase(); return x<y?-sortDir:x>y?sortDir:0; }
    x=(x===null||x===undefined)?-1:x; y=(y===null||y===undefined)?-1:y;
    return (x-y)*sortDir;
  });
  var h='';
  rows.forEach(function(r){
    h+='<tr><td class="mono">'+r.p+'</td><td>'+r.n+'</td><td>'+fmt(r.pss)+'</td><td>'+fmt(r.rss)+'</td><td>'+fmt(r.uss)+'</td><td>'+fmt(r.vss)+'</td></tr>';
  });
  document.getElementById('tb').innerHTML=h;
}
render();
var top=DATA.slice().sort(function(a,b){var x=a[METRIC]||0,y=b[METRIC]||0;return y-x;}).slice(0,15);
new Chart(document.getElementById('bar'),{
  type:'bar',
  data:{labels:top.map(function(r){return r.n.length>26?r.n.slice(0,25)+'…':r.n;}),
    datasets:[{label:METRIC.toUpperCase()+' (MiB)',data:top.map(function(r){return r[METRIC]||0;}),backgroundColor:'#1d4ed8'}]},
  options:{indexAxis:'y',responsive:true,maintainAspectRatio:false,
    plugins:{legend:{display:false},tooltip:{callbacks:{label:function(c){return Math.round(c.raw).toLocaleString()+' MiB';}}}},
    scales:{x:{title:{display:true,text:'MiB'}}}}
});
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
