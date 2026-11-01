package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.nativelib.taglib.AudioProperties
import io.github.lorddetson.piplayer.nativelib.taglib.Picture
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagReportTest {

    private val read = TagReadResult.Success(
        properties = mapOf("TITLE" to listOf("Ёжик в тумане"), "ARTIST" to listOf("PiPlayer Fixtures")),
        audio = AudioProperties(lengthMs = 2_000, bitrateKbps = 128, sampleRate = 44_100, channels = 2),
        pictures = listOf(Picture(data = ByteArray(3), sizeBytes = 3, "image/jpeg", "Back Cover", "back")),
    )

    @Test
    fun `a read keeps its tags, audio and picture summaries, without picture bytes`() {
        val report = TagReport.of(read)

        assertThat(report.error).isNull()
        assertThat(report.properties).isEqualTo(read.properties)
        assertThat(report.audio).isEqualTo(TagReport.Audio(2_000, 128, 44_100, 2))
        assertThat(report.pictures).containsExactly(TagReport.PictureInfo("Back Cover", "image/jpeg", "back", 3))
    }

    @Test
    fun `a failure keeps its reason`() {
        val report = TagReport.of(TagReadResult.Failure(TagReadError.NOT_SEEKABLE))

        assertThat(report).isEqualTo(TagReport(error = TagReadError.NOT_SEEKABLE))
        assertThat(report.toResult()).isEqualTo(TagReadResult.Failure(TagReadError.NOT_SEEKABLE))
    }

    @Test
    fun `back as a reader's result, pictures have their size and no bytes`() {
        val result = TagReport.of(read).toResult() as TagReadResult.Success

        assertThat(result.properties).isEqualTo(read.properties)
        assertThat(result.audio).isEqualTo(read.audio)
        val picture = result.pictures.single()
        assertThat(picture.data).isNull()
        assertThat(picture.sizeBytes).isEqualTo(3)
        assertThat(listOf(picture.mimeType, picture.pictureType, picture.description))
            .containsExactly("image/jpeg", "Back Cover", "back")
    }
}
