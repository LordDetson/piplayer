package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The cover URIs of controllers (docs/library.md §13.8.2). */
class CoverUrisTest {

    private var generation = 0L
    private val uris = CoverUris(AUTHORITY, tag = "k3x9", generation = { generation })

    /** The authority and path segments of [uri], as `Uri` splits a URI of this form. */
    private fun split(uri: String): Pair<String, List<String>> {
        assertThat(uri).startsWith("content://")
        val rest = uri.removePrefix("content://")
        return rest.substringBefore('/') to rest.substringAfter('/').split('/')
    }

    private fun parse(uri: String): CoverRequest? = split(uri).let { (authority, segments) -> uris.parse(authority, segments) }

    @Test
    fun aSongGoesByItsFileIdAndTheVersion() {
        assertThat(uris.of(CoverRequest.Track(1234))).isEqualTo("content://$AUTHORITY/s/1234/k3x90000")
        generation = 36 * 36 + 35
        assertThat(uris.of(CoverRequest.Track(1234))).isEqualTo("content://$AUTHORITY/s/1234/k3x9010z")
    }

    @Test
    fun albumsAndArtistsGoByNumbersThatNameNoKey() {
        val album = uris.of(CoverRequest.Album("first\u0000ann\u0000/storage/emulated/0/Music/Ann/First/"))
        val artist = uris.of(CoverRequest.Artist("ann"))
        val other = uris.of(CoverRequest.Album("second"))

        assertThat(album).isEqualTo("content://$AUTHORITY/a/1/k3x90000")
        assertThat(artist).isEqualTo("content://$AUTHORITY/r/1/k3x90000")
        assertThat(other).isEqualTo("content://$AUTHORITY/a/2/k3x90000")
        // The same key keeps its number.
        assertThat(uris.of(CoverRequest.Album("first\u0000ann\u0000/storage/emulated/0/Music/Ann/First/"))).isEqualTo(album)
        assertThat(listOf(album, artist, other)).allSatisfy { assertThat(it).doesNotContain("ann", "Music", "first") }
    }

    @Test
    fun everyUriParsesBackToWhatItNames() {
        val requests = listOf(
            CoverRequest.Track(1),
            CoverRequest.Track(Long.MAX_VALUE),
            CoverRequest.Album("a/b%c d"),
            CoverRequest.Artist("Ёлкин Бор"),
            CoverRequest.Album(""),
        )
        for (request in requests) assertThat(parse(uris.of(request))).describedAs(request.toString()).isEqualTo(request)
    }

    @Test
    fun aUriOfAnOlderGenerationStillNamesItsSubject() {
        val song = uris.of(CoverRequest.Track(7))
        val album = uris.of(CoverRequest.Album("x"))
        generation = 5

        assertThat(uris.of(CoverRequest.Track(7))).isNotEqualTo(song)
        assertThat(parse(song)).isEqualTo(CoverRequest.Track(7))
        assertThat(parse(album)).isEqualTo(CoverRequest.Album("x"))
    }

    @Test
    fun anotherProcessGivesOtherNumbersSoItsAlbumsAndArtistsAreNotFoundButItsSongsAre() {
        val before = CoverUris(AUTHORITY, tag = "aaaa", generation = { 0 })
        val album = before.of(CoverRequest.Album("x"))
        val artist = before.of(CoverRequest.Artist("y"))
        val song = before.of(CoverRequest.Track(7))
        uris.of(CoverRequest.Album("z"))

        assertThat(parse(album)).isNull()
        assertThat(parse(artist)).isNull()
        assertThat(parse(song)).isEqualTo(CoverRequest.Track(7))
    }

    @Test
    fun aNumberThisProcessNeverGaveIsNotFound() {
        uris.of(CoverRequest.Album("x"))

        assertThat(parse("content://$AUTHORITY/a/2/k3x90")).isNull()
        assertThat(parse("content://$AUTHORITY/r/1/k3x90")).isNull()
    }

