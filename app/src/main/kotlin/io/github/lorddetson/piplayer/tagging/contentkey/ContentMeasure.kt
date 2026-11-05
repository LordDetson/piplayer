package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.tagging.TagReport
import java.io.IOException

/** The content key and the duration plan of a file `:tagger` has just read (TI §3.1). */
object ContentMeasure {

    /**
     * The key depends on the file's content alone: not on its name, and not on whether TagLib took its tags. There is
     * none when reading failed for a reason that may pass, or for an empty file. [mpegLayout] is asked only about a
     * file whose first bytes name no other container; it may throw IOException.
     */
    fun of(source: ByteSource, read: TagReadResult, mpegLayout: () -> MpegLayout?): TagReport.Content? {
        val success = read as? TagReadResult.Success
        if (success == null && (read as TagReadResult.Failure).error != TagReadError.UNSUPPORTED_FORMAT) return null
        return try {
            if (source.size == 0L) return null
            val sniffed = PayloadLocator.sniff(source)
            val mpeg = if (sniffed == AudioContainer.OTHER) mpegLayout() else null
            val container = if (mpeg != null) AudioContainer.MPEG else sniffed
            val located = PayloadLocator.locate(source, container, mpeg)
            TagReport.Content(
                key = ContentKey.hex(ContentKey.of(source, located.payload)),
                token = located.payload.format.token,
                duration = DurationRules.plan(container, mpeg, success?.audio?.lengthMs, located),
            )
        } catch (_: IOException) {
            null
        }
    }
}
