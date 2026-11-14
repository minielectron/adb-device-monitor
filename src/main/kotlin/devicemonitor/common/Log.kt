package devicemonitor.common

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Application logger. Every line is written as
 *   `2026-11-13 22:13:05.123 INFO  [thread-name] message`
 * to the main log file (`~/ADB Device Monitor/adb-monitor.log`, rotated at 5 MB) and, while a run is
 * active, to that run's own `run.log`. INFO and above are also echoed (message only) to the console.
 * DEBUG lines (adb command traces, HTTP timings) go to the files only, so the terminal stays readable.
 *
 * Legacy `println` / `System.err.println` calls are captured too: [install] replaces System.out/err
 * with line-buffering streams that route each line through this logger (stdout = INFO, stderr = WARN).
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private const val MAX_BYTES = 5L * 1024 * 1024
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    private var console: PrintStream = System.out
    private var consoleErr: PrintStream = System.err
    @Volatile private var main: FileOutputStream? = null
    @Volatile private var run: FileOutputStream? = null
    @Volatile var file: File? = null; private set
    @Volatile var runFile: File? = null; private set

    /** Open the main log in [dir] (rotating an oversized one) and capture System.out / System.err. */
    fun install(dir: File) {
        synchronized(lock) {
            if (main != null) return
            try {
                dir.mkdirs()
                val f = File(dir, "adb-monitor.log")
                if (f.length() > MAX_BYTES) {
                    val old = File(dir, "adb-monitor.log.1")
                    old.delete(); f.renameTo(old)
                }
                main = FileOutputStream(f, true)
                file = f
            } catch (e: Exception) {
                System.err.println("Could not open log file: ${e.message}")
            }
            console = System.out
            consoleErr = System.err
            System.setOut(PrintStream(LineSink(Level.INFO), true))
            System.setErr(PrintStream(LineSink(Level.WARN), true))
        }
        Thread.setDefaultUncaughtExceptionHandler { t, e -> error("Uncaught exception in thread '${t.name}'", e) }
    }

    /** Mirror all log lines into this run's own log file until [detachRun]. */
    fun attachRun(f: File) {
        synchronized(lock) {
            try { run?.close() } catch (_: Exception) {}
            run = try { FileOutputStream(f, true) } catch (_: Exception) { null }
            runFile = if (run != null) f else null
        }
    }

    fun detachRun() {
        synchronized(lock) {
            try { run?.close() } catch (_: Exception) {}
            run = null
        }
    }

    fun debug(msg: String) = write(Level.DEBUG, msg, echo = false)
    fun info(msg: String) = write(Level.INFO, msg, echo = true)
    fun warn(msg: String) = write(Level.WARN, msg, echo = true)
    fun error(msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw)); "$msg\n$sw"
        }
        write(Level.ERROR, full, echo = true)
    }

    /** All thread stacks as text — written to the log (file only) and returned for diagnostics. */
    fun threadDump(reason: String): String {
        val sb = StringBuilder("THREAD DUMP — $reason\n")
        for ((t, stack) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
            sb.append("\"${t.name}\" daemon=${t.isDaemon} state=${t.state}\n")
            for (el in stack) sb.append("    at ").append(el).append('\n')
            sb.append('\n')
        }
        val s = sb.toString()
        write(Level.WARN, s, echo = false)
        return s
    }

    /** Last [maxBytes] of a log file (for the diagnostics bundle). */
    fun tail(f: File?, maxBytes: Int): ByteArray {
        if (f == null || !f.exists()) return ByteArray(0)
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val n = minOf(len, maxBytes.toLong()).toInt()
                raf.seek(len - n)
                ByteArray(n).also { raf.readFully(it) }
            }
        } catch (_: Exception) { ByteArray(0) }
    }

    private fun write(level: Level, msg: String, echo: Boolean) {
        val prefix = "${LocalDateTime.now().format(TS)} ${level.name.padEnd(5)} [${Thread.currentThread().name}] "
        val bytes = (prefix + msg + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            try { main?.let { it.write(bytes); it.flush() } } catch (_: Exception) {}
            try { run?.let { it.write(bytes); it.flush() } } catch (_: Exception) {}
            if (echo) {
                val out = if (level >= Level.WARN) consoleErr else console
                try { out.println(msg) } catch (_: Exception) {}
            }
        }
    }

    /** OutputStream that turns captured println output into logger lines (one buffer per thread). */
    private class LineSink(private val level: Level) : OutputStream() {
        private val buf = ThreadLocal.withInitial { ByteArrayOutputStream(256) }
        override fun write(b: Int) {
            if (b == '\n'.code) emit() else buf.get().write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var start = off
            for (i in off until off + len) {
                if (b[i] == '\n'.code.toByte()) {
                    buf.get().write(b, start, i - start); emit(); start = i + 1
                }
            }
            if (start < off + len) buf.get().write(b, start, off + len - start)
        }
        private fun emit() {
            val bb = buf.get()
            val line = bb.toString("UTF-8").trimEnd('\r')
            bb.reset()
            Log.write(level, line, echo = true)
        }
    }
}
