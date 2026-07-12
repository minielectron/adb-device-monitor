package devicemonitor

import java.awt.Desktop
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE = """
adb-device-monitor: live CPU/memory/app performance for adb-connected Android devices,
with a PDF report, CSV, logcat capture, anomaly flagging — and a web UI.

Web mode (recommended):
  adb-device-monitor --web 8080
  Opens ADB Performance Studio at http://localhost:8080 — pick a connected device,
  configure zapping / logcat / PSS / jank tracking in the browser, press Start, and
  watch live charts with health verdicts. Reports land in run_<timestamp>_<device>/.

CLI mode:
  adb-device-monitor --duration <seconds> --interval <seconds> [options]

  --duration <seconds>   Total monitoring time (required)
  --interval <seconds>   Sampling interval, decimals allowed (required)
  --package <pkg>        Sample the app's PSS via dumpsys meminfo each tick
  --jank                 Track UI jank % + frame phase breakdown via gfxinfo framestats (requires --package)
  --profile              ART sampling profiler: top main-thread methods table in the report
                         (requires --package; app must be debuggable)
  --perfetto             Record a Perfetto system trace for the run (trace.perfetto-trace)
  --playback             STB: track video player state + tunnel mode (TUNNELED/NON-TUNNELED) from logcat
  --zap-time <millis>    Send CHANNEL_UP every N ms (channel-zapping stress test)
  --zap-count <n>        Stop after n zaps (requires --zap-time)
  --no-logcat            Disable logcat capture / anomaly flagging / app-info extraction
  --label <name>         Tag this run (e.g. "tunnel-on", "baseline") — used in the folder name and PDF title
                         Useful for tunnel mode comparisons: run once with tunnel off (--label tunnel-off),
                         then again with it on (--label tunnel-on), and compare in the web UI
  --serve <port>         Live dashboard for this CLI run at http://localhost:<port>
  --device <serial>      Use a specific device serial (skips the picker)
  --ip <host[:port]>     Connect over TCP/IP first (port defaults to 5555)
  --output-dir <dir>     Base folder for run_* output (default: current dir if writable, else ~/ADB Device Monitor)
  --output <file.pdf>    Override report path (default: <run folder>/report.pdf)
  --csv <file.csv>       Override CSV path (default: <run folder>/samples.csv)
  --adb <path>           Path to adb binary (default: adb on PATH)
  --scan-logcat <path>   Analyze an existing logcat (no device): build tunnel flag + player states.
                         Accepts a logcat.log, a run folder, or "last" for the newest run_* folder.

Each run creates run_<timestamp>_<manufacturer>_<model>/ with report.pdf, samples.csv,
logcat.log, anomalies.log and device-info.log.

Examples:
  adb-device-monitor --web 8080
  adb-device-monitor --duration 300 --interval 2 --zap-time 5000
  adb-device-monitor --duration 300 --interval 2 --package com.telekom.onetv.tv.hr --jank
"""

private val BOOL_FLAGS = setOf("jank", "no-logcat", "profile", "perfetto", "playback", "app")

/** Build identifier read from the jar manifest — lets you confirm which build is running. */
fun buildTag(): String = try {
    val mf = java.util.jar.Manifest(object {}.javaClass.getResourceAsStream("/META-INF/MANIFEST.MF"))
    val ver = mf.mainAttributes.getValue("Implementation-Version") ?: "1.0.0"
    val bt = mf.mainAttributes.getValue("Build-Time") ?: "?"
    "v$ver (build $bt)"
} catch (e: Exception) {
    "v1.0.0"
}

