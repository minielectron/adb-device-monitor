package devicemonitor.data.device

import devicemonitor.data.adb.Adb
import devicemonitor.domain.releaseguard.*
import java.util.concurrent.ConcurrentHashMap

class CapabilityProbe(private val adb: Adb) {
    private data class Cached(val at: Long, val target: TargetDevice, val complete: Boolean)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun probe(serial: String, refresh: Boolean = false): TargetDevice {
        val now = System.currentTimeMillis()
        cache[serial]?.let { cached ->
            val ttl = if (cached.complete) 30_000 else 5_000
            if (!refresh && now - cached.at < ttl) return cached.target
        }
        val target = probeNow(serial)
        cache[serial] = Cached(now, target, DeviceCapability.SHELL in target.capabilities)
        return target
    }

    fun invalidate(serial: String) {
        cache.remove(serial)
    }

    private fun probeNow(serial: String): TargetDevice {
        val properties = parseProperties(safeShell(serial, PROPERTIES))
        val capabilities = mutableSetOf<DeviceCapability>()
        val shell = safeShell(serial, "echo release_guard_ready").lineSequence().any { it.trim() == "release_guard_ready" }
        if (shell) {
            capabilities += DeviceCapability.SHELL
            val tools = safeShell(serial, TOOLS)
            if (toolAvailable(tools, "package")) capabilities += DeviceCapability.PACKAGE_INSTALL
            if (toolAvailable(tools, "uiautomator")) capabilities += DeviceCapability.UI_HIERARCHY
            if (toolAvailable(tools, "input")) {
                capabilities += DeviceCapability.TOUCH_INPUT
                capabilities += DeviceCapability.DPAD_INPUT
            }
            if (toolAvailable(tools, "perfetto")) capabilities += DeviceCapability.PERFETTO
            if (toolAvailable(tools, "procrank")) capabilities += DeviceCapability.PROCRANK
            if (toolAvailable(tools, "screencap")) capabilities += DeviceCapability.SCREEN_CAPTURE
            if (toolAvailable(tools, "logcat")) capabilities += DeviceCapability.LOGCAT

            val signals = safeShell(serial, SIGNALS)
            if (signalAvailable(signals, "gfxinfo")) capabilities += DeviceCapability.GFXINFO
            if (signalAvailable(signals, "psi")) capabilities += DeviceCapability.PSI
            if (signalAvailable(signals, "thermal")) capabilities += DeviceCapability.THERMAL
            if (signalAvailable(signals, "battery")) capabilities += DeviceCapability.BATTERY_STATS
            if (signalAvailable(signals, "playback")) capabilities += DeviceCapability.PLAYBACK_DIAGNOSTICS
            if (signalAvailable(signals, "codec")) capabilities += DeviceCapability.MEDIA_CODEC_DIAGNOSTICS
            if (signalAvailable(signals, "audio")) capabilities += DeviceCapability.AUDIO_DIAGNOSTICS
            if (signalAvailable(signals, "surface")) capabilities += DeviceCapability.SURFACE_DIAGNOSTICS
            if (signalAvailable(signals, "root")) capabilities += DeviceCapability.ROOT
        }
        return TargetDevice(
            id = serial,
            transport = transport(serial),
            formFactor = formFactor(properties),
            properties = properties,
            capabilities = capabilities
        )
    }

    private fun safeShell(serial: String, command: String): String = try {
        adb.shell(serial, command, 5)
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val PROPERTIES =
            "echo model=$(getprop ro.product.model); echo android=$(getprop ro.build.version.release); " +
                "echo firmware=$(getprop ro.build.fingerprint); echo characteristics=$(getprop ro.build.characteristics)"
        private const val TOOLS =
            "for t in cmd uiautomator input perfetto procrank screencap logcat; do " +
                "command -v \$t >/dev/null 2>&1 && echo \$t=1 || echo \$t=0; done; " +
                "cmd package help >/dev/null 2>&1 && echo package=1 || echo package=0"
        private const val SIGNALS =
            "dumpsys gfxinfo >/dev/null 2>&1 && echo gfxinfo=1 || echo gfxinfo=0; " +
                "cat /proc/pressure/cpu >/dev/null 2>&1 && echo psi=1 || echo psi=0; " +
                "ls /sys/class/thermal/thermal_zone* >/dev/null 2>&1 && echo thermal=1 || echo thermal=0; " +
                "dumpsys battery >/dev/null 2>&1 && echo battery=1 || echo battery=0; " +
                "dumpsys media.player >/dev/null 2>&1 && echo playback=1 || echo playback=0; " +
                "dumpsys media.codec >/dev/null 2>&1 && echo codec=1 || echo codec=0; " +
                "dumpsys media.audio_flinger >/dev/null 2>&1 && echo audio=1 || echo audio=0; " +
                "dumpsys SurfaceFlinger >/dev/null 2>&1 && echo surface=1 || echo surface=0; " +
                "id | grep -q 'uid=0' && echo root=1 || echo root=0"

        internal fun parseProperties(raw: String): Map<String, String> = raw.lineSequence()
            .map { it.trim() }
            .filter { '=' in it }
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .filter { it.first in setOf("model", "android", "firmware", "characteristics") }
            .toMap()

        internal fun formFactor(properties: Map<String, String>): FormFactor? {
            val characteristics = properties["characteristics"].orEmpty().lowercase()
            return when {
                "emulator" in characteristics -> FormFactor.EMULATOR
                "tv" in characteristics -> FormFactor.TV
                "tablet" in characteristics -> FormFactor.TABLET
                "phone" in characteristics -> FormFactor.PHONE
                else -> null
            }
        }

        internal fun transport(serial: String): Transport = when {
            serial.startsWith("emulator-") -> Transport.EMULATOR
            ':' in serial -> Transport.TCP_IP
            serial.isNotBlank() -> Transport.USB
            else -> Transport.UNKNOWN
        }

        private fun toolAvailable(raw: String, tool: String): Boolean =
            raw.lineSequence().any { it.trim() == "$tool=1" }

        private fun signalAvailable(raw: String, signal: String): Boolean =
            raw.lineSequence().any { it.trim() == "$signal=1" }
    }
}
