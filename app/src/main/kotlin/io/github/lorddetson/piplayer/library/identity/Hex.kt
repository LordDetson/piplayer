package io.github.lorddetson.piplayer.library.identity

/** Content keys and MBIDs between the hex and UUID text of the library database and the blobs of the user database. */
object Hex {

    private val digits = "0123456789abcdef".toCharArray()

    fun bytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Not hex: $hex" }
        return ByteArray(hex.length / 2) { i -> ((digit(hex, 2 * i) shl 4) or digit(hex, 2 * i + 1)).toByte() }
    }

    // A table rather than String.format per byte: the mirror of decisions turns tens of thousands of keys and MBIDs.
    fun text(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[2 * i] = digits[value ushr 4]
            out[2 * i + 1] = digits[value and 0xF]
        }
        return String(out)
    }

    /** The 16 bytes of an MBID, or null when [mbid] is not a UUID. */
    fun mbidBytes(mbid: String?): ByteArray? {
        val hex = mbid?.trim()?.replace("-", "")?.lowercase() ?: return null
        if (hex.length != 32 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return bytes(hex)
    }

    /** An MBID in its usual form, from its 16 bytes. */
    fun mbidText(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != 16) return null
        val hex = text(bytes)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun digit(hex: String, at: Int): Int {
        val value = Character.digit(hex[at], 16)
        require(value >= 0) { "Not hex: $hex" }
        return value
    }
}
