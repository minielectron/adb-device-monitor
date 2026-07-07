package devicemonitor

import java.io.File
import java.io.RandomAccessFile

/**
 * Counts occurrences of a pattern in a growing log file, per tick.
 *
 * The captured logcat is a local file that only grows during a run, so instead of re-scanning
 * the whole file (or a fixed tail that can miss or double-count events) we remember the byte
 * offset we last read to and only scan the bytes appended since. That gives an exact "new events
 * since the previous tick" count, which divided by the tick duration is an events/second rate.
 */
class GrowTailCounter(private val pattern: Regex) {
    private var offset = 0L

    /** Number of new pattern matches appended since the last call. */
    fun countNew(file: File): Int {
        if (!file.isFile) return 0
        val len = file.length()
        if (len < offset) offset = 0L          // file rotated/truncated → restart
        if (len <= offset) return 0
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray((len - offset).toInt())
                raf.readFully(buf)
                offset = len
                pattern.findAll(String(buf, Charsets.UTF_8)).count()
            }
        } catch (e: Exception) {
            0
        }
    }
}

object LogMetrics {
    /**
     * Kernel memory "direct reclaim" — when an allocation can't be satisfied from free pages so
     * the allocating thread must synchronously reclaim memory itself (stalling it). A rising rate
     * is a strong sign of memory pressure. Matches the usual spellings surfaced in logcat.
     */
    val DIRECT_RECLAIM = Regex("(?i)direct[ _-]?reclaim|allocstall")
}
