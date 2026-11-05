package io.github.lorddetson.piplayer.nativelib.taglib

import io.github.lorddetson.piplayer.nativelib.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The reader names a file's container, with a name and from its content alone (docs/library.md §4.2). */
@RunWith(Parameterized::class)
class TagReaderContainerTest(private val file: String, private val container: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> = listOf(
            "id3v23.mp3" to "mpeg",
            "id3v1.mp3" to "mpeg",
            "adts.aac" to "adts",
            "vorbis-comment.flac" to "flac",
            "aac.m4a" to "mp4",
            "alac.m4a" to "mp4",
            "vorbis.ogg" to "ogg-vorbis",
            "opus.opus" to "ogg-opus",
            "riff-info.wav" to "wav",
        ).map { (file, container) -> arrayOf(file, container) }
    }

    init {
        TagReader.anyProcess = true
    }

    private fun read(fileName: String?): TagReadResult.Success {
        val result = TestFiles.open(TestFiles.copy("tags/$file")).use { pfd -> TagReader.read(pfd.fd, fileName) }
        assertThat(result).isInstanceOf(TagReadResult.Success::class.java)
        return result as TagReadResult.Success
    }

    @Test
    fun namesTheContainerOfANamedFile() {
        assertThat(read(file).container).isEqualTo(container)
    }

    @Test
    fun namesTheContainerFromTheContentAlone() {
        assertThat(read(null).container).isEqualTo(container)
    }
}
