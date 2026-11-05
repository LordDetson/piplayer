package io.github.lorddetson.piplayer.tagging

import java.io.File
import kotlinx.serialization.json.Json

/** How a reply crosses from `:tagger`: inline, or as the path of a file that holds it (docs/library.md §4.2). */
sealed interface TagReplyBody {

    /** [json] is the report as UTF-8 JSON. */
    class Inline(val json: ByteArray) : TagReplyBody

    data class InFile(val path: String) : TagReplyBody
}

/** Packs reports into reply bodies in `:tagger` and unpacks them in the main process. */
object TagReplies {

    /** The largest JSON that crosses inline, so that a binder reply stays under 128 KB. */
    const val MAX_INLINE_BYTES = 120 * 1024

    /** The longest string, in UTF-8, that crosses inline. */
    const val MAX_VALUE_BYTES = 16 * 1024

    /** [report] inline, or written to a new file in [spillDir] when it is too large to cross inline. */
    fun pack(report: TagReport, spillDir: File): TagReplyBody {
        val json = Json.encodeToString(report).encodeToByteArray()
        if (json.size <= MAX_INLINE_BYTES && strings(report).all { utf8Size(it) <= MAX_VALUE_BYTES }) {
            return TagReplyBody.Inline(json)
        }
        spillDir.mkdirs()
        val file = File.createTempFile("reply", ".json", spillDir)
        file.writeBytes(json)
        return TagReplyBody.InFile(file.path)
    }

    /** The report [body] holds; a file is read and deleted, and only files directly in [spillDir] are accepted. */
    fun unpack(body: TagReplyBody, spillDir: File): TagReport = when (body) {
        is TagReplyBody.Inline -> decode(body.json)
        is TagReplyBody.InFile -> {
            val file = File(body.path).canonicalFile
            require(file.parentFile == spillDir.canonicalFile) { "A reply file outside ${spillDir.path}" }
            try {
                decode(file.readBytes())
            } finally {
                file.delete()
            }
        }
    }

    private fun decode(json: ByteArray): TagReport = Json.decodeFromString(json.decodeToString())

    private fun strings(report: TagReport): Sequence<String> =
        report.properties.asSequence().flatMap { (key, values) -> sequenceOf(key) + values } +
            report.pictures.asSequence().flatMap { sequenceOf(it.pictureType, it.mimeType, it.description) } +
            sequenceOf(report.container, report.codec).filterNotNull()

    /** The length of [text] in UTF-8, as encodeToByteArray gives it (an unpaired surrogate becomes U+FFFD). */
    private fun utf8Size(text: String): Int {
        var size = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() == true -> 4.also { i++ }
                else -> 3
            }
            i++
        }
        return size
    }
}
