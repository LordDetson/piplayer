package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.nativelib.taglib.Picture
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An embedded front picture in the write request and in the rollback snapshot. */
@Serializable
data class TagArtwork(val mimeType: String, val description: String, val image: String) {
    fun bytes(): ByteArray = Base64.getDecoder().decode(image)
    internal fun picture(): Picture = bytes().let { Picture(it, it.size, mimeType, "Front Cover", description) }

    companion object {
        fun of(mimeType: String, description: String, bytes: ByteArray) =
            TagArtwork(mimeType, description, Base64.getEncoder().encodeToString(bytes))
    }
}

internal fun List<TagArtwork>.fingerprints(): List<String> = map { cover ->
    Hex.text(MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(cover).encodeToByteArray()))
}

@Serializable
internal data class TagFileEdits(
    val properties: Map<String, List<String>>,
    val id3v1Policy: Id3v1WritePolicy = Id3v1WritePolicy.UPDATE_IF_PRESENT,
    val frontCovers: List<TagArtwork>? = null,
)
