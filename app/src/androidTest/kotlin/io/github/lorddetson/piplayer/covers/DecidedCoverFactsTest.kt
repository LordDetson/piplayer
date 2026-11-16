package io.github.lorddetson.piplayer.covers

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.db.replaceDecided
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.lists.DisplayArtists
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the library database tells the covers of decided covers and releases (docs/library.md §13.1, M3), on a library
 * database of its own, so that no mirror run of the app replaces the decided values the test writes.
 */
@RunWith(AndroidJUnit4::class)
class DecidedCoverFactsTest {

    private val context = TestFiles.targetContext
    private val database = LibraryDatabase.open(context, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val user = UserDatabase.open(context, name = null)
    private val access = object : LibraryTrackRefs.Access {
        override fun <T> read(block: (LibraryDatabase) -> T): T = block(database)
    }
    private val facts = LibraryCoverFacts(access, user)

    @Before
    fun fill() {
        index.volume().rows = listOf(
            row(1, "One", path = "Music/Ann/Red/", artist = "Ann", album = "Red", year = 1990, trackNumber = 1),
            row(2, "Two", path = "Music/Ann/Red/", artist = "Ann", album = "Red", year = 1990, trackNumber = 2),
            row(3, "Three", path = "Music/Ann/Red/", artist = "Ann", album = "Red", year = 1990, trackNumber = 3),
            row(4, "Four", path = "Music/Ann/Blue/", artist = "Ann", album = "Blue", year = 2000, trackNumber = 1),
            row(5, "Five", path = "Music/Bob/", artist = "Bob", album = "Plain", year = 2010, trackNumber = 1),
        )
        runBlocking { sync.sync() }
    }

    @After
    fun close() {
        sync.close()
        database.close()
        user.close()
    }

    private fun fileId(mediaStoreId: Long) = runBlocking { database.library().files() }.single { it.mediaStoreId == mediaStoreId }.id

    private fun albumKey(mediaStoreId: Long) = checkNotNull(runBlocking { facts.file(fileId(mediaStoreId)) }?.albumKey)

    /** Decided values of the file [mediaStoreId], as the decision mirror would write them (TI §7.1). */
    private fun decide(mediaStoreId: Long, vararg values: Pair<String, String?>) = runBlocking {
        val id = fileId(mediaStoreId)
        val rows = database.decided().valuesOf(listOf(id)).filter { row -> values.none { it.first == row.field } } +
            values.map { (field, value) -> DecidedMirrorEntity(id, field, value, DecidedMirrorEntity.LEVEL_IDENTIFICATION, 1) }
        database.replaceDecided(listOf(id), rows)
    }

    /** What the tag pass would store for the file [mediaStoreId], with the release IDs in its tags. */
    private fun readTags(mediaStoreId: Long, release: String?, group: String?) = runBlocking {
        val id = fileId(mediaStoreId)
        val tags = TrackTags.from(
            mapOf("TITLE" to listOf("Five"), "ARTIST" to listOf("Bob"), "ALBUM" to listOf("Plain")) +
                listOfNotNull(release?.let { "MUSICBRAINZ_ALBUMID" to listOf(it) }, group?.let { "MUSICBRAINZ_RELEASEGROUPID" to listOf(it) }),
        )
        val dao = database.library()
        dao.addTrack(TrackEntity.of(id, tags))
        dao.markRead(id, at = 1, readerVersion = 2, format = "mp3", codec = "mp3", sampleRate = 44_100, bitDepth = null,
            channels = 2, bitrate = 320, tagEncoding = null, coverPresent = false, coverBytes = null, tagLengthMs = 60_000)
        dao.setContent(id, "%064x".format(mediaStoreId), 60_000, DurationSource.HEADER)
        TrackDisplayWriter(database).refresh(listOf(id))
    }

    @Test
    fun aFileHasItsDecidedCoverUnlessItIsEmpty() = runBlocking<Unit> {
        assertThat(facts.file(fileId(1))!!.decidedCover).isNull()

        decide(1, "cover" to "17")
        assertThat(facts.file(fileId(1))!!.decidedCover).isEqualTo(17)

        decide(1, "cover" to null)
        assertThat(facts.file(fileId(1))!!.decidedCover).isNull()
    }

    @Test
    fun aFilesReleaseIsTheDecidedOneBeforeTheTagsOnesAndADecidedFieldWithoutAReleaseHasNone() = runBlocking<Unit> {
        assertThat(facts.file(fileId(5))!!.release).isNull()
        readTags(5, TAG_RELEASE, TAG_GROUP)
        assertThat(facts.file(fileId(5))!!.release).isEqualTo(CoverRelease(TAG_RELEASE, TAG_GROUP))

        decide(5, "release_mbid" to DECIDED_RELEASE, "release_group_mbid" to DECIDED_GROUP)
        assertThat(facts.file(fileId(5))!!.release).isEqualTo(CoverRelease(DECIDED_RELEASE, DECIDED_GROUP))

        // The MusicBrainz IDs are decided as a whole: a decided release without a group keeps no group of the tags.
        decide(5, "release_mbid" to DECIDED_RELEASE, "release_group_mbid" to null)
        assertThat(facts.file(fileId(5))!!.release).isEqualTo(CoverRelease(DECIDED_RELEASE, null))

        decide(5, "release_mbid" to null)
        assertThat(facts.file(fileId(5))!!.release).isNull()
    }

    @Test
    fun anAlbumsDecidedCoverAndReleaseAreThoseOfItsFirstSongWithOneByDiscTrackAndName() = runBlocking<Unit> {
        val red = albumKey(1)
        assertThat(facts.albumDecidedCover(red)).isNull()
        assertThat(facts.albumRelease(red)).isNull()

        decide(3, "cover" to "30", "release_mbid" to DECIDED_RELEASE, "release_group_mbid" to DECIDED_GROUP)
        assertThat(facts.albumDecidedCover(red)).isEqualTo(30)
        assertThat(facts.albumRelease(red)).isEqualTo(CoverRelease(DECIDED_RELEASE, DECIDED_GROUP))

        decide(2, "cover" to "20", "release_mbid" to OTHER_RELEASE)
        assertThat(facts.albumDecidedCover(red)).isEqualTo(20)
        assertThat(facts.albumRelease(red)).isEqualTo(CoverRelease(OTHER_RELEASE, null))

        // An empty decided cover and a decided field without a release are passed over.
        decide(1, "cover" to null, "release_mbid" to null)
        assertThat(facts.albumDecidedCover(red)).isEqualTo(20)
        assertThat(facts.albumRelease(red)).isEqualTo(CoverRelease(OTHER_RELEASE, null))
        assertThat(facts.albumDecidedCover("no such album")).isNull()
    }

    @Test
    fun anArtistsAlbumsSayWhichHaveADecidedCoverOrARelease() = runBlocking<Unit> {
        decide(2, "cover" to "20")
        decide(4, "release_mbid" to DECIDED_RELEASE)

        assertThat(facts.artistAlbums(DisplayArtists.keyOf("Ann"))).containsExactly(
            ArtistAlbum(albumKey(1), embedded = false, decided = true, release = false),
            ArtistAlbum(albumKey(4), embedded = false, decided = false, release = true),
        )
    }

    private companion object {
        const val TAG_RELEASE = "5ab00000-1111-4222-8333-000000000001"
        const val TAG_GROUP = "5ab00000-1111-4222-8333-000000000002"
        const val DECIDED_RELEASE = "5ab00000-1111-4222-8333-000000000003"
        const val DECIDED_GROUP = "5ab00000-1111-4222-8333-000000000004"
        const val OTHER_RELEASE = "5ab00000-1111-4222-8333-000000000005"
    }
}
