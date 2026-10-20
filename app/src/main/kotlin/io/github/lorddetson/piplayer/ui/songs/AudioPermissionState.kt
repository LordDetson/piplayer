package io.github.lorddetson.piplayer.ui.songs

/** When the song list offers the app's settings instead of the system dialog (docs/playback.md §2.2). */
object AudioPermissionState {

    /**
     * Denied for good: not [granted], the system no longer explains why it asks ([rationale] false), and the user has
     * denied it before ([deniedBefore]); a dialog closed without an answer leaves the rationale false too, but is no
     * denial.
     */
    fun deniedForGood(granted: Boolean, rationale: Boolean, deniedBefore: Boolean): Boolean =
        !granted && !rationale && deniedBefore
}
