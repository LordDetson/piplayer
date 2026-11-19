package io.github.lorddetson.piplayer.tagging.write

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LegacyId3Change(val before: String?, val after: String?)

@Serializable
internal data class TagWriteRecord(
    val originalSha256: ByteArray,
    val originalDevice: Long,
    val originalInode: Long,
    val changes: Map<String, TagChange>,
    val undoOf: Long? = null,
    val conflicts: Set<String> = emptySet(),
    val markedTagHash: Boolean = false,
    val coverChange: TagChange? = null,
    val legacyId3Change: LegacyId3Change? = null,
    val id3v2Changes: Map<String, TagChange>? = null,
) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { it.write(Json.encodeToString(this)) }
        return bytes.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): TagWriteRecord? = try {
            GZIPInputStream(bytes.inputStream()).bufferedReader().use { Json.decodeFromString(it.readText()) }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
