package devicemonitor.data.metrics

import devicemonitor.data.adb.Adb

/**
 * Device FPS — how many frames per second the screen is actually presenting, split into:
 *   - screenFps: the focused window's surface (the on-screen UI / composition rate)
 *   - videoFps:  the video surface (a SurfaceView / codec output layer)
 *
 * Both are computed from SurfaceFlinger's per-layer frame timestamps
 * (`dumpsys SurfaceFlinger --latency <layer>`), the standard no-root way to measure presented
 * frame rate. Layer selection is best-effort (device layer names vary); the timestamp parser is the
 * reliable, unit-tested part. Any read that yields fewer than two recent frames returns null —
 * meaning "that surface isn't updating" (idle screen / paused video), which is the correct reading.
 */
class FpsCollector(
    private val adb: Adb,
    private val target: String,
    private val packageName: String?
) {
    data class Fps(val screenFps: Double?, val videoFps: Double?)

    /** Sample screen + video FPS over roughly the last [windowSec] seconds. */
    fun sample(windowSec: Double): Fps {
        val layers = listLayers()
        if (layers.isEmpty()) return Fps(null, null)
        val videoLayer = pickVideoLayer(layers)
        val screenLayer = pickScreenLayer(layers, videoLayer)
        val screenFps = screenLayer?.let { fpsOfLayer(it, windowSec) }
        val videoFps = when {
            videoLayer == null -> null
            videoLayer == screenLayer -> screenFps
            else -> fpsOfLayer(videoLayer, windowSec)
        }
        return Fps(screenFps, videoFps)
    }

    // Short timeout: FPS is optional; a slow/absent SurfaceFlinger must not stall the loop.
    private fun sh(cmd: String): String = try { adb.shell(target, cmd, 8) } catch (e: Exception) { "" }

    private fun listLayers(): List<String> =
        sh("dumpsys SurfaceFlinger --list").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Video is normally a SurfaceView / codec output layer; prefer one tied to the tracked app. */
    private fun pickVideoLayer(layers: List<String>): String? {
        packageName?.let { pkg ->
            layers.firstOrNull { it.contains("SurfaceView", true) && it.contains(pkg) }?.let { return it }
        }
        for (hint in listOf("SurfaceView", "MediaCodec", "VideoDecoder", "Video ")) {
            layers.firstOrNull { it.contains(hint, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** Screen = the focused window's layer; fall back to the app layer, the video layer, or the top layer. */
    private fun pickScreenLayer(layers: List<String>, videoLayer: String?): String? {
        focusedWindow()?.let { fw -> layers.firstOrNull { it.contains(fw) }?.let { return it } }
        packageName?.let { pkg -> layers.firstOrNull { it.contains(pkg) }?.let { return it } }
        return videoLayer ?: layers.lastOrNull()   // SurfaceFlinger lists roughly back-to-front
    }

    /** Focused window title from `dumpsys window` (piped grep keeps the output small). */
    private fun focusedWindow(): String? {
        val out = sh("dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedWindow'")
        val m = Regex("mCurrentFocus=Window\\{\\S+ \\S+ ([^}]+)\\}").find(out)
            ?: Regex("mFocusedWindow=Window\\{\\S+ \\S+ ([^}]+)\\}").find(out)
        return m?.groupValues?.getOrNull(1)?.trim()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun fpsOfLayer(layer: String, windowSec: Double): Double? =
        fpsFromLatency(sh("dumpsys SurfaceFlinger --latency \"$layer\""), windowSec)

    companion object {
        /**
         * Compute presented FPS from `dumpsys SurfaceFlinger --latency` output.
         * Format: line 1 = refresh period (ns); each following line =
         * `desiredPresent actualPresent frameReady` (ns). actualPresent of 0 or INT64_MAX is a
         * pending/invalid frame. FPS is measured over the frames presented in the last [windowSec].
         */
        fun fpsFromLatency(raw: String, windowSec: Double): Double? {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 3) return null
            val present = ArrayList<Long>(128)
            for (i in 1 until lines.size) {                 // skip line 0 (refresh period)
                val cols = lines[i].split(Regex("\\s+"))
                if (cols.size < 3) continue
                val actual = cols[1].toLongOrNull() ?: continue
                if (actual <= 0L || actual == Long.MAX_VALUE) continue
                present.add(actual)
            }
            if (present.size < 2) return null
            present.sort()
            val cutoff = present.last() - (windowSec * 1e9).toLong()
            val recent = present.filter { it >= cutoff }
            val use = if (recent.size >= 2) recent else present
            val spanNs = use.last() - use.first()
            if (spanNs <= 0) return null
            val fps = (use.size - 1) * 1e9 / spanNs
            return if (fps.isFinite() && fps in 0.0..1000.0) fps else null
        }
    }
}
