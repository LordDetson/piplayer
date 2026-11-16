package io.github.lorddetson.piplayer.ui.identification

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.details.PictureSize
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The embedded pictures the cover picker lists (TI §6, review screen B; §11.2 M3 "Review screen B"), read by the player's
 * extractors from the cover fixtures: every picture in the file's order with its header size, the same bytes once.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedImagesTest {

    private val context = TestFiles.targetContext

    private fun read(file: File): List<EmbeddedImage>? = runBlocking { EmbeddedImages.read(context, Uri.fromFile(file)) }

    private fun read(asset: String) = read(TestFiles.copy(asset))

    /** The fixtures' front cover (64 px) and back cover (32 px), PNG, as the MP3 fixture holds them. */
    private val pictures: List<ByteArray> by lazy { pngs(TestFiles.assets.open("playback/cover-back-then-front.mp3").use { it.readBytes() }) }
    private val back get() = pictures[0]
    private val front get() = pictures[1]

    @Test
    fun everyPictureOfEveryTaggedFormatInTheFilesOrderWithItsSize() {
        assertThat(pictures.map { it.size }).containsExactly(132, 207)
        val cases = mapOf(
            "playback/cover-back-then-front.mp3" to listOf(back, front),
            "playback/cover-front-then-back.flac" to listOf(front, back),
            "playback/cover-front.mp3" to listOf(front),
            "playback/cover-front.flac" to listOf(front),
            "playback/cover-front.m4a" to listOf(front),
            "playback/cover-front.ogg" to listOf(front),
            "playback/cover-front.opus" to listOf(front),
        )
        cases.forEach { (asset, expected) ->
            val images = read(asset)
            assertThat(images).describedAs(asset).isNotNull()
            assertThat(images!!.map { it.bytes.toList() }).describedAs(asset).isEqualTo(expected.map { it.toList() })
            assertThat(images.map { it.size }).describedAs(asset).isEqualTo(expected.map { if (it === front) PictureSize(64, 64) else PictureSize(32, 32) })
        }
    }

    @Test
    fun theSamePictureTwiceIsListedOnceAndALinkToAnAddressIsLeftOut() {
        val file = File(context.cacheDir, "embedded-twice.mp3")
        val tag = id3(
            apic("image/png", 3, "front", front),
            apic("image/png", 0, "again", front),
            apic("-->", 3, "link", "https://example.org/cover.png".toByteArray()),
            apic("image/png", 4, "back", back),
        )
        file.writeBytes(tag + audio("playback/cover-front.mp3"))

        val images = read(file)!!

        assertThat(images.map { it.bytes.toList() }).containsExactly(front.toList(), back.toList())
    }

    @Test
    fun aFileWithoutPicturesOrThatIsNoAudioHasNoneAndOneThatCannotBeOpenedCannotBeReadNow() {
        assertThat(read("playback/tagged-44100-stereo.mp3")).isEmpty()
        val text = File(context.cacheDir, "embedded-text.mp3").apply { writeText("not audio at all ".repeat(200)) }
        assertThat(read(text)).isEmpty()
        val missing = File(context.cacheDir, "embedded-missing.mp3").apply { delete() }
        assertThat(read(missing)).isNull()
    }

    /** The PNG images in [bytes], each from its signature to the end of its IEND chunk. */
    private fun pngs(bytes: ByteArray): List<ByteArray> {
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val end = "IEND".toByteArray()
        val found = mutableListOf<ByteArray>()
        var from = 0
        while (true) {
            val start = indexOf(bytes, signature, from).takeIf { it >= 0 } ?: break
            val iend = indexOf(bytes, end, start)
            found += bytes.copyOfRange(start, iend + end.size + 4)
            from = iend
        }
        return found
    }

    private fun indexOf(bytes: ByteArray, part: ByteArray, from: Int): Int =
        (from..bytes.size - part.size).firstOrNull { i -> part.indices.all { bytes[i + it] == part[it] } } ?: -1

    /** The audio of [asset] after its ID3v2 tag. */
    private fun audio(asset: String): ByteArray {
        val bytes = TestFiles.assets.open(asset).use { it.readBytes() }
        check(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())
        val size = (6..9).fold(0) { acc, i -> (acc shl 7) or (bytes[i].toInt() and 0x7F) }
        return bytes.copyOfRange(10 + size, bytes.size)
    }

    /** An ID3v2.3 tag of [frames]. */
    private fun id3(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        val size = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        return header + body
    }

    /** An ID3v2.3 `APIC` frame: Latin-1 text, [mime], picture [type], [description] and [data]. */
    private fun apic(mime: String, type: Int, description: String, data: ByteArray): ByteArray {
        val content = ByteArrayOutputStream().apply {
            write(0)
            write(mime.toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(type)
            write(description.toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(data)
        }.toByteArray()
        val size = content.size
        val header = "APIC".toByteArray() + byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(), 0, 0)
        return header + content
    }
}
