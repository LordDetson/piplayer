package io.github.lorddetson.piplayer

import android.app.Application
import android.content.Context

/**
 * What a process of PiPlayer is for, from its name (docs/playback.md §1.5). Components that belong to the main
 * process check it when they start.
 */
enum class ProcessRole {
    /** Named after the package: the UI, the playback service and, later, WorkManager, the databases and DataStore. */
    MAIN,

    /** `:tagger` (M2a): native tag calls only. */
    TAGGER,

    /** Any other name: nothing of PiPlayer's starts there. */
    OTHER;

    companion object {
        const val TAGGER_SUFFIX = ":tagger"

        fun of(processName: String, packageName: String): ProcessRole = when (processName) {
            packageName -> MAIN
            packageName + TAGGER_SUFFIX -> TAGGER
            else -> OTHER
        }

        /** The role of the calling process. */
        fun current(context: Context): ProcessRole = of(Application.getProcessName(), context.packageName)
    }
}