    @Test
    fun malformedUrisAndOtherAuthoritiesAreRefused() {
        uris.of(CoverRequest.Album("x"))
        val bad = listOf(
            "content://other.covers/s/1/k3x90",
            "content://$AUTHORITY/s/1",
            "content://$AUTHORITY/s/1/k3x90/more",
            "content://$AUTHORITY/x/1/k3x90",
            "content://$AUTHORITY/s/-1/k3x90",
            "content://$AUTHORITY/s/01/k3x90",
            "content://$AUTHORITY/s/1a/k3x90",
            "content://$AUTHORITY/s//k3x90",
            "content://$AUTHORITY/s/99999999999999999999/k3x90",
            "content://$AUTHORITY/s/1/K3X90",
            "content://$AUTHORITY/s/1/k3x9",
            "content://$AUTHORITY/s/1/",
            "content://$AUTHORITY/a/01/k3x90",
            "content://$AUTHORITY/a/0/k3x90",
            "content://$AUTHORITY/S/1/k3x90",
        )
        for (uri in bad) assertThat(parse(uri)).describedAs(uri).isNull()
        assertThat(uris.parse(null, listOf("s", "1", "k3x90"))).isNull()
        // The form alone, as getType asks it: the malformed ones fail, a number never given still has the form.
        val formed: (String) -> Boolean = { uri -> split(uri).let { (authority, segments) -> uris.wellFormed(authority, segments) } }
        for (uri in bad.filter { "/a/0/" !in it }) assertThat(formed(uri)).describedAs(uri).isFalse()
        assertThat(formed("content://$AUTHORITY/a/7/zzzz0000")).isTrue()
    }

    @Test
    fun aUriStaysShort() {
        // The weight of a browse item counts it (docs/playback.md §10.2).
        generation = Int.MAX_VALUE.toLong()
        val authority = CoverUris.authorityOf("io.github.lorddetson.piplayer")
        val real = CoverUris(authority, generation = { generation })
        assertThat(real.of(CoverRequest.Track(9_999_999_999))).hasSizeLessThanOrEqualTo(70)
        assertThat(real.of(CoverRequest.Album("x".repeat(500)))).hasSizeLessThanOrEqualTo(70)
        assertThat(authority).isEqualTo("io.github.lorddetson.piplayer.covers")
    }

    @Test
    fun aSaltedSongUriIsAnotherUriForTheSameSong() {
        val salted = uris.of(CoverRequest.Track(7), salt = "1z9")

        assertThat(salted).isEqualTo("content://$AUTHORITY/s/7/k3x900001z9").isNotEqualTo(uris.of(CoverRequest.Track(7)))
        assertThat(parse(salted)).isEqualTo(CoverRequest.Track(7))
        assertThatThrownBy { uris.of(CoverRequest.Album("x"), salt = "1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { uris.of(CoverRequest.Track(7), salt = "A/") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun theLengthIsKnownWithoutBuildingTheUri() {
        // The tree weighs 20 000 songs by the length alone (docs/playback.md §10.2).
        val requests = listOf(CoverRequest.Track(0), CoverRequest.Track(7), CoverRequest.Track(Long.MAX_VALUE), CoverRequest.Album("x"), CoverRequest.Artist("y"))
        for (g in listOf(0L, 35L, 36L * 36 * 36 * 36, Long.MAX_VALUE)) {
            generation = g
            for (request in requests) assertThat(uris.lengthOf(request)).describedAs("$request at $g").isEqualTo(uris.of(request).length)
        }
    }

    @Test
    fun aNewGenerationKeepsTheLengthSoThatBucketBordersStay() {
        // Items weigh their URIs (docs/playback.md §10.2): a URI one character longer could move a bucket's border.
        val lengths = listOf(0L, 1L, 35L, 36L, 1_295L, 1_296L, 36L * 36 * 36 * 36 - 1).map { g ->
            generation = g
            uris.of(CoverRequest.Track(1234)).length
        }
        assertThat(lengths.toSet()).hasSize(1)
    }

    @Test
    fun tagsAreFourCharactersOfDigitsAndSmallLetters() {
        val tags = (1..50).map { CoverUris.randomTag() }
        assertThat(tags).allSatisfy { assertThat(it).matches("[0-9a-z]{4}") }
        assertThat(tags.toSet().size).isGreaterThan(40)
        assertThatThrownBy { CoverUris(AUTHORITY, tag = "AB12", generation = { 0 }) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun numbersAreGivenOnceAlsoFromManyThreads() {
        val threads = (1..8).map { t ->
            Thread { repeat(500) { uris.of(CoverRequest.Album("album ${(it * 7 + t) % 300}")) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val numbers = (0 until 300).map { split(uris.of(CoverRequest.Album("album $it"))).second[1].toInt() }
        assertThat(numbers.toSet()).hasSize(300)
        assertThat(numbers.max()).isEqualTo(300)
    }

    private companion object {
        const val AUTHORITY = "io.github.lorddetson.piplayer.covers"
    }
}
