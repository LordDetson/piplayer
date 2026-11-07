package io.github.lorddetson.piplayer.playback

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Text inside media IDs: queue requests (docs/playback.md §2.3) and the nodes of the library tree (§10.1). */
internal object MediaIdText {

    /**
     * Percent-encoding with `%20` for spaces, so the text holds no space, `&`, `=`, `#`, `/`, `!` or control character:
     * only letters, digits, `.`, `-`, `*`, `_` and `%`.
     */
    // The overloads that take a Charset need Android 13.
    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** Null for text that is not percent-encoded UTF-8. */
    fun decode(text: String): String? {
        if ('+' in text) return null
        val bytes = try {
            URLDecoder.decode(text, "ISO-8859-1").toByteArray(StandardCharsets.ISO_8859_1)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
