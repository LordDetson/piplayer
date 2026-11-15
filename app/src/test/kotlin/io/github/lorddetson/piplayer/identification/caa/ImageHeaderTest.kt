package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.fakes.FakeCaa
import io.github.lorddetson.piplayer.library.details.PictureSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** TI §5.2: a cover's format and size come from its first bytes. */
class ImageHeaderTest {

    @Test
    fun `the JPEG fixtures of the fake CAA read with their sizes`() {
        for (image in listOf(FakeCaa.FRONT_250, FakeCaa.FRONT_500, FakeCaa.FRONT_1200, FakeCaa.ORIGINAL)) {
            val bytes = image.fixture.bytes()
            assertThat(ImageHeader.format(bytes)).isEqualTo(ImageFormat.JPEG)
            assertThat(ImageHeader.size(bytes)).isEqualTo(PictureSize(image.width, image.height))
        }
    }

    @Test
    fun `a progressive JPEG reads its size from its SOF2 segment`() {
        val bytes = bytes(
            0xFF, 0xD8,
            // APP0 of 16 bytes, skipped by its length.
            0xFF, 0xE0, 0x00, 0x10, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
            // Fill bytes before a marker are allowed.
            0xFF, 0xFF,
            0xFF, 0xC2, 0x00, 0x11, 0x08, 0x01, 0x00, 0x02, 0x00, 0x03,
        )
        assertThat(ImageHeader.size(bytes)).isEqualTo(PictureSize(512, 256))
    }

    @Test
    fun `a JPEG cut off before its frame header has no size`() {
        val bytes = FakeCaa.ORIGINAL.fixture.bytes().copyOf(20)
        assertThat(ImageHeader.format(bytes)).isEqualTo(ImageFormat.JPEG)
        assertThat(ImageHeader.size(bytes)).isNull()
    }

    @Test
    fun `a PNG reads its size from IHDR`() {
        val bytes = bytes(
            0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I'.code, 'H'.code, 'D'.code, 'R'.code,
            0, 0, 0x02, 0x80, 0, 0, 0x01, 0xE0, 8, 2, 0, 0, 0,
        )
        assertThat(ImageHeader.format(bytes)).isEqualTo(ImageFormat.PNG)
        assertThat(ImageHeader.size(bytes)).isEqualTo(PictureSize(640, 480))
    }

    @Test
    fun `a GIF reads its size from the screen descriptor`() {
        val bytes = "GIF89a".toByteArray() + bytes(0x40, 0x01, 0xC8, 0x00, 0, 0, 0)
        assertThat(ImageHeader.format(bytes)).isEqualTo(ImageFormat.GIF)
        assertThat(ImageHeader.size(bytes)).isEqualTo(PictureSize(320, 200))
    }

    @Test
    fun `a WebP is known as an image but has no size before decoding`() {
        val bytes = "RIFF".toByteArray() + bytes(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(20)
        assertThat(ImageHeader.format(bytes)).isEqualTo(ImageFormat.WEBP)
        assertThat(ImageHeader.size(bytes)).isNull()
    }

    @Test
    fun `other data is no image`() {
        for (bytes in listOf(
            "<html><body>Sign in</body></html>".toByteArray(),
            "%PDF-1.4".toByteArray(),
            "RIFF$\u0000\u0000\u0000WAVEfmt ".toByteArray(),
            ByteArray(0),
            bytes(0xFF),
        )) {
            assertThat(ImageHeader.format(bytes)).isNull()
            assertThat(ImageHeader.size(bytes)).isNull()
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