/** Generates the procrank + device-snapshot HTML from fixed test data (no device needed). */
private fun selfTest() {
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "adm-selftest").apply { mkdirs() }

    // --- procrank snapshot from the real telekom sample (fractional MiB values) ---
    val entries = listOf(
        Capabilities.ProcrankEntry(5077, "com.telekom.onetv.tv", 1987.7, 329.6, 204.7, 193.3),
        Capabilities.ProcrankEntry(687, "system_server", 2138.0, 201.3, 79.6, 73.6),
        Capabilities.ProcrankEntry(5683, "hu.telekom.telekomtv", 1748.5, 136.2, 48.4, 44.0),
        Capabilities.ProcrankEntry(1382, "com.google.android.gms.persistent", 1468.3, 184.0, 44.4, 30.2),
        Capabilities.ProcrankEntry(999, "surfaceflinger", 850.1, 90.5, 30.1, 25.0),
        // edge-case names that used to be able to break a client-side JS DATA array
        Capabilities.ProcrankEntry(12, "[kworker/0:1H]", 0.0, 3.2, 2.0, 1.5),
        Capabilities.ProcrankEntry(13, "weird\"name\\path", 10.0, 5.0, 4.0, 3.0),
        Capabilities.ProcrankEntry(14, "<script>alert(1)</script>", 10.0, 6.0, 5.0, 4.0)
    )
    val ps = Capabilities.ProcrankSnapshot(
        entries = entries, source = "procrank",
        totalPssMb = entries.sumOf { it.pssMb ?: 0.0 },
        totalRssMb = entries.sumOf { it.rssMb ?: 0.0 },
        memTotalMb = 1912.4, memFreeMb = 86.5, memAvailableMb = 411.7
    )
    val pf = java.io.File(dir, "selftest-procrank.html")
    Capabilities.writeProcrankHtml(ps, "selftest-device", pf)
    println("SELFTEST procrank  -> ${pf.absolutePath}  (entries=${ps.entries.size})")

    // --- device snapshot ---
    val snap = Capabilities.Snapshot(
        summary = listOf("Model" to "SEI SmartBox", "Android" to "11", "RAM" to "1912 MB"),
        memTotalMb = 1912.4, memFreeMb = 86.5, memAvailableMb = 411.7,
        androidMb = 512.3, thirdPartyMb = 204.7, launcherMb = 88.2,
        launcherPkg = "com.telekom.onetv.tv",
        processes = entries.map { Capabilities.ProcEntry(it.name, it.pssMb ?: 0.0, "third") }
    )
    val df = java.io.File(dir, "selftest-device.html")
    Capabilities.writeHtml(snap, "selftest-device", df)
    println("SELFTEST device    -> ${df.absolutePath}")
    println("SELFTEST locale    -> ${java.util.Locale.getDefault()}")
}

