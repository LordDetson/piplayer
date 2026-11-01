package io.github.lorddetson.piplayer.nativelib.taglib

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.nativelib.TestFiles
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** TagLib reads only in the app's `:tagger` process (docs/library.md §4.1); this test process is not one. */
@RunWith(AndroidJUnit4::class)
class TagReaderProcessTest {

    @After
    fun allowAgain() {
        TagReader.anyProcess = true
    }

    @Test
    fun readingOutsideTheTaggerProcessIsRefused() {
        TagReader.anyProcess = false
        val flac = TestFiles.copy("tags/vorbis-comment.flac")

        assertThatThrownBy { TestFiles.open(flac).use { TagReader.read(it.fd, "vorbis-comment.flac") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("TagReader reads only in the :tagger process")
    }
}
