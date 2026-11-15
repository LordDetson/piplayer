package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl

/**
 * One image of a release in Cover Art Archive (TI §5.2), for the cover picker. Its addresses come from CAA's JSON and
 * point at the configured Cover Art Archive address ([CoverUrls.fromIndex]).
 *
 * @property thumbnails by their size in pixels (250, 500, 1200).
 */
data class CaaImage(
    val id: String,
    val front: Boolean,
    val types: List<String>,
    val comment: String,
    val approved: Boolean,
    val original: HttpUrl,
    val thumbnails: Map<Int, HttpUrl>,
) {
    /** The thumbnail of [px] pixels, or null when CAA has none of that size. */
    fun thumbnail(px: Int): HttpUrl? = thumbnails[px]
}

/**
 * The images of a release (the JSON of `release/{mbid}`), in CAA's order; images without a usable original left out,
 * as are thumbnails without a usable address. Addresses on hosts other than Cover Art Archive are not usable.
 */
data class CaaReleaseIndex(val images: List<CaaImage>) {

    /** The first image marked as the front. */
    val front: CaaImage? get() = images.firstOrNull { it.front }

    companion object {
        /**
         * Reads CAA's JSON, with its image addresses moved onto [base], the configured Cover Art Archive address
         * ([CoverUrls.fromIndex]); throws [IllegalArgumentException] for a body that is not it.
         */
        fun parse(json: String, base: HttpUrl): CaaReleaseIndex =
            CaaReleaseIndex(MusicBrainzJson.decodeFromString<IndexDto>(json).images.mapNotNull { it.toImage(base) })

        /** The thumbnail sizes CAA names, with the older names that stand for them when the numbered ones are missing. */
        private val THUMBNAIL_NAMES = listOf(250 to "small", 500 to "large", 1200 to null)
    }

    /** The JSON of `release/{mbid}`; unknown fields are ignored. */
    @Serializable
    private data class IndexDto(val images: List<ImageDto> = emptyList())

    /** One image; CAA has sent its ID both as a number and as a string. */
    @Serializable
    private data class ImageDto(
        val id: JsonPrimitive? = null,
        val front: Boolean = false,
        val approved: Boolean = true,
        val comment: String = "",
        val image: String? = null,
        val thumbnails: Map<String, String?> = emptyMap(),
        val types: List<String> = emptyList(),
    ) {
        fun toImage(base: HttpUrl): CaaImage? {
            val original = CoverUrls.fromIndex(image, base) ?: return null
            val thumbnails = THUMBNAIL_NAMES.mapNotNull { (px, older) ->
                val url = CoverUrls.fromIndex(thumbnails[px.toString()], base)
                    ?: older?.let { CoverUrls.fromIndex(thumbnails[it], base) }
                url?.let { px to it }
            }.toMap()
            return CaaImage(
                id = id?.contentOrNull.orEmpty(),
                front = front,
                types = types,
                comment = comment,
                approved = approved,
                original = original,
                thumbnails = thumbnails,
            )
        }
    }
}