fun main(args: Array<String>) {
    if (args.contains("--version") || args.contains("-v")) {
        println("ADB Device Monitor ${buildTag()}")
        return
    }
    if (args.contains("--selftest")) {
        selfTest()
        return
    }
    val opts = parseArgs(args)
    val adb = Adb(Adb.locate(opts["adb"]))
    System.err.println("ADB Device Monitor ${buildTag()}")

    // ---------- desktop app mode (macOS .app / double-click): start web UI + open browser ----------
    if (opts.containsKey("app")) {
        val port = pickPort(opts["web"]?.toIntOrNull() ?: 8080)
        val app = WebApp(port, adb)
        try {
            app.start()
        } catch (e: Exception) {
            fail("Could not start ADB Device Monitor on port $port: ${e.message}")
        }
        Runtime.getRuntime().addShutdownHook(Thread { app.session?.finalizeOutputs() })
        val url = "http://localhost:$port"
        println("ADB Device Monitor running: $url")
        openBrowser(url)
        CountDownLatch(1).await()
        return
    }

    // ---------- scan an existing/last-run logcat (no device needed) ----------
    opts["scan-logcat"]?.let { pathArg ->
        scanLogcat(pathArg)
        return
    }

    // ---------- web mode ----------
    opts["web"]?.let { portArg ->
        val port = portArg.toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("--web must be a valid port")
        val app = WebApp(port, adb)
        try {
            app.start()
        } catch (e: Exception) {
            fail("Could not start web server on port $port: ${e.message}")
        }
        Runtime.getRuntime().addShutdownHook(Thread { app.session?.finalizeOutputs() })
        println("ADB Performance Studio running: http://localhost:$port")
        println("Open it in your browser. Press Ctrl+C here to quit (a running session still writes its report).")
        CountDownLatch(1).await()
        return
    }

    // ---------- CLI mode ----------
    val durationSec = opts.require("duration").toDoubleOrNull()
        ?: fail("--duration must be a number of seconds")
    val intervalSec = opts.require("interval").toDoubleOrNull()
        ?: fail("--interval must be a number of seconds")
    if (durationSec <= 0 || intervalSec <= 0) fail("--duration and --interval must be > 0")
    if (intervalSec > durationSec) fail("--interval cannot exceed --duration")
    val zapMs = opts["zap-time"]?.let {
        it.toLongOrNull()?.takeIf { z -> z > 0 } ?: fail("--zap-time must be a positive number of milliseconds")
    }
    val zapCount = opts["zap-count"]?.let {
        it.toIntOrNull()?.takeIf { n -> n > 0 } ?: fail("--zap-count must be a positive integer")
    }
    if (zapCount != null && zapMs == null) fail("--zap-count requires --zap-time")
    val packageName = opts["package"]
    if (opts.containsKey("jank") && packageName == null) fail("--jank requires --package")
    if (opts.containsKey("profile") && packageName == null) fail("--profile requires --package")

    val target = resolveTarget(adb, opts["ip"], opts["device"])

    val config = SessionConfig(
        target = target,
        durationSec = durationSec,
        intervalSec = intervalSec,
        zapMs = zapMs,
        zapCount = zapCount,
        packageName = packageName,
        logcatEnabled = !opts.containsKey("no-logcat"),
        jankEnabled = opts.containsKey("jank"),
        profileEnabled = opts.containsKey("profile"),
        perfettoEnabled = opts.containsKey("perfetto"),
        label = opts["label"],
        playbackEnabled = opts.containsKey("playback"),
        outputDir = opts["output-dir"],
        outputOverride = opts["output"]?.let { File(it) },
        csvOverride = opts["csv"]?.let { File(it) }
    )
    val session = MonitorSession(adb, config)

    val server = opts["serve"]?.let {
        val port = it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: fail("--serve must be a valid port number")
        val app = WebApp(port, adb, session)
        try {
            app.start()
        } catch (e: Exception) {
            fail("Could not start dashboard on port $port: ${e.message}")
        }
        println("Live dashboard: http://localhost:$port/dashboard")
        app
    }

    // Generate outputs even on Ctrl+C.
    Runtime.getRuntime().addShutdownHook(Thread { session.finalizeOutputs() })

    session.runBlocking()

    if (session.status == MonitorSession.Status.FAILED) {
        System.err.println("Run failed: ${session.error}")
        if (server == null) exitProcess(1)
    }
    if (server != null) {
        println("Dashboard still available at http://localhost:${opts["serve"]}/dashboard — press Ctrl+C to exit.")
        CountDownLatch(1).await()
    }
}

/**
 * Decide which device to monitor:
 *  --ip       → adb connect over TCP/IP
 *  --device   → use that serial (must be attached and authorized)
 *  otherwise  → discover attached devices: 0 = error, 1 = auto, >1 = ask the user.
 */
private fun resolveTarget(adb: Adb, ip: String?, serial: String?): String {
    if (ip != null) {
        println("Connecting to $ip ...")
        return try {
            adb.connect(ip)
        } catch (e: Exception) {
            fail("Connection failed: ${e.message}")
        }
    }

    val all = try {
        adb.devices()
    } catch (e: Exception) {
        fail("Could not run adb: ${e.message}")
    }
    val usable = all.filter { it.state == "device" }
    val unusable = all.filterNot { it.state == "device" }

    if (serial != null) {
        usable.firstOrNull { it.serial == serial }?.let { return it.serial }
        val state = unusable.firstOrNull { it.serial == serial }?.state
        fail(
            if (state != null) "Device $serial is '$state' (check the authorization prompt on the device)"
            else "Device $serial not found. Attached: ${all.joinToString { it.serial }.ifEmpty { "none" }}"
        )
    }

    if (usable.isEmpty()) {
        val extra = unusable.joinToString("") { "\n  ${it.serial} (${it.state})" }
        fail(
            "No device connected. Please connect a device using adb (USB, or 'adb connect <ip>') to use this application." +
            if (extra.isNotEmpty()) "\nDetected but not ready:$extra" else ""
        )
    }

    if (usable.size == 1) {
        val d = usable[0]
        println("Using device: ${d.serial}  ${d.description}")
        return d.serial
    }

    println("Multiple devices connected:")
    usable.forEachIndexed { i, d -> println("  [${i + 1}] ${d.serial}  ${d.description}") }
    while (true) {
        print("Select device [1-${usable.size}]: ")
        val input = readLine() ?: fail("No selection made (stdin closed)")
        val choice = input.trim().toIntOrNull()
        if (choice != null && choice in 1..usable.size) return usable[choice - 1].serial
        println("Invalid selection, enter a number between 1 and ${usable.size}.")
    }
}

