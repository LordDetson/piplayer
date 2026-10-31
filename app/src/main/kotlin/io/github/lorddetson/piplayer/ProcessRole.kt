package io.github.lorddetson.piplayer

import android.app.Application
import android.content.Context

/**
 * What a process of PiPlayer is for, from its name (docs/playback.md §1.5). Components that belong to the main
 * process check it when they start.
 */
enum class ProcessRole {
    /** Named after the package: the UI, the playback service, the databases, DataStore and, later, WorkManager. */
    MAIN,

    /** `:tagger` (M2a): native tag calls only. */
    TAGGER,

    /** Any other name: nothing of PiPlayer's starts there. */
    OTHER;

    /** Stops [component], which belongs to the main process, from starting in any other one. */
    fun requireMain(component: String) = check(this == MAIN) { "$component runs only in the main process" }

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
