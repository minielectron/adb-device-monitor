package devicemonitor.data.logcat

import devicemonitor.domain.*
import java.io.File

object TraceParser {

    const val SLOW_FRAME_MS = 16.7

    /** Main-thread idle/parking — waiting, not working. */
    private val IDLE_SUFFIXES = listOf(
        "MessageQueue.nativePollOnce", "Object.wait", "Unsafe.park", "Thread.parkFor"
    )

    private data class Interval(val method: Long, val start: Long, val end: Long)
    private class Entry(val mid: Long, val start: Long, var childUs: Long = 0)

    fun analyze(file: File, packageName: String?, limit: Int = 15): TraceAnalysis? = try {
        parse(file.readBytes(), packageName, limit)
    } catch (e: Exception) {
        null
    }

    fun writeReport(analysis: TraceAnalysis?, packageName: String?, file: File) {
        file.bufferedWriter().use { w ->
            if (analysis == null || analysis.topMethods.isEmpty()) {
                w.appendLine("No main-thread method data (trace missing, app not debuggable, or parse failed).")
                w.appendLine("The raw method-profile.trace (if present) can be opened in Android Studio or ui.perfetto.dev.")
                return
            }
            w.appendLine(
                "Main thread: %.0f ms busy of %.0f ms traced (rest = idle waiting for messages)"
                    .format(analysis.busyMs, analysis.spanMs)
            )
            w.appendLine()
            w.appendLine("== Hot methods by self time (idle waits excluded from %busy) ==")
            w.appendLine()
            w.appendLine("%-10s %-10s %-7s %-7s %s".format("self(ms)", "incl(ms)", "%busy", "calls", "method"))
            for (s in analysis.topMethods) {
                w.appendLine("%-10.1f %-10.1f %-7.1f %-7d %s".format(s.selfMs, s.inclusiveMs, s.pctBusy, s.calls, s.method))
            }
            w.appendLine()
            w.appendLine("== Methods from ${packageName ?: "<no package>"} (by inclusive time) ==")
            w.appendLine()
            if (analysis.appMethods.isEmpty()) {
                w.appendLine("None observed on the main thread. If unexpected: is $packageName correct, and was the app in the foreground?")
            } else {
                w.appendLine("%-10s %-10s %-7s %-7s %s".format("self(ms)", "incl(ms)", "%busy", "calls", "method"))
                for (s in analysis.appMethods) {
                    w.appendLine("%-10.1f %-10.1f %-7.1f %-7d %s".format(s.selfMs, s.inclusiveMs, s.pctBusy, s.calls, s.method))
                }
            }
            w.appendLine()
            w.appendLine(
                "== Jank attribution: ${analysis.slowFrames}/${analysis.totalFrames} frames slower than " +
                "${analysis.slowThresholdMs} ms (Choreographer.doFrame) =="
            )
            w.appendLine()
            when {
                analysis.totalFrames == 0 ->
                    w.appendLine("No Choreographer.doFrame slices found in the trace (UI idle, or sampling missed them).")
                packageName == null ->
                    w.appendLine("No package configured — cannot attribute jank to app methods.")
                analysis.jankMethods.isEmpty() ->
                    w.appendLine("No $packageName methods observed inside slow frames.")
                else -> {
                    w.appendLine("$packageName methods running inside slow frames:")
                    w.appendLine()
                    w.appendLine("%-12s %-10s %-8s %s".format("ms-in-slow", "%slowtime", "frames", "method"))
                    for (j in analysis.jankMethods) {
                        w.appendLine("%-12.1f %-10.1f %-8d %s".format(j.msInSlowFrames, j.pctOfSlowTime, j.framesTouched, j.method))
                    }
                }
            }
        }
    }

    // ---------- internals ----------

