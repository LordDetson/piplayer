package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.MediumDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseGroupDto
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures.browse
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures.release
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** TI §4.2 step 2 and the §11.1 M3 "Release ranking" row. */
class ReleaseRankingTest {

    private fun ids(releases: List<RankedRelease>) = releases.map { it.release.id }

    // Key 3: the full type order.

    @ParameterizedTest(name = "{0} + [{1}] ranks {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "Album|''|1",
            "Single|''|2",
            "EP|''|3",
            "Album|Soundtrack|4",
            "Single|Soundtrack|4",
            "EP|Soundtrack|4",
            "Broadcast|Soundtrack|5",
            "Broadcast|''|5",
            "Other|''|5",
            "Album|Remix|6",
            "EP|Mixtape/Street|6",
            "Single|Demo|6",
            "Album|Live|7",
            "Album|Compilation|8",
            "''|Compilation|8",
            "Album|DJ-mix|9",
            "Album|Spokenword|10",
            "Album|Interview|10",
            "Album|Audiobook|10",
            "Other|Audio drama|10",
            "Album|Field recording|10",
            "''|''|10",
            "''|Soundtrack|10",
            "Album|Compilation,Soundtrack|8",
            "Album|Live,Remix|7",
            "Album|Live,DJ-mix|9",
            "Album|Something new|10",
        ],
    )
    fun `type rank`(primary: String, secondary: String, rank: Int) {
        val group = ReleaseGroupDto(
            id = "group",
            primaryType = primary.ifEmpty { null },
            secondaryTypes = secondary.split(',').filter { it.isNotEmpty() },
        )
        assertThat(ReleaseRanking.typeRank(group)).isEqualTo(rank)
    }

    @Test
    fun `a release without a release group ranks as one without a type`() {
        assertThat(ReleaseRanking.typeRank(null)).isEqualTo(10)
    }

    @Test
    fun `the full type order decides between otherwise equal releases`() {
        val releases = listOf(
            release("j", primary = null),
            release("i", secondary = listOf("DJ-mix")),
            release("h", secondary = listOf("Compilation")),
            release("g", secondary = listOf("Live")),
            release("f", secondary = listOf("Remix")),
            release("e", primary = "Broadcast"),
            release("d", secondary = listOf("Soundtrack")),
            release("c", primary = "EP"),
            release("b", primary = "Single"),
            release("a"),
        )
        assertThat(ids(ReleaseRanking.rank(releases, ReleaseContext())))
            .containsExactly("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
    }

    // Key 4: format.

    @ParameterizedTest
    @CsvSource("CD,true", "Digital Media,true", "SHM-CD,true", "Enhanced CD,true", "8cm CD,true", "HDCD,true",
        "Blu-spec CD,true", "Copy Control CD,true", "Vinyl,false", "12\" Vinyl,false", "Cassette,false",
        "DVD-Audio,false", "SACD,false", "''," + "false")
    fun `CD and its variants count as CD, like Digital Media`(format: String, expected: Boolean) {
        assertThat(ReleaseRanking.isCdOrDigital(format.ifEmpty { null })).isEqualTo(expected)
    }

    @Test
    fun `Digital Media and CD rank equal, so the date decides, and both rank ahead of vinyl`() {
        val releases = listOf(
            release("vinyl", format = "12\" Vinyl", date = "1975"),
            release("cd", format = "CD", date = "1990"),
            release("digital", format = "Digital Media", date = "1985"),
        )
        assertThat(ids(ReleaseRanking.rank(releases, ReleaseContext()))).containsExactly("digital", "cd", "vinyl")
    }

    @Test
    fun `the first medium's format counts`() {
        val combo = release("combo").copy(media = listOf(MediumDto(1, format = "DVD-Video"), MediumDto(2, format = "CD")))
        val cd = release("cd", date = "2010")
        assertThat(ids(ReleaseRanking.rank(listOf(combo, cd), ReleaseContext()))).containsExactly("cd", "combo")
    }

    // Key 5: country.

    @Test
    fun `the user's country comes first, then worldwide, then the others, from every release event`() {
        val releases = listOf(
            release("de", countries = listOf("DE"), date = "1970"),
            release("xw", countries = listOf("XW"), date = "1990"),
            release("gb-too", countries = listOf("XE", "BY"), date = "2000"),
            release("none", countries = emptyList(), date = "1960"),
        )
        assertThat(ids(ReleaseRanking.rank(releases, ReleaseContext(country = "BY"))))
            .containsExactly("gb-too", "xw", "none", "de")
        assertThat(ids(ReleaseRanking.rank(releases, ReleaseContext(country = null))))
            .containsExactly("xw", "none", "de", "gb-too")
    }

    // Key 6: date, then MBID.

    @Test
    fun `earlier dates first, undated releases last, then the MBID`() {
        val releases = listOf(
            release("d", date = null),
            release("c", date = "1999-02-01"),
            release("b", date = "1999"),
            release("a", date = "2005"),
            release("e", date = ""),
            release("f", date = "1999"),
        )
        assertThat(ids(ReleaseRanking.rank(releases, ReleaseContext()))).containsExactly("b", "f", "c", "a", "d", "e")
    }

    // Keys 1 and 2.

    @Test
    fun `a release confirmed in the same folder beats every other key`() {
        val releases = listOf(release("best"), release("folder", primary = null, format = "Cassette", date = null))
        val ranked = ReleaseRanking.rank(releases, ReleaseContext(confirmedInFolder = setOf("folder")))
        assertThat(ids(ranked)).containsExactly("folder", "best")
        assertThat(ranked.first().keys.folder).isZero()
    }

    @Test
    fun `agreeing with the album and track total tags comes next`() {
        val releases = listOf(
            release("plain", title = "Greatest Hits", trackCount = 18),
            release("album", title = "Harbour Lights", trackCount = 18),
            release("both", primary = "EP", title = "Harbor Lights", trackCount = 11),
            release("total", title = "Other", trackCount = 11, date = "2010"),
        )
        val context = ReleaseContext(albumTag = "Harbor Lights", trackTotalTag = 11)
        val ranked = ReleaseRanking.rank(releases, context)
        assertThat(ids(ranked)).containsExactly("both", "album", "total", "plain")
        assertThat(ranked.map { it.keys.tagAgreement }).containsExactly(0, 1, 1, 2)
    }

    @Test
    fun `the track total may match any medium of the release`() {
        val twoDiscs = release("two").copy(media = listOf(MediumDto(1, trackCount = 12), MediumDto(2, trackCount = 11)))
        val ranked = ReleaseRanking.rank(listOf(release("one", date = "1990"), twoDiscs), ReleaseContext(trackTotalTag = 11))
        assertThat(ids(ranked)).containsExactly("two", "one")
    }

    // Recorded fixtures.

    private val liveReleases = browse("release-browse-live.json").releases

    @Test
    fun `the live recording in Great Britain gets the UK CD of 1986`() {
        val ranked = ReleaseRanking.rank(liveReleases, ReleaseContext(country = "GB"))
        assertThat(ranked.first().release.id).startsWith("388e1d01")
        // The compilation ranks after every live album, whatever its country or format.
        assertThat(ranked.last().release.id).startsWith("3d481e02")
    }

    @Test
    fun `the live recording in Japan gets the CDs by date, the SHM-CD counted as a CD`() {
        val ranked = ReleaseRanking.rank(liveReleases, ReleaseContext(country = "JP"))
        assertThat(ids(ranked).take(3).map { it.take(8) }).containsExactly("eb42def3", "606867d4", "157cb7bb")
    }

    @Test
    fun `the live recording elsewhere gets the worldwide digital release, although undated`() {
        val ranked = ReleaseRanking.rank(liveReleases, ReleaseContext(country = "BY"))
        assertThat(ranked.first().release.id).startsWith("b663aa15")
        assertThat(ranked.first().release.date).isNullOrEmpty()
    }

    @Test
    fun `the folder hint puts a confirmed release first in the recorded list`() {
        val confirmed = liveReleases.single { it.id.startsWith("157cb7bb") }.id
        val ranked = ReleaseRanking.rank(liveReleases, ReleaseContext(country = "GB", confirmedInFolder = setOf(confirmed)))
        assertThat(ranked.first().release.id).isEqualTo(confirmed)
    }

    @Test
    fun `the best release of the studio recording is on the second page`() {
        val first = browse("release-browse-paging-1.json").releases
        val second = browse("release-browse-paging-2.json").releases
        val all = first + second

        assertThat(ReleaseRanking.rank(first, ReleaseContext(country = "GB")).first().release.id).startsWith("10117d2c")
        assertThat(ReleaseRanking.rank(all, ReleaseContext(country = "GB")).first().release.id).startsWith("3718806e")
        assertThat(ReleaseRanking.rank(all, ReleaseContext(country = "BR")).first().release.id).startsWith("0a1982c2")
        assertThat(second.map { it.id }).anyMatch { it.startsWith("3718806e") }
    }
}
