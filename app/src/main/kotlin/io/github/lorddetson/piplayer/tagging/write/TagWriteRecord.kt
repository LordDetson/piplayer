package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.userdata.FileReferences
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LegacyId3Change(val before: String?, val after: String?)

@Serializable
internal data class VerifiedTagReferences(
    val parts: Int,
    val hashes: Map<Int, Int> = emptyMap(),
    val decisions: Map<Int, Long> = emptyMap(),
)

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
    val writtenPartHashes: Map<Int, Int> = emptyMap(),
    val verifiedReferences: VerifiedTagReferences? = null,
) {
    fun withVerifiedReferences(winners: Map<Int, Long>, defaultDecision: Long?, actual: Map<Int, Int>): TagWriteRecord {
        val typed = hashesFromChanges()
        val mask = winners.keys.fold(0) { result, code -> result or (1 shl (code - 1)) }
        val references = VerifiedTagReferences(
            mask,
            actual.filter { (code, hash) -> code in winners && typed[code] != hash },
            winners.filterValues { it != defaultDecision },
        )
        return copy(writtenPartHashes = emptyMap(), verifiedReferences = references.takeIf { mask != 0 })
    }

    fun verifiedPartHashes(defaultDecision: Long?, winners: Map<DecidedField, Long>): Map<DecidedField, Int> {
        val references = verifiedReferences ?: return emptyMap()
        val typed = hashesFromChanges()
        return buildMap {
            for ((part, winner) in winners) {
                val code = part.code
                if (references.parts and (1 shl (code - 1)) == 0) continue
                if ((references.decisions[code] ?: defaultDecision) != winner) continue
                val hash = references.hashes[code] ?: typed[code] ?: continue
                put(part, hash)
            }
        }
    }

    private fun hashesFromChanges(): Map<Int, Int> =
        DecisionContent.ofTags(TrackTags.from(changes.mapValues { (_, change) -> change.after }))
            .entries.associate { (part, value) -> part.code to FileReferences.hash(value) }

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
