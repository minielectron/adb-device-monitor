package devicemonitor.application

import devicemonitor.domain.*
import devicemonitor.domain.releaseguard.*
import devicemonitor.data.adb.*
import devicemonitor.data.metrics.*
import devicemonitor.data.logcat.*
import devicemonitor.presentation.report.*
import devicemonitor.common.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Everything a monitoring run needs to know. Built from CLI flags or the web UI. */
data class SessionConfig(
    val target: String,
    val durationSec: Double,
    val intervalSec: Double,
    val zapMs: Long? = null,            // null = zapping disabled
    val zapCount: Int? = null,          // stop after n zaps (needs zapMs)
    val packageName: String? = null,    // enables PSS sampling
    val logcatEnabled: Boolean = true,  // logcat capture + anomalies + event-derived app info
    val jankEnabled: Boolean = false,   // gfxinfo framestats: jank % + frame phase breakdown (needs packageName)
    val profileEnabled: Boolean = false, // ART sampling profiler → top main-thread methods (needs packageName, debuggable app)
    val perfettoEnabled: Boolean = false, // capture a Perfetto system trace for the whole run
    val label: String? = null,            // optional run label shown in folder name and PDF title (e.g. "tunnel-on")
    val outputDir: String? = null,        // base folder for run_* output (null = auto: cwd if writable, else ~/ADB Device Monitor)
    val outputOverride: File? = null,
    val csvOverride: File? = null,
    val runDirectoryOverride: File? = null,
    // ── Metric toggles — each maps 1:1 to a chart; disable to hide it (and, where it costs
    //    device time, to skip collecting it). ──
    // Defaults: only CPU pressure (PSI) and App PSS are on; everything else is opt-in.
    val cpuUtilEnabled: Boolean = false,    // CPU utilization %
    val cpuIdleEnabled: Boolean = false,    // CPU idle %
    val memoryMbEnabled: Boolean = false,   // Memory used/available/cached (MB)
    val memoryPctEnabled: Boolean = false,  // Memory used %
    val swapEnabled: Boolean = false,       // Swap used (MB)
    val zoneInfoEnabled: Boolean = false,   // /proc/zoneinfo — free pages vs min/low/high watermarks
    val perCoreEnabled: Boolean = false,    // per-core CPU % from /proc/stat cpuN lines
    val psiEnabled: Boolean = true,         // /proc/pressure/{cpu,memory,io} PSI stall metrics (default ON)
    val thermalEnabled: Boolean = false,    // /sys/class/thermal — CPU temperature
    val cpuFreqEnabled: Boolean = false,    // /sys/devices/system/cpu/*/cpufreq/scaling_cur_freq
    val networkEnabled: Boolean = false,    // /proc/net/dev — RX/TX KB/s
    val pageFaultsEnabled: Boolean = false, // /proc/vmstat pgmajfault — memory thrashing rate
    val loadEnabled: Boolean = false,       // /proc/loadavg — 1-min load average
    val directReclaimEnabled: Boolean = false, // direct-reclaim events/s from logcat (needs logcat)
    val topProcsEnabled: Boolean = false,   // top-10 process CPU% — reads /proc/*/stat per tick
    val topMemEnabled: Boolean = false,     // top processes by memory (PSS) — procrank (root) / dumpsys (non-root)
    val fpsEnabled: Boolean = false,        // device FPS (screen + video) via SurfaceFlinger --latency
    val playbackEnabled: Boolean = false,   // STB: tunnel mode + video player state from logcat tail
    // App metrics (require a package)
    val appPssEnabled: Boolean = true,      // dumpsys meminfo — PSS total + breakdown (default ON)
    val appProcCpuEnabled: Boolean = false, // whole-process CPU%
    val mainThreadCpuEnabled: Boolean = false, // main-thread CPU%
    val mediaCpuEnabled: Boolean = false    // media/HAL process CPU%
)

/**
 * One monitoring run: owns the sampling loop, zapper, logcat capture and output
 * generation. Runs synchronously (CLI) via [runBlocking] or in a thread (web UI)
 * via [startAsync]. All collections are synchronized so the dashboard can read live.
 */
class MonitorSession(private val adb: Adb, val config: SessionConfig) {

    enum class Status { STARTING, RUNNING, DONE, FAILED }

    companion object {
        /** Default place for reports when the working directory isn't writable (e.g. a Finder-launched .app). */
        fun defaultReportsDir(): File = File(System.getProperty("user.home"), "ADB Device Monitor")

        /**
         * Base folder under which run_* directories are created. An explicit [outputDir] wins;
         * otherwise the current working directory if it's writable (CLI use), else the default
         * reports folder in the user's home (GUI/.app use, where cwd is "/").
         */
        fun resolveBaseDir(outputDir: String?): File {
            if (!outputDir.isNullOrBlank()) return File(outputDir.trim())
            val cwd = File(System.getProperty("user.dir") ?: ".")
            return if (cwd.canWrite()) cwd else defaultReportsDir()
        }
    }

