package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM
import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM_ARTIST
import io.github.lorddetson.piplayer.library.values.DecidedField.ALBUM_ARTIST_SORT
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST_CREDITS
import io.github.lorddetson.piplayer.library.values.DecidedField.ARTIST_SORT
import io.github.lorddetson.piplayer.library.values.DecidedField.COMPILATION
import io.github.lorddetson.piplayer.library.values.DecidedField.COVER
import io.github.lorddetson.piplayer.library.values.DecidedField.DATE
import io.github.lorddetson.piplayer.library.values.DecidedField.DISC_NUMBER
import io.github.lorddetson.piplayer.library.values.DecidedField.ORIGINAL_DATE
import io.github.lorddetson.piplayer.library.values.DecidedField.RECORDING_MBID
import io.github.lorddetson.piplayer.library.values.DecidedField.TITLE
import io.github.lorddetson.piplayer.library.values.DecidedField.TRACK_NUMBER
import io.github.lorddetson.piplayer.library.values.DecisionKind.APPLY
import io.github.lorddetson.piplayer.library.values.DecisionKind.EDIT
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_FILE_NAME
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_IDENTIFICATION
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_INDEX
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_TAGS
import io.github.lorddetson.piplayer.library.values.DecisionKind.Companion.LEVEL_USER_EDIT
import io.github.lorddetson.piplayer.settings.ProtectedField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The decided values in the merged values (TI §7.1, §1): every field with its level, the display, search and reason
 * columns, and the badge (TI §11.1 M3 "Decided values per field" and "Database-only undo").
 */
class DecidedMergeTest {

    private val tags = TrackTags.from(
        mapOf(
            "TITLE" to listOf("Tag title"),
            "ARTIST" to listOf("Tag artist"),
            "ARTISTSORT" to listOf("Artist, Tag"),
            "ALBUM" to listOf("Tag album"),
            "ALBUMARTIST" to listOf("Tag album artist"),
            "DATE" to listOf("1990"),
            "TRACKNUMBER" to listOf("3/9"),
            "DISCNUMBER" to listOf("1/1"),
            "LABEL" to listOf("Tag label"),
            "GENRE" to listOf("Rock"),
        ),
    )
    private val emptyTags = TrackTags.from(emptyMap())
    private val index = IndexValues(title = "Index title", artist = "Index artist", album = "Index album", year = 1985, trackNumber = 2004)

    private fun decided(level: Int, vararg values: Pair<DecidedField, String?>) =
        FieldOverrides(values.associate { (part, value) -> part to Decided(value, level, 7) })

    private fun identified(vararg values: Pair<DecidedField, String?>) = decided(LEVEL_IDENTIFICATION, *values)

    private fun merge(
        tags: TrackTags? = this.tags,
        read: Boolean = true,
        overrides: FieldOverrides = FieldOverrides.NONE,
        displayName: String = "05 Name Artist - Name Title.mp3",
        embeddedCover: Boolean = false,
    ) = MergedValues.of(displayName, index, tags, read, embeddedCover = embeddedCover, overrides = overrides)

    @Test
    fun `decided values win over the tags in the display columns`() {
        val values = merge(
            overrides = identified(
                TITLE to "Song", ARTIST to "Ann & Bob", ARTIST_CREDITS to DecidedField.credits(listOf("Ann" to " & ", "Bob" to "")),
                ARTIST_SORT to "Ann & Bob, sorted", ALBUM to "Record", ALBUM_ARTIST to "Ann", ALBUM_ARTIST_SORT to "Ann, sorted",
                DATE to "2001-05-04", TRACK_NUMBER to "7", DISC_NUMBER to "2", COMPILATION to "1",
            ),
        )

        assertThat(values.title).isEqualTo("Song")
        assertThat(values.artist).isEqualTo("Ann & Bob")
        assertThat(values.artists).containsExactly("Ann", "Bob")
        assertThat(values.album).isEqualTo("Record")
        assertThat(values.albumArtist).isEqualTo("Ann")
        assertThat(values.year).isEqualTo(2001)
        assertThat(values.trackNumber).isEqualTo(7)
        assertThat(values.discNumber).isEqualTo(2)
        assertThat(values.compilation).isTrue()
        assertThat(values.artistSort).isEqualTo(TextForms().sortKey("Ann & Bob, sorted"))
        assertThat(values.albumArtistSort).isEqualTo(TextForms().sortKey("Ann, sorted"))
    }

