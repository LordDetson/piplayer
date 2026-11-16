package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.release.Credit
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.details.DetailRow
import io.github.lorddetson.piplayer.library.details.DetailValue
import io.github.lorddetson.piplayer.library.details.MusicBrainzKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.currentDetails
import io.github.lorddetson.piplayer.ui.identification.ReviewFixtures.harborDetails
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The diff of the review (TI §6, review screen A; §11.1 M3 "Review diff"), on the shared fixtures. */
class ReviewDiffTest {

    private fun lines(
        current: Map<ProtectedField, FieldValue> = ReviewDiff.current(currentDetails()),
        proposed: Map<ProtectedField, FieldValue> = ReviewDiff.proposed(harborDetails),
        protected: Set<ProtectedField> = emptySet(),
    ) = ReviewDiff.lines(current, proposed, protected)

    private fun List<DiffLine>.of(field: ProtectedField) = single { it.field == field }

    @Test
    fun `the new values are the chosen release's, credits as printed and lists as lists`() {
        val proposed = ReviewDiff.proposed(harborDetails)

        assertThat(proposed[ProtectedField.TITLE]).isEqualTo(FieldValue.Text("Paper Lanterns"))
        assertThat(proposed[ProtectedField.ARTIST]).isEqualTo(FieldValue.Text(harborDetails.trackArtist))
        assertThat(harborDetails.trackArtist).contains("The Quiet Harbors").contains("Ada Vale")
        assertThat(proposed[ProtectedField.ALBUM]).isEqualTo(FieldValue.Text("Harbor Lights"))
        assertThat(proposed[ProtectedField.ALBUM_ARTIST]).isEqualTo(FieldValue.Text("The Quiet Harbors"))
        assertThat(proposed[ProtectedField.DATE]).isEqualTo(FieldValue.Text("2019-05"))
        assertThat(proposed[ProtectedField.ORIGINAL_DATE]).isEqualTo(FieldValue.Text("2018-11-30"))
        assertThat(proposed[ProtectedField.TRACK_NUMBER]).isEqualTo(FieldValue.OfTotal(3, 11))
        assertThat(proposed[ProtectedField.DISC_NUMBER]).isEqualTo(FieldValue.OfTotal(1, 1))
        assertThat(proposed[ProtectedField.COMPILATION]).isEqualTo(FieldValue.Flag(false))
        assertThat(proposed[ProtectedField.LABEL]).isEqualTo(FieldValue.Text("Pier Records; Lantern Songs"))
        assertThat(proposed[ProtectedField.CATALOG_NUMBER]).isEqualTo(FieldValue.Text("PIER-001"))
        assertThat(proposed[ProtectedField.ISRC]).isEqualTo(FieldValue.Text("XXA001900001"))
        // The release has no barcode and the medium no title.
        assertThat(proposed).doesNotContainKeys(ProtectedField.BARCODE, ProtectedField.DISC_SUBTITLE, ProtectedField.COVER)
        val ids = proposed[ProtectedField.MUSICBRAINZ_IDS] as FieldValue.Ids
        assertThat(ids.ids).contains(harborDetails.recordingId, harborDetails.releaseId, harborDetails.releaseGroupId, harborDetails.trackId)
            .contains("22222222-2222-4222-8222-222222222222")
    }

