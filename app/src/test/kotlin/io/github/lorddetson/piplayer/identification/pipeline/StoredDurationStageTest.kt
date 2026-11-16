package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Stage 0 for a library file the tag pass has read (TI §3 "One run", §3.1): the length as the pass left it. */
class StoredDurationStageTest {

    private fun row(ms: Long?, source: String?) = AudioFileEntity(
        id = 1, mediaStoreId = 10, volumeName = "external_primary", relativePath = "Music/", displayName = "a.flac",
        size = 1_000, mtime = 1, generationModified = 1, mimeType = "audio/flac", isMusic = true, isPodcast = false,
        isAudiobook = false, isRingtone = false, isNotification = false, isAlarm = false, isRecording = false,
        recording = false, folderRule = 0, tagPassAt = 5, contentKey = "ab".repeat(32), durationMs = ms,
        durationSource = source,
    )

    @Test
    fun `a header length and a walked length are exact`() {
        assertThat(AndroidLocalStage.stored(row(214_000, DurationSource.HEADER))).isEqualTo(StageDuration.Exact(214_000))
        assertThat(AndroidLocalStage.stored(row(201_400, DurationSource.WALK))).isEqualTo(StageDuration.Exact(201_400))
    }

    @Test
    fun `a length no extractor could read, a missing one and a zero one are unknown`() {
        assertThat(AndroidLocalStage.stored(row(null, DurationSource.UNKNOWN))).isEqualTo(StageDuration.Unknown)
        assertThat(AndroidLocalStage.stored(row(214_000, DurationSource.UNKNOWN))).isEqualTo(StageDuration.Unknown)
        assertThat(AndroidLocalStage.stored(row(null, DurationSource.HEADER))).isEqualTo(StageDuration.Unknown)
        assertThat(AndroidLocalStage.stored(row(0, DurationSource.HEADER))).isEqualTo(StageDuration.Unknown)
        assertThat(AndroidLocalStage.stored(row(0, DurationSource.WALK))).isEqualTo(StageDuration.Unknown)
    }

    @Test
    fun `a length left for a walk is walked from nothing`() {
        assertThat(AndroidLocalStage.stored(row(null, DurationSource.PENDING_WALK)))
            .isEqualTo(StageDuration.Pending(DurationPlan(null, DurationCheck.WALK)))
    }

    @Test
    fun `a length left for the FLAC end check is checked against the stored header length`() {
        assertThat(AndroidLocalStage.stored(row(180_000, DurationSource.PENDING_FLAC_END)))
            .isEqualTo(StageDuration.Pending(DurationPlan(180_000, DurationCheck.FLAC_END)))
    }
}