    @Test
    fun `the search text holds the decided title, credited names and album instead of the tags'`() {
        val text = merge(
            overrides = identified(
                TITLE to "Song", ARTIST to "Ann & Bob", ARTIST_CREDITS to DecidedField.credits(listOf("Ann" to " & ", "Bob" to "")),
                ALBUM to "Record",
            ),
        ).searchText

        assertThat(text).contains("song", "ann", "bob", "record").doesNotContain("tag title", "tag artist", "tag album")
    }

    @Test
    fun `an unread file shows decided values over the index`() {
        val values = merge(tags = null, read = false, overrides = identified(ALBUM to "Record", DATE to "2001"))

        assertThat(values.album).isEqualTo("Record")
        assertThat(values.title).isEqualTo("Index title")
        assertThat(values.year).isEqualTo(2001)
    }

    @Test
    fun `a cleared date hides the index's year of an unread file`() {
        val values = merge(tags = null, read = false, overrides = decided(LEVEL_USER_EDIT, DATE to null))

        assertThat(values.year).isNull()
    }

    @Test
    fun `a decided original date alone keeps the index's year of an unread file`() {
        // Only the original date was ticked: the date field is not decided, and the index's year is its level-4 value.
        assertThat(merge(tags = null, read = false, overrides = identified(ORIGINAL_DATE to null)).year).isEqualTo(1985)
        assertThat(merge(tags = null, read = false, overrides = identified(ORIGINAL_DATE to "1977")).year).isEqualTo(1985)
    }

    @Test
    fun `a decided original date gives an unread file its year when neither the date nor the index does`() {
        val values = MergedValues.of(
            "x.mp3", index.copy(year = null), tags = null, read = false, overrides = identified(ORIGINAL_DATE to "1977"),
        )

        assertThat(values.year).isEqualTo(1977)
    }

    @Test
    fun `a decided original date gives the year when no date is decided or tagged`() {
        val values = merge(tags = emptyTags, overrides = identified(DATE to null, ORIGINAL_DATE to "1977"))

        assertThat(values.year).isEqualTo(1977)
    }

    @Test
    fun `an empty decided title leaves it to the file name, and an empty album to nothing`() {
        val values = merge(overrides = decided(LEVEL_USER_EDIT, TITLE to null, ALBUM to null))

        assertThat(values.title).isEqualTo("Name Title")
        assertThat(values.album).isNull()
        assertThat(values.reasons!! and MergedValues.TITLE_FROM_FILE_NAME).isNotZero()
        assertThat(values.reasons!! and MergedValues.NO_ALBUM).isNotZero()
    }

    @Test
    fun `a decided recording ID, title, artist, album and cover clear their reasons`() {
        val bare = merge(tags = emptyTags, displayName = "track07.mp3")
        assertThat(bare.reasons).isEqualTo(
            MergedValues.TITLE_FROM_FILE_NAME or MergedValues.NO_ARTIST or MergedValues.NO_ALBUM or MergedValues.NO_COVER or
                MergedValues.NO_RECORDING_ID,
        )

        val identified = merge(
            tags = emptyTags,
            displayName = "track07.mp3",
            overrides = identified(
                TITLE to "Song", ARTIST to "Ann", ALBUM to "Record", RECORDING_MBID to "0b8a3a2e-1111-4222-8333-444455556666",
                COVER to "12",
            ),
        )
        assertThat(identified.reasons).isEqualTo(0)
    }

    @Test
    fun `an empty decided recording ID is no recording ID, also when the tags have one`() {
        val withId = TrackTags.from(mapOf("MUSICBRAINZ_TRACKID" to listOf("0b8a3a2e-1111-4222-8333-444455556666")))

        val values = merge(tags = withId, overrides = decided(LEVEL_USER_EDIT, RECORDING_MBID to null))

        assertThat(values.reasons!! and MergedValues.NO_RECORDING_ID).isNotZero()
    }

