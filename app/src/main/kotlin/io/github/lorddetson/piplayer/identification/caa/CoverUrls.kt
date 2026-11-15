package io.github.lorddetson.piplayer.identification.caa

import io.github.lorddetson.piplayer.identification.musicbrainz.CoverArtArchiveDto
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.provider.CoverArtArchiveProvider
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

    /** The first of [sources]: the source asked for first. */
    fun source(releaseId: String?, releaseGroupId: String?, flags: CoverArtArchiveDto?): CoverSource? =
        sources(releaseId, releaseGroupId, flags).firstOrNull()

    /**
     * The sources of a release's cover, in the order they are asked for ([CoverArtArchiveClient.cover]): the release
     * itself when its [flags] show a usable front image (front, not darkened) or are not known (a release MBID from the
     * tags that was not looked up), then its release group, which is asked for when the release has no front image
     * after all. Without a usable front image only the release group is left; an ID that is not an MBID counts as
     * missing, so nothing is sent for it.
     */
    fun sources(releaseId: String?, releaseGroupId: String?, flags: CoverArtArchiveDto?): List<CoverSource> {
        val release = releaseId?.let(MusicBrainzClient::plainMbid)
        val group = releaseGroupId?.let(MusicBrainzClient::plainMbid)
        val releaseUsable = release != null && (flags == null || flags.front && !flags.darkened)
        return listOfNotNull(
            release?.takeIf { releaseUsable }?.let { CoverSource(CoverEntity.RELEASE, it) },
            group?.let { CoverSource(CoverEntity.RELEASE_GROUP, it) },
        )
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

    /**
     * An image address of the release index, sent only to Cover Art Archive (TI §5.2): on [base]'s own host and port it
     * keeps its path on [base]; on [CAA_HOST] it is moved onto [base], its path appended to the base's path; on any other
     * host, or with a scheme other than HTTP(S), it gives null. Either way it takes [base]'s scheme, so in the app,
     * whose base is CAA's HTTPS address, `http://` becomes `https://` as in [fromJson].
     */
    fun fromIndex(text: String?, base: HttpUrl): HttpUrl? {
        val url = text?.trim()?.toHttpUrlOrNull() ?: return null
        return when {
            url.host == base.host && url.port == base.port ->
                base.newBuilder().encodedPath(url.encodedPath).encodedQuery(url.encodedQuery).build()
            url.host == CAA_HOST -> base.newBuilder()
                .apply { url.pathSegments.filter { it.isNotEmpty() }.forEach(::addPathSegment) }
                .encodedQuery(url.encodedQuery)
                .build()
            else -> null
        }
    }

    /** Cover Art Archive's own host, which its JSON names in image addresses. */
    private val CAA_HOST = CoverArtArchiveProvider.host
}
