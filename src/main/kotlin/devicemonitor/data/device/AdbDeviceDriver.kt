package devicemonitor.data.device

import devicemonitor.data.adb.Adb
import devicemonitor.domain.releaseguard.*
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.roundToInt

class AdbDeviceDriver(
    private val adb: Adb,
    override val serial: String,
    private val probe: CapabilityProbe = CapabilityProbe(adb)
) : DeviceDriver {
    override fun target(refresh: Boolean): TargetDevice = probe.probe(serial, refresh)

    override fun isConnected(): Boolean = try {
        adb.devices().any { it.serial == serial && it.state == "device" }
    } catch (_: Exception) {
        false
    }

    override fun perform(
        step: ScenarioStep,
        packageName: String,
        artifactDirectory: File,
        cancelled: () -> Boolean
    ): StepOutput {
        validatePackage(packageName)
        if (cancelled()) throw InterruptedException("Release Guard run cancelled")
        return when (step) {
            is ScenarioStep.InstallApk -> {
                val apk = File(step.path)
                require(apk.isFile) { "APK does not exist: ${apk.absolutePath}" }
                val output = adb.device(serial, "install", "-r", apk.absolutePath)
                require(output.contains("success", ignoreCase = true)) { "APK install failed: $output" }
                StepOutput("APK installed")
            }
            is ScenarioStep.UninstallPackage -> {
                val output = adb.device(serial, "uninstall", packageName)
                require(output.contains("success", ignoreCase = true) || output.contains("not installed", ignoreCase = true)) {
                    "Package uninstall failed: $output"
                }
                StepOutput("Package uninstalled")
            }
            is ScenarioStep.ClearAppData -> checkedShell("pm clear ${shellQuote(packageName)}", "App data cleared")
            is ScenarioStep.LaunchApp -> checkedShell(
                "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1",
                "Application launched"
            )
            is ScenarioStep.ForceStopApp -> checkedShell("am force-stop ${shellQuote(packageName)}", "Application stopped")
            is ScenarioStep.GrantPermission -> {
                require(SAFE_PERMISSION.matches(step.permission)) { "Invalid Android permission '${step.permission}'" }
                checkedShell("pm grant ${shellQuote(packageName)} ${shellQuote(step.permission)}", "Permission granted")
            }
            is ScenarioStep.Wait -> {
                waitDuration(step.durationMs, cancelled)
                StepOutput("Waited ${step.durationMs} ms")
            }
            is ScenarioStep.BeginSection -> StepOutput("Section '${step.section}' started")
            is ScenarioStep.EndSection -> StepOutput("Section '${step.section}' ended")
            is ScenarioStep.PressKey -> checkedShell("input keyevent ${KEY_CODES.getValue(step.key)}", "Key ${step.key} pressed")
            is ScenarioStep.TapElement -> {
                val bounds = findBounds(step.selector)
                if (bounds != null) tap(bounds.centerX, bounds.centerY)
                else if (step.selector.fallbackXPct != null && step.selector.fallbackYPct != null) {
                    tapPercent(step.selector.fallbackXPct, step.selector.fallbackYPct)
                } else error("UI element was not found")
                StepOutput("Element tapped")
            }
            is ScenarioStep.TapAt -> {
                tapPercent(step.xPct, step.yPct)
                StepOutput("Screen tapped")
            }
            is ScenarioStep.Swipe -> {
                val size = displaySize()
                val fromX = pixel(step.fromXPct, size.first)
                val fromY = pixel(step.fromYPct, size.second)
                val toX = pixel(step.toXPct, size.first)
                val toY = pixel(step.toYPct, size.second)
                checkedShell("input swipe $fromX $fromY $toX $toY ${step.durationMs}", "Screen swiped")
            }
            is ScenarioStep.EnterText -> checkedShell("input text ${shellQuote(step.text.replace(" ", "%s"))}", "Text entered")
            is ScenarioStep.WaitForElement -> {
                waitUntil(step.timeoutMs, cancelled) { findBounds(step.selector) != null }
                StepOutput("Element appeared")
            }
            is ScenarioStep.WaitForActivity -> {
                waitUntil(step.timeoutMs, cancelled) {
                    adb.shell(serial, "dumpsys activity activities | head -80", 5).contains(step.activity, ignoreCase = true)
                }
                StepOutput("Activity appeared")
            }
            is ScenarioStep.WaitForLog -> {
                val regex = try { Regex(step.pattern) } catch (e: Exception) { error("Invalid log pattern: ${e.message}") }
                waitUntil(step.timeoutMs, cancelled) {
                    regex.containsMatchIn(adb.shell(serial, "logcat -d -t 2000", 5))
                }
                StepOutput("Log pattern appeared")
            }
            is ScenarioStep.WaitForProcess -> {
                waitUntil(step.timeoutMs, cancelled) {
                    adb.shell(serial, "pidof ${shellQuote(packageName)}", 5).trim().matches(Regex("[0-9 ]+"))
                }
                StepOutput("Process is running")
            }
            is ScenarioStep.CaptureScreenshot -> {
                require(File(step.fileName).name == step.fileName && step.fileName !in setOf(".", "..")) {
                    "Screenshot name must not contain a path"
                }
                val artifactRoot = artifactDirectory.toPath().toAbsolutePath().normalize()
                Files.createDirectories(artifactRoot)
                val target = artifactRoot.resolve(step.fileName).normalize()
                require(target.startsWith(artifactRoot)) { "Screenshot path escapes artifact directory" }
                val remote = "/data/local/tmp/release_guard_${System.nanoTime()}.png"
                try {
                    checkedShell("screencap -p ${shellQuote(remote)}", "Screenshot captured")
                    adb.pull(serial, remote, target.toString())
                    require(Files.isRegularFile(target) && Files.size(target) > 0) { "Screenshot pull produced no file" }
                } finally {
                    try { adb.shell(serial, "rm -f ${shellQuote(remote)}", 5) } catch (_: Exception) {}
                }
                StepOutput("Screenshot captured", target.toFile())
            }
            is ScenarioStep.Shell -> {
                val output = adb.shell(serial, step.command, seconds(step.timeoutMs))
                StepOutput(output.ifBlank { "Command completed" })
            }
        }
    }

    private fun checkedShell(command: String, success: String): StepOutput {
        val output = adb.shell(serial, command)
        require(!looksLikeShellFailure(output)) { "$success failed: $output" }
        return StepOutput(success)
    }

    private fun tapPercent(x: Double, y: Double) {
        require(x in 0.0..1.0 && y in 0.0..1.0) { "Tap coordinates must be between 0 and 1" }
        val size = displaySize()
        tap(pixel(x, size.first), pixel(y, size.second))
    }

    private fun tap(x: Int, y: Int) {
        checkedShell("input tap $x $y", "Tap")
    }

    private fun displaySize(): Pair<Int, Int> = parseDisplaySize(adb.shell(serial, "wm size", 5))
        ?: error("Could not determine device display size")

    private fun findBounds(selector: UiSelector): Bounds? {
        val xml = adb.shell(
            serial,
            "uiautomator dump /data/local/tmp/release_guard_window.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/release_guard_window.xml; rm -f /data/local/tmp/release_guard_window.xml",
            10
        )
        return findBounds(xml, selector)
    }

    private fun waitUntil(timeoutMs: Long, cancelled: () -> Boolean, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        do {
            if (cancelled()) throw InterruptedException("Release Guard run cancelled")
            if (condition()) return
            Thread.sleep(minOf(250, maxOf(1, timeoutMs)))
        } while (System.nanoTime() < deadline)
        throw IllegalStateException("Timed out after $timeoutMs ms")
    }

    private fun waitDuration(durationMs: Long, cancelled: () -> Boolean) {
        val deadline = System.nanoTime() + durationMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cancelled()) throw InterruptedException("Release Guard run cancelled")
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            Thread.sleep(minOf(250, remainingMs))
        }
    }

    companion object {
        private val SAFE_PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val SAFE_PERMISSION = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val BOUNDS = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
        private val SIZE = Regex("(\\d+)x(\\d+)")
        private val KEY_CODES = mapOf(
            LogicalKey.BACK to 4,
            LogicalKey.HOME to 3,
            LogicalKey.ENTER to 66,
            LogicalKey.DPAD_UP to 19,
            LogicalKey.DPAD_DOWN to 20,
            LogicalKey.DPAD_LEFT to 21,
            LogicalKey.DPAD_RIGHT to 22,
            LogicalKey.DPAD_CENTER to 23,
            LogicalKey.CHANNEL_UP to 166,
            LogicalKey.CHANNEL_DOWN to 167,
            LogicalKey.MEDIA_PLAY_PAUSE to 85
        )

        internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
            val centerX: Int get() = (left + right) / 2
            val centerY: Int get() = (top + bottom) / 2
        }

        internal fun parseDisplaySize(raw: String): Pair<Int, Int>? {
            val lines = raw.lines().map { it.trim() }
            val preferred = lines.firstOrNull { it.startsWith("Override size:") }
                ?: lines.firstOrNull { it.startsWith("Physical size:") }
                ?: lines.firstOrNull { SIZE.containsMatchIn(it) }
                ?: return null
            val match = SIZE.find(preferred) ?: return null
            return match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }

        internal fun findBounds(xml: String, selector: UiSelector): Bounds? {
            if (xml.isBlank()) return null
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val nodes = document.getElementsByTagName("node")
            val elements = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            val matches = when {
                !selector.resourceId.isNullOrBlank() -> elements.filter {
                    val value = it.getAttribute("resource-id")
                    value == selector.resourceId || value.endsWith(":id/${selector.resourceId}") || value.endsWith("/${selector.resourceId}")
                }
                !selector.contentDescription.isNullOrBlank() -> elements.filter {
                    it.getAttribute("content-desc") == selector.contentDescription
                }
                !selector.text.isNullOrBlank() -> elements.filter { it.getAttribute("text") == selector.text }
                else -> emptyList()
            }
            require(matches.size <= 1) { "UI selector matched ${matches.size} elements" }
            return matches.singleOrNull()?.getAttribute("bounds")?.let(::parseBounds)
        }

        private fun parseBounds(raw: String): Bounds? {
            val match = BOUNDS.matchEntire(raw) ?: return null
            return Bounds(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt()
            )
        }

        private fun pixel(percent: Double, size: Int): Int = (percent * (size - 1)).roundToInt().coerceIn(0, size - 1)
        private fun seconds(milliseconds: Long): Long = ceil(milliseconds / 1000.0).toLong().coerceAtLeast(1)
        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        private fun validatePackage(value: String) {
            require(SAFE_PACKAGE.matches(value)) { "Invalid Android package '$value'" }
        }
        private fun looksLikeShellFailure(output: String): Boolean {
            val lower = output.lowercase()
            return "permission denied" in lower || "not found" in lower || "unknown command" in lower ||
                "error:" in lower || "failed" in lower || "failure" in lower
        }
    }
}
