package devicemonitor

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

    /** Run a raw adb command (no -s). */
    fun raw(vararg args: String): String = exec(listOf(adbPath) + args)

    /** Run an adb command against a specific device serial/target. */
    fun device(serial: String, vararg args: String): String =
        exec(listOf(adbPath, "-s", serial) + args)

    /** Run a shell command on the device. */
    fun shell(serial: String, command: String): String =
        device(serial, "shell", command)

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

    private fun exec(cmd: List<String>): String {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Command timed out: ${cmd.joinToString(" ")}")
        }
        return output.trim()
    }
}
