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

    @Test
    fun leaveUntouchedPreservesTheWholeLegacyTagWhileWritingId3v2() {
        val file = TestFiles.copy("tags/id3v1.mp3")
        val oldLegacyTag = file.readBytes().takeLast(128)

        writeTitle(file, "Updated title", Id3v1WritePolicy.LEAVE_UNTOUCHED)

        assertThat(file.readBytes().takeLast(128)).isEqualTo(oldLegacyTag)
        TestFiles.open(file).use {
            val tags = TagReader.read(it.fd, "id3v1.mp3") as TagReadResult.Success
            assertThat(tags.properties["TITLE"]).containsExactly("Updated title")
        }
    }

    @Test
    fun removalWithNoTextChangesStripsOnlyTheLegacyTag() {
        val file = TestFiles.copy("tags/id3v1.mp3")
        val audio = file.readBytes().dropLast(128).toByteArray()

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
            assertThat(TagWriter.write(it.fd, emptyMap(), Id3v1WritePolicy.REMOVE)).isTrue()
        }

        assertThat(file.readBytes()).isEqualTo(audio)
    }

    private fun writeTitle(file: File, title: String, policy: Id3v1WritePolicy = Id3v1WritePolicy.UPDATE_IF_PRESENT) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
            assertThat(TagWriter.write(it.fd, mapOf("TITLE" to listOf(title)), policy)).isTrue()
        }
    }

    private fun id3v1Title(file: File): String {
        val tag = file.readBytes().takeLast(128).toByteArray()
        assertThat(String(tag, 0, 3, Charsets.US_ASCII)).isEqualTo("TAG")
        return String(tag, 3, 30, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
    }
}