    @Test
    fun `the badge state follows the decided levels`() {
        assertThat(merge().badgeState).isEqualTo(DecisionResolver.BADGE_NONE)
        assertThat(merge(overrides = identified(TITLE to "Song")).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
        assertThat(merge(overrides = decided(LEVEL_USER_EDIT, TITLE to "Song")).badgeState).isEqualTo(DecisionResolver.BADGE_EDITED)
    }

    @Test
    fun `apply then undo gives back the earlier merged values`() {
        val apply = DecisionValues(
            1, APPLY, 10, setOf(ProtectedField.TITLE, ProtectedField.ALBUM), mapOf(TITLE to "Song", ALBUM to "Record"),
        )
        val before = merge()
        val applied = merge(overrides = FieldOverrides(DecisionResolver.resolve(listOf(apply))))
        val undone = merge(overrides = FieldOverrides(DecisionResolver.resolve(listOf(apply.copy(undone = true)))))

        assertThat(applied.title to applied.album).isEqualTo("Song" to "Record")
        assertThat(undone).isEqualTo(before)
    }

    @Test
    fun `stacked decisions reach the display as the resolver orders them`() {
        val first = DecisionValues(1, APPLY, 10, setOf(ProtectedField.TITLE), mapOf(TITLE to "First"))
        val second = DecisionValues(2, APPLY, 20, setOf(ProtectedField.TITLE), mapOf(TITLE to "Second"))
        val edit = DecisionValues(3, EDIT, 30, setOf(ProtectedField.TITLE), mapOf(TITLE to "Mine"))
        fun shown(vararg decisions: DecisionValues) = merge(overrides = FieldOverrides(DecisionResolver.resolve(decisions.toList()))).title

        assertThat(shown(first, second)).isEqualTo("Second")
        assertThat(shown(first.copy(undone = true), second)).isEqualTo("Second")
        assertThat(shown(first, second.copy(undone = true))).isEqualTo("First")
        assertThat(shown(first, second, edit)).isEqualTo("Mine")
        assertThat(shown(first, second, edit.copy(undone = true))).isEqualTo("Second")
    }

    @Test
    fun `every part has its value and level from the decided values, the tags, the index or the file name`() {
        val parts = MergedValues.parts(
            "05 Name Artist - Name Title.mp3", index, tags, read = true,
            overrides = decided(LEVEL_USER_EDIT, TITLE to "Mine") .let { FieldOverrides(it.decided + (ALBUM to Decided("Record", LEVEL_IDENTIFICATION, 3))) },
        )

        assertThat(parts[TITLE]).isEqualTo(Sourced("Mine", LEVEL_USER_EDIT))
        assertThat(parts[ALBUM]).isEqualTo(Sourced("Record", LEVEL_IDENTIFICATION))
        assertThat(parts[ARTIST]).isEqualTo(Sourced("Tag artist", LEVEL_TAGS))
        assertThat(parts[ARTIST_SORT]).isEqualTo(Sourced("Artist, Tag", LEVEL_TAGS))
        assertThat(parts[ALBUM_ARTIST]).isEqualTo(Sourced("Tag album artist", LEVEL_TAGS))
        assertThat(parts[DATE]).isEqualTo(Sourced("1990", LEVEL_TAGS))
        assertThat(parts[TRACK_NUMBER]).isEqualTo(Sourced("3", LEVEL_TAGS))
        assertThat(parts[DecidedField.TRACK_TOTAL]).isEqualTo(Sourced("9", LEVEL_TAGS))
        assertThat(parts[DecidedField.LABEL]).isEqualTo(Sourced("Tag label", LEVEL_TAGS))
        // No level gives a barcode.
        assertThat(parts).doesNotContainKey(DecidedField.BARCODE)
    }

    @Test
    fun `an unread file's parts come from the index, and the file name fills the title, artist and track number`() {
        val unread = MergedValues.parts("05 Name Artist - Name Title.mp3", index, tags = null, read = false)
        assertThat(unread[TITLE]).isEqualTo(Sourced("Index title", LEVEL_INDEX))
        assertThat(unread[ALBUM]).isEqualTo(Sourced("Index album", LEVEL_INDEX))
        assertThat(unread[DATE]).isEqualTo(Sourced("1985", LEVEL_INDEX))
        assertThat(unread[TRACK_NUMBER]).isEqualTo(Sourced("4", LEVEL_INDEX))
        assertThat(unread[DISC_NUMBER]).isEqualTo(Sourced("2", LEVEL_INDEX))

        val bare = MergedValues.parts("05 Name Artist - Name Title.mp3", index = null, tags = emptyTags, read = true)
        assertThat(bare[TITLE]).isEqualTo(Sourced("Name Title", LEVEL_FILE_NAME))
        assertThat(bare[ARTIST]).isEqualTo(Sourced("Name Artist", LEVEL_FILE_NAME))
        assertThat(bare[TRACK_NUMBER]).isEqualTo(Sourced("5", LEVEL_FILE_NAME))
        assertThat(bare).doesNotContainKey(ALBUM)
    }

    @Test
    fun `a decided empty part is there with its level, and a decided title hides the file name`() {
        val parts = MergedValues.parts("x.mp3", index = null, tags = tags, read = true, overrides = decided(LEVEL_USER_EDIT, ALBUM to null))

        assertThat(parts[ALBUM]).isEqualTo(Sourced(null, LEVEL_USER_EDIT))
    }

    @Test
    fun `a cleared title and artist are the file-name guess in the parts, as the lists show them`() {
        val overrides = decided(LEVEL_USER_EDIT, TITLE to null, ARTIST to null, ARTIST_CREDITS to null, ARTIST_SORT to null)
        val name = "05 Name Artist - Name Title.mp3"

        val parts = MergedValues.parts(name, index, tags, read = true, overrides = overrides)
        val shown = merge(overrides = overrides, displayName = name)

        assertThat(parts[TITLE]).isEqualTo(Sourced("Name Title", LEVEL_FILE_NAME))
        assertThat(parts[ARTIST]).isEqualTo(Sourced("Name Artist", LEVEL_FILE_NAME))
        assertThat(parts[TITLE]?.value).isEqualTo(shown.title)
        assertThat(parts[ARTIST]?.value).isEqualTo(shown.artist)
        // The tags do not come back: the other parts of the cleared artist stay decided and empty.
        assertThat(parts[ARTIST_SORT]).isEqualTo(Sourced(null, LEVEL_USER_EDIT))
        // An unread file too: the index does not fill a decided title.
        val unread = MergedValues.parts(name, index, tags = null, read = false, overrides = overrides)
        assertThat(unread[TITLE]).isEqualTo(Sourced("Name Title", LEVEL_FILE_NAME))
    }

    @Test
    fun `every field of TI 1 has its parts resolved for a fully tagged file`() {
        val full = TrackTags.from(
            mapOf(
                "TITLE" to listOf("t"), "ARTIST" to listOf("a"), "ARTISTS" to listOf("a"), "ARTISTSORT" to listOf("as"),
                "ALBUM" to listOf("b"), "ALBUMARTIST" to listOf("aa"), "ALBUMARTISTSORT" to listOf("aas"), "DATE" to listOf("2000"),
                "ORIGINALDATE" to listOf("1999"), "TRACKNUMBER" to listOf("1/2"), "DISCNUMBER" to listOf("1/1"),
                "DISCSUBTITLE" to listOf("s"), "COMPILATION" to listOf("1"), "LABEL" to listOf("l"), "CATALOGNUMBER" to listOf("c"),
                "BARCODE" to listOf("123"), "ISRC" to listOf("GBAAA0000001"),
                "MUSICBRAINZ_TRACKID" to listOf("0b8a3a2e-1111-4222-8333-444455556661"),
                "MUSICBRAINZ_ALBUMID" to listOf("0b8a3a2e-1111-4222-8333-444455556662"),
                "MUSICBRAINZ_RELEASEGROUPID" to listOf("0b8a3a2e-1111-4222-8333-444455556663"),
                "MUSICBRAINZ_RELEASETRACKID" to listOf("0b8a3a2e-1111-4222-8333-444455556664"),
                "MUSICBRAINZ_ARTISTID" to listOf("0b8a3a2e-1111-4222-8333-444455556665"),
                "MUSICBRAINZ_ALBUMARTISTID" to listOf("0b8a3a2e-1111-4222-8333-444455556666"),
            ),
        )

        val parts = MergedValues.parts("x.mp3", index = null, tags = full, read = true)

        // Every part but the cover, which comes from the cover chain (TI §5.2).
        assertThat(parts.keys).containsExactlyInAnyOrderElementsOf(DecidedField.entries - COVER)
        assertThat(parts.values).allSatisfy { assertThat(it.level).isEqualTo(LEVEL_TAGS) }
        assertThat(parts[DecidedField.ALBUM_ARTIST_IDS]?.value).isEqualTo("0b8a3a2e-1111-4222-8333-444455556666")
        assertThat(parts[COMPILATION]?.value).isEqualTo("1")
    }
}
