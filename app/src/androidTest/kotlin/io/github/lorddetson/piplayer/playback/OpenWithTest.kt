package io.github.lorddetson.piplayer.playback

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** What PiPlayer takes from an open-with intent (docs/playback.md §5.1). */
@RunWith(AndroidJUnit4::class)
class OpenWithTest {

    private val first = Uri.parse("content://com.example.files/audio/first.mp3")
    private val second = Uri.parse("content://com.example.files/audio/second.flac")

    @Test
    fun aViewIntentCarriesItsFileInTheData() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(first, "audio/mpeg")

        assertThat(OpenWith.uris(intent)).containsExactly(first)
    }

    @Test
    fun aClipDataWithSeveralFilesKeepsTheirOrder() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(first, "audio/mpeg")
        intent.clipData = ClipData.newRawUri("second", second)

        assertThat(OpenWith.uris(intent)).containsExactly(first, second)
    }

    @Test
    fun anExplicitStreamIsReadToo() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(first, "audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, second)

        assertThat(OpenWith.uris(intent)).containsExactly(first, second)
    }

    @Test
    fun aSendIntentCarriesItsFileInTheStream() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, first)
        intent.clipData = ClipData.newRawUri("second", second)

        assertThat(OpenWith.uris(intent)).containsExactly(first, second)
    }

    @Test
    fun aMultipleSendKeepsTheStreamOrder() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("audio/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))

        assertThat(OpenWith.uris(intent)).containsExactly(first, second)
    }

    @Test
    fun theSameFileTwiceIsOpenedOnce() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(first, "audio/mpeg")
        intent.clipData = ClipData.newRawUri("first again", first)

        assertThat(OpenWith.uris(intent)).containsExactly(first)
    }

    @Test
    fun fileUrisAreRefused() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("file:///sdcard/Music/song.mp3"), "audio/mpeg")

        assertThat(OpenWith.uris(intent)).isEmpty()
    }

    @Test
    fun anIntentThatDoesNotOpenAFileCarriesNothing() {
        val intent = Intent(Intent.ACTION_MAIN).setData(first)

        assertThat(OpenWith.uris(intent)).isEmpty()
    }
}
