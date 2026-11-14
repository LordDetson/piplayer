package io.github.lorddetson.piplayer.library.details

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.CoverCopy
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.details.DetailField.ADDED
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.BITRATE
import io.github.lorddetson.piplayer.library.details.DetailField.CODEC
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SOURCE
import io.github.lorddetson.piplayer.library.details.DetailField.DATE
import io.github.lorddetson.piplayer.library.details.DetailField.FAVORITE
import io.github.lorddetson.piplayer.library.details.DetailField.FILE_NAME
import io.github.lorddetson.piplayer.library.details.DetailField.FOLDER
import io.github.lorddetson.piplayer.library.details.DetailField.FORMAT
import io.github.lorddetson.piplayer.library.details.DetailField.INDEX_LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.LAST_PLAYED
import io.github.lorddetson.piplayer.library.details.DetailField.LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.MIME_TYPE
import io.github.lorddetson.piplayer.library.details.DetailField.MODIFIED
import io.github.lorddetson.piplayer.library.details.DetailField.PLAY_COUNT
import io.github.lorddetson.piplayer.library.details.DetailField.RECORDING_ID
import io.github.lorddetson.piplayer.library.details.DetailField.SAMPLE_RATE
import io.github.lorddetson.piplayer.library.details.DetailField.SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.TAGS
import io.github.lorddetson.piplayer.library.details.DetailField.TAG_ENCODING
import io.github.lorddetson.piplayer.library.details.DetailField.TITLE
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.userdata.PlayStats
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Track details read from the real databases (docs/library.md §18.2, §18.3), with a probe in place of the pictures. */
@RunWith(AndroidJUnit4::class)
class LibraryTrackDetailsTest {

