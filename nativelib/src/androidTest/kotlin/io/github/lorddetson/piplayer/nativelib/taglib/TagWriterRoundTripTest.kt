package io.github.lorddetson.piplayer.nativelib.taglib

import android.os.ParcelFileDescriptor
import io.github.lorddetson.piplayer.nativelib.TestFiles
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TagWriterRoundTripTest(private val fixture: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun formats() = listOf(
            "id3v23.mp3", "id3v24.mp3", "vorbis-comment.flac", "aac.m4a",
            "alac.m4a", "vorbis.ogg", "opus.opus", "riff-info.wav",
        )
    }

    init {
        TagReader.anyProcess = true
    }

    private fun read(file: File): TagReadResult.Success = TestFiles.open(file).use {
        TagReader.read(it.fd, fixture, Pictures.DATA) as TagReadResult.Success
    }

    @Test
    fun writesOneFieldAndKeepsTheOtherPropertiesAndPictures() {
        val file = TestFiles.copy("tags/$fixture")
        val before = read(file)
        val title = "Новый заголовок"
        val originalId3Version = if (fixture.endsWith(".mp3")) file.readBytes()[3] else null

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
            assertThat(TagWriter.write(it.fd, mapOf("TITLE" to listOf(title)))).isTrue()
        }

        val after = read(file)
        assertThat(after.properties).isEqualTo(before.properties + ("TITLE" to listOf(title)))
        assertThat(after.audio).isEqualTo(before.audio)
        assertThat(after.pictures).hasSameSizeAs(before.pictures)
        before.pictures.zip(after.pictures).forEach { (old, new) ->
            assertThat(new.data).isEqualTo(old.data)
            assertThat(new.pictureType).isEqualTo(old.pictureType)
        }
        if (originalId3Version != null) {
            val bytes = file.readBytes()
            assertThat(bytes[3]).isEqualTo(originalId3Version)
            assertThat(String(bytes.copyOfRange(bytes.size - 128, bytes.size - 125), Charsets.US_ASCII))
                .isNotEqualTo("TAG")
        }
    }
}