    private fun parse(bytes: ByteArray, packageName: String?, limit: Int): TraceAnalysis? {
        val marker = "*end\n".toByteArray()
        val markerIdx = indexOf(bytes, marker)
        if (markerIdx < 0) return null
        val text = String(bytes, 0, markerIdx, Charsets.UTF_8)
        val binStart = markerIdx + marker.size

        var mainTid = -1
        val methods = HashMap<Long, String>()
        var section = ""
        for (line in text.lines()) {
            if (line.startsWith("*")) {
                section = line.trim()
                continue
            }
            val parts = line.split('\t')
            when (section) {
                "*threads" ->
                    if (parts.size >= 2 && parts[1].trim() == "main") {
                        mainTid = parts[0].trim().toIntOrNull() ?: -1
                    }
                "*methods" ->
                    if (parts.size >= 3) {
                        val id = parts[0].trim().removePrefix("0x").toLongOrNull(16)
                        if (id != null) methods[id] = prettyName(parts[1].trim(), parts[2].trim())
                    }
            }
        }
        if (mainTid < 0) return null

        val doFrameIds = methods.filterValues { it.endsWith("Choreographer.doFrame") }.keys
        val pkgPrefix = packageName?.plus(".")
        val pkgIds = if (pkgPrefix != null) methods.filterValues { it.startsWith(pkgPrefix) }.keys else emptySet()
        val idleIds = methods.filterValues { v -> IDLE_SUFFIXES.any { v.endsWith(it) } }.keys

        // Binary header: magic u32, version u16, headerOffset u16, startTime u64 [, recordSize u16 (v3)]
        if (binStart + 16 > bytes.size) return null
        if (u32(bytes, binStart) != 0x574F4C53L) return null   // 'SLOW'
        val version = u16(bytes, binStart + 4)
        val headerOffset = u16(bytes, binStart + 6)
        val dual = text.contains("clock=dual")
        val recordSize = when (version) {
            1 -> 9
            2 -> 10
            3 -> u16(bytes, binStart + 16)
            else -> return null
        }
        if (recordSize < 9) return null

        val selfUs = HashMap<Long, Long>()
        val inclusive = HashMap<Long, Long>()
        val calls = HashMap<Long, Int>()
        val onStack = HashMap<Long, Int>()
        val stack = ArrayDeque<Entry>()
        val frameIntervals = mutableListOf<Interval>()
        val pkgIntervals = mutableListOf<Interval>()
        var minT = Long.MAX_VALUE
        var maxT = 0L

        fun close(e: Entry, now: Long) {
            val dur = (now - e.start).coerceAtLeast(0)
            selfUs[e.mid] = (selfUs[e.mid] ?: 0L) + (dur - e.childUs).coerceAtLeast(0)
            stack.lastOrNull()?.let { it.childUs += dur }
            val left = (onStack[e.mid] ?: 1) - 1
            onStack[e.mid] = left
            if (left == 0) {
                inclusive[e.mid] = (inclusive[e.mid] ?: 0L) + dur
                if (e.mid in doFrameIds) frameIntervals += Interval(e.mid, e.start, now)
                if (e.mid in pkgIds) pkgIntervals += Interval(e.mid, e.start, now)
            }
        }

        var pos = binStart + headerOffset
        while (pos + recordSize <= bytes.size) {
            val tid = if (version == 1) (bytes[pos].toInt() and 0xff) else u16(bytes, pos)
            val base = pos + if (version == 1) 1 else 2
            val methodAndAction = u32(bytes, base)
            val t1 = u32(bytes, base + 4)
            val time = if (dual && recordSize >= (base - pos) + 12) u32(bytes, base + 8) else t1
            pos += recordSize
            if (tid != mainTid) continue

            val action = (methodAndAction and 0x3).toInt()
            val mid = methodAndAction and 0x3L.inv()
            if (time < minT) minT = time
            if (time > maxT) maxT = time
            when (action) {
                0 -> {
                    stack.addLast(Entry(mid, time))
                    onStack[mid] = (onStack[mid] ?: 0) + 1
                    calls[mid] = (calls[mid] ?: 0) + 1
                }
                1, 2 -> {
                    while (stack.isNotEmpty()) {
                        val e = stack.removeLast()
                        close(e, time)
                        if (e.mid == mid) break
                    }
                }
            }
        }
        while (stack.isNotEmpty()) close(stack.removeLast(), maxT)
        if (inclusive.isEmpty() || maxT <= minT) return null

        val spanUs = (maxT - minT).toDouble()
        val idleUs = idleIds.sumOf { selfUs[it] ?: 0L }.toDouble()
        val busyUs = (spanUs - idleUs).coerceAtLeast(1.0)

        fun stat(mid: Long): MethodStat = MethodStat(
            method = methods[mid] ?: "0x${mid.toString(16)}",
            selfMs = (selfUs[mid] ?: 0L) / 1000.0,
            inclusiveMs = (inclusive[mid] ?: selfUs[mid] ?: 0L) / 1000.0,
            calls = calls[mid] ?: 0,
            pctBusy = 100.0 * (selfUs[mid] ?: 0L) / busyUs
        )

        // 1) hottest by self time, idle waits excluded
        val top = selfUs.entries
            .filter { it.key !in idleIds && it.value > 0 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { stat(it.key) }

        // 2) app package methods by inclusive time
        val app = inclusive.entries
            .filter { it.key in pkgIds }
            .sortedByDescending { it.value }
            .take(limit)
            .map { stat(it.key).copy(pctBusy = 100.0 * (inclusive[it.key] ?: 0L) / busyUs) }

        // 3) jank attribution: package methods overlapping slow doFrame windows
        val slowUs = (SLOW_FRAME_MS * 1000).toLong()
        val slowFrames = frameIntervals.filter { it.end - it.start > slowUs }
        val slowTotalUs = slowFrames.sumOf { it.end - it.start }.toDouble()
        val overlapUs = HashMap<Long, Long>()
        val framesTouched = HashMap<Long, MutableSet<Int>>()
        for (p in pkgIntervals) {
            slowFrames.forEachIndexed { fi, f ->
                val o = minOf(p.end, f.end) - maxOf(p.start, f.start)
                if (o > 0) {
                    overlapUs[p.method] = (overlapUs[p.method] ?: 0L) + o
                    framesTouched.getOrPut(p.method) { mutableSetOf() } += fi
                }
            }
        }
        val jank = overlapUs.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (mid, us) ->
                JankMethodStat(
                    method = methods[mid] ?: "0x${mid.toString(16)}",
                    msInSlowFrames = us / 1000.0,
                    framesTouched = framesTouched[mid]?.size ?: 0,
                    pctOfSlowTime = if (slowTotalUs > 0) 100.0 * us / slowTotalUs else 0.0
                )
            }

        return TraceAnalysis(
            topMethods = top,
            appMethods = app,
            jankMethods = jank,
            totalFrames = frameIntervals.size,
            slowFrames = slowFrames.size,
            slowThresholdMs = SLOW_FRAME_MS,
            busyMs = busyUs / 1000.0,
            spanMs = spanUs / 1000.0
        )
    }

    /** "Lcom/foo/Bar;" + "doWork" → "com.foo.Bar.doWork" */
    private fun prettyName(clazz: String, method: String): String =
        clazz.removePrefix("L").removeSuffix(";").replace('/', '.') + "." + method

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, i: Int): Long =
        u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
