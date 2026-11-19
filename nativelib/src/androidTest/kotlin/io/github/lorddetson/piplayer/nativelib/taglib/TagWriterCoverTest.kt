package io.github.lorddetson.piplayer.nativelib.taglib

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import io.github.lorddetson.piplayer.nativelib.TestFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TagWriterCoverTest {
    init {
        TagReader.anyProcess = true
    }

    @Test
    fun replacesAllFrontPicturesAndLeavesTheBackAndAudioUntouched() {
        val file = TestFiles.copy("tags/id3v23.mp3")
        val original = file.readBytes()
        val tagSize = original.sliceArray(6..9).fold(0) { size, part -> (size shl 7) or (part.toInt() and 127) }
        val audio = original.copyOfRange(10 + tagSize, original.size)
        val back = png(0xff0000ff.toInt())
        val old = apic(4, "Back booklet", back) + apic(3, "First front", png(0xff00ff00.toInt())) +
            apic(3, "Second front", png(0xffff0000.toInt()))
        val size = ByteArray(4) { (old.size shr (7 * (3 - it)) and 127).toByte() }
        file.writeBytes("ID3".toByteArray() + byteArrayOf(3, 0, 0) + size + old + audio)
        assertThat(read(file).map { it.pictureType }).containsExactly("Back Cover", "Front Cover", "Front Cover")

        val replacement = png(0xffffff00.toInt())
        write(file, listOf(Picture(replacement, replacement.size, "image/png", "Front Cover", "")))

        val byType = read(file).associateBy { it.pictureType }
        assertThat(byType.keys).containsExactlyInAnyOrder("Back Cover", "Front Cover")
        assertThat(read(file)).hasSize(2)
        assertThat(byType.getValue("Front Cover").data).isEqualTo(replacement)
        assertThat(byType.getValue("Back Cover").data).isEqualTo(back)
        assertThat(byType.getValue("Back Cover").description).isEqualTo("Back booklet")
        assertThat(file.readBytes().takeLast(audio.size).toByteArray()).isEqualTo(audio)

        write(file, emptyList())
        assertThat(read(file).single().data).isEqualTo(back)
        assertThat(file.readBytes().takeLast(audio.size).toByteArray()).isEqualTo(audio)
    }

    private fun read(file: File): List<Picture> = TestFiles.open(file).use {
        (TagReader.read(it.fd, "id3v23.mp3", Pictures.DATA) as TagReadResult.Success).pictures
    }

    private fun write(file: File, frontCovers: List<Picture>) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
            assertThat(TagWriter.write(it.fd, emptyMap(), frontCovers = frontCovers)).isTrue()
        }
    }

    private fun apic(type: Int, description: String, picture: ByteArray): ByteArray {
        val body = byteArrayOf(0) + "image/png\u0000".toByteArray() + byteArrayOf(type.toByte()) +
            description.toByteArray() + byteArrayOf(0) + picture
        return "APIC".toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + byteArrayOf(0, 0) + body
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use {
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }
}
