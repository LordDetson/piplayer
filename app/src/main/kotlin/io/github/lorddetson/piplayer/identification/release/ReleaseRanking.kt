package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseGroupDto
import io.github.lorddetson.piplayer.identification.text.Similarity
import java.util.Locale

/**
 * What the file tells the release choice (TI §4.2): its tags, its exact duration, the user's country and the releases
 * already confirmed for other files of its folder.
 *
 * @property country the user's country from the locale, as an ISO 3166-1 code; null when the locale has none.
 */
data class ReleaseContext(
    val albumTag: String? = null,
    val trackTotalTag: Int? = null,
    val trackNumberTag: Int? = null,
    val durationMs: Long? = null,
    val country: String? = null,
    val confirmedInFolder: Set<String> = emptySet(),
)

/**
 * The ranking keys of one release (TI §4.2 step 2), each the lower the better.
 *
 * @property folder 0 for a release confirmed in the same folder, 1 otherwise.
 * @property tagAgreement 0 when album and track total agree, 1 when one does, 2 when none does.
 * @property type the release type rank, 1 to 10.
 * @property format 0 for CD or Digital Media, 1 for any other format.
 * @property country 0 for the user's country, 1 for worldwide, 2 for any other.
 */
data class RankKeys(val folder: Int, val tagAgreement: Int, val type: Int, val format: Int, val country: Int)

data class RankedRelease(val release: ReleaseDto, val keys: RankKeys)

/** The release (edition) ranking of TI §4.2 step 2: pure, over every release a browse returned. */
object ReleaseRanking {

    /** An album tag at least this similar to the release title agrees with it. */
    const val ALBUM_MATCH = 0.9

    private const val WORLDWIDE = "XW"
    private const val NO_TYPE = 10
    private const val SOUNDTRACK = 4

    private val primaryRanks = mapOf("album" to 1, "single" to 2, "ep" to 3, "broadcast" to 5, "other" to 5)

    private val secondaryRanks = mapOf(
        "soundtrack" to SOUNDTRACK,
        "remix" to 6,
        "mixtape/street" to 6,
        "demo" to 6,
        "live" to 7,
        "compilation" to 8,
        "dj-mix" to 9,
        "spokenword" to 10,
        "interview" to 10,
        "audiobook" to 10,
        "audio drama" to 10,
        "field recording" to 10,
    )

    /** CD and the MusicBrainz formats derived from it, which rank as CD (TI §4.2 step 2 key 4). */
    private val cdFormats = setOf(
        "cd", "cd-r", "8cm cd", "blu-spec cd", "copy control cd", "data cd", "dts cd", "enhanced cd", "hdcd", "hqcd",
        "shm-cd", "cd+g", "minimax cd", "mixed mode cd", "hybrid sacd (cd layer)", "sacd (cd layer)",
    )
    private const val DIGITAL_MEDIA = "digital media"

    private val comparator: Comparator<RankedRelease> = compareBy<RankedRelease>(
        { it.keys.folder },
        { it.keys.tagAgreement },
        { it.keys.type },
        { it.keys.format },
        { it.keys.country },
    )
        // Undated releases last; dates as written, so a year alone comes before full dates of that year.
        .thenBy { it.release.date.isNullOrBlank() }
        .thenBy { it.release.date.orEmpty() }
        .thenBy { it.release.id }

    /** Releases best first; equal keys fall to the date (undated last) and then to the release MBID. */
    fun rank(releases: List<ReleaseDto>, context: ReleaseContext): List<RankedRelease> =
        releases.map { RankedRelease(it, keys(it, context)) }.sortedWith(comparator)

    fun keys(release: ReleaseDto, context: ReleaseContext): RankKeys = RankKeys(
        folder = if (release.id in context.confirmedInFolder) 0 else 1,
        tagAgreement = 2 - agreements(release, context),
        type = typeRank(release.releaseGroup),
        format = if (isCdOrDigital(release.media.firstOrNull()?.format)) 0 else 1,
        country = countryRank(release, context.country),
    )

    /**
     * The type rank of TI §4.2 step 2 key 3, from 1 (album) to 10 (spoken word or no type). With secondary types the
     * lowest-ranked one counts, whatever the primary type (none included); Soundtrack ranks as the primary type alone,
     * but never ahead of an album soundtrack (4). An unknown secondary type ranks last.
     */
    fun typeRank(group: ReleaseGroupDto?): Int {
        val primary = group?.primaryType?.lowercase(Locale.ROOT)?.let(primaryRanks::get) ?: NO_TYPE
        val secondary = group?.secondaryTypes.orEmpty()
        if (secondary.isEmpty()) return primary
        return secondary.maxOf { type ->
            when (val rank = secondaryRanks[type.lowercase(Locale.ROOT)]) {
                null -> NO_TYPE
                SOUNDTRACK -> maxOf(SOUNDTRACK, primary)
                else -> rank
            }
        }
    }

    /** Whether [format] is Digital Media or a CD, which rank equal (TI §4.2 step 2 key 4). */
    fun isCdOrDigital(format: String?): Boolean {
        val name = format?.trim()?.lowercase(Locale.ROOT) ?: return false
        return name == DIGITAL_MEDIA || name in cdFormats
    }

    /** 0 when a release event is in [country], 1 when one is worldwide, 2 otherwise. */
    private fun countryRank(release: ReleaseDto, country: String?): Int {
        val codes = release.releaseEvents.flatMap { it.area?.isoCodes.orEmpty() }
            .ifEmpty { listOfNotNull(release.country) }
            .map { it.uppercase(Locale.ROOT) }
        return when {
            country != null && country.uppercase(Locale.ROOT) in codes -> 0
            WORLDWIDE in codes -> 1
            else -> 2
        }
    }

    /** How many of the album tag and the track total tag agree with the release (TI §4.2 step 2 key 2). */
    private fun agreements(release: ReleaseDto, context: ReleaseContext): Int {
        val album = context.albumTag?.takeIf { it.isNotBlank() }
            ?.let { Similarity.text(it, release.title) >= ALBUM_MATCH } == true
        val total = context.trackTotalTag?.let { tag -> release.media.any { it.trackCount == tag } } == true
        return (if (album) 1 else 0) + (if (total) 1 else 0)
    }
}
