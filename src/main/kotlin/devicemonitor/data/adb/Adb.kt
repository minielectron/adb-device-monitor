package devicemonitor.data.adb

import devicemonitor.common.Log
import java.io.File
import java.util.concurrent.TimeUnit

data class AdbDevice(val serial: String, val state: String, val description: String)

/** Thin wrapper around the adb binary. */
class Adb(private val adbPath: String) {

    companion object {
        /**
         * Resolve a usable `adb` path. A GUI app launched from Finder inherits a minimal PATH
         * (often just /usr/bin:/bin:/usr/sbin:/sbin), so a bare "adb" won't be found even when
         * it's installed. We therefore check the explicit override, the Android SDK env vars,
         * the standard macOS SDK location, Homebrew prefixes, and finally each PATH entry.
         */
        fun locate(explicit: String?): String {
            val home = System.getProperty("user.home")
            val candidates = buildList {
                explicit?.takeIf { it.isNotBlank() }?.let { add(it) }
                System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
                System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
                add("$home/Library/Android/sdk/platform-tools/adb")   // Android Studio default (macOS)
                add("$home/Android/Sdk/platform-tools/adb")            // Linux default
                add("/opt/homebrew/bin/adb")                           // Homebrew (Apple silicon)
                add("/usr/local/bin/adb")                              // Homebrew (Intel)
                add("/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
            }
            for (c in candidates) {
                val f = File(c)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
            // Fall back to scanning PATH explicitly.
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                if (dir.isNotBlank()) {
                    val f = File(dir, "adb")
                    if (f.isFile && f.canExecute()) return f.absolutePath
                }
            }
            return explicit?.takeIf { it.isNotBlank() } ?: "adb"  // last resort — fails with a clear error
        }
    }

    // Every running adb child process is registered here so a Stop can force-kill all of them at
    // once (abortInflight), instantly unblocking any thread parked in exec()'s waitFor.
    private val inflight = java.util.concurrent.ConcurrentHashMap<Process, Thread>()   // process → owner thread

    // Abort gate, scoped to the *run's own threads*. While a thread is in this set, exec() on it
    // returns immediately without launching adb. This makes Stop near-instant: the remaining adb
    // calls in the in-progress sampling tick (pss, gfxinfo, top-CPU, media…) short-circuit instead of
    // each running to completion / its timeout. It is per-thread on purpose — a global gate would
    // also blank the device list and break snapshots requested from the UI while a run is stopping.
    private val abortedThreads: MutableSet<Thread> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Force-kill in-flight adb commands (all of them, or only those owned by [owners]). */
    fun abortInflight(owners: Collection<Thread>? = null) {
        val procs = inflight.entries.filter { owners == null || it.value in owners }.map { it.key }
        if (procs.isEmpty()) return
        Log.warn("[adb] Stop: aborting ${procs.size} in-flight command(s)")
        for (p in procs) { try { p.destroyForcibly() } catch (_: Exception) {} }
    }

    /** Enter abort mode for [threads]: kill their in-flight commands and make their new exec calls return instantly. */
    fun beginAbort(threads: Collection<Thread>) { abortedThreads.addAll(threads); abortInflight(threads) }

    /** Leave abort mode so normal adb calls (e.g. finalize's trace pulls) work again. */
    fun endAbort() { abortedThreads.clear() }

    /** Run a raw adb command (no -s). */
    fun raw(vararg args: String): String = exec(listOf(adbPath) + args)

    /** Run an adb command against a specific device serial/target. */
    fun device(serial: String, vararg args: String): String =
        exec(listOf(adbPath, "-s", serial) + args)

    /** Run a shell command on the device (default 20 s timeout). */
    fun shell(serial: String, command: String): String =
        device(serial, "shell", command)

    /** Run a shell command with an explicit timeout (seconds) — for potentially slow reads. */
    fun shell(serial: String, command: String, timeoutSec: Long): String =
        exec(listOf(adbPath, "-s", serial, "shell", command), timeoutSec)

    /** List devices known to adb (USB and TCP/IP), parsed from `adb devices -l`. */
    fun devices(): List<AdbDevice> =
        raw("devices", "-l").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("List of devices") || it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val desc = parts.drop(2)
                    .filter { it.startsWith("model:") || it.startsWith("product:") || it.startsWith("transport_id:") }
                    .joinToString("  ")
                AdbDevice(serial = parts[0], state = parts[1], description = desc)
            }

    /**
     * Connect to a device over TCP/IP. Returns the resolved target ("host:port").
     * Throws if the connection fails.
     */
    fun connect(ip: String): String {
        val target = if (ip.contains(":")) ip else "$ip:5555"
        val out = raw("connect", target)
        if (!out.contains("connected", ignoreCase = true)) {
            throw IllegalStateException("adb connect failed for $target: $out")
        }
        // Sanity check: device must respond to a trivial shell command.
        val echo = shell(target, "echo ok").trim()
        if (echo != "ok") {
            throw IllegalStateException("Device $target connected but not responding (got: '$echo')")
        }
        return target
    }

    /** adb pull a file from the device. */
    fun pull(serial: String, remote: String, local: String): String =
        device(serial, "pull", remote, local)

    /** Clears the log buffer (best effort) and starts streaming logcat into [outFile]. */
    fun startLogcat(serial: String, outFile: java.io.File): Process {
        try { device(serial, "logcat", "-c") } catch (_: Exception) { /* some buffers refuse -c */ }
        return ProcessBuilder(adbPath, "-s", serial, "logcat", "-v", "threadtime")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(outFile))
            .start()
    }

    private fun exec(cmd: List<String>, timeoutSec: Long = 20): String {
        val me = Thread.currentThread()
        val label = cmd.drop(1).joinToString(" ").replace('\n', ' ').take(160)
        if (me in abortedThreads) { Log.debug("[adb] skipped (run stopping): $label"); return "" }
        val t0 = System.nanoTime()
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        inflight[process] = me
        try {
            // Read stdout on a separate thread (per-call holder — exec runs concurrently). If a
            // command hangs with its stdout still open (e.g. `su` waiting for approval), a plain
            // readText() would block forever *before* any timeout — so we bound the read with
            // waitFor and forcibly kill the process if it overruns (or Stop calls abortInflight).
            val out = java.util.concurrent.atomic.AtomicReference("")
            val reader = Thread {
                try { out.set(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                reader.join(1000)
                Log.warn("[adb] TIMEOUT after ${timeoutSec}s: $label")
                throw IllegalStateException("Command timed out (${timeoutSec}s): ${cmd.joinToString(" ")}")
            }
            reader.join(2000)   // process exited; let the reader drain remaining buffered output
            val ms = (System.nanoTime() - t0) / 1_000_000
            val exit = try { process.exitValue() } catch (_: Exception) { -1 }
            val line = "[adb] ${ms}ms exit=$exit out=${out.get().length}B: $label"
            if (ms > 3000) Log.warn(line) else Log.debug(line)
            return out.get().trim()
        } finally {
            inflight.remove(process)
        }
    }
}