/**
 * Post-hoc analysis of an existing logcat (no device). Accepts a logcat.log path, a run folder,
 * or "last" to pick the newest run_* folder in the current directory. Prints the STB build tunnel
 * flag, actual tunnel-config events, and the mapped player-state timeline.
 */
private fun scanLogcat(arg: String) {
    val file: File = when {
        arg == "last" -> {
            val newest = File(".").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("run_") }
                ?.mapNotNull { d -> File(d, "logcat.log").takeIf { it.isFile } }
                ?.maxByOrNull { it.lastModified() }
                ?: fail("No run_*/logcat.log found in the current directory")
            newest
        }
        File(arg).isDirectory -> File(arg, "logcat.log")
        else -> File(arg)
    }
    if (!file.isFile) fail("Logcat file not found: ${file.path}")

    println("Scanning ${file.path} ...")
    val a = Playback.analyzeFile(file)

    val buildLine = when (a.buildTunnel) {
        true -> "ENABLED  (isTunneledPlaybackEnabled=true)"
        false -> "disabled (isTunneledPlaybackEnabled=false)"
        null -> "not found in logcat"
    }
    println()
    println("==== STB playback analysis ====")
    println("Tunnel enabled in build : $buildLine")
    println("Actual tunnel config    : ${a.tunneledEvents} TUNNELED, ${a.nonTunneledEvents} NON-TUNNELED MediaCodec events")
    val actual = when {
        a.tunneledEvents > 0 && a.nonTunneledEvents > 0 -> "mixed"
        a.tunneledEvents > 0 -> "tunneled"
        a.nonTunneledEvents > 0 -> "non-tunneled"
        else -> "not observed during playback"
    }
    println("Actual tunnel mode      : $actual")
    println("Final player state      : ${a.finalVideoState ?: "unknown"}")
    println("Lifecycle transitions   : ${a.stateTransitions.size}")
    if (a.stateTransitions.isNotEmpty()) {
        println()
        println("  time        lifecycle state")
        a.stateTransitions.take(60).forEach { (el, ev) ->
            val t = el?.let { "%7.1fs".format(it) } ?: "      ?"
            println("  $t   $ev")
        }
        if (a.stateTransitions.size > 60) println("  … ${a.stateTransitions.size - 60} more")
    }
}

/** Use the preferred port if free, otherwise let the OS pick any open port. */
private fun pickPort(preferred: Int): Int {
    try { ServerSocket(preferred).use { return preferred } } catch (_: Exception) {}
    ServerSocket(0).use { return it.localPort }
}

/** Open the default browser at [url]; on macOS falls back to the `open` command. */
private fun openBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url)); return
        }
    } catch (_: Exception) { /* fall through to `open` */ }
    try {
        ProcessBuilder("open", url).start()
    } catch (_: Exception) {
        System.err.println("Could not auto-open a browser. Please open $url manually.")
    }
}

// ---------- arg parsing ----------

private fun parseArgs(args: Array<String>): Map<String, String> {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(USAGE)
        exitProcess(0)
    }
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) fail("Unexpected argument: $a")
        val key = a.removePrefix("--")
        if (key in BOOL_FLAGS) {
            map[key] = "true"
            i += 1
            continue
        }
        val value = args.getOrNull(i + 1)
            ?: fail("Missing value for --$key")
        if (value.startsWith("--")) fail("Missing value for --$key")
        map[key] = value
        i += 2
    }
    val known = setOf(
        "web", "ip", "device", "duration", "interval", "zap-time", "zap-count",
        "package", "label", "serve", "output", "csv", "adb", "scan-logcat", "output-dir"
    ) + BOOL_FLAGS
    map.keys.firstOrNull { it !in known }?.let { fail("Unknown option: --$it") }
    return map
}

private fun Map<String, String>.require(key: String): String =
    this[key] ?: fail("Missing required option: --$key\n$USAGE")

private fun fail(msg: String): Nothing {
    System.err.println("Error: $msg")
    exitProcess(1)
}
