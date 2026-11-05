package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TagRepliesTest {

    @TempDir
    lateinit var dir: File

    private val full = TagReport(
        properties = mapOf("TITLE" to listOf("Ёжик в тумане"), "ARTISTS" to listOf("Кино", "Би-2"), "EMPTY" to emptyList()),
        audio = TagReport.Audio(lengthMs = 2_000, bitrateKbps = 128, sampleRate = 44_100, channels = 2),
        pictures = listOf(TagReport.PictureInfo("Front Cover", "image/png", "Обложка", sizeBytes = 1_234)),
    )

    private fun inlineSize(report: TagReport): Int = (TagReplies.pack(report, dir) as TagReplyBody.Inline).json.size

    /** Ten values of under 16 KiB each, the last one padded so that the report's JSON takes exactly [bytes]. */
    private fun reportOf(bytes: Int): TagReport {
        val values = (0 until 10).associate { "KEY$it" to listOf("a".repeat(12_000)) }
        val padding = bytes - inlineSize(TagReport(properties = values))
        return TagReport(properties = values + ("KEY9" to listOf("a".repeat(12_000 + padding))))
    }

    @Test
    fun `a small report crosses inline and comes back whole`() {
        val body = TagReplies.pack(full, dir)

        assertThat(body).isInstanceOf(TagReplyBody.Inline::class.java)
        assertThat(dir.listFiles()).isEmpty()
        assertThat(TagReplies.unpack(body, dir)).isEqualTo(full)
    }

    @Test
    fun `a reply that cannot be written to its file comes back as a read error`() {
        val notADirectory = File(dir, "occupied").apply { writeText("") }
        val large = reportOf(TagReplies.MAX_INLINE_BYTES + 1)

        val body = TagReplies.pack(large, notADirectory)

        assertThat(body).isInstanceOf(TagReplyBody.Inline::class.java)
        assertThat(TagReplies.unpack(body, notADirectory).error).isEqualTo(TagReadError.IO_ERROR)
    }

    @Test
    fun `a failure crosses inline`() {
        val failure = TagReport(error = TagReadError.UNSUPPORTED_FORMAT)

        assertThat(TagReplies.unpack(TagReplies.pack(failure, dir), dir)).isEqualTo(failure)
    }

    @Test
    fun `a report of up to 120 KiB crosses inline`() {
        val report = reportOf(TagReplies.MAX_INLINE_BYTES)

        assertThat(inlineSize(report)).isEqualTo(120 * 1024)
        assertThat(dir.listFiles()).isEmpty()
    }

    @Test
    fun `a larger report goes through a file, which unpacking deletes`() {
        val report = reportOf(TagReplies.MAX_INLINE_BYTES + 1)

        val body = TagReplies.pack(report, dir)

        assertThat(body).isInstanceOf(TagReplyBody.InFile::class.java)
        val file = File((body as TagReplyBody.InFile).path)
        assertThat(file.parentFile).isEqualTo(dir)
        assertThat(file.length()).isEqualTo(TagReplies.MAX_INLINE_BYTES + 1L)
        assertThat(TagReplies.unpack(body, dir)).isEqualTo(report)
        assertThat(file).doesNotExist()
    }

    @Test
    fun `a value over 16 KiB in UTF-8 sends the report through a file`() {
        // Two bytes per "ё": 8 192 of them take exactly 16 KiB.
        val longest = TagReport(properties = mapOf("LYRICS" to listOf("ё".repeat(8_192))))
        val longer = TagReport(properties = mapOf("LYRICS" to listOf("ё".repeat(8_193))))

        assertThat(TagReplies.pack(longest, dir)).isInstanceOf(TagReplyBody.Inline::class.java)
        val body = TagReplies.pack(longer, dir)
        assertThat(body).isInstanceOf(TagReplyBody.InFile::class.java)
        assertThat(TagReplies.unpack(body, dir)).isEqualTo(longer)
    }

    @Test
    fun `keys and picture details count as values too`() {
        val over = "a".repeat(TagReplies.MAX_VALUE_BYTES + 1)
        val reports = listOf(
            TagReport(properties = mapOf(over to listOf("x"))),
            TagReport(pictures = listOf(TagReport.PictureInfo("Front Cover", "image/png", over, 1))),
            TagReport(pictures = listOf(TagReport.PictureInfo("Front Cover", over, "", 1))),
            TagReport(pictures = listOf(TagReport.PictureInfo(over, "image/png", "", 1))),
        )

        for (report in reports) {
            assertThat(TagReplies.pack(report, dir)).isInstanceOf(TagReplyBody.InFile::class.java)
        }
    }

    @Test
    fun `only files directly in the spill directory are accepted`() {
        val outside = File(dir.parentFile, "outside-${dir.name}.json").apply { writeText("{}") }
        try {
            for (path in listOf(outside.path, File(dir, "../${outside.name}").path)) {
                assertThatThrownBy { TagReplies.unpack(TagReplyBody.InFile(path), dir) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThat(outside).exists()
        } finally {
            outside.delete()
        }
    }
}
