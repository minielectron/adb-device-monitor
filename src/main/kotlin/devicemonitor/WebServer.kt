package devicemonitor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Locale

/**
 * ADB Performance Studio — the web layer.
 *
 * "/"            home: connected devices, run configuration, start button
 * "/dashboard"   live charts + health badges + event feed + stop/report controls
 * "/api/devices" GET  connected device list (enriched with Android version)
 * "/api/start"   POST form-encoded run config -> starts a MonitorSession
 * "/api/stop"    POST stop the active session early
 * "/api/session" GET  ?since=<n> incremental live data for the dashboard
 * "/api/report"  GET  the finished run's PDF
 */
class WebApp(
    private val port: Int,
    private val adb: Adb,
    initialSession: MonitorSession? = null
) {
    @Volatile var session: MonitorSession? = initialSession

    private var server: HttpServer? = null
    @Volatile private var anomalyCache: List<Anomaly> = emptyList()
    @Volatile private var anomalyCacheLen = -1L
    @Volatile private var anomalyCacheFile: File? = null

    // Device-snapshot (one-shot STB capabilities capture) state.
    @Volatile private var snapRunning = false
    @Volatile private var snapProgress = 0
    @Volatile private var snapError: String? = null
    @Volatile private var snapFile: File? = null

    // Procrank-snapshot (one-shot per-process memory capture) state.
    @Volatile private var procRunning = false
    @Volatile private var procProgress = 0
    @Volatile private var procError: String? = null
    @Volatile private var procFile: File? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.createContext("/") { ex -> respond(ex, 200, "text/html", HOME_PAGE.replace("__BUILD__", esc(buildTag()))) }
        s.createContext("/dashboard") { ex -> respond(ex, 200, "text/html", DASHBOARD_PAGE.replace("__BUILD__", esc(buildTag()))) }
        s.createContext("/api/devices") { ex -> respond(ex, 200, "application/json", devicesJson()) }
        s.createContext("/api/defaults") { ex ->
            val dir = MonitorSession.resolveBaseDir(null).absolutePath
            respond(ex, 200, "application/json", "{\"reportsDir\":\"${esc(dir)}\",\"build\":\"${esc(buildTag())}\"}")
        }
        s.createContext("/api/pick-folder") { ex -> handlePickFolder(ex) }
        s.createContext("/api/snapshot") { ex -> handleSnapshotStart(ex) }
        s.createContext("/api/snapshot-status") { ex ->
            val done = snapFile != null
            respond(ex, 200, "application/json",
                "{\"running\":$snapRunning,\"progress\":$snapProgress,\"done\":$done," +
                    "\"error\":${snapError?.let { "\"${esc(it)}\"" } ?: "null"}}")
        }
        s.createContext("/api/snapshot-report") { ex -> handleSnapshotReport(ex) }
        s.createContext("/api/procrank-snapshot") { ex -> handleProcrankStart(ex) }
        s.createContext("/api/procrank-status") { ex ->
            val done = procFile != null
            respond(ex, 200, "application/json",
                "{\"running\":$procRunning,\"progress\":$procProgress,\"done\":$done," +
                    "\"error\":${procError?.let { "\"${esc(it)}\"" } ?: "null"}}")
        }
        s.createContext("/api/procrank-report") { ex -> handleProcrankReport(ex) }
        s.createContext("/api/start") { ex -> handleStart(ex) }
        s.createContext("/api/stop") { ex ->
            session?.requestStop()
            respond(ex, 200, "application/json", "{\"ok\":true}")
        }
        s.createContext("/api/session") { ex -> respond(ex, 200, "application/json", sessionJson(ex)) }
        s.createContext("/api/report") { ex -> handleReport(ex) }
        s.createContext("/api/root") { ex -> handleRoot(ex) }
        s.createContext("/api/runs") { ex -> respond(ex, 200, "application/json", runsJson()) }
        s.createContext("/api/runcsv") { ex -> handleRunCsv(ex) }
        s.createContext("/compare") { ex -> respond(ex, 200, "text/html", COMPARE_PAGE) }
        s.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
    }

    // ---------- handlers ----------

    private fun devicesJson(): String {
        val devices = try { adb.devices() } catch (e: Exception) { emptyList() }
        val sb = StringBuilder("{\"devices\":[")
        devices.forEachIndexed { i, d ->
            if (i > 0) sb.append(",")
            val extra = if (d.state == "device") enrich(d.serial) else null
            sb.append("{\"serial\":\"").append(esc(d.serial))
                .append("\",\"state\":\"").append(esc(d.state))
                .append("\",\"desc\":\"").append(esc(d.description))
                .append("\",\"android\":").append(extra?.first?.let { "\"${esc(it)}\"" } ?: "null")
                .append(",\"root\":\"").append(extra?.second ?: "unknown")
                .append("\"}")
        }
        val busy = session?.status in listOf(MonitorSession.Status.STARTING, MonitorSession.Status.RUNNING)
        sb.append("],\"busy\":").append(busy).append("}")
        return sb.toString()
    }

    /** (android version, root status) — root status: "root" | "available" | "unavailable". */
    private fun enrich(serial: String): Pair<String?, String> = try {
        val out = adb.shell(serial, "getprop ro.build.version.release; id; getprop ro.build.type; getprop ro.debuggable")
        val lines = out.lines().map { it.trim() }
        val version = lines.getOrNull(0)?.ifBlank { null }
        val root = when {
            lines.any { it.startsWith("uid=0") } -> "root"
            lines.any { it == "userdebug" || it == "eng" } || lines.any { it == "1" } -> "available"
            else -> "unavailable"
        }
        version to root
    } catch (e: Exception) {
        null to "unknown"
    }

    private fun handleRoot(ex: HttpExchange) {
        if (ex.requestMethod != "POST") { respond(ex, 405, "application/json", err("POST only")); return }
        val serial = parseForm(ex)["serial"]
            ?: run { respond(ex, 400, "application/json", err("Missing serial")); return }
        val out = try {
            adb.device(serial, "root").ifBlank { "restarting adbd as root" }
        } catch (e: Exception) {
            respond(ex, 200, "application/json", err("adb root failed: ${e.message}")); return
        }
        // adbd restarts; give the device a moment to come back before the next refresh.
        Thread.sleep(1500)
        println("Web: adb root on $serial -> $out")
        respond(ex, 200, "application/json", "{\"ok\":true,\"message\":\"${esc(out.lines().first())}\"}")
    }

    private fun handleSnapshotStart(ex: HttpExchange) {
        if (ex.requestMethod != "POST") { respond(ex, 405, "application/json", err("POST only")); return }
        if (snapRunning) { respond(ex, 409, "application/json", err("A snapshot is already running")); return }
        val serial = parseForm(ex)["serial"] ?: run { respond(ex, 400, "application/json", err("No device selected")); return }
        snapRunning = true; snapProgress = 0; snapError = null; snapFile = null
        Thread {
            try {
                val data = Capabilities.capture(adb, serial) { p -> snapProgress = p }
                val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val out = File(MonitorSession.resolveBaseDir(null), "device-snapshot_${ts}.html")
                Capabilities.writeHtml(data, serial, out)
                snapProgress = 100
                snapFile = out
                println("Web: device snapshot written -> ${out.absolutePath}")
            } catch (e: Exception) {
                snapError = e.message ?: "snapshot failed"
                System.err.println("Snapshot failed: ${e.message}")
            } finally {
                snapRunning = false
            }
        }.apply { isDaemon = true; name = "device-snapshot" }.start()
        respond(ex, 200, "application/json", "{\"ok\":true}")
    }

    private fun handleSnapshotReport(ex: HttpExchange) {
        val f = snapFile
        if (f == null || !f.exists()) { respond(ex, 404, "application/json", err("Snapshot not ready")); return }
        val bytes = f.readBytes()
        // Serve inline so it opens as an interactive page (with a download option in the browser).
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Content-Disposition", "inline; filename=\"${f.name}\"")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleProcrankStart(ex: HttpExchange) {
        if (ex.requestMethod != "POST") { respond(ex, 405, "application/json", err("POST only")); return }
        if (procRunning) { respond(ex, 409, "application/json", err("A procrank snapshot is already running")); return }
        val serial = parseForm(ex)["serial"] ?: run { respond(ex, 400, "application/json", err("No device selected")); return }
        procRunning = true; procProgress = 0; procError = null; procFile = null
        Thread {
            try {
                val data = Capabilities.captureProcrank(adb, serial) { p -> procProgress = p }
                val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val base = MonitorSession.resolveBaseDir(null)
                val out = File(base, "procrank-snapshot_${ts}.html")
                Capabilities.writeProcrankHtml(data, serial, out)
                // Write the capture debug log next to the HTML so device output can be inspected/shared.
                try { File(base, "procrank-snapshot_${ts}.debug.txt").writeText(Capabilities.lastProcrankDebug) } catch (_: Exception) {}
                procProgress = 100
                procFile = out
                println("Web: procrank snapshot written -> ${out.absolutePath} (source=${data.source}, entries=${data.entries.size})")
            } catch (e: Exception) {
                procError = e.message ?: "procrank snapshot failed"
                System.err.println("Procrank snapshot failed: ${e.message}")
            } finally {
                procRunning = false
            }
        }.apply { isDaemon = true; name = "procrank-snapshot" }.start()
        respond(ex, 200, "application/json", "{\"ok\":true}")
    }

    private fun handleProcrankReport(ex: HttpExchange) {
        val f = procFile
        if (f == null || !f.exists()) { respond(ex, 404, "application/json", err("Procrank snapshot not ready")); return }
        val bytes = f.readBytes()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Content-Disposition", "inline; filename=\"${f.name}\"")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleStart(ex: HttpExchange) {
        if (ex.requestMethod != "POST") { respond(ex, 405, "application/json", err("POST only")); return }
        val active = session
        if (active != null && active.status in listOf(MonitorSession.Status.STARTING, MonitorSession.Status.RUNNING)) {
            respond(ex, 409, "application/json", err("A session is already running")); return
        }
        val form = parseForm(ex)
        val serial = form["serial"] ?: run { respond(ex, 400, "application/json", err("No device selected")); return }
        val duration = form["duration"]?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: run { respond(ex, 400, "application/json", err("Invalid duration")); return }
        val interval = form["interval"]?.toDoubleOrNull()?.takeIf { it > 0 && it <= duration }
            ?: run { respond(ex, 400, "application/json", err("Invalid interval")); return }
        val zapEnabled = form["zap"] == "true"
        val zapMs = if (zapEnabled) {
            form["zapMs"]?.toLongOrNull()?.takeIf { it > 0 }
                ?: run { respond(ex, 400, "application/json", err("Invalid zap time")); return }
        } else null
        val zapCount = if (zapEnabled) form["zapCount"]?.toIntOrNull()?.takeIf { it > 0 } else null
        val pkg = form["package"]?.trim()?.ifBlank { null }
        val jank = form["jank"] == "true" && pkg != null

        // Metric toggles — default true so old clients / CLI mode get everything
        fun bool(key: String) = form[key] != "false"   // absent or "true" → true; only "false" disables

        val cfg = SessionConfig(
            target = serial,
            durationSec = duration,
            intervalSec = interval,
            zapMs = zapMs,
            zapCount = zapCount,
            packageName = pkg,
            logcatEnabled = form["logcat"] == "true",
            jankEnabled = jank,
            profileEnabled = form["profile"] == "true" && pkg != null,
            perfettoEnabled = form["perfetto"] == "true",
            label = form["label"]?.trim()?.ifBlank { null },
            outputDir = form["outputDir"]?.trim()?.ifBlank { null },
            cpuUtilEnabled    = bool("cpuUtil"),
            cpuIdleEnabled    = bool("cpuIdle"),
            memoryMbEnabled   = bool("memoryMb"),
            memoryPctEnabled  = bool("memoryPct"),
            swapEnabled       = bool("swap"),
            zoneInfoEnabled   = bool("zoneinfo"),
            perCoreEnabled    = bool("perCore"),
            psiEnabled        = bool("psi"),
            thermalEnabled    = bool("thermal"),
            cpuFreqEnabled    = bool("cpuFreq"),
            networkEnabled    = bool("network"),
            pageFaultsEnabled = bool("pageFaults"),
            loadEnabled       = bool("load"),
            directReclaimEnabled = bool("reclaim"),
            topProcsEnabled   = bool("topProcs"),
            topMemEnabled     = form["topMem"] == "true",   // opt-in (procrank/dumpsys per tick)
            playbackEnabled   = form["playback"] == "true",  // STB opt-in — off by default
            appPssEnabled     = bool("appPss"),
            appProcCpuEnabled = bool("appCpu"),
            mainThreadCpuEnabled = bool("mainCpu"),
            mediaCpuEnabled   = bool("mediaCpu")
        )
        val newSession = MonitorSession(adb, cfg)
        session = newSession
        anomalyCacheFile = null
        newSession.startAsync()
        val metricsOn = listOfNotNull(
            if (cfg.psiEnabled) "psi" else null,
            if (cfg.thermalEnabled) "thermal" else null,
            if (cfg.networkEnabled) "net" else null,
            if (cfg.cpuFreqEnabled) "freq" else null,
            if (cfg.perCoreEnabled) "percore" else null,
            if (cfg.pageFaultsEnabled) "pgfault" else null,
            if (cfg.loadEnabled) "load" else null,
            if (cfg.topProcsEnabled) "topprocs" else null,
            if (cfg.playbackEnabled) "playback" else null
        ).joinToString(",")
        println("Web: started session on $serial (duration=${duration}s interval=${interval}s zap=${zapMs ?: "off"} pkg=${pkg ?: "-"} logcat=${cfg.logcatEnabled} jank=$jank metrics=[$metricsOn])")
        respond(ex, 200, "application/json", "{\"ok\":true}")
    }

    @Volatile private var pickerOpen = false
    private fun handlePickFolder(ex: HttpExchange) {
        if (!FolderPicker.isSupported()) {
            respond(ex, 200, "application/json", "{\"supported\":false,\"path\":null}"); return
        }
        if (pickerOpen) {  // only one native dialog at a time
            respond(ex, 200, "application/json", "{\"supported\":true,\"path\":null,\"busy\":true}"); return
        }
        val initial = ex.requestURI.query
            ?.split("&")?.firstOrNull { it.startsWith("initial=") }
            ?.substringAfter("=")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        pickerOpen = true
        val path = try { FolderPicker.choose(initial) } catch (e: Exception) { null } finally { pickerOpen = false }
        respond(ex, 200, "application/json",
            "{\"supported\":true,\"path\":${path?.let { "\"${esc(it)}\"" } ?: "null"}}")
    }

    private fun handleReport(ex: HttpExchange) {
        val f = session?.outFile
        if (f == null || !f.exists()) { respond(ex, 404, "application/json", err("Report not ready")); return }
        val bytes = f.readBytes()
        ex.responseHeaders.add("Content-Type", "application/pdf")
        ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"${f.name}\"")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sessionJson(ex: HttpExchange): String {
        val s = session ?: return "{\"status\":\"idle\"}"
        val since = ex.requestURI.query
            ?.split("&")?.firstOrNull { it.startsWith("since=") }
            ?.substringAfter("=")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val newSamples: List<Sample>
        val mediaProcNames: List<String>
        synchronized(s.samples) {
            newSamples = if (since < s.samples.size) s.samples.subList(since, s.samples.size).toList() else emptyList()
            mediaProcNames = s.samples.flatMap { it.mediaProcs.keys }.distinct()
        }
        val zaps: List<Double>
        synchronized(s.zapTimes) { zaps = s.zapTimes.toList() }
        val anomalies = liveAnomalies(s)
        val info = s.info

        val sb = StringBuilder(4096)
        sb.append("{\"status\":\"").append(s.status.name.lowercase()).append("\",")
        sb.append("\"build\":\"").append(esc(buildTag())).append("\",")
        sb.append("\"error\":").append(s.error?.let { "\"${esc(it)}\"" } ?: "null").append(",")
        sb.append("\"device\":\"").append(esc(info?.let {
            "${it.manufacturer} ${it.model} | Android ${it.androidVersion} (SDK ${it.sdk}) | ${it.cpuCores} cores | ${"%.0f".format(Locale.US, it.totalMemMb)} MB"
        } ?: "connecting...")).append("\",")
        sb.append("\"target\":\"").append(esc(s.config.target)).append("\",")
        sb.append("\"intervalSec\":").append(num(s.config.intervalSec)).append(",")
        sb.append("\"durationSec\":").append(num(s.config.durationSec)).append(",")
        val pkg = s.config.packageName != null
        sb.append("\"hasCpu\":").append(s.config.cpuUtilEnabled).append(",")
        sb.append("\"hasCpuIdle\":").append(s.config.cpuIdleEnabled).append(",")
        sb.append("\"hasMem\":").append(s.config.memoryMbEnabled).append(",")
        sb.append("\"hasMemPct\":").append(s.config.memoryPctEnabled).append(",")
        sb.append("\"hasSwap\":").append(s.config.swapEnabled).append(",")
        sb.append("\"hasZone\":").append(s.config.zoneInfoEnabled).append(",")
        sb.append("\"hasPss\":").append(pkg && s.config.appPssEnabled).append(",")
        sb.append("\"hasAppCpu\":").append(pkg && s.config.appProcCpuEnabled).append(",")
        sb.append("\"hasMainCpu\":").append(pkg && s.config.mainThreadCpuEnabled).append(",")
        sb.append("\"hasJank\":").append(s.config.jankEnabled).append(",")
        sb.append("\"hasLogcat\":").append(s.config.logcatEnabled).append(",")
        sb.append("\"hasReclaim\":").append(s.config.logcatEnabled && s.config.directReclaimEnabled).append(",")
        sb.append("\"hasPsi\":").append(s.config.psiEnabled).append(",")
        sb.append("\"hasThermal\":").append(s.config.thermalEnabled).append(",")
        sb.append("\"hasNetwork\":").append(s.config.networkEnabled).append(",")
        sb.append("\"hasPageFaults\":").append(s.config.pageFaultsEnabled).append(",")
        sb.append("\"hasCpuFreq\":").append(s.config.cpuFreqEnabled).append(",")
        sb.append("\"hasLoad\":").append(s.config.loadEnabled).append(",")
        sb.append("\"hasTopProcs\":").append(s.config.topProcsEnabled).append(",")
        sb.append("\"hasTopMem\":").append(s.config.topMemEnabled).append(",")
        sb.append("\"memSource\":").append(s.memSource?.let { "\"${esc(it)}\"" } ?: "null").append(",")
        sb.append("\"hasPlayback\":").append(s.config.playbackEnabled).append(",")
        sb.append("\"buildTunnel\":").append(s.buildTunnelEnabled?.toString() ?: "null").append(",")
        sb.append("\"zapMs\":").append(s.config.zapMs?.toString() ?: "null").append(",")
        sb.append("\"label\":").append(s.config.label?.let { "\"${esc(it)}\"" } ?: "null").append(",")
        sb.append("\"runDir\":").append(s.runDir?.let { "\"${esc(it.name)}\"" } ?: "null").append(",")
        sb.append("\"nextIndex\":").append(since + newSamples.size).append(",")
        sb.append("\"mediaProcNames\":[")
        mediaProcNames.forEachIndexed { i, n -> if (i > 0) sb.append(","); sb.append("\"${esc(n)}\"") }
        sb.append("],")
        sb.append("\"hasMedia\":").append(mediaProcNames.isNotEmpty() && s.config.mediaCpuEnabled).append(",")

        sb.append("\"samples\":[")
        newSamples.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"t\":").append(num(p.elapsedSec))
                .append(",\"cpu\":").append(num(p.cpuUtilPct))
                .append(",\"cpuIdle\":").append(num(p.cpuIdlePct))
                .append(",\"memUsed\":").append(num(p.memUsedMb))
                .append(",\"memUsedPct\":").append(num(p.memUsedPct))
                .append(",\"memAvail\":").append(num(p.memAvailableMb))
                .append(",\"psiCpu\":").append(num(p.cpuPsiSomeAvg10))
                .append(",\"psiMem\":").append(num(p.memPsiSomeAvg10))
                .append(",\"psiIo\":").append(num(p.ioPsiSomeAvg10))
                .append(",\"rx\":").append(num(p.netRxKbps))
                .append(",\"tx\":").append(num(p.netTxKbps))
                .append(",\"temp\":").append(num(p.cpuTempC))
                .append(",\"freq\":").append(num(p.cpuFreqMhz))
                .append(",\"faults\":").append(num(p.majorFaultsPerSec))
                .append(",\"reclaim\":").append(num(p.directReclaimPerSec))
                .append(",\"swap\":").append(num(p.swapUsedMb))
                .append(",\"zoneFree\":").append(num(p.zoneFreePages))
                .append(",\"zoneMin\":").append(num(p.zoneWmMinPages))
                .append(",\"zoneLow\":").append(num(p.zoneWmLowPages))
                .append(",\"zoneHigh\":").append(num(p.zoneWmHighPages))
                .append(",\"zoneActive\":").append(p.zoneActiveCount ?: "null")
                .append(",\"load1\":").append(num(p.load1))
                .append(",\"pss\":").append(num(p.pss?.totalMb))
                .append(",\"pssJava\":").append(num(p.pss?.javaHeapMb))
                .append(",\"pssNative\":").append(num(p.pss?.nativeHeapMb))
                .append(",\"pssCode\":").append(num(p.pss?.codeMb))
                .append(",\"pssStack\":").append(num(p.pss?.stackMb))
                .append(",\"pssGfx\":").append(num(p.pss?.graphicsMb))
                .append(",\"pssPriv\":").append(num(p.pss?.privateOtherMb))
                .append(",\"pssSys\":").append(num(p.pss?.systemMb))
                .append(",\"jank\":").append(num(p.jankPct))
                .append(",\"mainCpu\":").append(num(p.mainThreadCpuPct))
                .append(",\"fIn\":").append(num(p.frameInputMs))
                .append(",\"fAnim\":").append(num(p.frameAnimMs))
                .append(",\"fLay\":").append(num(p.frameLayoutMs))
                .append(",\"fDraw\":").append(num(p.frameDrawMs))
                .append(",\"appCpu\":").append(num(p.appCpuPct))
                .append(",\"media\":{")
            p.mediaProcs.entries.forEachIndexed { mi, (name, v) ->
                if (mi > 0) sb.append(",")
                sb.append("\"").append(esc(name)).append("\":").append(num(v))
            }
            sb.append("},\"procs\":[")
            p.topProcs.forEachIndexed { pi, proc ->
                if (pi > 0) sb.append(",")
                sb.append("{\"pid\":").append(proc.pid)
                    .append(",\"n\":\"").append(esc(proc.name))
                    .append("\",\"pct\":").append(num(proc.cpuPct))
                    .append("}")
            }
            sb.append("]")  // close procs array
            sb.append(",\"memprocs\":[")
            p.topMemProcs.forEachIndexed { pi, proc ->
                if (pi > 0) sb.append(",")
                sb.append("{\"pid\":").append(proc.pid)
                    .append(",\"n\":\"").append(esc(proc.name))
                    .append("\",\"mb\":").append(num(proc.pssMb))
                    .append("}")
            }
            sb.append("]")
            sb.append(",\"video\":").append(p.videoState?.let { "\"${esc(it)}\"" } ?: "null")
            sb.append(",\"tunnel\":").append(p.tunnelMode?.let { "\"${esc(it)}\"" } ?: "null")
            sb.append(",\"act\":").append(p.topActivity?.let { "\"${esc(it)}\"" } ?: "null")
            sb.append("}")  // close sample object
        }
        sb.append("],")
        sb.append("\"zaps\":[").append(zaps.joinToString(",") { num(it) }).append("],")
        sb.append("\"anomalies\":[")
        anomalies.forEachIndexed { i, a ->
            if (i > 0) sb.append(",")
            sb.append("{\"time\":\"").append(esc(a.deviceTime))
                .append("\",\"t\":").append(num(a.elapsedSec))
                .append(",\"type\":\"").append(esc(a.type))
                .append("\",\"detail\":\"").append(esc(a.detail.take(500)))
                .append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun liveAnomalies(s: MonitorSession): List<Anomaly> {
        if (!s.config.logcatEnabled) return emptyList()
        // The session scans logcat incrementally on the sampling thread; just read its list —
        // no per-poll full re-scan (which used to make long runs lag).
        return s.liveAnomalies()
    }

    /** Lists run_* folders (with samples.csv) in cwd, newest first. */
    private fun runsJson(): String {
        val dirs = File(".").listFiles { f ->
            f.isDirectory && f.name.startsWith("run_") && File(f, "samples.csv").exists()
        }?.sortedByDescending { it.name } ?: emptyList()
        val sb = StringBuilder("{\"runs\":[")
        dirs.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            // Extract label from folder name: run_<TS>[_<label>]_<mfr>_<model>
            val label = extractLabel(f.name)
            sb.append("{\"folder\":\"${esc(f.name)}\",\"label\":${if (label != null) "\"${esc(label)}\"" else "null"}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Returns the label segment from a run folder name (between timestamp and manufacturer), if any. */
    private fun extractLabel(folderName: String): String? {
        // run_20240612_143022_tunnel-on_samsung_galaxy  → "tunnel-on"
        // run_20240612_143022_samsung_galaxy            → null
        val after = folderName.removePrefix("run_")
        val parts = after.split("_")
        // parts[0] = date (8 digits), parts[1] = time (6 digits), parts[2..] = label?_mfr_model
        if (parts.size < 4) return null
        val labelCandidate = parts[2]
        // If it looks like a date/time or known device prefix, no label
        return if (labelCandidate.matches(Regex("[0-9]+"))) null else labelCandidate
    }

    /** Serves a run's samples.csv as plain text (folder name must match run_* pattern). */
    private fun handleRunCsv(ex: HttpExchange) {
        val folder = ex.requestURI.query
            ?.split("&")?.firstOrNull { it.startsWith("folder=") }
            ?.substringAfter("=")?.let { URLDecoder.decode(it, "UTF-8") }
            ?: run { respond(ex, 400, "application/json", err("Missing folder")); return }
        if (!folder.matches(Regex("run_[A-Za-z0-9._-]+"))) {
            respond(ex, 400, "application/json", err("Invalid folder")); return
        }
        val csv = File(folder, "samples.csv")
        if (!csv.exists()) { respond(ex, 404, "application/json", err("Not found")); return }
        val bytes = csv.readBytes()
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ---------- helpers ----------

    private fun parseForm(ex: HttpExchange): Map<String, String> =
        ex.requestBody.bufferedReader().readText()
            .split("&")
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null
                else URLDecoder.decode(it.substring(0, idx), "UTF-8") to
                     URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            }.toMap()

    private fun respond(ex: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        // Never let the browser cache the dashboard HTML/JS or the live JSON — a stale cached
        // dashboard was a likely cause of the chart showing old/blank state after an upgrade.
        ex.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate")
        ex.responseHeaders.add("Pragma", "no-cache")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun err(msg: String) = "{\"ok\":false,\"error\":\"${esc(msg)}\"}"

    private fun num(v: Double?): String =
        if (v == null || !v.isFinite()) "null" else String.format(Locale.US, "%.3f", v)

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> {}
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
}
