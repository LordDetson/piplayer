package io.github.lorddetson.piplayer.ui.details

import java.util.Locale

/** The formats of track details that need no resources (docs/library.md §18.2). */
object DetailFormat {

    /** A length to the millisecond: `3:25.412`, with hours when needed (`1:02:03.004`). */
    fun length(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val millis = total % 1_000
        val seconds = total / 1_000 % 60
        val minutes = total / 60_000 % 60
        val hours = total / 3_600_000
        return if (hours > 0) {
            "%d:%02d:%02d.%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
        } else {
            "%d:%02d.%03d".format(Locale.ROOT, minutes, seconds, millis)
        }
    }
}
