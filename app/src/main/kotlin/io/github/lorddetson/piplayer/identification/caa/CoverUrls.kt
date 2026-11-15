package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.identification.musicbrainz.CoverArtArchiveDto
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a Cover Art Archive image belongs to, by its path segment. */
enum class CoverEntity(val path: String) {
    RELEASE("release"),

    /** The release group's front image: CAA picks it from one of the group's releases, so another edition. */
    RELEASE_GROUP("release-group"),
}

/** The front images Cover Art Archive serves (TI §5.2): three thumbnails and the uploaded original. */
enum class CoverSize(val imageName: String, val px: Int?) {
    /** Lists. */
    LIST("front-250", 250),

    /** The review, the cover picker and Now Playing. */
    DISPLAY("front-500", 500),

    /** Embedding into files. */
    EMBED("front-1200", 1200),

    /** The uploaded original, of any size. */
    ORIGINAL("front", null),
}

/**
 * Where a cover comes from: a release or release group by its plain MBID. [anotherEdition] marks a release group
 * image, shown in the review and the cover picker as taken from another edition (TI §5.2).
 */
data class CoverSource(val entity: CoverEntity, val mbid: String) {
    val anotherEdition: Boolean get() = entity == CoverEntity.RELEASE_GROUP
}

/** The addresses of Cover Art Archive images (TI §5.2). */
object CoverUrls {

    /**
     * The source of a release's cover: the release itself when its [flags] show a usable front image (front, not
     * darkened); otherwise its release group, or none without a usable group ID. Unknown flags (a release MBID from the
     * tags that was not looked up) ask the release itself, and CAA's 404 answers a release without a front image. An ID
     * that is not an MBID counts as missing, so nothing is sent for it.
     */
    fun source(releaseId: String?, releaseGroupId: String?, flags: CoverArtArchiveDto?): CoverSource? {
        val release = releaseId?.let(MusicBrainzClient::plainMbid)
        val group = releaseGroupId?.let(MusicBrainzClient::plainMbid)
        val releaseUsable = release != null && (flags == null || flags.front && !flags.darkened)
        return when {
            releaseUsable -> CoverSource(CoverEntity.RELEASE, release)
            group != null -> CoverSource(CoverEntity.RELEASE_GROUP, group)
            else -> null
        }
    }

    /** `{base}{entity}/{mbid}/{image}`; [base] is used as configured, never rewritten. */
    fun image(base: HttpUrl, source: CoverSource, size: CoverSize): HttpUrl = base.newBuilder()
        .addPathSegment(source.entity.path)
        .addPathSegment(source.mbid)
        .addPathSegment(size.imageName)
        .build()

    /** `{base}release/{mbid}`, the release index; null for an ID that is not an MBID. */
    fun releaseIndex(base: HttpUrl, releaseId: String): HttpUrl? {
        val id = MusicBrainzClient.plainMbid(releaseId) ?: return null
        return base.newBuilder().addPathSegment(CoverEntity.RELEASE.path).addPathSegment(id).build()
    }

    /**
     * An image address taken from MusicBrainz or Cover Art Archive JSON: `http://` becomes `https://` (Android blocks
     * cleartext), `https://` stays, and anything else, or text that is no address, gives null.
     */
    fun fromJson(text: String?): HttpUrl? {
        // toHttpUrlOrNull takes only http and https; a default port is dropped by the scheme change.
        val url = text?.trim()?.toHttpUrlOrNull() ?: return null
        return if (url.isHttps) url else url.newBuilder().scheme("https").build()
    }
}
