package devicemonitor.domain.playerdebug

object PlayerTelemetryProtocol {
    const val TAG = "OPTD"
    const val SCHEMA_V1 = "optd/1"
    const val MAX_EVENT_BYTES = 8 * 1024
    const val MAX_JSON_DEPTH = 12
    const val MAX_JSON_VALUES = 256
    const val MAX_ID_CHARS = 256
    const val MAX_TYPE_CHARS = 96
    const val MAX_ATTRIBUTE_CHARS = 2 * 1024

    val V1_EVENT_TYPES = setOf(
        "capabilities",
        "session.started",
        "session.content_changed",
        "session.ended",
        "player.created",
        "player.activated",
        "player.released",
        "state.changed",
        "buffer.started",
        "buffer.ended",
        "render.first_frame",
        "render.video_available",
        "render.video_unavailable",
        "audio.available",
        "audio.unavailable",
        "track.changed",
        "quality.changed",
        "metric.sample",
        "error.reported",
        "handoff.requested",
        "handoff.completed",
        "diagnostic.snapshot"
    )
}