    @Test
    fun `the old values are what track details show, track and disc with their totals and the IDs as one set`() {
        val details = currentDetails(
            artist = "Quiet Harbors",
            extra = listOf(
                DetailRow(DetailField.DISC, DetailValue.OfTotal(1, 2)),
                DetailRow(DetailField.COMPILATION, DetailValue.Flag(true)),
                DetailRow(DetailField.LABEL, DetailValue.Text("Pier")),
                ReviewFixtures.recordingIdRow("rec"),
                DetailRow(DetailField.ARTIST_ID, DetailValue.MusicBrainz(MusicBrainzKind.ARTIST, "art")),
                // Fields outside the list never show.
                DetailRow(DetailField.GENRE, DetailValue.Texts(listOf("Folk"))),
            ),
        )

        assertThat(ReviewDiff.current(details)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                ProtectedField.TITLE to FieldValue.Text("track_07"),
                ProtectedField.ARTIST to FieldValue.Text("Quiet Harbors"),
                ProtectedField.ALBUM to FieldValue.Text("Harbor Lights"),
                ProtectedField.TRACK_NUMBER to FieldValue.OfTotal(7, null),
                ProtectedField.DISC_NUMBER to FieldValue.OfTotal(1, 2),
                ProtectedField.COMPILATION to FieldValue.Flag(true),
                ProtectedField.LABEL to FieldValue.Text("Pier"),
                ProtectedField.MUSICBRAINZ_IDS to FieldValue.Ids(setOf("rec", "art")),
            ),
        )
        assertThat(ReviewDiff.current(null)).isEmpty()
    }

    @Test
    fun `one line per field with a value on either side, in the protected-fields list's order`() {
        val result = lines()

        assertThat(result.map { it.field }).containsExactly(
            ProtectedField.TITLE,
            ProtectedField.ARTIST,
            ProtectedField.ALBUM,
            ProtectedField.ALBUM_ARTIST,
            ProtectedField.DATE,
            ProtectedField.ORIGINAL_DATE,
            ProtectedField.TRACK_NUMBER,
            ProtectedField.DISC_NUMBER,
            ProtectedField.LABEL,
            ProtectedField.CATALOG_NUMBER,
            ProtectedField.ISRC,
            ProtectedField.MUSICBRAINZ_IDS,
        )
        // No compilation on either side, no barcode and no disc subtitle: no line.
        val title = result.of(ProtectedField.TITLE)
        assertThat(title.old).isEqualTo(FieldValue.Text("track_07"))
        assertThat(title.new).isEqualTo(FieldValue.Text("Paper Lanterns"))
        assertThat(title.ticked).isTrue()
        assertThat(title.unchanged).isFalse()
        assertThat(result.of(ProtectedField.ARTIST).old).isNull()
    }

    @Test
    fun `an equal pair says no change and starts ticked, so Apply confirms it`() {
        val album = lines().of(ProtectedField.ALBUM)

        assertThat(album.unchanged).isTrue()
        assertThat(album.ticked).isTrue()
    }

    @Test
    fun `a field the release leaves empty while the file has it starts unticked`() {
        val details = currentDetails(extra = listOf(DetailRow(DetailField.BARCODE, DetailValue.Text("0123456789012"))))

        val barcode = lines(current = ReviewDiff.current(details)).of(ProtectedField.BARCODE)

        assertThat(barcode.old).isEqualTo(FieldValue.Text("0123456789012"))
        assertThat(barcode.new).isNull()
        assertThat(barcode.unchanged).isFalse()
        assertThat(barcode.ticked).isFalse()
    }

    @Test
    fun `track and disc compare with their totals`() {
        val sameNumber = mapOf(ProtectedField.TRACK_NUMBER to FieldValue.OfTotal(3, null))
        val proposed = mapOf(ProtectedField.TRACK_NUMBER to FieldValue.OfTotal(3, 11))

        val track = ReviewDiff.lines(sameNumber, proposed, emptySet()).single()

        assertThat(track.unchanged).isFalse()
        assertThat(track.ticked).isTrue()
        val same = ReviewDiff.lines(proposed, proposed, emptySet()).single()
        assertThat(same.unchanged).isTrue()
    }

    @Test
    fun `the compilation flag counts off as no value`() {
        val off = mapOf(ProtectedField.COMPILATION to FieldValue.Flag(false))
        val on = mapOf(ProtectedField.COMPILATION to FieldValue.Flag(true))

        assertThat(ReviewDiff.lines(emptyMap(), off, emptySet())).isEmpty()
        val turnedOn = ReviewDiff.lines(emptyMap(), on, emptySet()).single()
        assertThat(turnedOn.ticked).isTrue()
        assertThat(turnedOn.unchanged).isFalse()
        // Off is a value of the release: a file marked as a compilation changes, ticked.
        val turnedOff = ReviewDiff.lines(on, off, emptySet()).single()
        assertThat(turnedOff.unchanged).isFalse()
        assertThat(turnedOff.ticked).isTrue()
    }

    @Test
    fun `the MusicBrainz IDs compare as one set, whatever their order`() {
        val proposed = ReviewDiff.proposed(harborDetails)
        val sameIds = ReviewDiff.lines(proposed, proposed, emptySet()).of(ProtectedField.MUSICBRAINZ_IDS)
        val reordered = (proposed[ProtectedField.MUSICBRAINZ_IDS] as FieldValue.Ids).ids.reversed().toSet()

        assertThat(sameIds.unchanged).isTrue()
        assertThat(sameValue(FieldValue.Ids(reordered), proposed[ProtectedField.MUSICBRAINZ_IDS])).isTrue()
    }

    @Test
    fun `a protected field has no line`() {
        val result = lines(protected = setOf(ProtectedField.TITLE, ProtectedField.LABEL))

        assertThat(result.map { it.field }).doesNotContain(ProtectedField.TITLE, ProtectedField.LABEL).contains(ProtectedField.ARTIST)
    }

    @Test
    fun `Apply takes the ticked lines and the cover only when its line is ticked`() = runTest {
        val result = lines().map { if (it.field == ProtectedField.ALBUM) it.copy(ticked = false) else it }
        val cover = ReviewDiff.coverLine(ShownCover.None, ReviewFixtures.cover())!!

        assertThat(ReviewDiff.fields(result, cover)).contains(ProtectedField.TITLE, ProtectedField.COVER).doesNotContain(ProtectedField.ALBUM)
        assertThat(ReviewDiff.fields(result, cover.copy(ticked = false))).doesNotContain(ProtectedField.COVER)
        assertThat(ReviewDiff.fields(result, null)).doesNotContain(ProtectedField.COVER)
    }

    @Test
    fun `the cover shown now comes from the source and size track details show`() {
        assertThat(ReviewDiff.shownCover(currentDetails(coverSource = CoverSource.EMBEDDED, coverWidth = 600)))
            .isEqualTo(ShownCover.Known(600, 600, 48_000))
        assertThat(ReviewDiff.shownCover(currentDetails(coverSource = CoverSource.SONG_OWN, coverWidth = 300))).isEqualTo(ShownCover.Own(song = true))
        assertThat(ReviewDiff.shownCover(currentDetails(coverSource = CoverSource.ALBUM_OWN, coverWidth = 300))).isEqualTo(ShownCover.Own(song = false))
        assertThat(ReviewDiff.shownCover(currentDetails(coverSource = CoverSource.THUMBNAIL, coverWidth = null))).isEqualTo(ShownCover.Thumbnail)
        assertThat(ReviewDiff.shownCover(currentDetails(coverSource = CoverSource.NONE, coverWidth = null))).isEqualTo(ShownCover.None)
        assertThat(ReviewDiff.shownCover(null)).isEqualTo(ShownCover.None)
    }

    @Test
    fun `the cover line starts by the size rule`() = runTest {
        val image = ReviewFixtures.cover()
        val px = image.size!!.width

        assertThat(ReviewDiff.coverLine(ShownCover.None, image)!!.ticked).isTrue()
        assertThat(ReviewDiff.coverLine(ShownCover.Thumbnail, image)!!.ticked).isTrue()
        assertThat(ReviewDiff.coverLine(ShownCover.Known(px - 1, px - 1, null), image)!!.ticked).isTrue()
        assertThat(ReviewDiff.coverLine(ShownCover.Known(px, px, null), image)!!.ticked).isTrue()
        assertThat(ReviewDiff.coverLine(ShownCover.Known(px + 1, px + 1, null), image)!!.ticked).isFalse()
    }

    @Test
    fun `under an own cover the line says it stays on top and starts unticked`() = runTest {
        for (song in listOf(true, false)) {
            val line = ReviewDiff.coverLine(ShownCover.Own(song), ReviewFixtures.cover())!!
            assertThat(line.ticked).isFalse()
            assertThat(line.ownStaysOnTop).isTrue()
        }
        assertThat(ReviewDiff.coverLine(ShownCover.None, ReviewFixtures.cover())!!.ownStaysOnTop).isFalse()
    }

    @Test
    fun `no proposed image or a protected cover gives no cover line`() = runTest {
        assertThat(ReviewDiff.coverLine(ShownCover.None, null)).isNull()
        assertThat(ReviewDiff.coverLine(ShownCover.None, ReviewFixtures.cover(), setOf(ProtectedField.COVER))).isNull()
    }

    @Test
    fun `an image without its source has no cover line, since Apply could not store it`() = runTest {
        val image = ReviewFixtures.cover()
        val sourceless = CoverImage(
            image.bytes, image.format, image.size, image.requested, image.url, image.original, source = null,
        )

        assertThat(ReviewDiff.coverLine(ShownCover.None, sourceless)).isNull()
    }

    @Test
    fun `a release group image is marked as from another edition`() = runTest {
        val line = ReviewDiff.coverLine(ShownCover.None, ReviewFixtures.cover(CoverEntity.RELEASE_GROUP))!!

        assertThat(line.new.anotherEdition).isTrue()
    }

    @Test
    fun `labels, catalog numbers and ISRCs join their items`() {
        val details = harborDetails.copy(
            labels = emptyList(),
            catalogNumbers = listOf("A-1", "B-2"),
            isrcs = listOf("XXA001900001", "XXA001900002"),
            compilation = true,
            releaseCredits = listOf(Credit("Various Artists", "Various Artists", "89ad4ac3-39f7-470e-963a-56509c546377", "Various Artists", "")),
        )

        val proposed = ReviewDiff.proposed(details)

        assertThat(proposed).doesNotContainKey(ProtectedField.LABEL)
        assertThat(proposed[ProtectedField.CATALOG_NUMBER]).isEqualTo(FieldValue.Text("A-1; B-2"))
        assertThat(proposed[ProtectedField.ISRC]).isEqualTo(FieldValue.Text("XXA001900001; XXA001900002"))
        assertThat(proposed[ProtectedField.COMPILATION]).isEqualTo(FieldValue.Flag(true))
        assertThat(proposed[ProtectedField.ALBUM_ARTIST]).isEqualTo(FieldValue.Text("Various Artists"))
    }
}
