package io.github.lorddetson.piplayer.tagging.write

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.TestImages
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagCoverEncoderTest {
    @Test
    fun compatibleAndCarStereoScaleToDifferentMaximumSizes() = runBlocking<Unit> {
        val input = TestImages.png(1_200, 600)
        withImage(input) { file ->
            for ((preset, width) in listOf(TagCoverPreset.COMPATIBLE to 1_000, TagCoverPreset.CAR_STEREO to 600)) {
                val cover = requireNotNull(encodeFrontCover(file, preset))
                assertThat(cover.mimeType).isEqualTo("image/jpeg")
                val bytes = cover.bytes()
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    assertThat(image.width).isEqualTo(width)
                    assertThat(image.height).isEqualTo(width / 2)
                } finally {
                    image.recycle()
                }
            }
        }
    }

    @Test
    fun originalPreservesImageBytesAndDetectedMimeType() = runBlocking<Unit> {
        val input = TestImages.png(40, 20)
        withImage(input) { file ->
            val cover = requireNotNull(encodeFrontCover(file, TagCoverPreset.ORIGINAL))
            assertThat(cover.mimeType).isEqualTo("image/png")
            assertThat(cover.bytes()).isEqualTo(input)
        }
    }

    private suspend fun withImage(bytes: ByteArray, use: suspend (File) -> Unit) {
        val file = File.createTempFile("tag-cover-", ".image", TestFiles.targetContext.cacheDir)
        try {
            file.writeBytes(bytes)
            use(file)
        } finally {
            file.delete()
        }
    }
}
