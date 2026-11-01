package io.github.lorddetson.piplayer.library.rules

/** Whether a file counts as a recording (docs/library.md §3.5). */
object Recordings {

    /** [flag] is the index's own flag, null on Android 11, which has none: then a `Recordings` folder decides. */
    fun isRecording(flag: Boolean?, relativePath: String): Boolean =
        flag ?: relativePath.split('/').any { it.equals(FOLDER, ignoreCase = true) }

    private const val FOLDER = "Recordings"
}
