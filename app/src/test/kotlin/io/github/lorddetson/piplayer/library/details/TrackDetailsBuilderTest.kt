package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.library.details.DetailField.ADDED
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_ARTIST_ID
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_GAIN
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST_ID
import io.github.lorddetson.piplayer.library.details.DetailField.BARCODE
import io.github.lorddetson.piplayer.library.details.DetailField.BITRATE
import io.github.lorddetson.piplayer.library.details.DetailField.BIT_DEPTH
import io.github.lorddetson.piplayer.library.details.DetailField.CATALOG_NUMBER
import io.github.lorddetson.piplayer.library.details.DetailField.CHANNELS
import io.github.lorddetson.piplayer.library.details.DetailField.CODEC
import io.github.lorddetson.piplayer.library.details.DetailField.COMPILATION
import io.github.lorddetson.piplayer.library.details.DetailField.COMPOSER
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SOURCE
import io.github.lorddetson.piplayer.library.details.DetailField.DATE
import io.github.lorddetson.piplayer.library.details.DetailField.DISC
import io.github.lorddetson.piplayer.library.details.DetailField.DISC_SUBTITLE
import io.github.lorddetson.piplayer.library.details.DetailField.FAVORITE
import io.github.lorddetson.piplayer.library.details.DetailField.FILE_NAME
import io.github.lorddetson.piplayer.library.details.DetailField.FOLDER
import io.github.lorddetson.piplayer.library.details.DetailField.FORMAT
import io.github.lorddetson.piplayer.library.details.DetailField.GENRE
import io.github.lorddetson.piplayer.library.details.DetailField.INDEX_LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.ISRC
import io.github.lorddetson.piplayer.library.details.DetailField.LABEL
import io.github.lorddetson.piplayer.library.details.DetailField.LAST_PLAYED
import io.github.lorddetson.piplayer.library.details.DetailField.LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.MIME_TYPE
import io.github.lorddetson.piplayer.library.details.DetailField.MODIFIED
import io.github.lorddetson.piplayer.library.details.DetailField.ORIGINAL_DATE
import io.github.lorddetson.piplayer.library.details.DetailField.PLAY_COUNT
import io.github.lorddetson.piplayer.library.details.DetailField.RECORDING_ID
import io.github.lorddetson.piplayer.library.details.DetailField.RELEASE_GROUP_ID
import io.github.lorddetson.piplayer.library.details.DetailField.RELEASE_ID
import io.github.lorddetson.piplayer.library.details.DetailField.SAMPLE_RATE
import io.github.lorddetson.piplayer.library.details.DetailField.SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.TAGS
import io.github.lorddetson.piplayer.library.details.DetailField.TAG_ENCODING
import io.github.lorddetson.piplayer.library.details.DetailField.TITLE
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK_GAIN
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK_ID
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.FieldOverrides
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TrackTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The rows of track details (docs/library.md §18.2 to §18.5): merged values, never raw tags (TI §7.1). */
class TrackDetailsBuilderTest {

    private val recording = "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
    private val release = "1e0eee38-a9f6-49bf-84d0-45d0647799af"
    private val releaseGroup = "2b4c7b4d-5a3a-4b1e-9e4f-8e3b8a4c7a10"
    private val track = "c3d2a2f1-1a2b-4c3d-8e4f-5a6b7c8d9e0f"
    private val artistOne = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val artistTwo = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"

    private val index = IndexValues(
        title = "Index title", artist = "Index artist", album = "Index album", composer = "Index composer",
        genre = "Index genre", durationMs = 205_000, albumArtist = "Index album artist", year = 1999, trackNumber = 2005,
    )

