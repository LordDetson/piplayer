package io.github.lorddetson.piplayer.covers

import android.content.ContentUris
import android.provider.MediaStore
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.lists.DisplayArtists
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** What the library database tells the covers (docs/library.md §13.1). */
@RunWith(AndroidJUnit4::class)
class LibraryCoverFactsTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "One", path = "Music/Ann/Old/", artist = "Ann", album = "Old", year = 1990, trackNumber = 1),
            row(2, "Two", path = "Music/Ann/Old/", artist = "Ann", album = "Old", year = 1990, trackNumber = 2),
            row(3, "Three", path = "Music/Ann/Old/", artist = "Ann", album = "Old", year = 1990, trackNumber = 3),
            row(4, "Four", path = "Music/Ann/New/", artist = "Ann", album = "New", year = 2020, trackNumber = 1),
            row(5, "Five", path = "Music/", artist = "Bob", album = null),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private val facts get() = Covers.facts(context)

    @Before
    fun synced() = runBlocking { Library.sync(context).awaitIdle() }

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun embedded(mediaStoreId: Long, present: Boolean?) = runBlocking {
        Library.database(context).withWriteTransaction {
            usePrepared("UPDATE audio_file SET embedded_cover_present = ? WHERE media_store_id = ?") { statement ->
                if (present == null) statement.bindNull(1) else statement.bindLong(1, if (present) 1 else 0)
                statement.bindLong(2, mediaStoreId)
                statement.step()
            }
        }
    }

    private fun albumKey(mediaStoreId: Long) = runBlocking { facts.file(fileId(mediaStoreId))!!.albumKey!! }

    @Test
    fun aFileComesWithItsUriStateAlbumAndPicture() = runBlocking<Unit> {
        embedded(1, true)
        val id = fileId(1)

        val file = facts.file(id)!!

        assertThat(file.fileId).isEqualTo(id)
        assertThat(file.uri).isEqualTo(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 1).toString())
        assertThat(file.size).isEqualTo(1_001)
        assertThat(file.mtime).isPositive()
        assertThat(file.embedded).isTrue()
        assertThat(file.albumKey).isNotNull()
        assertThat(file.identityId).isNull()
        assertThat(facts.file(fileId(2))!!.embedded).isNull()
        assertThat(facts.file(999_999)).isNull()
    }

    @Test
    fun aFileWithAKnownKeyHasItsIdentity() = runBlocking<Unit> {
        val id = fileId(1)
        Library.database(context).library().setContent(id, "%064x".format(id), 60_000, DurationSource.HEADER)
        Library.identities(context).attach()
        val identity = UserData.database(context).identities().identities().single()

        assertThat(facts.file(id)!!.identityId).isEqualTo(identity.id)
    }

    @Test
    fun theSongThatStandsForAnAlbumIsTheFirstWithAPictureThenUnreadThenTheRest() = runBlocking<Unit> {
        val key = albumKey(1)
        embedded(1, false)
        embedded(2, null)
        embedded(3, true)
        assertThat(facts.albumFile(key)!!.fileId).isEqualTo(fileId(3))

        embedded(3, false)
        assertThat(facts.albumFile(key)!!.fileId).isEqualTo(fileId(2))

        embedded(2, false)
        assertThat(facts.albumFile(key)!!.fileId).isEqualTo(fileId(1))
        assertThat(facts.albumFile("no such album")).isNull()
    }

    @Test
    fun anArtistsAlbumsComeByYearWithTheirPictures() = runBlocking<Unit> {
        embedded(4, true)
        val ann = DisplayArtists.keyOf("Ann")

        val albums = facts.artistAlbums(ann)

        assertThat(albums).containsExactly(ArtistAlbum(albumKey(1), embedded = false), ArtistAlbum(albumKey(4), embedded = true))
        assertThat(facts.artistAlbums(DisplayArtists.keyOf("Bob"))).isEmpty()
    }

    @Test
    fun anArtistWithoutAlbumsHasTheirFirstSong() = runBlocking<Unit> {
        assertThat(facts.artistFile(DisplayArtists.keyOf("Bob"))!!.fileId).isEqualTo(fileId(5))
        assertThat(facts.artistFile("nobody")).isNull()
    }
}
