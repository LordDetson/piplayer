package io.github.lorddetson.piplayer.library.diagnostics

import io.github.lorddetson.piplayer.library.rules.FolderRuleState
import io.github.lorddetson.piplayer.settings.HiddenFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackVisibilityTest {
    @Test
    fun `the excluded folder takes precedence over the file settings`() {
        val evidence = IndexedTrackEvidence(FolderRuleState.EXCLUDE, systemSound = true, recording = true, durationMs = 1_000)

        assertEquals(
            TrackVisibilityFinding.Filtered(listOf(TrackVisibilityFilter.EXCLUDED_FOLDER)),
            diagnoseIndexedTrack(evidence, HiddenFiles()),
        )
    }

    @Test
    fun `an explicit include bypasses both type and minimum duration settings`() {
        val evidence = IndexedTrackEvidence(FolderRuleState.INCLUDE, systemSound = true, recording = true, durationMs = 1_000)

        assertEquals(TrackVisibilityFinding.Visible, diagnoseIndexedTrack(evidence, HiddenFiles()))
    }

    @Test
    fun `reports each active type filter and the exact displayed duration below the current limit`() {
        val evidence = IndexedTrackEvidence(systemSound = true, recording = true, durationMs = 14_999)
        val settings = HiddenFiles(minLengthSeconds = 15)

        assertEquals(
            TrackVisibilityFinding.Filtered(
                listOf(TrackVisibilityFilter.SYSTEM_SOUND, TrackVisibilityFilter.RECORDING, TrackVisibilityFilter.MINIMUM_LENGTH),
                durationMs = 14_999,
                minimumSeconds = 15,
            ),
            diagnoseIndexedTrack(evidence, settings),
        )
    }

    @Test
    fun `enabled types and off minimum leave the track visible`() {
        val evidence = IndexedTrackEvidence(systemSound = true, recording = true, durationMs = 1)

        assertEquals(
            TrackVisibilityFinding.Visible,
            diagnoseIndexedTrack(evidence, HiddenFiles(showSystemSounds = true, showRecordings = true, minLengthSeconds = 0)),
        )
    }

    @Test
    fun `unknown duration and the exact boundary are not evidence of a short track`() {
        val settings = HiddenFiles(minLengthSeconds = 30)

        assertEquals(TrackVisibilityFinding.Visible, diagnoseIndexedTrack(IndexedTrackEvidence(durationMs = 30_000), settings))
        assertEquals(TrackVisibilityFinding.Visible, diagnoseIndexedTrack(IndexedTrackEvidence(durationMs = null), settings))
    }

    @Test
    fun `the app cannot declare a track visible before it has a display row`() {
        assertEquals(
            TrackVisibilityFinding.PendingLibrary,
            diagnoseIndexedTrack(IndexedTrackEvidence(folderRule = FolderRuleState.INCLUDE, hasDisplayRow = false), HiddenFiles()),
        )
    }
}