    private val tags = TrackTags.from(
        mapOf(
            "TITLE" to listOf("Tag title"),
            "ARTIST" to listOf("Ann feat. Bob"),
            "ARTISTS" to listOf("Ann", "Bob"),
            "ALBUM" to listOf("Tag album"),
            "ALBUMARTIST" to listOf("Ann"),
            "TRACKNUMBER" to listOf("3/12"),
            "DISCNUMBER" to listOf("1/2"),
            "DISCSUBTITLE" to listOf("The first"),
            "DATE" to listOf("2003-05-12"),
            "ORIGINALDATE" to listOf("2001"),
            "GENRE" to listOf("Rock", "Pop"),
            "COMPOSER" to listOf("Carl"),
            "COMPILATION" to listOf("1"),
            "LABEL" to listOf("Label"),
            "CATALOGNUMBER" to listOf("CAT-1"),
            "BARCODE" to listOf("0123456789012"),
            "ISRC" to listOf("USRC17607839"),
            "MUSICBRAINZ_TRACKID" to listOf(recording),
            "MUSICBRAINZ_ALBUMID" to listOf(release),
            "MUSICBRAINZ_RELEASEGROUPID" to listOf(releaseGroup),
            "MUSICBRAINZ_RELEASETRACKID" to listOf(track),
            "MUSICBRAINZ_ARTISTID" to listOf(artistOne, artistTwo),
            "MUSICBRAINZ_ALBUMARTISTID" to listOf(artistOne),
            "REPLAYGAIN_TRACK_GAIN" to listOf("-6.5 dB"),
            "REPLAYGAIN_TRACK_PEAK" to listOf("0.98"),
            "REPLAYGAIN_ALBUM_GAIN" to listOf("-7.25 dB"),
        ),
    )

    private val readFile = FileFacts(
        displayName = "03 Tag title.flac",
        volume = "external_primary",
        relativePath = "Music/Ann/Tag album/",
        size = 31_457_280,
        mtimeSeconds = 1_700_000_000,
        mimeType = "audio/flac",
        read = true,
        format = "flac",
        codec = "flac",
        sampleRate = 96_000,
        bitDepth = 24,
        channels = 2,
        bitrateKbps = 2_400,
        exactDurationMs = 205_412,
        durationSource = DurationSource.WALK,
        tagLengthMs = 205_000,
    )

    private val unreadFile = readFile.copy(read = false, format = null, codec = null, sampleRate = null, bitDepth = null,
        channels = null, bitrateKbps = null, exactDurationMs = null, durationSource = null, tagLengthMs = null)

    private fun details(
        file: FileFacts = readFile,
        index: IndexValues? = this.index,
        tags: TrackTags? = this.tags,
        user: UserFacts? = null,
        cover: CoverDetail? = null,
        overrides: FieldOverrides = FieldOverrides.NONE,
    ) = TrackDetailsBuilder.of(DetailInputs(7, file, index, tags, user, cover, overrides))

    private fun TrackDetails.text(field: DetailField): String? = (value(field) as? DetailValue.Text)?.text

    @Test
    fun `a read file shows the merged values of its tags, not the index's`() {
        val details = details()

        assertThat(details.fileId).isEqualTo(7)
        assertThat(details.title).isEqualTo("Tag title")
        assertThat(details.artist).isEqualTo("Ann feat. Bob")
        assertThat(details.album).isEqualTo("Tag album")
        assertThat(details.text(TITLE)).isEqualTo("Tag title")
        assertThat(details.text(ARTIST)).isEqualTo("Ann feat. Bob")
        assertThat(details.text(ALBUM)).isEqualTo("Tag album")
        assertThat(details.text(ALBUM_ARTIST)).isEqualTo("Ann")
        assertThat(details.value(TRACK)).isEqualTo(DetailValue.OfTotal(3, 12))
        assertThat(details.value(DISC)).isEqualTo(DetailValue.OfTotal(1, 2))
        assertThat(details.text(DISC_SUBTITLE)).isEqualTo("The first")
        assertThat(details.text(DATE)).isEqualTo("2003-05-12")
        assertThat(details.text(ORIGINAL_DATE)).isEqualTo("2001")
        assertThat(details.value(GENRE)).isEqualTo(DetailValue.Texts(listOf("Rock", "Pop")))
        assertThat(details.value(COMPOSER)).isEqualTo(DetailValue.Texts(listOf("Carl")))
        assertThat(details.value(COMPILATION)).isEqualTo(DetailValue.Flag(true))
        assertThat(details.text(LABEL)).isEqualTo("Label")
        assertThat(details.text(CATALOG_NUMBER)).isEqualTo("CAT-1")
        assertThat(details.text(BARCODE)).isEqualTo("0123456789012")
        assertThat(details.text(ISRC)).isEqualTo("USRC17607839")
        assertThat(details.value(TRACK_GAIN)).isEqualTo(DetailValue.Gain(-6.5, 0.98))
        assertThat(details.value(ALBUM_GAIN)).isEqualTo(DetailValue.Gain(-7.25, null))
        assertThat(details.rows.map { it.value }).doesNotContain(DetailValue.Text("Index title"), DetailValue.Text("Index album"))
    }

