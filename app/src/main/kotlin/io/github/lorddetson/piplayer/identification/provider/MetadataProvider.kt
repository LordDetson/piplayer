package io.github.lorddetson.piplayer.identification.provider

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The kinds of item a provider can link to. */
enum class ItemKind { ACOUSTID_TRACK, RECORDING, RELEASE, RELEASE_GROUP, ARTIST }

/**
 * One online service and the policy its terms set (TI §9): what PiPlayer may do with its data, what it must show, and
 * how often it may be asked. Decisions keep the provider's [id] as provenance (TI §7.1).
 */
interface MetadataProvider {
    val id: ProviderId

    /** The host every request starts at; redirects to other hosts stay inside the same call. */
    val host: String

    /** Whether the terms allow use in a commercial product. */
    val commercialAllowed: Boolean

    /** Whether answers may be kept on the device (caches and decisions). */
    val mayPersist: Boolean

    /** Whether its data may be written into the user's files (TI §7.2, §9: CC0 core fields and covers only). */
    val mayWriteToFile: Boolean

    /** How long fetched data may be shown before it must be fetched again; null when the terms set no limit. */
    val maxDisplayAge: Duration?

    /** Whether requests need an application key. Keys shipped in the APK are not secrets (TI §9). */
    val requiresSecret: Boolean

    /** The attribution line for About (M5) and the details screen. */
    val attribution: String

    /** The service's own page for one item, or null when it has none for that kind. */
    fun itemLink(kind: ItemKind, id: String): String?

    /** The shortest gap between two requests of this process (TI §8); one limiter per provider. */
    val minInterval: Duration
}

/** AcoustID and Chromaprint: fingerprints in, AcoustID tracks with MusicBrainz recording IDs out (TI §3.2). */
object AcoustIdProvider : MetadataProvider {
    override val id = ProviderId.ACOUSTID
    override val host = "api.acoustid.org"

    /** The free Open Source plan covers a free app without ads or paid features, not a commercial one. */
    override val commercialAllowed = false
    override val mayPersist = true

    /** Nothing of AcoustID's own goes into files; its track ID stays in the user database (TI §10.2). */
    override val mayWriteToFile = false
    override val maxDisplayAge: Duration? = null
    override val requiresSecret = true
    override val attribution = "Identification: AcoustID / Chromaprint"

    override fun itemLink(kind: ItemKind, id: String): String? =
        if (kind == ItemKind.ACOUSTID_TRACK) "https://acoustid.org/track/$id" else null

    override val minInterval = AcoustIdClient.MIN_INTERVAL
}

/** MusicBrainz ws/2: recordings, releases and their details (TI §3.3, §4, §5.1). */
object MusicBrainzProvider : MetadataProvider {
    override val id = ProviderId.MUSICBRAINZ
    override val host = "musicbrainz.org"

    /** Core data is CC0; tags and genres (CC BY-NC-SA) are never requested. */
    override val commercialAllowed = true
    override val mayPersist = true
    override val mayWriteToFile = true
    override val maxDisplayAge: Duration? = null
    override val requiresSecret = false
    override val attribution = "Metadata: MusicBrainz (CC0)"

    override fun itemLink(kind: ItemKind, id: String): String? = when (kind) {
        ItemKind.RECORDING -> "https://musicbrainz.org/recording/$id"
        ItemKind.RELEASE -> "https://musicbrainz.org/release/$id"
        ItemKind.RELEASE_GROUP -> "https://musicbrainz.org/release-group/$id"
        ItemKind.ARTIST -> "https://musicbrainz.org/artist/$id"
        ItemKind.ACOUSTID_TRACK -> null
    }

    /** One request per second per IP, with a margin (TI §8, §9). */
    override val minInterval = 1_100.milliseconds
}

/** Cover Art Archive: front images of releases and release groups (TI §5.2). */
object CoverArtArchiveProvider : MetadataProvider {
    override val id = ProviderId.COVER_ART_ARCHIVE

    /** Image requests are redirected to archive.org; those hops belong to the same call and the same limiter slot. */
    override val host = "coverartarchive.org"

    /** The images stay copyrighted by their rights holders ("use at your own risk"). */
    override val commercialAllowed = false
    override val mayPersist = true

    /** Accepted covers are embedded as TI §5.2 describes. */
    override val mayWriteToFile = true
    override val maxDisplayAge: Duration? = null
    override val requiresSecret = false
    override val attribution = "Covers: Cover Art Archive"

    override fun itemLink(kind: ItemKind, id: String): String? = when (kind) {
        ItemKind.RELEASE -> "https://musicbrainz.org/release/$id/cover-art"
        ItemKind.RELEASE_GROUP -> "https://musicbrainz.org/release-group/$id/cover-art"
        else -> null
    }

    /** No documented limit; one request per second keeps lazy list covers from bursting (TI §8). */
    override val minInterval = 1_000.milliseconds
}

/** Every provider of identification, in the order of the attribution line (TI §9). */
val identificationProviders: List<MetadataProvider> = listOf(AcoustIdProvider, MusicBrainzProvider, CoverArtArchiveProvider)
