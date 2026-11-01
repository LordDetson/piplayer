package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.tagging.TagRead
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TaggerResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The debug screen reads tags through `:tagger` (docs/library.md §4.5). */
class SpikeTagsTest {

    @Test
    fun `a reply becomes the reader's result`() {
        val report = TagReport(properties = mapOf("TITLE" to listOf("Paper Lanterns")))

        val result = TaggerResult.Replied(TagRead(report, viaFile = false)).tagsOrThrow()

        assertThat(result).isEqualTo(TagReadResult.Success(report.properties, null, emptyList()))
    }

    @Test
    fun `a failure of TagLib stays a failure of the reader`() {
        val reply = TaggerResult.Replied(TagRead(TagReport(error = TagReadError.UNSUPPORTED_FORMAT), viaFile = false))

        assertThat(reply.tagsOrThrow()).isEqualTo(TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT))
    }

    @Test
    fun `a crash, a timeout and an unsafe file fail the tag stage with what happened`() {
        val cases = mapOf(
            TaggerResult.Crashed to "crashed",
            TaggerResult.TimedOut to "did not answer within 30 s",
            TaggerResult.Unsafe to "marked unsafe",
        )

        for ((result, message) in cases) {
            assertThatThrownBy { result.tagsOrThrow() }.hasMessageContaining(message)
        }
    }
}
