package devicemonitor.domain

/** One flagged logcat event. [elapsedSec] is relative to run start (null if not derivable). */
data class Anomaly(
    val deviceTime: String,
    val elapsedSec: Double?,
    val type: String,
    val detail: String
)
