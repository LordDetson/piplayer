package io.github.lorddetson.piplayer.library.details

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.CHANNELS
import io.github.lorddetson.piplayer.library.details.DetailField.CODEC
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SOURCE
import io.github.lorddetson.piplayer.library.details.DetailField.FILE_NAME
import io.github.lorddetson.piplayer.library.details.DetailField.FOLDER
import io.github.lorddetson.piplayer.library.details.DetailField.FORMAT
import io.github.lorddetson.piplayer.library.details.DetailField.LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.SAMPLE_RATE
import io.github.lorddetson.piplayer.library.details.DetailField.SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.TAGS
import io.github.lorddetson.piplayer.library.details.DetailField.TITLE
import io.github.lorddetson.piplayer.tagging.Tagger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The details of a file opened from another app, read from the file through the real `:tagger` (docs/library.md §18.5). */
@RunWith(AndroidJUnit4::class)
class OpenedFileDetailsTest {

    private val context = TestFiles.targetContext
    private val details = OpenedFileDetails(context, Tagger.client(context), AndroidPictureProbe(context))
    private val copies = mutableListOf<File>()

    @After
    fun delete() {
        copies.forEach(File::delete)
    }

    private fun copy(asset: String) = TestFiles.copy(asset).also { copies += it }

    private fun read(file: File) = runBlocking { details.read(Uri.fromFile(file).toString()) }

    private fun TrackDetails.text(field: DetailField) = (value(field) as? DetailValue.Text)?.text

    @Test
    fun theTagsAndTechnicalDataComeFromTheFileItself() {
        val file = copy("playback/tagged-44100-stereo.mp3")

        val details = read(file)

        assertThat(details.fileId).isNull()
        assertThat(details.text(TITLE)).isEqualTo("Ёжик в тумане")
        assertThat(details.text(ARTIST)).isEqualTo("PiPlayer Fixtures")
        assertThat(details.text(ALBUM)).isEqualTo("Тесты")
        assertThat(details.text(FORMAT)).isEqualTo("mpeg")
        assertThat(details.text(CODEC)).isEqualTo("mp3")
        assertThat(details.value(SAMPLE_RATE)).isEqualTo(DetailValue.Hertz(44_100))
        assertThat(details.value(CHANNELS)).isEqualTo(DetailValue.Channels(2))
        assertThat((details.value(LENGTH) as DetailValue.Length).source).isEqualTo(LengthSource.ESTIMATE)
        assertThat(details.text(FILE_NAME)).isEqualTo(file.name)
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(file.length()))
        assertThat(details.value(FOLDER)).isNull()
        assertThat(details.value(TAGS)).isNull()
        assertThat(details.sections.map { it.first }).doesNotContain(DetailSection.PLAYS)
        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.NONE))
    }

    @Test
    fun theEmbeddedPictureHasItsSizeInPixelsAndBytes() {
        val file = File(context.cacheDir, "Cover Front.mp3").also { copies += it }
        copy("playback/cover-front.mp3").copyTo(file, overwrite = true)

        val details = read(file)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(64, 64, 207))
        // No title tag: the file name.
        assertThat(details.text(TITLE)).isEqualTo("Cover Front")
    }

    @Test
    fun aFileTagLibCannotReadShowsWhatThereIs() {
        val file = File(context.cacheDir, "not-audio.bin").apply { writeText("not audio at all") }.also { copies += it }

        val details = read(file)

        assertThat(details.text(FILE_NAME)).isEqualTo("not-audio.bin")
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(file.length()))
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.CANNOT_READ))
    }

    @Test
    fun aFileThatCanNoLongerBeOpenedShowsWhatThereIsAndThatItCouldNotBeRead() {
        val gone = File(context.cacheDir, "gone.mp3").apply { delete() }

        val details = read(gone)

        assertThat(details.fileId).isNull()
        assertThat(details.text(TITLE)).isEqualTo("gone")
        assertThat(details.text(FILE_NAME)).isEqualTo("gone.mp3")
        // A file that is not there has no size, rather than 0 bytes.
        assertThat(details.value(SIZE)).isNull()
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.CANNOT_READ))
        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.NONE))
    }

    @Test
    fun aDocumentNoProviderServesAnyMoreIsNamedByTheLastSegmentOfItsUri() {
        val uri = "content://io.github.lorddetson.piplayer.test.nothing/document/Lost%20Song.mp3"

        val details = runBlocking { details.read(uri) }

        assertThat(details.text(FILE_NAME)).isEqualTo("Lost Song.mp3")
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.CANNOT_READ))
    }
}
