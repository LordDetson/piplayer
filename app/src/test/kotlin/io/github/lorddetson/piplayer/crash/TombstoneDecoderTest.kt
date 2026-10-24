package io.github.lorddetson.piplayer.crash

import java.io.ByteArrayOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TombstoneDecoderTest {

    /** Writes protobuf the way tombstone.proto is encoded. */
    private class Proto {
        val out = ByteArrayOutputStream()

        fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun int(field: Int, value: Long) {
            varint((field shl 3).toLong())
            varint(value)
        }

        fun bytes(field: Int, data: ByteArray) {
            varint(((field shl 3) or 2).toLong())
            varint(data.size.toLong())
            out.write(data)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray())

        fun message(field: Int, build: Proto.() -> Unit) = bytes(field, Proto().apply(build).out.toByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun Proto.thread(tid: Long, name: String, vararg frames: Proto.() -> Unit) = message(16) {
        int(1, tid)
        message(2) {
            int(1, tid)
            string(2, name)
            frames.forEach { message(4, it) }
        }
    }

    private fun tombstone(otherThreadFirst: Boolean = false) = Proto().apply {
        int(1, 1) // arm64
        string(2, "google/shiba/shiba:16/BP2A/123:user/release-keys")
        string(4, "2026-10-24 20:00:00.123456789+0300")
        int(5, 1234)
        if (otherThreadFirst) thread(1234, "main", { int(1, 0x999); string(6, "/system/lib64/libc.so") })
        int(6, 1240)
        int(7, 10235)
        string(9, "io.github.lorddetson.piplayer")
        message(10) {
            int(1, 6)
            string(2, "SIGABRT")
            int(3, -6) // negative codes are ten-byte varints
            string(4, "SI_TKILL")
        }
        string(14, "taglib: bad frame")
        message(15) { string(1, "Abort called") }
        thread(1240, "tagger", {
            int(1, 0x1234)
            string(4, "TagLib::File::readBlock")
            int(5, 16)
            string(6, "/data/app/~~x/io.github.lorddetson.piplayer/lib/arm64/libtaglib.so")
            int(7, 0x4000)
            string(8, "abcdef0123456789")
        }, {
            int(1, 0x56)
            string(6, "/system/lib64/libc.so")
        })
        if (!otherThreadFirst) thread(1234, "main", { int(1, 0x999); string(6, "/system/lib64/libc.so") })
        string(1000, "a field of a newer Android or a vendor")
        int(20, 42)
    }.toByteArray()

    @Test
    fun `the tombstone reads as Android's own crash dump`() {
        val text = TombstoneDecoder.decode(tombstone())

        assertThat(text).contains(
            "Build fingerprint: 'google/shiba/shiba:16/BP2A/123:user/release-keys'",
            "ABI: 'arm64'",
            "Timestamp: 2026-10-24 20:00:00.123456789+0300",
            "Cmdline: io.github.lorddetson.piplayer",
            "pid: 1234, tid: 1240, name: tagger  >>> io.github.lorddetson.piplayer <<<",
            "uid: 10235",
            "signal 6 (SIGABRT), code -6 (SI_TKILL)",
            "Cause: Abort called",
            "Abort message: 'taglib: bad frame'",
            "#00 pc 0000000000001234  /data/app/~~x/io.github.lorddetson.piplayer/lib/arm64/libtaglib.so (offset 0x4000) " +
                "(TagLib::File::readBlock+16) (BuildId: abcdef0123456789)",
            "#01 pc 0000000000000056  /system/lib64/libc.so",
        )
    }

    @Test
    fun `the crashing thread comes first, whatever the order in the file`() {
        val text = TombstoneDecoder.decode(tombstone(otherThreadFirst = true))

        assertThat(text.indexOf("TagLib::File::readBlock")).isGreaterThanOrEqualTo(0)
        assertThat(text.indexOf("TagLib::File::readBlock")).isLessThan(text.indexOf("0000000000000999"))
        assertThat(text).contains("name: main")
    }

    @Test
    fun `input that is no tombstone is refused`() {
        val whole = tombstone()

        assertThatThrownBy { TombstoneDecoder.decode(whole.copyOf(whole.size / 2)) }
            .isInstanceOf(TombstoneDecoder.MalformedTombstone::class.java)
        assertThatThrownBy { TombstoneDecoder.decode(byteArrayOf(0x0B, 0x01)) }
            .isInstanceOf(TombstoneDecoder.MalformedTombstone::class.java)
    }
}