    @Volatile var status = Status.STARTING; private set
    @Volatile var error: String? = null; private set
    @Volatile var info: DeviceInfo? = null; private set
    @Volatile var runDir: File? = null; private set
    @Volatile var outFile: File? = null; private set
    @Volatile var csvFile: File? = null; private set
    @Volatile var deviceTimeAnchor: String? = null; private set
    @Volatile var buildTunnelEnabled: Boolean? = null; private set  // isTunneledPlaybackEnabled (STB build flag)

    val samples: MutableList<Sample> = Collections.synchronizedList(mutableListOf())
    val zapTimes: MutableList<Double> = Collections.synchronizedList(mutableListOf())
    val scenarioMarkers: MutableList<ScenarioMarker> = Collections.synchronizedList(mutableListOf())
    @Volatile private var captureStartNanos: Long? = null

    val logcatFile: File? get() = runDir?.let { File(it, "logcat.log") }

    @Volatile private var anomalyScanner: AnomalyScanner.Incremental? = null
    /** Anomalies accumulated incrementally so far (fast — no full re-scan). */
    fun liveAnomalies(): List<Anomaly> = anomalyScanner?.snapshot() ?: emptyList()

    fun addScenarioMarker(
        attempt: Int,
        stepIndex: Int,
        label: String,
        section: String?,
        type: ScenarioMarkerType,
        outcome: ScenarioMarkerOutcome = ScenarioMarkerOutcome.RUNNING,
        detail: String? = null
    ): ScenarioMarker {
        val start = captureStartNanos
        val elapsed = if (start == null) 0.0 else ((System.nanoTime() - start) / 1e9).coerceAtLeast(0.0)
        return ScenarioMarker(elapsed, attempt, stepIndex, label, section, type, outcome, detail).also {
            scenarioMarkers += it
        }
    }

    // Heavy per-app collectors (dumpsys / procrank) run on a background thread and publish here,
    // so the fixed-interval sampling loop reads them without ever blocking on a slow adb call.
    @Volatile private var latestTopMem: List<ProcMemSample> = emptyList()
    @Volatile private var latestActivity: String? = null
    @Volatile private var slowCollectorRun = false
    @Volatile private var slowThread: Thread? = null
    @Volatile var memSource: String? = null; private set   // "PSS" (procrank) or "RSS" (/proc)

    @Volatile private var logcatProc: Process? = null
    @Volatile private var stopRequested = false
    @Volatile private var outputsWritten = false
    @Volatile private var profileStarted = false
    @Volatile private var perfettoRemotePath: String? = null
    private val profileRemotePath = "/data/local/tmp/adm_method.trace"

    @Volatile private var sessionThread: Thread? = null
    @Volatile private var finalizing = false
    @Volatile private var zapperThread: Thread? = null

    // ---- watchdog: detects a stalled sampling loop / finalize and dumps every thread's stack ----
    @Volatile private var lastBeatNanos = System.nanoTime()
    @Volatile private var phase = "startup"
    private fun beat() { lastBeatNanos = System.nanoTime() }