    private val context = TestFiles.targetContext
    private val database = LibraryDatabase.open(context, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val user = UserDatabase.open(context, name = null)
    private val ownDir = File(context.cacheDir, "details-own-covers")
    private val own = OwnCovers(user, ownDir)
    private val probe = FakeProbe()
    private val access = object : LibraryTrackRefs.Access {
        override fun <T> read(block: (LibraryDatabase) -> T): T = block(database)
    }
    private val details = LibraryTrackDetails(access, user, own, probe)

    private val recording = "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
    private val key = "%064x".format(77)

    /** Answers for the pictures, and the URIs asked. */
    private class FakeProbe : PictureProbe {
        var embedded: PictureSize? = null
        var thumbnail = false
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun embedded(uri: String): PictureSize? {
            asked += "embedded $uri"
            return embedded
        }

        override suspend fun hasThumbnail(uri: String): Boolean {
            asked += "thumbnail $uri"
            return thumbnail
        }
    }

    @Before
    fun fill() {
        ownDir.deleteRecursively()
        ownDir.mkdirs()
        index.volume().rows = listOf(
            row(1, "Index title", path = "Music/Ann/Red/", name = "03 Song.flac", artist = "Index artist", album = "Index album",
                durationMs = 205_000, year = 1999, trackNumber = 3),
            row(2, "Other", path = "Music/Bob/", name = "other.mp3", artist = "Bob", album = "Plain"),
        )
        runBlocking { sync.sync() }
    }

    @After
    fun close() {
        sync.close()
        database.close()
        user.close()
        ownDir.deleteRecursively()
    }

    private fun fileOf(mediaStoreId: Long) = runBlocking { database.library().files() }.single { it.mediaStoreId == mediaStoreId }.id

    private fun read(fileId: Long = fileOf(1)) = checkNotNull(runBlocking { details.read(fileId) })

    private fun TrackDetails.text(field: DetailField) = (value(field) as? DetailValue.Text)?.text

    /** What the tag pass would store for the file of [mediaStoreId]: its tags, technical data, key and walked length. */
    private fun readTags(
        mediaStoreId: Long = 1,
        coverPresent: Boolean = false,
        coverBytes: Long? = null,
        tagEncoding: String? = null,
    ): Long {
        val fileId = fileOf(mediaStoreId)
        val tags = TrackTags.from(
            mapOf(
                "TITLE" to listOf("Tag title"),
                "ARTIST" to listOf("Ann"),
                "ALBUM" to listOf("Red"),
                "DATE" to listOf("2003-05-12"),
                "MUSICBRAINZ_TRACKID" to listOf(recording),
            ),
        )
        runBlocking {
            val dao = database.library()
            dao.addTrack(TrackEntity.of(fileId, tags))
            dao.markRead(fileId, at = 1, readerVersion = 2, format = "flac", codec = "flac", sampleRate = 96_000, bitDepth = 24,
                channels = 2, bitrate = 2_400, tagEncoding = tagEncoding, coverPresent = coverPresent, coverBytes = coverBytes,
                tagLengthMs = 205_000)
            dao.setContent(fileId, key, 205_412, DurationSource.WALK)
            TrackDisplayWriter(database).refresh(listOf(fileId))
        }
        return fileId
    }

    private fun addIdentity(createdAt: Long = 1_600_000_000_000): Long = runBlocking {
        user.identities().add(
            TrackIdentityEntity(contentKey = Hex.bytes(key), durationMs = 205_412, recordingMbid = null, hintVolumeName = null,
                hintRelativePath = null, hintDisplayName = null, hintSize = null, createdAt = createdAt, missingSince = null),
        )
    }

    @Test
    fun anUnreadFileShowsTheIndexValuesAndThatItsTagsAreNotRead() {
        val details = read()

        assertThat(details.text(TITLE)).isEqualTo("Index title")
        assertThat(details.text(ARTIST)).isEqualTo("Index artist")
        assertThat(details.text(ALBUM)).isEqualTo("Index album")
        assertThat(details.text(DATE)).isEqualTo("1999")
        assertThat(details.value(LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.INDEX))
        assertThat(details.value(TAGS)).isEqualTo(DetailValue.Tags(TagState.NOT_READ))
        assertThat(details.text(FILE_NAME)).isEqualTo("03 Song.flac")
        assertThat(details.value(FOLDER)).isEqualTo(DetailValue.Folder(FakeMediaIndex.PRIMARY, "Music/Ann/Red/"))
        assertThat(details.value(SIZE)).isEqualTo(DetailValue.Bytes(1_001))
        assertThat(details.value(MODIFIED)).isEqualTo(DetailValue.Time(1_700_000_001_000))
        assertThat(details.text(MIME_TYPE)).isEqualTo("audio/mpeg")
        assertThat(details.sections.map { it.first }).doesNotContain(DetailSection.PLAYS)
        assertThat(details.value(ADDED)).isNull()
        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.NONE))
    }

    @Test
    fun aFailedReadSaysWhy() {
        val fileId = fileOf(1)
        runBlocking { database.library().markFailed(fileId, "unsupported", readerVersion = 2) }
        assertThat(read(fileId).value(TAGS)).isEqualTo(DetailValue.Tags(TagState.UNSUPPORTED))

        runBlocking { database.library().markFailed(fileId, "open", readerVersion = null) }
        assertThat(read(fileId).value(TAGS)).isEqualTo(DetailValue.Tags(TagState.CANNOT_READ))

        runBlocking { database.library().markFailed(fileId, "crash", readerVersion = null) }
        assertThat(read(fileId).value(TAGS)).isEqualTo(DetailValue.Tags(TagState.FAILED))
    }

    @Test
    fun aReadFileShowsItsMergedTagsAndTechnicalData() {
        val fileId = readTags(tagEncoding = "windows-1251")

        val details = read(fileId)

        assertThat(details.text(TITLE)).isEqualTo("Tag title")
        assertThat(details.text(ARTIST)).isEqualTo("Ann")
        assertThat(details.text(ALBUM)).isEqualTo("Red")
        assertThat(details.text(DATE)).isEqualTo("2003-05-12")
        assertThat(details.text(FORMAT)).isEqualTo("flac")
        assertThat(details.text(CODEC)).isEqualTo("flac")
        assertThat(details.value(SAMPLE_RATE)).isEqualTo(DetailValue.Hertz(96_000))
        assertThat(details.value(BITRATE)).isEqualTo(DetailValue.Kbps(2_400))
        assertThat(details.value(LENGTH)).isEqualTo(DetailValue.Length(205_412, LengthSource.WALK))
        assertThat(details.value(INDEX_LENGTH)).isEqualTo(DetailValue.Length(205_000, LengthSource.INDEX))
        assertThat(details.text(TAG_ENCODING)).isEqualTo("windows-1251")
        assertThat(details.value(TAGS)).isNull()
        assertThat(details.values(RECORDING_ID)).containsExactly(DetailValue.MusicBrainz(MusicBrainzKind.RECORDING, recording))
        // The same values as the lists show.
        val shown = runBlocking { database.library().displays() }.single { it.fileId == fileId }
        assertThat(details.title).isEqualTo(shown.title)
        assertThat(details.artist).isEqualTo(shown.artist)
        assertThat(details.album).isEqualTo(shown.album)
    }

    @Test
    fun theIdentityGivesTheDateAddedThePlaysAndTheFavorite() {
        val fileId = readTags()
        val identity = addIdentity(createdAt = 1_600_000_000_000)
        val now = System.currentTimeMillis()
        runBlocking {
            user.favorites().add(identity, now)
            // One play long folded into the totals, one recent: both count, the recent one is the last.
            user.plays().addEvent(identity, startedAt = 1_000, listenedMs = 100_000)
            user.plays().addEvent(identity, startedAt = now - 60_000, listenedMs = 100_000)
            PlayStats(user).fold(now)
        }

        val details = read(fileId)

        assertThat(details.value(ADDED)).isEqualTo(DetailValue.Time(1_600_000_000_000))
        assertThat(details.value(FAVORITE)).isEqualTo(DetailValue.Flag(true))
        assertThat(details.value(PLAY_COUNT)).isEqualTo(DetailValue.Count(2))
        assertThat(details.value(LAST_PLAYED)).isEqualTo(DetailValue.Time(now - 60_000))
    }

    @Test
    fun anIdentityNeverPlayedShowsZeroAndNo() {
        val fileId = readTags()
        addIdentity()

        val details = read(fileId)

        assertThat(details.value(FAVORITE)).isEqualTo(DetailValue.Flag(false))
        assertThat(details.value(PLAY_COUNT)).isEqualTo(DetailValue.Count(0))
        assertThat(details.value(LAST_PLAYED)).isNull()
    }

    @Test
    fun theSongsOwnCoverComesFirstWithItsStoredSize() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        val identity = addIdentity()
        probe.embedded = PictureSize(600, 600)
        File(ownDir, "song.jpg").writeBytes(byteArrayOf(1))
        File(ownDir, "album.jpg").writeBytes(byteArrayOf(1))
        runBlocking {
            own.setTrack(identity, CoverCopy("song.jpg", 1500, 1000))
            own.setAlbum(albumKey(fileId), CoverCopy("album.jpg", 800, 800))
        }

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.SONG_OWN))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(1500, 1000, null))
        assertThat(probe.asked).isEmpty()
    }

    @Test
    fun theAlbumsOwnCoverComesNext() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        addIdentity()
        File(ownDir, "album.jpg").writeBytes(byteArrayOf(1))
        runBlocking { own.setAlbum(albumKey(fileId), CoverCopy("album.jpg", 800, 700)) }

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.ALBUM_OWN))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(800, 700, null))
    }

    @Test
    fun anOwnCoverWhoseCopyIsGoneDoesNotCount() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        val identity = addIdentity()
        probe.embedded = PictureSize(600, 600)
        File(ownDir, "song.jpg").writeBytes(byteArrayOf(1))
        runBlocking { own.setTrack(identity, CoverCopy("song.jpg", 1500, 1000)) }
        File(ownDir, "song.jpg").delete()

        assertThat(read(fileId).value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))
    }

    @Test
    fun theEmbeddedPictureHasItsSizeAndBytes() {
        val fileId = readTags(coverPresent = true, coverBytes = 48_213)
        probe.embedded = PictureSize(600, 500)

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.EMBEDDED))
        assertThat(details.value(COVER_SIZE)).isEqualTo(DetailValue.Pixels(600, 500, 48_213))
        assertThat(probe.asked).containsExactly("embedded content://media/external/audio/media/1")
    }

    @Test
    fun withoutAnEmbeddedPictureTheThumbnailCountsWithoutASize() {
        val fileId = readTags(coverPresent = false)
        probe.thumbnail = true

        val details = read(fileId)

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.THUMBNAIL))
        assertThat(details.value(COVER_SIZE)).isNull()
        // The tag pass found no picture: it is not looked for.
        assertThat(probe.asked).containsExactly("thumbnail content://media/external/audio/media/1")
    }

    @Test
    fun anUnreadFileIsProbedForBothPictures() {
        probe.thumbnail = true

        val details = read()

        assertThat(details.value(COVER_SOURCE)).isEqualTo(DetailValue.Cover(CoverSource.THUMBNAIL))
        assertThat(probe.asked).containsExactly(
            "embedded content://media/external/audio/media/1",
            "thumbnail content://media/external/audio/media/1",
        )
    }

    @Test
    fun aFileNotInTheLibraryHasNoDetails() {
        assertThat(runBlocking { details.read(9_999) }).isNull()
    }

    private fun albumKey(fileId: Long) = checkNotNull(LibraryLists(database).links(fileId)?.albumKey)
}
