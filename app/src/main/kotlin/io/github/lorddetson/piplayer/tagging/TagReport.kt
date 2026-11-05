package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.nativelib.taglib.AudioProperties
import io.github.lorddetson.piplayer.nativelib.taglib.Picture
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import kotlinx.serialization.Serializable

/**
 * What `:tagger` found in a file, as it crosses to the main process (docs/library.md §4.2): TagLib's property map, the
 * audio properties and a summary of each embedded picture, never its bytes; or why TagLib could not read the file.
 */
@Serializable
data class TagReport(
    val error: TagReadError? = null,
    val properties: Map<String, List<String>> = emptyMap(),
    val audio: Audio? = null,
    val pictures: List<PictureInfo> = emptyList(),
    val content: Content? = null,
) {

    /** The content key in hex, its format token and how far the file's header settles its length (TI §3.1). */
    @Serializable
    data class Content(val key: String, val token: String, val duration: DurationPlan)

    @Serializable
    data class Audio(val lengthMs: Int, val bitrateKbps: Int, val sampleRate: Int, val channels: Int)

    /** An embedded picture without its bytes; [pictureType] uses the ID3v2 names, such as "Front Cover". */
    @Serializable
    data class PictureInfo(val pictureType: String, val mimeType: String, val description: String, val sizeBytes: Int)

    /** The report as the M0 reader's result, with pictures that carry their size but no bytes. */
    fun toResult(): TagReadResult {
        if (error != null) return TagReadResult.Failure(error)
        return TagReadResult.Success(
            properties = properties,
            audio = audio?.let { AudioProperties(it.lengthMs, it.bitrateKbps, it.sampleRate, it.channels) },
            pictures = pictures.map { Picture(null, it.sizeBytes, it.mimeType, it.pictureType, it.description) },
        )
    }

    companion object {
        fun of(result: TagReadResult): TagReport = when (result) {
            is TagReadResult.Failure -> TagReport(error = result.error)
            is TagReadResult.Success -> TagReport(
                properties = result.properties,
                audio = result.audio?.let { Audio(it.lengthMs, it.bitrateKbps, it.sampleRate, it.channels) },
                pictures = result.pictures.map { PictureInfo(it.pictureType, it.mimeType, it.description, it.sizeBytes) },
            )
        }
    }
}
