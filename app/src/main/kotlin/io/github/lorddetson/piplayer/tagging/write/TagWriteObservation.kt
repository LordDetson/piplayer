package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import java.security.MessageDigest
import kotlinx.serialization.json.Json

internal data class TagWriteObservation(
    val volume: String,
    val relativePath: String,
    val displayName: String,
    val size: Long,
    val modifiedSeconds: Long,
    val contentKey: ByteArray,
    val tagHash: ByteArray?,
)

internal fun AppliedChangeEntity.matchesWrite(observed: TagWriteObservation): Boolean {
    if (volumeName != observed.volume || relativePath != observed.relativePath || displayName != observed.displayName ||
        copySize != observed.size || !expectedContentKey.contentEquals(observed.contentKey) ||
        !tagHash.contentEquals(observed.tagHash)
    ) return false

    return when (state) {
        "pending", "streaming", "streamed" -> true
        "verified" -> observed.size == actualSize &&
            actualMtime?.let { Math.floorDiv(it, 1_000) } == observed.modifiedSeconds
        else -> false
    }
}

internal fun tagPropertiesHash(properties: Map<String, List<String>>): ByteArray {
    val ordered: Map<String, List<String>> = properties.toSortedMap()
    return MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(ordered).toByteArray())
}
