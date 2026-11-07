package io.github.lorddetson.piplayer.library.identity

/** Content keys and MBIDs between the hex and UUID text of the library database and the blobs of the user database. */
object Hex {

    fun bytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Not hex: $hex" }
        return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun text(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

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
}