    @Test
    fun `title, artist and album are exactly what the lists show`() {
        for (file in listOf(readFile, unreadFile, readFile.copy(displayName = "Some Artist - Some Song.mp3"))) {
            val emptyTags = TrackTags.from(emptyMap())
            for (tags in listOf(tags, emptyTags)) {
                val merged = MergedValues.of(file.displayName, index, tags, file.read, file.exactDurationMs, file.tagLengthMs)
                val details = details(file = file, tags = tags)

                assertThat(details.text(TITLE)).isEqualTo(merged.title)
                assertThat(details.text(ARTIST)).isEqualTo(merged.artist)
                assertThat(details.text(ALBUM)).isEqualTo(merged.album)
                assertThat((details.value(LENGTH) as DetailValue.Length?)?.ms).isEqualTo(merged.durationMs)
            }
        }
    }

    @Test
    fun `an unread file shows the index's values, its year as the date and the disc from the track number`() {
        val details = details(file = unreadFile)

        assertThat(details.text(TITLE)).isEqualTo("Index title")
        assertThat(details.text(ARTIST)).isEqualTo("Index artist")
        assertThat(details.text(ALBUM)).isEqualTo("Index album")
        assertThat(details.text(ALBUM_ARTIST)).isEqualTo("Index album artist")
        assertThat(details.text(DATE)).isEqualTo("1999")
        assertThat(details.value(DISC)).isEqualTo(DetailValue.OfTotal(2, null))
        assertThat(details.value(TRACK)).isEqualTo(DetailValue.OfTotal(5, null))
        assertThat(details.value(GENRE)).isEqualTo(DetailValue.Texts(listOf("Index genre")))
        assertThat(details.value(COMPOSER)).isEqualTo(DetailValue.Texts(listOf("Index composer")))
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.NOT_READ))
        // Nothing of the unread tags shows.
        assertThat(details.values(RECORDING_ID)).isEmpty()
        assertThat(details.value(LABEL)).isNull()
    }

    @Test
    fun `once read an empty tag stays empty and the index does not fill it`() {
        val details = details(tags = TrackTags.from(mapOf("TITLE" to listOf("Only a title"))))

        assertThat(details.text(TITLE)).isEqualTo("Only a title")
        assertThat(details.value(ALBUM)).isNull()
        assertThat(details.value(GENRE)).isNull()
        assertThat(details.value(COMPOSER)).isNull()
        assertThat(details.value(DATE)).isNull()
        assertThat(details.value(TAGS)).isNull()
    }

    @Test
    fun `the file name is the title's last resort, and index placeholders count as empty`() {
        val placeholders = IndexValues(title = "<unknown>", artist = "<unknown>", album = " ", composer = "<unknown>", genre = "")
        val details = details(file = unreadFile.copy(displayName = "Ann - Song.mp3"), index = placeholders)

        assertThat(details.text(TITLE)).isEqualTo("Song")
        assertThat(details.text(ARTIST)).isEqualTo("Ann")
        assertThat(details.value(ALBUM)).isNull()
        assertThat(details.value(COMPOSER)).isNull()
        assertThat(details.value(GENRE)).isNull()
    }

    @Test
    fun `an override of levels 1 and 2 wins over the tags`() {
        val details = details(overrides = FieldOverrides(title = "Edited title"))

        assertThat(details.title).isEqualTo("Edited title")
        assertThat(details.text(TITLE)).isEqualTo("Edited title")
    }

    @Test
    fun `the technical data of a read file`() {
        val details = details()

        assertThat(details.text(FORMAT)).isEqualTo("flac")
        assertThat(details.text(CODEC)).isEqualTo("flac")
        assertThat(details.value(BITRATE)).isEqualTo(DetailValue.Kbps(2_400))
        assertThat(details.value(SAMPLE_RATE)).isEqualTo(DetailValue.Hertz(96_000))
        assertThat(details.value(CHANNELS)).isEqualTo(DetailValue.Channels(2))
        assertThat(details.value(BIT_DEPTH)).isEqualTo(DetailValue.Bits(24))
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(31_457_280))
        assertThat(details.text(FILE_NAME)).isEqualTo("03 Tag title.flac")
        assertThat(details.value(FOLDER)).isEqualTo(DetailValue.Folder("external_primary", "Music/Ann/Tag album/"))
        assertThat(details.text(MIME_TYPE)).isEqualTo("audio/flac")
        assertThat(details.value(MODIFIED)).isEqualTo(DetailValue.Time(1_700_000_000_000))
        assertThat(details.value(TAG_ENCODING)).isNull()
        assertThat(details(file = readFile.copy(tagEncoding = "windows-1251")).text(TAG_ENCODING)).isEqualTo("windows-1251")
    }

    @Test
    fun `the length shown says where it comes from, with the index's length beside a measured one`() {
        val walked = details()
        assertThat(walked.value(LENGTH)).isEqualTo(DetailValue.Length(205_412, LengthSource.WALK))
        assertThat(walked.value(INDEX_LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.INDEX))

        val header = details(file = readFile.copy(durationSource = DurationSource.HEADER))
        assertThat(header.value(LENGTH)).isEqualTo(DetailValue.Length(205_412, LengthSource.HEADER))

        val unchecked = details(file = readFile.copy(durationSource = DurationSource.PENDING_FLAC_END))
        assertThat(unchecked.value(LENGTH)).isEqualTo(DetailValue.Length(205_412, LengthSource.HEADER_UNCHECKED))

        // Waiting for a walk: the index's length is the one shown, and has no second row.
        val pending = details(file = readFile.copy(exactDurationMs = null, durationSource = DurationSource.PENDING_WALK))
        assertThat(pending.value(LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.INDEX))
        assertThat(pending.value(INDEX_LENGTH)).isNull()

        val estimate = details(file = readFile.copy(exactDurationMs = null, durationSource = DurationSource.UNKNOWN), index = null)
        assertThat(estimate.value(LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.ESTIMATE))

        val none = details(file = unreadFile, index = index.copy(durationMs = null))
        assertThat(none.value(LENGTH)).isNull()
    }

    @Test
    fun `unknown values are left out, and so are empty sections`() {
        val details = details(file = FileFacts(displayName = "x.mp3", read = true), index = null, tags = TrackTags.from(emptyMap()))

        assertThat(details.rows.map { it.field }).containsExactly(TITLE, FILE_NAME)
        assertThat(details.sections.map { it.first }).containsExactly(DetailSection.SONG, DetailSection.FILE)
    }

    @Test
    fun `plays and the favorite come from the identity, and stay at zero and no`() {
        val played = details(user = UserFacts(favorite = true, playCount = 12, lastPlayedAt = 1_700_000_500_000, addedAt = 1_600_000_000_000))
        assertThat(played.value(FAVORITE)).isEqualTo(DetailValue.Flag(true))
        assertThat(played.value(PLAY_COUNT)).isEqualTo(DetailValue.Count(12))
        assertThat(played.value(LAST_PLAYED)).isEqualTo(DetailValue.Time(1_700_000_500_000))
        assertThat(played.value(ADDED)).isEqualTo(DetailValue.Time(1_600_000_000_000))

        val never = details(user = UserFacts(favorite = false, playCount = 0, lastPlayedAt = null, addedAt = 1))
        assertThat(never.value(FAVORITE)).isEqualTo(DetailValue.Flag(false))
        assertThat(never.value(PLAY_COUNT)).isEqualTo(DetailValue.Count(0))
        assertThat(never.value(LAST_PLAYED)).isNull()

        val noIdentity = details(user = null)
        assertThat(noIdentity.sections.map { it.first }).doesNotContain(DetailSection.PLAYS)
        assertThat(noIdentity.value(ADDED)).isNull()
    }

    @Test
    fun `the cover's source and size`() {
        val own = details(cover = CoverDetail(CoverSource.SONG_OWN, 1500, 1000))
        assertThat(own.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.SONG_OWN))
        assertThat(own.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(1500, 1000, null))

        val embedded = details(cover = CoverDetail(CoverSource.EMBEDDED, 600, 600, 48_213))
        assertThat(embedded.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(600, 600, 48_213))

        val thumbnail = details(cover = CoverDetail(CoverSource.THUMBNAIL))
        assertThat(thumbnail.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.THUMBNAIL))
        assertThat(thumbnail.value(COVER_SIZE)).isNull()

        val none = details(cover = CoverDetail(CoverSource.NONE))
        assertThat(none.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.NONE))
    }

    @Test
    fun `MusicBrainz IDs, one row each, with their pages`() {
        val details = details()

        assertThat(details.values(RECORDING_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.RECORDING, recording))
        assertThat(details.values(RELEASE_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.RELEASE, release))
        assertThat(details.values(RELEASE_GROUP_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.RELEASE_GROUP, releaseGroup))
        assertThat(details.values(TRACK_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.TRACK, track))
        assertThat(details.values(ARTIST_ID)).containsExactly(
            DetailValue.MusicBrainz(MusicBrainzKind.ARTIST, artistOne),
            DetailValue.MusicBrainz(MusicBrainzKind.ARTIST, artistTwo),
        )
        assertThat(details.values(ALBUM_ARTIST_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.ARTIST, artistOne))
        assertThat((details.value(RECORDING_ID) as DetailValue.MusicBrainz).url)
            .isEqualTo("https://musicbrainz.org/recording/$recording")
    }

    @Test
    fun `links name each kind's page, and a malformed ID is no link`() {
        assertThat(MusicBrainzLinks.url(MusicBrainzKind.RELEASE_GROUP, releaseGroup))
            .isEqualTo("https://musicbrainz.org/release-group/$releaseGroup")
        assertThat(MusicBrainzLinks.url(MusicBrainzKind.TRACK, track)).isEqualTo("https://musicbrainz.org/track/$track")
        assertThat(MusicBrainzLinks.url(MusicBrainzKind.ARTIST, artistOne.uppercase()))
            .isEqualTo("https://musicbrainz.org/artist/${artistOne.uppercase()}")
        assertThat(MusicBrainzLinks.url(MusicBrainzKind.ARTIST, "not an id")).isNull()
        assertThat(MusicBrainzLinks.url(MusicBrainzKind.ARTIST, "$artistOne/../x")).isNull()
        assertThat(MusicBrainzLinks.url(MusicBrainzKind.ARTIST, "")).isNull()
    }

    @Test
    fun `rows come in field order`() {
        val fields = details(user = UserFacts(true, 1, 2, 3), cover = CoverDetail(CoverSource.EMBEDDED, 1, 1)).rows.map { it.field }

        assertThat(fields).isSortedAccordingTo(compareBy { it.ordinal })
    }

    @Test
    fun `an opened file shows its own tags and technical data, without a place, plays or the index`() {
        val opened = FileFacts(
            displayName = "song.mp3",
            size = 4_404_019,
            mimeType = "audio/mpeg",
            read = true,
            format = "mpeg",
            codec = "mp3",
            sampleRate = 44_100,
            channels = 2,
            bitrateKbps = 320,
            tagLengthMs = 110_093,
        )
        val details = TrackDetailsBuilder.of(DetailInputs(null, opened, tags = tags, cover = CoverDetail(CoverSource.EMBEDDED, 500, 500, 30_000)))

        assertThat(details.fileId).isNull()
        assertThat(details.text(TITLE)).isEqualTo("Tag title")
        assertThat(details.value(LENGTH)).isEqualTo(DetailValue.Length(110_093, LengthSource.ESTIMATE))
        assertThat(details.value(FOLDER)).isNull()
        assertThat(details.value(INDEX_LENGTH)).isNull()
        assertThat(details.value(MODIFIED)).isNull()
        assertThat(details.sections.map { it.first }).doesNotContain(DetailSection.PLAYS)
        assertThat(details.text(CODEC)).isEqualTo("mp3")
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(4_404_019))
    }

    @Test
    fun `an opened file that could not be read shows what its provider gives`() {
        val details = TrackDetailsBuilder.of(
            DetailInputs(null, FileFacts(displayName = "Ann - Song.mp3", size = 10, mimeType = "audio/mpeg", tagState = TagState.CANNOT_READ)),
        )

        assertThat(details.text(TITLE)).isEqualTo("Song")
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.CANNOT_READ))
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(10))
    }
}
