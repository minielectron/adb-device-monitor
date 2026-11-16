package devicemonitor.data.playerdebug

import devicemonitor.data.adb.Adb
import devicemonitor.domain.releaseguard.DeviceCapability
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class MediaSnapshotProvider(val fileStem: String, val command: String, val capability: DeviceCapability) {
    CODEC("codec", "dumpsys media.codec", DeviceCapability.MEDIA_CODEC_DIAGNOSTICS),
    AUDIO("audio", "dumpsys media.audio_flinger", DeviceCapability.AUDIO_DIAGNOSTICS),
    SURFACE("surface", "dumpsys SurfaceFlinger", DeviceCapability.SURFACE_DIAGNOSTICS),
    MEDIA("media", "dumpsys media.player", DeviceCapability.PLAYBACK_DIAGNOSTICS)
}

enum class MediaSnapshotStatus { CAPTURED, EMPTY, FAILED, BACKED_OFF, RATE_LIMITED }

data class MediaSnapshot(
    val provider: MediaSnapshotProvider,
    val atNs: Long,
    val reason: String,
    val status: MediaSnapshotStatus,
    val artifact: File? = null,
    val bytes: Int = 0,
    val error: String? = null
)

class MediaSnapshotCollector internal constructor(
    private val shell: (String, Long) -> String,
    private val artifactDirectory: File,
    capabilities: Set<DeviceCapability>,
    private val timeoutSec: Long = 5,
    private val maxBytes: Int = 1024 * 1024,
    private val periodicIntervalNs: Long = 10_000_000_000L,
    private val triggerIntervalNs: Long = 5_000_000_000L
) {
    constructor(
        adb: Adb,
        target: String,
        artifactDirectory: File,
        capabilities: Set<DeviceCapability>,
        timeoutSec: Long = 5,
        maxBytes: Int = 1024 * 1024,
        periodicIntervalNs: Long = 10_000_000_000L,
        triggerIntervalNs: Long = 5_000_000_000L
    ) : this({ command, timeout -> adb.shell(target, command, timeout) }, artifactDirectory, capabilities, timeoutSec, maxBytes, periodicIntervalNs, triggerIntervalNs)

    private val providers = MediaSnapshotProvider.entries.filter { it.capability in capabilities }
    private val nextAllowed = mutableMapOf<MediaSnapshotProvider, Long>()
    private val failures = mutableMapOf<MediaSnapshotProvider, Int>()
    private var nextTriggeredAtNs = 0L
    private val counter = AtomicInteger()

    init {
        require(timeoutSec in 1..20) { "Snapshot timeout must be between 1 and 20 seconds" }
        require(maxBytes in 1024..4 * 1024 * 1024) { "Snapshot size limit is invalid" }
        require(periodicIntervalNs > 0 && triggerIntervalNs > 0) { "Snapshot intervals must be positive" }
        if (!artifactDirectory.mkdirs() && !artifactDirectory.isDirectory) {
            throw IllegalStateException("Cannot create snapshot folder ${artifactDirectory.absolutePath}")
        }
    }

    @Synchronized fun collectPeriodic(nowNs: Long): List<MediaSnapshot> =
        providers.map { capture(it, nowNs, "periodic", force = false) }

    @Synchronized fun collectTriggered(reason: String, nowNs: Long): List<MediaSnapshot> {
        if (nowNs < nextTriggeredAtNs) return providers.map {
            MediaSnapshot(it, nowNs, reason, MediaSnapshotStatus.RATE_LIMITED)
        }
        nextTriggeredAtNs = nowNs + triggerIntervalNs
        return providers.map { capture(it, nowNs, reason.take(80), force = true) }
    }

    private fun capture(provider: MediaSnapshotProvider, nowNs: Long, reason: String, force: Boolean): MediaSnapshot {
        val allowed = nextAllowed[provider] ?: 0L
        if (nowNs < allowed) return MediaSnapshot(provider, nowNs, reason, MediaSnapshotStatus.BACKED_OFF)
        return try {
            val raw = shell(provider.command, timeoutSec)
            if (raw.isBlank()) {
                scheduleFailure(provider, nowNs)
                MediaSnapshot(provider, nowNs, reason, MediaSnapshotStatus.EMPTY)
            } else {
                failures[provider] = 0
                nextAllowed[provider] = nowNs + if (force) triggerIntervalNs else periodicIntervalNs
                val bytes = raw.toByteArray(Charsets.UTF_8)
                val kept = if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
                val file = File(artifactDirectory, "%06d_%s.txt".format(counter.incrementAndGet(), provider.fileStem))
                file.writeBytes(kept)
                MediaSnapshot(provider, nowNs, reason, MediaSnapshotStatus.CAPTURED, file, kept.size)
            }
        } catch (e: Exception) {
            scheduleFailure(provider, nowNs)
            MediaSnapshot(provider, nowNs, reason, MediaSnapshotStatus.FAILED, error = e.message?.take(200))
        }
    }

    private fun scheduleFailure(provider: MediaSnapshotProvider, nowNs: Long) {
        val count = ((failures[provider] ?: 0) + 1).coerceAtMost(3)
        failures[provider] = count
        nextAllowed[provider] = nowNs + when (count) {
            1 -> 10_000_000_000L
            2 -> 30_000_000_000L
            else -> 60_000_000_000L
        }
    }
}
