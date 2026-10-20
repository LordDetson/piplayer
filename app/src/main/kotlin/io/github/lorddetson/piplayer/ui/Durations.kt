package io.github.lorddetson.piplayer.ui

/** m:ss, or h:mm:ss from an hour on; "--:--" when the length is unknown. */
fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "--:--"
    val seconds = durationMs / 1000
    val (hours, minutes, rest) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
