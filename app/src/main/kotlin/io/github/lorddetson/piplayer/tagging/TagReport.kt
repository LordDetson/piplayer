package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.nativelib.taglib.AudioProperties
import io.github.lorddetson.piplayer.nativelib.taglib.LegacyId3Tag
import io.github.lorddetson.piplayer.nativelib.taglib.Picture
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * What `:tagger` found in a file, as it crosses to the main process (docs/library.md §4.2): TagLib's property map, the
 * audio properties and a summary of its embedded pictures; or why TagLib could not read the file.
 */
@Serializable
data class TagReport(
    val error: TagReadError? = null,
    val properties: Map<String, List<String>> = emptyMap(),
    val audio: Audio? = null,
    val pictures: List<PictureInfo> = emptyList(),
    val container: String? = null,
    val codec: String? = null,
    val bitDepth: Int? = null,
    val content: Content? = null,
    /** The major version of a leading ID3v2 header in an MP3. */
    val id3Version: Int? = null,
    /** Front pictures read for a write. Null when omitted or any front image could not be read in full. */
    val frontCovers: List<TagArtwork>? = null,
    val legacyId3: LegacyId3? = null,
    val mpegId3v2Properties: Map<String, List<String>>? = null,
) {

    /** The content key in hex, its format token and how far the file's header settles its length (TI §3.1). */
    @Serializable
    data class Content(val key: String, val token: String, val duration: DurationPlan)

    @Serializable
    data class Audio(val lengthMs: Int, val bitrateKbps: Int, val sampleRate: Int, val channels: Int)

    /** An embedded picture without its bytes; [pictureType] uses the ID3v2 names, such as "Front Cover". */
    @Serializable
    data class PictureInfo(val pictureType: String, val mimeType: String, val description: String, val sizeBytes: Int)

    /** Legacy ID3v1 bytes and the properties still visible if that tag is removed. */
    @Serializable
    data class LegacyId3(val rawTag: String, val otherProperties: Map<String, List<String>>)

    /** The report as the M0 reader's result, with pictures that carry their size but no bytes. */
    fun toResult(): TagReadResult {
        if (error != null) return TagReadResult.Failure(error)
        return TagReadResult.Success(
            properties = properties,
            audio = audio?.let { AudioProperties(it.lengthMs, it.bitrateKbps, it.sampleRate, it.channels) },
            pictures = pictures.map { Picture(null, it.sizeBytes, it.mimeType, it.pictureType, it.description) },
            container = container,
            codec = codec,
            bitDepth = bitDepth,
            legacyId3Tag = legacyId3?.let { LegacyId3Tag(Base64.getDecoder().decode(it.rawTag), it.otherProperties) },
            mpegId3v2Properties = mpegId3v2Properties,
        )
    }

    companion object {
        fun of(result: TagReadResult): TagReport = when (result) {
            is TagReadResult.Failure -> TagReport(error = result.error)
            is TagReadResult.Success -> TagReport(
                properties = result.properties,
                audio = result.audio?.let { Audio(it.lengthMs, it.bitrateKbps, it.sampleRate, it.channels) },
                pictures = result.pictures.map { PictureInfo(it.pictureType, it.mimeType, it.description, it.sizeBytes) },
                container = result.container,
                codec = result.codec,
                bitDepth = result.bitDepth,
                legacyId3 = result.legacyId3Tag?.let { LegacyId3(Base64.getEncoder().encodeToString(it.bytes), it.otherProperties) },
                mpegId3v2Properties = result.mpegId3v2Properties,
            )
        }
    }
}