    private fun startWatchdog() {
        Thread {
            var reportedBeat = 0L
            while (status == Status.STARTING || status == Status.RUNNING) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                val beatAt = lastBeatNanos
                val stalledSec = (System.nanoTime() - beatAt) / 1e9
                // Sampling should beat every interval; startup/finalize legitimately take longer.
                val limit = if (phase == "sampling") maxOf(15.0, config.intervalSec * 6) else 90.0
                if (stalledSec > limit && beatAt != reportedBeat) {
                    reportedBeat = beatAt   // one dump per stall
                    Log.warn("[watchdog] run appears HUNG: phase=$phase, no progress for ${"%.0f".format(stalledSec)}s — thread dump written to the log")
                    Log.threadDump("watchdog: phase=$phase stalled ${"%.0f".format(stalledSec)}s")
                }
            }
        }.apply { isDaemon = true; name = "watchdog"; start() }
    }

    fun startAsync() {
        sessionThread = Thread({ runBlocking() }, "monitor-session").apply { start() }
    }

    /** Ask the run to finish early; outputs are still generated. */
    fun requestStop() {
        val t = java.time.LocalTime.now()
        println("[stop] requested at $t — signalling threads and aborting in-flight adb commands")
        stopRequested = true
        slowCollectorRun = false
        slowThread?.interrupt()
        // Enter abort mode: force-kill the adb command currently blocking the loop/collector AND make
        // every subsequent adb call return instantly, so the remaining reads in the in-progress tick
        // (pss, gfxinfo, top-CPU, media…) don't each run to completion before the loop can break.
        // The gate is scoped to this run's own threads, so the device list and UI snapshots keep working.
        if (!finalizing) try { adb.beginAbort(listOfNotNull(sessionThread, slowThread, zapperThread)) } catch (_: Exception) {}
        // NOTE: we deliberately do NOT interrupt the sampling thread. Interrupting it could surface as
        // an InterruptedException out of an unrelated blocking call (join/waitFor), which would mark
        // the run FAILED and hide the report button. The loop instead polls stopRequested during a
        // short-sliced inter-sample wait, so Stop is still caught within ~150 ms.
    }

    fun runBlocking() {
        if (sessionThread == null) sessionThread = Thread.currentThread()
        startWatchdog()
        try {
            doRun()
            val t0 = System.nanoTime()
            println("[stop] finalizing outputs (report/CSV/logcat)…")
            finalizeOutputs()
            println("[stop] finalize done in ${(System.nanoTime() - t0) / 1_000_000}ms — status DONE")
            status = Status.DONE
        } catch (e: Exception) {
            Thread.interrupted()   // clear any lingering interrupt flag before finalizing
            try { finalizeOutputs() } catch (_: Exception) {}
            // A stop mid-blocking-call (or InterruptedException) is a normal stop, not a failure:
            // if a report was written, report DONE so the download button appears.
            if (stopRequested || e is InterruptedException) {
                status = if (outputsWritten) Status.DONE else Status.FAILED
                if (status == Status.FAILED) error = "stopped before enough samples were collected for a report"
                println("[stop] stopped early — status $status")
            } else {
                error = e.message ?: e.toString()
                Log.error("Session failed: $error", e)
                status = Status.FAILED
            }
        } finally {
            Log.info("[run] finished: status=$status samples=${samples.size}" + (error?.let { " error=$it" } ?: ""))
            Log.detachRun()
        }
    }

    // ---------- run pipeline ----------

    private fun doRun() {
        adb.endAbort()   // fresh run — ensure a prior Stop's abort gate isn't still set
        val collector = MetricsCollector(
            adb, config.target,
            thermalEnabled    = config.thermalEnabled,
            networkEnabled    = config.networkEnabled,
            pageFaultsEnabled = config.pageFaultsEnabled,
            cpuFreqEnabled    = config.cpuFreqEnabled,
            perCoreEnabled    = config.perCoreEnabled,
            psiEnabled        = config.psiEnabled,
            loadEnabled       = config.loadEnabled,
            zoneInfoEnabled   = config.zoneInfoEnabled
        )
        val info = collector.deviceInfo()
        this.info = info
        println(
            "Connected: ${info.manufacturer} ${info.model} | Android ${info.androidVersion} " +
            "(SDK ${info.sdk}) | ${info.cpuCores} cores | ${"%.0f".format(info.totalMemMb)} MB RAM"
        )

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val labelPart = config.label?.let { "_${sanitize(it)}" } ?: ""
        val baseDir = resolveBaseDir(config.outputDir)
        val dir = config.runDirectoryOverride
            ?: File(baseDir, "run_${stamp}${labelPart}_${sanitize(info.manufacturer)}_${sanitize(info.model)}")
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException(
                "Cannot create the reports folder at ${dir.absolutePath}. " +
                "Pick a writable Reports folder (the field on the start screen) and try again."
            )
        }
        runDir = dir
        Log.attachRun(File(dir, "run.log"))
        Log.info("[run] folder=${dir.absolutePath}")
        Log.info("[run] config=$config")
        outFile = config.outputOverride ?: File(dir, "report.pdf")
        csvFile = config.csvOverride ?: File(dir, "samples.csv")
        writeDeviceInfo(File(dir, "device-info.log"), info)
        println("Run folder: ${dir.absolutePath}")
        println(collector.probePsi())

        config.packageName?.let { pkg ->
            val probe = collector.pss(pkg)
            if (probe == null) {
                val raw = collector.pssRaw(pkg)
                val firstLine = raw.lines().firstOrNull { it.isNotBlank() } ?: "<empty reply>"
                println("PSS: could not get data for '$pkg'. Device replied: '${firstLine.take(100)}'")
                println("     Check the package name and that the app is running (adb shell pidof $pkg).")
            } else {
                println("PSS: tracking $pkg (currently ${probe.totalMb?.let { "%.1f MB".format(it) } ?: "?"})")
            }
        }

        if (config.logcatEnabled) {
            deviceTimeAnchor = try {
                adb.shell(config.target, "date '+%m-%d %H:%M:%S'")
            } catch (e: Exception) { null }
            logcatProc = try {
                adb.startLogcat(config.target, File(dir, "logcat.log"))
            } catch (e: Exception) {
                System.err.println("logcat capture failed to start: ${e.message}")
                null
            }
            anomalyScanner = AnomalyScanner.Incremental(deviceTimeAnchor)
        }

        if (config.profileEnabled && config.packageName != null) {
            try {
                adb.shell(config.target, "rm -f $profileRemotePath")
                adb.shell(config.target, "am profile start --sampling 1000 ${config.packageName} $profileRemotePath")
                profileStarted = true
                println("Method profiler: ART sampling started (1 ms interval) — top main-thread methods will be in the report.")
            } catch (e: Exception) {
                System.err.println("Method profiler failed to start: ${e.message} (app must be debuggable)")
            }
        }

        if (config.perfettoEnabled) {
            val remote = "/data/misc/perfetto-traces/adm_${System.currentTimeMillis()}.perfetto-trace"
            try {
                val out = adb.shell(
                    config.target,
                    "perfetto -o $remote -t ${(config.durationSec + 60).toInt()}s --background " +
                        "sched freq idle gfx view input am wm binder_driver"
                )
                perfettoRemotePath = remote
                println("Perfetto: system trace recording started${if (out.isNotBlank()) " (${out.lines().first()})" else ""}.")
            } catch (e: Exception) {
                System.err.println("Perfetto failed to start: ${e.message}")
            }
        }

        val totalSamples = (config.durationSec / config.intervalSec).toInt().coerceAtLeast(1)
        println("Sampling $totalSamples points every ${config.intervalSec}s (${config.durationSec}s total).")
        if (config.zapMs != null) {
            val until = config.zapCount?.let { " for $it zaps, then stop" } ?: ""
            println("Zapping enabled: CHANNEL_UP every ${config.zapMs}ms$until")
        }
        println()

        status = Status.RUNNING
        val startNanos = System.nanoTime()
        captureStartNanos = startNanos
        if (config.zapMs != null) startZapper(startNanos)

        var prevSnap: Snapshot? = collector.snapshot()
        var prevElapsed = 0.0
        var prevGfx: GfxData? = null
        var lastVsync = 0L
        var prevMainTicks: Long? = null
        var prevAppTicks: Long? = null
        var prevMediaTicks: Map<String, Long?> = emptyMap()
        var prevProcTicks: Map<Int, Pair<String, Long>>? = null
        val playbackTracker = if (config.playbackEnabled && config.logcatEnabled) Playback.Tracker() else null
        val reclaimCounter = if (config.logcatEnabled && config.directReclaimEnabled) GrowTailCounter(LogMetrics.DIRECT_RECLAIM) else null
        val fpsCollector = if (config.fpsEnabled) FpsCollector(adb, config.target, config.packageName) else null

        // Background collector for the heavy dumpsys/procrank reads (top-memory, foreground
        // activity). These can take several seconds, so running them here — not inline in the
        // sampling loop — keeps the loop on its fixed interval and all other charts live.
        val wantActivity = config.appPssEnabled && config.packageName != null
        if (config.topMemEnabled || wantActivity) {
            slowCollectorRun = true
            slowThread = Thread {
                var cycle = 0
                while (slowCollectorRun && !stopRequested) {
                    val t0 = System.nanoTime()
                    try {
                        if (config.topMemEnabled) {
                            latestTopMem = collector.topMemoryProcs()
                            memSource = collector.lastMemSource
                        }
                        if (wantActivity && !stopRequested) latestActivity = collector.topActivity()
                    } catch (e: Exception) { /* keep previous values */ }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    // Log every cycle so a slow/blocking read on this device is visible.
                    println("[collector] cycle #${++cycle}: topMem=${latestTopMem.size} rows, source=$memSource, took=${ms}ms")
                    // Adaptive backoff: if the read is slow it competes with the sampling loop for
                    // the adb transport, so poll it less often (up to 15 s) to keep other charts live.
                    val sleep = when {
                        ms > 5000 -> 15000L
                        ms > 2000 -> 9000L
                        else -> 4000L
                    }
                    if (ms > 3000) System.err.println("[collector] WARNING: memory read took ${ms}ms — backing off to ${sleep / 1000}s so it doesn't delay other charts")
                    try { Thread.sleep(sleep) } catch (e: InterruptedException) { break }
                }
                println("[collector] stopped")
            }.apply { isDaemon = true; name = "slow-collector"; start() }
        }
        var nextDeadline = startNanos + (config.intervalSec * 1e9).toLong()

        for (i in 1..totalSamples) {
            // Responsive inter-sample wait: poll stopRequested in ~150 ms slices instead of one long
            // sleep, so Stop is caught fast WITHOUT interrupting the thread (which risked a stray
            // InterruptedException marking the run FAILED).
            phase = "sampling"; beat()
            while (!stopRequested) {
                val remaining = (nextDeadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                try { Thread.sleep(minOf(remaining, 150L)) } catch (e: InterruptedException) { Thread.interrupted(); break }
            }
            nextDeadline += (config.intervalSec * 1e9).toLong()
            // Catch a stop request before doing any per-tick work, so Stop is snappy.
            if (stopRequested) { println("Stop requested — finishing run and generating the report."); break }

            val snapT0 = System.nanoTime()
            val snap = try {
                collector.snapshot()
            } catch (e: Exception) {
                if (stopRequested) { println("Stop requested — sample aborted, finishing run."); break }
                System.err.println("[$i/$totalSamples] sample failed: ${e.message}")
                continue
            }
            val snapMs = (System.nanoTime() - snapT0) / 1_000_000
            beat()
            // A snapshot is normally well under a second. If it blocks much longer, an adb call is
            // contending with the background collector — surface it so the cause is visible.
            if (snapMs > 1500) System.err.println("[loop] sample #$i snapshot took ${snapMs}ms (adb contention — likely the Top Process Memory collector)")
            // Bail out of the tick the moment Stop arrives, before running the rest of the per-tick
            // adb reads — the partial sample is discarded and the report is generated immediately.
            if (stopRequested) { println("Stop requested — finishing run and generating the report."); break }
            val pss = if (config.appPssEnabled) config.packageName?.let { collector.pss(it) } else null

            // UI jank % + frame phase breakdown (gfxinfo framestats)
            var jankPct: Double? = null
            var phases: FramePhases? = null
            if (config.jankEnabled && config.packageName != null) {
                val gfx = collector.gfxFramestats(config.packageName)
                val prev = prevGfx
                if (gfx != null && prev != null) {
                    val dT = gfx.totalFrames - prev.totalFrames
                    val dJ = gfx.jankyFrames - prev.jankyFrames
                    if (dT > 0 && dJ >= 0) jankPct = (100.0 * dJ / dT).coerceIn(0.0, 100.0)
                }
                if (gfx != null) {
                    prevGfx = gfx
                    val fresh = gfx.frames.filter { it.vsync > lastVsync }
                    if (fresh.isNotEmpty()) {
                        lastVsync = fresh.maxOf { it.vsync }
                        phases = FramePhases(
                            vsync = lastVsync,
                            inputMs = fresh.map { it.inputMs }.average(),
                            animMs = fresh.map { it.animMs }.average(),
                            layoutMs = fresh.map { it.layoutMs }.average(),
                            drawMs = fresh.map { it.drawMs }.average()
                        )
                    }
                }
            }

            val elapsed = (System.nanoTime() - startNanos) / 1e9
            val dtSec = elapsed - prevElapsed

            // App main-thread CPU% and full-process CPU% (all threads summed)
            var mainCpu: Double? = null
            var appCpu: Double? = null
            if (config.packageName != null && (config.mainThreadCpuEnabled || config.appProcCpuEnabled)) {
                val pid = collector.pid(config.packageName)
                if (pid != null) {
                    if (config.mainThreadCpuEnabled) {
                        val mainTicks = collector.mainThreadTicks(pid)
                        val prevMain = prevMainTicks
                        if (mainTicks != null && prevMain != null && dtSec > 0) {
                            val d = mainTicks - prevMain
                            if (d >= 0) mainCpu = (d / dtSec).coerceIn(0.0, 100.0)
                            else collector.invalidatePid()  // counter backwards → app restarted
                        }
                        prevMainTicks = mainTicks
                        if (mainTicks == null) collector.invalidatePid()
                    }
                    if (config.appProcCpuEnabled) {
                        val appTicks = collector.appProcessTicks(pid)
                        val prevApp = prevAppTicks
                        if (appTicks != null && prevApp != null && dtSec > 0) {
                            val d = appTicks - prevApp
                            if (d >= 0) appCpu = (d / dtSec).coerceAtLeast(0.0)
                        }
                        prevAppTicks = appTicks
                        // Non-root fallback: /proc task stats unreadable → use dumpsys cpuinfo.
                        if (appTicks == null) appCpu = collector.appCpuFromDumpsys(config.packageName)
                    }
                } else if (config.appProcCpuEnabled) {
                    // pidof blocked (non-root) — still try a dumpsys-based reading by package.
                    appCpu = collector.appCpuFromDumpsys(config.packageName)
                }
            }

            // Media process CPU — mediaserver, media.codec, audioserver, vendor HAL
            var mediaCpus: Map<String, Double?> = emptyMap()
            if (config.packageName != null && config.mediaCpuEnabled) {
                val ticks = collector.mediaProcessTicks()
                mediaCpus = ticks.mapValues { (name, curTicks) ->
                    val prevT = prevMediaTicks[name]
                    if (curTicks != null && prevT != null && dtSec > 0) {
                        val d = curTicks - prevT
                        if (d >= 0) (d / dtSec).coerceAtLeast(0.0) else null
                    } else null
                }
                prevMediaTicks = ticks
            }

            // Top-10 process CPU% — only when enabled (one extra shell call per tick)
            var topProcs: List<ProcessSample> = emptyList()
            if (config.topProcsEnabled) {
                val curProcTicks = collector.allProcessTicks()
                val prevProcs = prevProcTicks
                if (prevProcs != null && dtSec > 0) {
                    val list = mutableListOf<ProcessSample>()
                    for ((pid, entry) in curProcTicks) {
                        val prevTicks = prevProcs[pid]?.second ?: continue
                        val d = entry.second - prevTicks
                        if (d < 0) continue
                        val pct = d / dtSec
                        if (pct > 0.01) list += ProcessSample(pid, entry.first, pct)
                    }
                    list.sortByDescending { it.cpuPct }
                    topProcs = list.take(10)
                }
                prevProcTicks = curProcTicks
            }

            // STB playback: replay logcat tail → current video state + tunnel mode (no extra adb)
            var videoState: String? = null
            var tunnelMode: String? = null
            if (playbackTracker != null) {
                val lf = File(dir, "logcat.log")
                playbackTracker.update(Playback.readLogTail(lf))
                val snapPb = playbackTracker.snapshot()
                videoState = snapPb.first
                tunnelMode = snapPb.second
                if (buildTunnelEnabled == null && playbackTracker.buildTunnel != null)
                    buildTunnelEnabled = playbackTracker.buildTunnel
            }

            // Kernel direct-reclaim events/s from the growing logcat file (memory pressure)
            var directReclaim: Double? = null
            if (reclaimCounter != null && dtSec > 0) {
                val n = reclaimCounter.countNew(File(dir, "logcat.log"))
                directReclaim = n / dtSec
            }
            // Incrementally scan new logcat lines for anomalies (keeps live polling cheap).
            anomalyScanner?.scanNew(File(dir, "logcat.log"))

            // Device FPS — screen (focused surface) + video (SurfaceView) presented frame rate.
            var screenFps: Double? = null
            var videoFps: Double? = null
            if (fpsCollector != null) {
                val f = fpsCollector.sample(config.intervalSec)
                screenFps = f.screenFps
                videoFps = f.videoFps
            }

            // Read the latest values from the background collector (never blocks the loop).
            val topActivity = if (wantActivity) latestActivity else null
            val topMem = if (config.topMemEnabled) latestTopMem else emptyList()
            if (config.topMemEnabled && i <= 4) println("[loop] sample #$i: memprocs attached = ${topMem.size} rows (latestTopMem=${latestTopMem.size})")

            samples += collector.toSample(prevSnap, dtSec, snap, elapsed, pss, jankPct, mainCpu, phases, appCpu, mediaCpus, topProcs, videoState, tunnelMode, directReclaim, topActivity, topMem, screenFps, videoFps)
            prevSnap = snap
            prevElapsed = elapsed

            val pssText = when {
                config.packageName == null -> ""
                pss?.totalMb != null -> "  PSS %.1f MB".format(pss.totalMb)
                else -> "  PSS n/a"
            }
            val jankText = jankPct?.let { "  jank %.1f%%".format(it) } ?: ""
            val mainText = mainCpu?.let { "  main %.0f%%".format(it) } ?: ""
            val appText = appCpu?.let { "  app %.0f%%".format(it) } ?: ""
            val totalMedia = mediaCpus.values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
            val mediaText = totalMedia?.let { "  media %.0f%%".format(it) } ?: ""
            val playText = if (playbackTracker != null) {
                buildString {
                    if (videoState != null) append("  vid=$videoState")
                    if (tunnelMode != null && tunnelMode != "n/a") append("  tunnel=$tunnelMode")
                }
            } else ""
            println(
                "[%d/%d] t=%6.1fs  CPU %s%%  Mem %.0f/%.0f MB  PSI cpu=%s mem=%s io=%s%s%s".format(
                    i, totalSamples, elapsed,
                    samples.last().cpuUtilPct?.let { "%5.1f".format(it) } ?: "  n/a",
                    samples.last().memUsedMb, samples.last().memTotalMb,
                    samples.last().cpuPsiSomeAvg10?.toString() ?: "n/a",
                    samples.last().memPsiSomeAvg10?.toString() ?: "n/a",
                    samples.last().ioPsiSomeAvg10?.toString() ?: "n/a",
                    pssText, jankText
                ) + mainText + appText + mediaText + playText
            )

            if (stopRequested) {
                println("Stop requested — finishing run and generating the report.")
                break
            }
        }

        slowCollectorRun = false
        slowThread?.interrupt()
        slowThread?.join(2000)   // give the collector up to 2s to unwind; it's a daemon regardless
        println("[loop] sampling finished (${samples.size} samples); building report…")

        if (samples.size < 2) throw IllegalStateException("Not enough samples collected to build a report")
    }

    /** Daemon thread sending CHANNEL_UP every zapMs; honors zapCount and stop requests. */
    private fun startZapper(startNanos: Long) {
        val zapMs = config.zapMs ?: return
        val thread = Thread {
            while (!stopRequested) {
                Thread.sleep(zapMs)
                if (stopRequested) return@Thread
                try {
                    adb.shell(config.target, "input keyevent KEYCODE_CHANNEL_UP")
                    val elapsed = (System.nanoTime() - startNanos) / 1e9
                    zapTimes += elapsed
                    println("        >> zap #%d%s: CHANNEL_UP at t=%.1fs".format(
                        zapTimes.size, config.zapCount?.let { "/$it" } ?: "", elapsed
                    ))
                } catch (e: Exception) {
                    System.err.println("        >> zap failed: ${e.message}")
                }
                if (config.zapCount != null && zapTimes.size >= config.zapCount) {
                    println("Zap count reached (${config.zapCount}) — stopping early.")
                    stopRequested = true
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = "zapper"
        zapperThread = thread
        thread.start()
    }

    // ---------- outputs ----------

    @Synchronized
    fun finalizeOutputs() {
        // Leave abort mode (if a Stop set it) so the trace pulls below can use adb again.
        finalizing = true
        phase = "finalize"; beat()
        try { adb.endAbort() } catch (_: Exception) {}
        if (outputsWritten || samples.size < 2) return
        outputsWritten = true
        val dir = runDir ?: return
        val info = info ?: return

        logcatProc?.let {
            it.destroy()
            it.waitFor(2, TimeUnit.SECONDS)
        }

        val lf = File(dir, "logcat.log")
        // Reuse the incrementally-scanned anomalies (one final catch-up pass) — avoids a full
        // re-scan of a possibly-huge logcat at stop.
        val anomalies = if (config.logcatEnabled) {
            anomalyScanner?.let { it.scanNew(lf); it.snapshot() } ?: AnomalyScanner.scan(lf, deviceTimeAnchor)
        } else emptyList()
        if (config.logcatEnabled) {
            AnomalyScanner.writeReport(anomalies, File(dir, "anomalies.log"))
            println("\nAnomalies: ${AnomalyScanner.summary(anomalies)}")
        }

        val appInfo = if (config.logcatEnabled) EventInfo.extract(lf) else emptyMap()
        if (appInfo.isNotEmpty()) {
            println("App info from logcat events: ${appInfo.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }

        // STB build tunnel flag. It's normally already latched live; only fall back to a full-file
        // scan when we never saw it (avoids re-reading a huge logcat at stop).
        if (config.playbackEnabled && config.logcatEnabled && buildTunnelEnabled == null) {
            val a = Playback.analyzeFile(lf)
            if (a.buildTunnel != null) buildTunnelEnabled = a.buildTunnel
            println("Playback: build tunnel flag = ${buildTunnelEnabled?.let { if (it) "ENABLED" else "disabled" } ?: "not found"}" +
                "  | actual tunnel events: ${a.tunneledEvents} tunneled, ${a.nonTunneledEvents} non-tunneled")
        }

        // ART method profiler: stop, pull, analyze (top methods + jank attribution).
        var analysis: TraceAnalysis? = null
        if (profileStarted && config.packageName != null) {
            try {
                adb.shell(config.target, "am profile stop ${config.packageName}")
                Thread.sleep(2000)   // give ART time to flush the trace
                val local = File(dir, "method-profile.trace")
                adb.pull(config.target, profileRemotePath, local.absolutePath)
                if (local.exists() && local.length() > 0) {
                    analysis = TraceParser.analyze(local, config.packageName)
                    TraceParser.writeReport(analysis, config.packageName, File(dir, "main-thread-methods.txt"))
                    if (analysis != null && analysis.topMethods.isNotEmpty()) {
                        println("Main thread busy %.0f ms of %.0f ms traced.".format(analysis.busyMs, analysis.spanMs))
                        println("Hot methods by self time (full list in main-thread-methods.txt):")
                        analysis.topMethods.take(5).forEach {
                            println("  %6.1f ms self  %4.1f%% busy  %s".format(it.selfMs, it.pctBusy, it.method))
                        }
                        println("Frames in trace: ${analysis.totalFrames}, slow (>${analysis.slowThresholdMs} ms): ${analysis.slowFrames}")
                        if (analysis.jankMethods.isNotEmpty()) {
                            println("Jank culprits from ${config.packageName}:")
                            analysis.jankMethods.take(5).forEach {
                                println("  %6.1f ms in %d slow frames  %s".format(it.msInSlowFrames, it.framesTouched, it.method))
                            }
                        }
                    } else {
                        println("Method trace pulled but no main-thread data parsed — open method-profile.trace in Android Studio.")
                    }
                }
            } catch (e: Exception) {
                System.err.println("Method profiler collection failed: ${e.message}")
            }
        }

        // Perfetto: stop the recording and pull the trace.
        perfettoRemotePath?.let { remote ->
            try {
                adb.shell(config.target, "pkill -TERM perfetto || true")
                Thread.sleep(1500)
                val local = File(dir, "trace.perfetto-trace")
                adb.pull(config.target, remote, local.absolutePath)
                if (local.exists() && local.length() > 0) {
                    println("Perfetto trace saved: ${local.name} — open it at https://ui.perfetto.dev (main-thread slices: Choreographer#doFrame, traversal, measure, layout, binder).")
                }
            } catch (e: Exception) {
                System.err.println("Perfetto trace collection failed: ${e.message}")
            }
        }

        println("Generating report with ${samples.size} samples ...")
        PdfReport(
            samples.toList(), info, config.target, config.intervalSec,
            config.zapMs, zapTimes.toList(), config.packageName, anomalies, appInfo,
            scenarioMarkers = scenarioMarkers.toList(),
            label = config.label,
            loadEnabled = config.loadEnabled,
            pageFaultsEnabled = config.pageFaultsEnabled,
            buildTunnelEnabled = buildTunnelEnabled,
            cpuUtilEnabled = config.cpuUtilEnabled,
            cpuIdleEnabled = config.cpuIdleEnabled,
            memoryMbEnabled = config.memoryMbEnabled,
            memoryPctEnabled = config.memoryPctEnabled,
            swapEnabled = config.swapEnabled,
            psiEnabled = config.psiEnabled,
            zoneInfoEnabled = config.zoneInfoEnabled
        ).write(outFile!!)
        writeCsv(samples.toList(), csvFile!!)
        println("Run folder: ${dir.absolutePath}")
        println("  PDF: ${outFile!!.absolutePath}")
        println("  CSV: ${csvFile!!.absolutePath}")
        if (config.logcatEnabled) println("  logcat.log, anomalies.log, device-info.log")
    }

    private fun writeDeviceInfo(file: File, info: DeviceInfo) {
        val fingerprint = try {
            adb.shell(config.target, "getprop ro.build.fingerprint").lines().firstOrNull { it.isNotBlank() } ?: "?"
        } catch (e: Exception) { "?" }
        val lines = mutableListOf(
            "Run started:       ${LocalDateTime.now()} (host time)",
            "Target:            ${config.target}"
        )
        config.label?.let { lines += "Run label:         $it" }
        lines += listOf(
            "Manufacturer:      ${info.manufacturer}",
            "Model:             ${info.model}",
            "Android version:   ${info.androidVersion} (SDK ${info.sdk})",
            "CPU cores:         ${info.cpuCores}",
            "Total RAM:         ${"%.0f".format(info.totalMemMb)} MB",
            "Build fingerprint: $fingerprint",
            "Config:            duration=${config.durationSec}s interval=${config.intervalSec}s zap=${config.zapMs?.let { "${it}ms" } ?: "off"} zapCount=${config.zapCount ?: "-"} package=${config.packageName ?: "-"} logcat=${config.logcatEnabled} jank=${config.jankEnabled}"
        )
        file.writeText(lines.joinToString("\n") + "\n")
    }
}

internal fun sanitize(s: String): String =
    s.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "unknown" }

// Fixed CSV column order for media procs (key = proc name, used in mediaProcs map)
internal val MEDIA_PROC_CSV = listOf(
    "mediaserver"                           to "media_mediaserver_cpu",
    "media.codec"                           to "media_codec_cpu",
    "audioserver"                           to "media_audioserver_cpu",
    "vendor.mediaswcodec"                   to "media_swcodec_cpu",
    "android.hardware.media.omx@1.0-service" to "media_omx_cpu"
)

internal fun writeCsv(samples: List<Sample>, file: File) {
    file.bufferedWriter().use { w ->
        val mediaHeader = MEDIA_PROC_CSV.joinToString(",") { it.second }
        w.appendLine(
            "elapsed_sec,cpu_util_pct,cpu_idle_pct,cpu_psi_some_avg10,mem_psi_some_avg10,mem_psi_full_avg10," +
            "io_psi_some_avg10,mem_total_mb,mem_used_mb,mem_used_pct,mem_available_mb,cached_mb,swap_used_mb," +
            "zone_active,zone_free_pages,zone_wm_min,zone_wm_low,zone_wm_high,load1," +
            "net_rx_kb_s,net_tx_kb_s,cpu_temp_c,cpu_freq_mhz,major_faults_per_s,direct_reclaim_per_s,jank_pct," +
            "main_thread_cpu_pct,app_cpu_pct,frame_input_ms,frame_anim_ms,frame_layout_ms,frame_draw_ms," +
            "pss_total_mb,pss_java_heap_mb,pss_native_heap_mb,pss_code_mb,pss_stack_mb," +
            "pss_graphics_mb,pss_private_other_mb,pss_system_mb,$mediaHeader,video_state,tunnel_mode,top_activity"
        )
        for (s in samples) {
            val base = listOf<Any?>(
                "%.2f".format(s.elapsedSec), s.cpuUtilPct, s.cpuIdlePct, s.cpuPsiSomeAvg10,
                s.memPsiSomeAvg10, s.memPsiFullAvg10, s.ioPsiSomeAvg10,
                "%.1f".format(s.memTotalMb), "%.1f".format(s.memUsedMb), "%.1f".format(s.memUsedPct),
                "%.1f".format(s.memAvailableMb), "%.1f".format(s.cachedMb),
                s.swapUsedMb,
                s.zoneActiveCount, s.zoneFreePages, s.zoneWmMinPages, s.zoneWmLowPages, s.zoneWmHighPages,
                s.load1,
                s.netRxKbps, s.netTxKbps, s.cpuTempC, s.cpuFreqMhz, s.majorFaultsPerSec, s.directReclaimPerSec, s.jankPct,
                s.mainThreadCpuPct, s.appCpuPct,
                s.frameInputMs, s.frameAnimMs, s.frameLayoutMs, s.frameDrawMs,
                s.pss?.totalMb, s.pss?.javaHeapMb, s.pss?.nativeHeapMb, s.pss?.codeMb,
                s.pss?.stackMb, s.pss?.graphicsMb, s.pss?.privateOtherMb, s.pss?.systemMb
            )
            val media = MEDIA_PROC_CSV.map { (key, _) -> s.mediaProcs[key] }
            val playback = listOf<Any?>(s.videoState, s.tunnelMode, s.topActivity?.replace(",", " "))
            w.appendLine((base + media + playback).joinToString(",") { it?.toString() ?: "" })
        }
    }
}
