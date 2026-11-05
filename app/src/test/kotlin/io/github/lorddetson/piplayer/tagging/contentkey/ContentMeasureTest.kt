package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.AudioProperties
import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** What `:tagger` reports about a file's content, whatever TagLib made of the file (TI §3.1). */
class ContentMeasureTest {

    private val flac = bytesOf("fLaC", 0x80, bigEndian(34, 3), ByteArray(34), pattern(3_000))
    private val read = TagReadResult.Success(emptyMap(), AudioProperties(5_000, 128, 44_100, 2), emptyList())
    private val noMpeg: () -> MpegLayout? = { null }

    @Test
    fun `a file TagLib reads gets its key and the plan for its length`() {
        val content = ContentMeasure.of(ArraySource(flac), read, noMpeg)!!

        assertThat(content.token).isEqualTo("flac")
        assertThat(content.key).hasSize(64)
        assertThat(content.duration).isEqualTo(DurationPlan(5_000, DurationCheck.FLAC_END))
    }

    @Test
    fun `the format does not depend on TagLib accepting the metadata`() {
        val refused = TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT)

        val content = ContentMeasure.of(ArraySource(flac), refused, noMpeg)!!

        assertThat(content.key).isEqualTo(ContentMeasure.of(ArraySource(flac), read, noMpeg)!!.key)
        assertThat(content.token).isEqualTo("flac")
        assertThat(content.duration).isEqualTo(DurationPlan(null, DurationCheck.WALK))
    }

    @Test
    fun `a file of no known format gets a key from its whole bytes`() {
        val refused = TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT)

        val content = ContentMeasure.of(ArraySource(pattern(500)), refused, noMpeg)!!

        assertThat(content.token).isEqualTo("file")
        assertThat(content.duration).isEqualTo(DurationPlan(null, DurationCheck.WALK))
    }

    @Test
    fun `MPEG frames found by the probe make an mp3 key`() {
        val layout = MpegLayout(false, 0, 200, false, 400, 100, 500, null, 128)

        val content = ContentMeasure.of(ArraySource(pattern(500)), read) { layout }!!

        assertThat(content.token).isEqualTo("mp3")
    }

    @Test
    fun `the probe is not asked about a file the content already names`() {
        val content = ContentMeasure.of(ArraySource(flac), read) { error("not an MPEG file") }

        assertThat(content!!.token).isEqualTo("flac")
    }

    @ParameterizedTest
    @EnumSource(value = TagReadError::class, names = ["UNSUPPORTED_FORMAT"], mode = EnumSource.Mode.EXCLUDE)
    fun `a read that failed for another reason, which may pass, gives no key`(error: TagReadError) {
        assertThat(ContentMeasure.of(ArraySource(flac), TagReadResult.Failure(error), noMpeg)).isNull()
    }

    @Test
    fun `an empty file gives no key`() {
        assertThat(ContentMeasure.of(ArraySource(ByteArray(0)), read, noMpeg)).isNull()
    }

    @Test
    fun `a read failing during the key gives no key`() {
        assertThat(ContentMeasure.of(ArraySource(pattern(500)), read) { throw IOException("gone") }).isNull()

        val failing = object : ByteSource {
            override val size = 1_000L

            override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int = throw IOException("gone")
        }
        assertThat(ContentMeasure.of(failing, read, noMpeg)).isNull()
    }
}
