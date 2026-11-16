package devicemonitor.presentation.web

import devicemonitor.domain.*
import devicemonitor.data.adb.*
import devicemonitor.application.*
import devicemonitor.presentation.report.*
import devicemonitor.common.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Locale

internal val OFFLINE_WEB_ASSETS = mapOf(
    "/assets/chart.umd.js" to ("web/chart.umd.js" to "application/javascript"),
    "/assets/jspdf.umd.min.js" to ("web/jspdf.umd.min.js" to "application/javascript")
)

internal const val PAGE_CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

internal fun resolveOfflineAsset(path: String): Pair<String, String>? = OFFLINE_WEB_ASSETS[path]

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
    initialSession: MonitorSession? = null,
    private val bindHost: String? = null   // null = all interfaces (browser mode); "127.0.0.1" for the desktop shell
) {
    @Volatile var session: MonitorSession? = initialSession

    private var server: HttpServer? = null

    // Base folder that runs are written to (the UI "Reports folder"). Tracked so the Run Comparison
    // page lists runs from the SAME place they were saved — not just the process cwd, which is "/"
    // for a Finder-launched .app. Updated on each Start; defaults to the same dir the UI shows.
    @Volatile private var reportsBaseDir: File = MonitorSession.resolveBaseDir(null)

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
        val s = HttpServer.create(if (bindHost != null) InetSocketAddress(bindHost, port) else InetSocketAddress(port), 0)
        route(s, "/") { ex -> respondPage(ex, HOME_PAGE.replace("__BUILD__", esc(buildTag())).replace("</head>", STUDIO_UI_CSS + CLIENT_LOG_TAG + "</head>")) }
        route(s, "/dashboard") { ex -> respondPage(ex, DASHBOARD_PAGE.replace("__BUILD__", esc(buildTag())).replace("</head>", STUDIO_UI_CSS + CLIENT_LOG_TAG + "</head>")) }
        OFFLINE_WEB_ASSETS.keys.forEach { path -> route(s, path) { ex -> handleOfflineAsset(ex, path) } }
        route(s, "/api/devices") { ex -> respond(ex, 200, "application/json", devicesJson()) }
        route(s, "/api/defaults") { ex ->
            val dir = MonitorSession.resolveBaseDir(null).absolutePath
            respond(ex, 200, "application/json", "{\"reportsDir\":\"${esc(dir)}\",\"build\":\"${esc(buildTag())}\"}")
        }
        route(s, "/api/pick-folder") { ex -> handlePickFolder(ex) }
        route(s, "/api/snapshot") { ex -> handleSnapshotStart(ex) }
        route(s, "/api/snapshot-status") { ex ->
            val done = snapFile != null
            respond(ex, 200, "application/json",
                "{\"running\":$snapRunning,\"progress\":$snapProgress,\"done\":$done," +
                    "\"error\":${snapError?.let { "\"${esc(it)}\"" } ?: "null"}}")
        }
        route(s, "/api/snapshot-report") { ex -> handleSnapshotReport(ex) }
        route(s, "/api/procrank-snapshot") { ex -> handleProcrankStart(ex) }
        route(s, "/api/procrank-status") { ex ->
            val done = procFile != null
            respond(ex, 200, "application/json",
                "{\"running\":$procRunning,\"progress\":$procProgress,\"done\":$done," +
                    "\"error\":${procError?.let { "\"${esc(it)}\"" } ?: "null"}}")
        }
        route(s, "/api/procrank-report") { ex -> handleProcrankReport(ex) }
        route(s, "/api/start") { ex -> handleStart(ex) }
        route(s, "/api/stop") { ex ->
            session?.requestStop()
            respond(ex, 200, "application/json", "{\"ok\":true}")
        }
        route(s, "/api/session") { ex -> respond(ex, 200, "application/json", sessionJson(ex)) }
        route(s, "/api/report") { ex -> handleReport(ex) }
        route(s, "/api/root") { ex -> handleRoot(ex) }
        route(s, "/api/runs") { ex -> respond(ex, 200, "application/json", runsJson()) }
        route(s, "/api/runcsv") { ex -> handleRunCsv(ex) }
        route(s, "/compare") { ex -> respondPage(ex, COMPARE_PAGE.replace("</head>", STUDIO_UI_CSS + CLIENT_LOG_TAG + "</head>")) }
        route(s, "/clientlog.js") { ex -> respond(ex, 200, "application/javascript", CLIENT_LOG_JS) }
        route(s, "/api/clientlog") { ex -> handleClientLog(ex) }
        route(s, "/api/diagnostics") { ex -> handleDiagnostics(ex) }
        // Unbounded named daemon pool: a slow handler (unresponsive device, native folder dialog)
        // can no longer starve the dashboard poll the way the old fixed pool of 4 did.
        val n = java.util.concurrent.atomic.AtomicInteger()
        s.executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "http-" + n.incrementAndGet()).apply { isDaemon = true }
        }
        startHttpWatchdog()
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
    }

    // ---------- request plumbing: timing, error capture, stall detection ----------

    private class InFlight(val what: String, val startNanos: Long, val thread: Thread) { @Volatile var dumped = false }
    private val inFlight = java.util.concurrent.ConcurrentHashMap<HttpExchange, InFlight>()
    // Polled endpoints: only logged when slow, otherwise they would flood the log.
    private val quietPaths = setOf("/api/session", "/api/devices", "/api/snapshot-status", "/api/procrank-status", "/api/clientlog", "/clientlog.js")

    /** Register a handler that is timed, never leaks an exception, and always closes the exchange. */
    private fun route(s: HttpServer, path: String, handler: (HttpExchange) -> Unit) {
        s.createContext(path) { ex ->
            val what = ex.requestMethod + " " + ex.requestURI
            val t0 = System.nanoTime()
            inFlight[ex] = InFlight(what, t0, Thread.currentThread())
            try {
                handler(ex)
            } catch (e: Throwable) {
                Log.error("[http] $what failed", e)
                try { respond(ex, 500, "application/json", err(e.message ?: e.toString())) } catch (_: Exception) {}
            } finally {
                inFlight.remove(ex)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > 1500 && path != "/api/pick-folder") Log.warn("[http] SLOW ${ms}ms $what")
                else if (path !in quietPaths) Log.debug("[http] ${ms}ms $what")
                try { ex.close() } catch (_: Exception) {}
            }
        }
    }

    /** Dumps all thread stacks once when a request has been stuck for more than 10 s. */
    private fun startHttpWatchdog() {
        Thread {
            while (true) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
                val now = System.nanoTime()
                for (f in inFlight.values) {
                    val sec = (now - f.startNanos) / 1e9
                    if (sec > 10 && !f.dumped && !f.what.contains("/api/pick-folder")) {
                        f.dumped = true
                        Log.warn("[watchdog] HTTP request stuck for ${sec.toInt()}s on ${f.thread.name}: ${f.what} — thread dump written to the log")
                        Log.threadDump("http stuck: ${f.what}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "http-watchdog"; start() }
    }

    // Browser-side errors (window.onerror, unhandled rejections, console.error) land in the log too.
    private val clientLogWindow = java.util.concurrent.atomic.AtomicLong(0)
    private val clientLogCount = java.util.concurrent.atomic.AtomicInteger(0)
    private fun handleClientLog(ex: HttpExchange) {
        val body = ex.requestBody.bufferedReader().readText().take(4000).replace('\r', ' ')
        val minute = System.currentTimeMillis() / 60_000
        if (clientLogWindow.getAndSet(minute) != minute) clientLogCount.set(0)
        val n = clientLogCount.incrementAndGet()
        if (n <= 40) Log.warn("[client] $body")
        else if (n == 41) Log.warn("[client] (more browser errors this minute suppressed)")
        respond(ex, 200, "application/json", "{\"ok\":true}")
    }

    /** One zip with everything needed to debug a hang: log tails, live thread dump, state summary. */
    private fun handleDiagnostics(ex: HttpExchange) {
        val s = session
        val info = buildString {
            append("build: ").append(buildTag()).append('\n')
            append("time: ").append(java.time.LocalDateTime.now()).append('\n')
            append("java: ").append(System.getProperty("java.version")).append(" / ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n')
            append("locale: ").append(Locale.getDefault()).append('\n')
            append("cwd: ").append(System.getProperty("user.dir")).append('\n')
            append("log: ").append(Log.file?.absolutePath).append('\n')
            append("reportsBaseDir: ").append(reportsBaseDir.absolutePath).append('\n')
            val rt = Runtime.getRuntime()
            append("heap: used=").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append("MB max=").append(rt.maxMemory() / 1048576).append("MB\n")
            append("http in-flight: ").append(inFlight.values.joinToString { it.what + " (" + (System.nanoTime() - it.startNanos) / 1_000_000 + "ms)" }).append('\n')
            if (s == null) append("session: none\n") else {
                append("session.status: ").append(s.status).append('\n')
                append("session.error: ").append(s.error).append('\n')
                append("session.samples: ").append(s.samples.size).append('\n')
                append("session.runDir: ").append(s.runDir?.absolutePath).append('\n')
                append("session.config: ").append(s.config).append('\n')
            }
        }
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            fun entry(name: String, bytes: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(bytes); z.closeEntry() }
            entry("info.txt", info.toByteArray())
            entry("threads.txt", Log.threadDump("diagnostics export").toByteArray())
            entry("adb-monitor.log", Log.tail(Log.file, 3 * 1024 * 1024))
            val runLog = s?.runDir?.let { File(it, "run.log") } ?: Log.runFile
            if (runLog != null && runLog.exists()) entry("run.log", Log.tail(runLog, 3 * 1024 * 1024))
        }
        val bytes = bos.toByteArray()
        val name = "adb-monitor-diagnostics_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip"
        ex.responseHeaders.add("Content-Type", "application/zip")
        ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"$name\"")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        Log.info("[diag] diagnostics bundle exported (${bytes.size / 1024} KB)")
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
    // The home page polls /api/devices every 5 s. Version/root rarely change, so cache them per
    // device (30 s; failures 10 s) and bound the read to 5 s — an unresponsive device used to hold a
    // request for 20 s per poll and pile requests up until the whole UI froze.
    private val enrichCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String?, String>>>()
    private fun enrich(serial: String): Pair<String?, String> {
        val now = System.currentTimeMillis()
        enrichCache[serial]?.let { (at, v) -> if (now - at < (if (v.second == "unknown") 10_000 else 30_000)) return v }
        val v = enrichNow(serial)
        enrichCache[serial] = System.currentTimeMillis() to v
        return v
    }

    private fun enrichNow(serial: String): Pair<String?, String> = try {
        val out = adb.shell(serial, "getprop ro.build.version.release; id; getprop ro.build.type; getprop ro.debuggable", 5)
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
        enrichCache.remove(serial)
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
        // Remember where this run's outputs land, so /api/runs lists from the same folder.
        reportsBaseDir = MonitorSession.resolveBaseDir(form["outputDir"]?.trim()?.ifBlank { null })
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
        val scenarioMarkers: List<devicemonitor.domain.releaseguard.ScenarioMarker>
        synchronized(s.scenarioMarkers) { scenarioMarkers = s.scenarioMarkers.toList() }
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
        sb.append("\"scenarioMarkers\":[")
        scenarioMarkers.forEachIndexed { i, marker ->
            if (i > 0) sb.append(",")
            sb.append("{\"t\":").append(num(marker.elapsedSec))
                .append(",\"attempt\":").append(marker.attempt)
                .append(",\"step\":").append(marker.stepIndex)
                .append(",\"label\":\"").append(esc(marker.label))
                .append("\",\"section\":").append(marker.section?.let { "\"${esc(it)}\"" } ?: "null")
                .append(",\"type\":\"").append(marker.type.name.lowercase())
                .append("\",\"outcome\":\"").append(marker.outcome.name.lowercase())
                .append("\",\"detail\":").append(marker.detail?.let { "\"${esc(it.take(500))}\"" } ?: "null")
                .append("}")
        }
        sb.append("],")
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

    /** Directories to look for run_* folders in: the tracked reports folder, the default reports
     *  folder (~/ADB Device Monitor), and the process cwd — de-duplicated, existing dirs only. */
    private fun reportsSearchDirs(): List<File> =
        listOf(reportsBaseDir, MonitorSession.defaultReportsDir(), File("."))
            .map { it.absoluteFile }
            .distinctBy { it.path }
            .filter { it.isDirectory }

    /** Lists run_* folders (with samples.csv) across the reports folders, newest first. */
    private fun runsJson(): String {
        val seen = HashSet<String>()
        val dirs = reportsSearchDirs()
            .flatMap { base ->
                base.listFiles { f -> f.isDirectory && f.name.startsWith("run_") && File(f, "samples.csv").exists() }
                    ?.toList() ?: emptyList()
            }
            .filter { seen.add(it.name) }        // first occurrence of each run folder wins
            .sortedByDescending { it.name }
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
        // No label:  run_<date>_<time>_<mfr>_<model>            → 4 segments
        // With label: run_<date>_<time>_<label>_<mfr>_<model>   → 5+ segments
        if (parts.size < 5) return null
        val labelCandidate = parts[2]
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
        // Find the run folder in the same set of reports directories that runsJson lists from.
        val csv = reportsSearchDirs().map { File(it, "$folder/samples.csv") }.firstOrNull { it.exists() }
            ?: run { respond(ex, 404, "application/json", err("Not found")); return }
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

    private fun respondPage(ex: HttpExchange, body: String) {
        ex.responseHeaders.add("Content-Security-Policy", PAGE_CSP)
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        respond(ex, 200, "text/html", body)
    }

    private fun handleOfflineAsset(ex: HttpExchange, expectedPath: String) {
        if (ex.requestMethod != "GET" || ex.requestURI.path != expectedPath) {
            respond(ex, 404, "text/plain", "Not found")
            return
        }
        val (resource, type) = resolveOfflineAsset(expectedPath)
            ?: run { respond(ex, 404, "text/plain", "Not found"); return }
        val bytes = WebApp::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: run { Log.error("[http] bundled asset missing: $resource"); respond(ex, 500, "text/plain", "Bundled asset missing"); return }
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "public, max-age=31536000, immutable")
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private val CLIENT_LOG_TAG = "<script src=\"/clientlog.js\"></script>"

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
