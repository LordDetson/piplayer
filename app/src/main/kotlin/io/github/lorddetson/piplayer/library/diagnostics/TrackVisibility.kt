package io.github.lorddetson.piplayer.library.diagnostics

import io.github.lorddetson.piplayer.library.rules.FolderRuleState
import io.github.lorddetson.piplayer.settings.HiddenFiles

internal data class IndexedTrackEvidence(
    val folderRule: FolderRuleState = FolderRuleState.NONE,
    val systemSound: Boolean = false,
    val recording: Boolean = false,
    val durationMs: Long? = null,
    val hasDisplayRow: Boolean = true,
)

internal enum class TrackVisibilityFilter { EXCLUDED_FOLDER, SYSTEM_SOUND, RECORDING, MINIMUM_LENGTH }

internal sealed interface TrackVisibilityFinding {
    data object Inaccessible : TrackVisibilityFinding
    data object NoLibraryPermission : TrackVisibilityFinding
    data object Undetermined : TrackVisibilityFinding
    data object NotInMediaStore : TrackVisibilityFinding
    data object PendingLibrary : TrackVisibilityFinding
    data object Visible : TrackVisibilityFinding
    data class Filtered(
        val filters: List<TrackVisibilityFilter>,
        val durationMs: Long? = null,
        val minimumSeconds: Int? = null,
    ) : TrackVisibilityFinding
}

/** The same folder precedence, type flags and display duration used by the library's visibility predicate. */
internal fun diagnoseIndexedTrack(evidence: IndexedTrackEvidence, settings: HiddenFiles): TrackVisibilityFinding {
    if (evidence.folderRule == FolderRuleState.EXCLUDE) {
        return TrackVisibilityFinding.Filtered(listOf(TrackVisibilityFilter.EXCLUDED_FOLDER))
    }
    if (!evidence.hasDisplayRow) return TrackVisibilityFinding.PendingLibrary
    if (evidence.folderRule == FolderRuleState.INCLUDE) return TrackVisibilityFinding.Visible

    val duration = evidence.durationMs
    val tooShort = duration != null && duration < settings.minLengthMs
    val filters = buildList {
        if (evidence.systemSound && !settings.showSystemSounds) add(TrackVisibilityFilter.SYSTEM_SOUND)
        if (evidence.recording && !settings.showRecordings) add(TrackVisibilityFilter.RECORDING)
        if (tooShort) add(TrackVisibilityFilter.MINIMUM_LENGTH)
    }
    return if (filters.isEmpty()) TrackVisibilityFinding.Visible else TrackVisibilityFinding.Filtered(
        filters,
        durationMs = duration.takeIf { tooShort },
        minimumSeconds = settings.minLengthSeconds.takeIf { tooShort },
    )
}
