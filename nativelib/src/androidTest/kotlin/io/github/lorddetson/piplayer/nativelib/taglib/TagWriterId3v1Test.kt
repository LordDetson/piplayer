package io.github.lorddetson.piplayer.nativelib.taglib

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.nativelib.TestFiles
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagWriterId3v1Test {
    init {
        TagReader.anyProcess = true
    }

    @Test
    fun updatesAnExistingId3v1FieldWhenTheNewValueFits() {
        val file = TestFiles.copy("tags/id3v1.mp3")

        writeTitle(file, "Café")

        assertThat(id3v1Title(file)).isEqualTo("Café")
    }

    @Test
    fun preservesTheOldId3v1FieldWhenTheNewValueCannotBeEncoded() {
        val file = TestFiles.copy("tags/id3v1.mp3")
        val oldTitle = id3v1Title(file)

        writeTitle(file, "Заголовок")

        assertThat(id3v1Title(file)).isEqualTo(oldTitle)
    }

    private fun writeTitle(file: File, title: String) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
            assertThat(TagWriter.write(it.fd, mapOf("TITLE" to listOf(title)))).isTrue()
        }
    }

    private fun id3v1Title(file: File): String {
        val tag = file.readBytes().takeLast(128).toByteArray()
        assertThat(String(tag, 0, 3, Charsets.US_ASCII)).isEqualTo("TAG")
        return String(tag, 3, 30, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
    }
}
