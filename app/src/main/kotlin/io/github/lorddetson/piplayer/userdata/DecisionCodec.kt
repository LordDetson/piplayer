package io.github.lorddetson.piplayer.userdata

import io.github.lorddetson.piplayer.identification.contract.LicenseClass
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.PartKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The stored forms of the values of user database version 6 (TI §10.2). */
object DecisionCodec {

    /** The stored value of [field] for its [text] (`PartKind`): MBIDs as 16 bytes each, the rest as UTF-8; null stays null. */
    fun encode(field: DecidedField, text: String?): ByteArray? {
        if (text.isNullOrEmpty()) return null
        return when (field.kind) {
            PartKind.MBID -> Hex.mbidBytes(text)
            PartKind.MBID_LIST -> DecidedField.items(text).mapNotNull(Hex::mbidBytes).takeIf { it.isNotEmpty() }
                ?.let { ids -> ByteArray(ids.size * MBID_BYTES).also { out -> ids.forEachIndexed { i, id -> id.copyInto(out, i * MBID_BYTES) } } }
            else -> text.toByteArray(Charsets.UTF_8)
        }
    }

    /** The text of a stored value of [field]. */
    fun decode(field: DecidedField, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return when (field.kind) {
            PartKind.MBID -> Hex.mbidText(bytes)
            PartKind.MBID_LIST -> DecidedField.list(
                (0 until bytes.size / MBID_BYTES).mapNotNull { i -> Hex.mbidText(bytes.copyOfRange(i * MBID_BYTES, (i + 1) * MBID_BYTES)) },
            )
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    /** The bits of [fields] (TI §10.2: bit 0 `title` to bit 15 `cover`, in the order of the protected-fields list). */
    fun bits(fields: Set<ProtectedField>): Int = fields.fold(0) { bits, field -> bits or (1 shl bitOf(field)) }

    /** The fields of [bits] this build knows. */
    fun fields(bits: Int): Set<ProtectedField> = ProtectedField.entries.filterTo(LinkedHashSet()) { bits and (1 shl bitOf(it)) != 0 }

    /** The fixed bit of [field]: its place in the list of TI §10, which a later field extends at the end. */
    private fun bitOf(field: ProtectedField): Int = when (field) {
        ProtectedField.TITLE -> 0
        ProtectedField.ARTIST -> 1
        ProtectedField.ALBUM -> 2
        ProtectedField.ALBUM_ARTIST -> 3
        ProtectedField.DATE -> 4
        ProtectedField.ORIGINAL_DATE -> 5
        ProtectedField.TRACK_NUMBER -> 6
        ProtectedField.DISC_NUMBER -> 7
        ProtectedField.DISC_SUBTITLE -> 8
        ProtectedField.COMPILATION -> 9
        ProtectedField.LABEL -> 10
        ProtectedField.CATALOG_NUMBER -> 11
        ProtectedField.BARCODE -> 12
        ProtectedField.ISRC -> 13
        ProtectedField.MUSICBRAINZ_IDS -> 14
        ProtectedField.COVER -> 15
    }

    fun providerCode(provider: ProviderId): Int = when (provider) {
        ProviderId.MUSICBRAINZ -> 1
        ProviderId.ACOUSTID -> 2
        ProviderId.COVER_ART_ARCHIVE -> 3
    }

    fun provider(code: Int?): ProviderId? = ProviderId.entries.firstOrNull { code != null && providerCode(it) == code }

    fun licenseCode(license: LicenseClass): Int = when (license) {
        LicenseClass.CC0 -> 1
        LicenseClass.RESTRICTED -> 2
        LicenseClass.UNKNOWN -> 3
    }

    fun license(code: Int?): LicenseClass? = LicenseClass.entries.firstOrNull { code != null && licenseCode(it) == code }

    /** A confidence from 0 to 1 in thousandths. */
    fun confidenceCode(confidence: Double): Int = (confidence * 1_000).roundToInt()

    fun confidence(code: Int?): Double? = code?.let { it / 1_000.0 }

    // The reason blob (TI §10.2): a first byte with the source (bits 0-1), the tier (bit 2), the stages that found it
    // (bits 3-6) and whether a length difference follows (bit 7); 4 bytes, most significant first, with the disagreements
    // (bits 30-31) and the three scores in thousandths (bits 20-29, 10-19, 0-9; NO_SCORE for none); then the length
    // difference as a zigzag varint.
    private fun sourceCode(source: Source): Int = when (source) {
        Source.TAG_RECORDING_ID -> 0
        Source.ISRC -> 1
        Source.FINGERPRINT -> 2
        Source.TEXT_SEARCH -> 3
    }

    private fun tierCode(tier: Tier): Int = when (tier) {
        Tier.IDENTIFIER -> 0
        Tier.SIMILARITY -> 1
    }

    private fun disagreementBit(field: HintField): Int = when (field) {
        HintField.TITLE -> 30
        HintField.ARTIST -> 31
    }

    /** The stored blob of a match's [reason] (TI §10.2): 7 bytes for a length difference within 8 s. */
    fun reason(reason: Reason): ByteArray {
        val delta = reason.durationDeltaMs
        var first = sourceCode(reason.source) or (tierCode(reason.tier) shl 2)
        for (source in reason.foundBy) first = first or (1 shl (3 + sourceCode(source)))
        if (delta != null) first = first or 0x80
        var scores = (score(reason.fingerprintScore) shl 20) or (score(reason.title) shl 10) or score(reason.artist)
        for (field in reason.disagreement) scores = scores or (1L shl disagreementBit(field))
        val out = ByteArrayOutputStream(7)
        out.write(first)
        for (shift in 24 downTo 0 step 8) out.write((scores shr shift).toInt() and 0xff)
        if (delta != null) {
            var zigzag = (delta shl 1) xor (delta shr 63)
            while (zigzag and 0x7fL.inv() != 0L) {
                out.write((zigzag and 0x7f).toInt() or 0x80)
                zigzag = zigzag ushr 7
            }
            out.write(zigzag.toInt())
        }
        return out.toByteArray()
    }

    /** The reason of its stored [blob]; null for a blob this build cannot read (TI §10.2). */
    fun reason(blob: ByteArray?): Reason? {
        if (blob == null || blob.size < SCORES_END) return null
        val first = blob[0].toInt() and 0xff
        val scores = (1 until SCORES_END).fold(0L) { word, i -> (word shl 8) or (blob[i].toLong() and 0xff) }
        var delta: Long? = null
        var end = SCORES_END
        if (first and 0x80 != 0) {
            var zigzag = 0L
            var shift = 0
            while (true) {
                if (end == blob.size || shift >= Long.SIZE_BITS) return null
                val byte = blob[end++].toInt() and 0xff
                zigzag = zigzag or ((byte and 0x7f).toLong() shl shift)
                shift += 7
                if (byte and 0x80 == 0) break
            }
            delta = (zigzag ushr 1) xor -(zigzag and 1)
        }
        if (end != blob.size) return null
        val (fingerprint, title, artist) = listOf(20, 10, 0).map { shift ->
            when (val value = (scores shr shift).toInt() and NO_SCORE) {
                NO_SCORE -> null
                in 0..1_000 -> value / 1_000.0
                else -> return null
            }
        }
        return Reason(
            source = Source.entries.first { sourceCode(it) == first and 0x3 },
            tier = Tier.entries.first { tierCode(it) == (first shr 2) and 0x1 },
            foundBy = Source.entries.filterTo(LinkedHashSet()) { first and (1 shl (3 + sourceCode(it))) != 0 },
            fingerprintScore = fingerprint,
            durationDeltaMs = delta,
            title = title,
            artist = artist,
            disagreement = HintField.entries.filterTo(LinkedHashSet()) { scores and (1L shl disagreementBit(it)) != 0L },
        )
    }

    /** A score from 0 to 1 in thousandths, at the nearer end when outside; NO_SCORE for none. */
    private fun score(value: Double?): Long =
        value?.takeUnless { it.isNaN() }?.let { (it * 1_000).roundToInt().coerceIn(0, 1_000).toLong() } ?: NO_SCORE.toLong()

    private const val SCORES_END = 5
    private const val NO_SCORE = 1023

    private const val MBID_BYTES = 16
}

/** The release-level values of one `release_choice` (TI §10.2): parts by field, and the per-disc parts by disc. */
data class ReleaseValues(
    val values: Map<DecidedField, String?>,
    val discs: Map<Int, Map<DecidedField, String?>> = emptyMap(),
) {
    /**
     * These values with [other]'s discs added, when the two can share one row (everything but the discs equal and no disc
     * differing); null when they cannot.
     */
    fun merged(other: ReleaseValues): ReleaseValues? {
        if (values.present() != other.values.present()) return null
        val discs = LinkedHashMap(discs)
        for ((disc, parts) in other.discs) {
            val mine = discs[disc]
            if (mine == null) discs[disc] = parts else if (mine.present() != parts.present()) return null
        }
        return copy(discs = discs)
    }

    /** The parts of disc [disc] together with the others. */
    fun forDisc(disc: Int?): Map<DecidedField, String?> = values + disc?.let { discs[it] }.orEmpty()

    // Empty values are not stored, so values that differ only by them are equal.
    override fun equals(other: Any?): Boolean = other is ReleaseValues && values.present() == other.values.present() &&
        discs.mapValues { it.value.present() } == other.discs.mapValues { it.value.present() }

    override fun hashCode(): Int = values.present().hashCode()

    /** gzip-compressed JSON. */
    fun encode(): ByteArray {
        val json = JsonObject(
            values.present().mapKeys { it.key.stored }.mapValues { JsonPrimitive(it.value) } +
                (DISCS to JsonObject(discs.mapKeys { it.key.toString() }.mapValues { (_, parts) -> JsonObject(parts.present().mapKeys { it.key.stored }.mapValues { JsonPrimitive(it.value) }) })),
        )
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    companion object {
        private const val DISCS = "discs"

        fun decode(bytes: ByteArray): ReleaseValues {
            val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
            val json = Json.parseToJsonElement(text).jsonObject
            fun parts(obj: JsonObject) = obj.mapNotNull { (name, value) ->
                DecidedField.ofName(name)?.let { field -> field to (value as? JsonPrimitive)?.contentOrNull }
            }.toMap()
            val discs = (json[DISCS] as? JsonObject).orEmpty().mapNotNull { (disc, parts) ->
                disc.toIntOrNull()?.let { it to parts(parts.jsonObject) }
            }.toMap()
            return ReleaseValues(parts(JsonObject(json - DISCS)), discs)
        }

        /** The release-level parts of [parts], with the per-disc ones under [disc]. */
        fun of(parts: Map<DecidedField, String?>, disc: Int?): ReleaseValues {
            val release = parts.filterKeys { it.release && !it.perDisc && it != DecidedField.COVER }
            val perDisc = parts.filterKeys { it.perDisc }
            return ReleaseValues(release, if (disc == null || perDisc.isEmpty()) emptyMap() else mapOf(disc to perDisc))
        }

        /** Empty values are not stored, so two maps that differ only by them are equal. */
        private fun Map<DecidedField, String?>.present(): Map<DecidedField, String> =
            mapNotNull { (field, value) -> value?.takeIf { it.isNotEmpty() }?.let { field to it } }.toMap()
    }
}

/**
 * The "changed in the file" references of TI §7.1: a 4-byte hash per decided part whose value in the file differs from
 * the decided one.
 */
object FileReferences {

    /** CRC-32 of the value's UTF-8 text; an empty value is 0. */
    fun hash(value: String?): Int {
        if (value.isNullOrEmpty()) return 0
        return CRC32().apply { update(value.toByteArray(Charsets.UTF_8)) }.value.toInt()
    }

    /** The references of a location whose [file] values meet the [decided] ones: only the parts that differ. */
    fun of(decided: Map<DecidedField, String?>, file: Map<DecidedField, String?>): Map<DecidedField, Int> =
        decided.keys.filter { it != DecidedField.COVER }
            .mapNotNull { part -> hash(file[part]).takeIf { it != hash(decided[part]) }?.let { part to it } }
            .toMap()

    /**
     * A 4-byte mask of the parts whose file value was empty (bit `code - 1`), then a field code byte and a 4-byte hash
     * for each other part (TI §10.2): a file without tags keeps all its references in the mask.
     */
    fun encode(references: Map<DecidedField, Int>): ByteArray? {
        if (references.isEmpty()) return null
        val empty = references.filterValues { it == 0 }.keys
        val others = references.filterValues { it != 0 }.entries.sortedBy { it.key.code }
        val buffer = ByteBuffer.allocate(MASK_BYTES + others.size * ENTRY_BYTES)
        buffer.putInt(empty.fold(0) { mask, part -> mask or (1 shl (part.code - 1)) })
        for ((part, hash) in others) buffer.put(part.code.toByte()).putInt(hash)
        return buffer.array()
    }

    fun decode(bytes: ByteArray?): Map<DecidedField, Int> {
        if (bytes == null || bytes.size < MASK_BYTES) return emptyMap()
        val buffer = ByteBuffer.wrap(bytes)
        val result = LinkedHashMap<DecidedField, Int>()
        val mask = buffer.getInt()
        for (part in DecidedField.entries) if (mask and (1 shl (part.code - 1)) != 0) result[part] = 0
        while (buffer.remaining() >= ENTRY_BYTES) {
            val part = DecidedField.ofCode(buffer.get().toInt() and 0xFF)
            val hash = buffer.getInt()
            if (part != null) result[part] = hash
        }
        return result
    }

    /**
     * The decided parts whose value in the file changed: [winners] are the decided values that win now; a part's
     * reference is the location's own ([location]), else that of the decision it comes from ([ofDecision]), else the
     * hash of the decided value; [current] are the file's values now. The cover is never compared.
     */
    fun changed(
        winners: Map<DecidedField, Decided>,
        ofDecision: Map<Long, Map<DecidedField, Int>>,
        location: Map<DecidedField, Int>,
        current: Map<DecidedField, String?>,
    ): Set<DecidedField> = winners.filter { (part, decided) ->
        part != DecidedField.COVER &&
            hash(current[part]) != (location[part] ?: ofDecision[decided.decisionId]?.get(part) ?: hash(decided.value))
    }.keys

    private const val ENTRY_BYTES = 5
    private const val MASK_BYTES = 4
}
