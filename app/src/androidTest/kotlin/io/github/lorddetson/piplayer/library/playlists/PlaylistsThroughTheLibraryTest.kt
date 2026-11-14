package io.github.lorddetson.piplayer.library.playlists

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Playlists through the real library (docs/library.md §16.7): entries keep resolving after moves, renames, index
 * rebuilds, rebuilds and deletions of the library database; unavailable entries come back with their files; M3U8 export
 * and import round-trip on the device.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistsThroughTheLibraryTest {

    private val context = TestFiles.targetContext
    private val rows = listOf(
        row(1, path = "Music/A/"),
        row(2, path = "Music/A/"),
        row(3, path = "Music/B/"),
        row(4, path = "Music/B/"),
    )
    private val index = FakeMediaIndex().apply { volume().rows = rows }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private val playlists get() = Library.playlists(context)
    private val user get() = UserData.playlists(context)

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun ready() {
        withTimeout(30_000) { Library.status(context).first { it.ready && it.known } }
        Library.sync(context).awaitIdle()
    }

    private suspend fun sync() {
        Library.sync(context).sync()
        ready()
    }

    /** What the tag pass would store: each file's key by its media ID, with the index's length; then the attach pass. */
    private suspend fun keysComeBack() {
        val dao = Library.database(context).library()
        for (file in dao.files()) dao.setContent(file.id, key(file.mediaStoreId % 100), 60_000, DurationSource.HEADER)
        Library.identities(context).attach()
    }

    private suspend fun fileOf(mediaStoreId: Long) =
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun identityOf(n: Long) = UserData.database(context).identities().idOfKey(Hex.bytes(key(n)))

    /** The titles the playlist shows, null for an unavailable entry. */
    private fun songs(id: Long) = playlists.items(id).map { it.song?.title }

    /** Songs 1, 2 and 1 again, after the tag pass. */
    private suspend fun playlist(): Long {
        ready()
        keysComeBack()
        val id = user.create("P")
        playlists.addFiles(id, listOf(fileOf(1), fileOf(2), fileOf(1)))
        assertThat(songs(id)).containsExactly("Song 1", "Song 2", "Song 1")
        assertThat(user.entries(id).map { it.identityId }).containsExactly(identityOf(1), identityOf(2), identityOf(1))
        return id
    }

    private fun IndexRow.moved(path: String = relativePath, name: String = displayName, id: Long = mediaStoreId) =
        copy(relativePath = path, displayName = name, mediaStoreId = id, generationModified = generationModified + 100)

    @Test
    fun entriesResolveAfterAMoveARenameAndAnIndexRebuildWithNewIds() = runBlocking<Unit> {
        val id = playlist()

        index.volume().rows = listOf(rows[0].moved(path = "Music/C/"), rows[1].moved(name = "Two.mp3"), rows[2], rows[3])
        sync()
        keysComeBack()
        assertThat(songs(id)).containsExactly("Song 1", "Song 2", "Song 1")

        index.volume().version = "2"
        index.volume().rows = index.volume().rows.map { it.copy(mediaStoreId = it.mediaStoreId + 100, generationModified = 1) }
        sync()
        keysComeBack()
        assertThat(songs(id)).containsExactly("Song 1", "Song 2", "Song 1")
    }

    @Test
    fun entriesSurviveARebuildOfTheLibrary() = runBlocking<Unit> {
        val id = playlist()

        Library.rebuild(context)
        ready()
        // No keys yet: the identities have no file.
        assertThat(songs(id)).containsExactly(null, null, null)

        keysComeBack()
        assertThat(songs(id)).containsExactly("Song 1", "Song 2", "Song 1")
    }

    @Test
    fun entriesSurviveADeletionOfTheLibraryDatabase() = runBlocking<Unit> {
        val id = playlist()

        Library.reopen(context, index, empty = true)
        LibraryRule.startAsANewProcess()
        ready()
        keysComeBack()

        assertThat(songs(id)).containsExactly("Song 1", "Song 2", "Song 1")
    }

    @Test
    fun anEntryOfADeletedFileStaysUnavailableAndComesBackWithTheFile() = runBlocking<Unit> {
        val id = playlist()

        index.volume().rows = listOf(rows[0], rows[2], rows[3])
        sync()
        keysComeBack()
        val items = playlists.items(id)
        assertThat(items.map { it.song?.title }).containsExactly("Song 1", null, "Song 1")
        assertThat(items[1].title).isEqualTo("Song 2")
        // The playlist plays what is there.
        assertThat(SongList.load(context, query = SongQuery.playlist(id)).map { it.title }).containsExactly("Song 1", "Song 1")

        index.volume().rows = rows.map { it.moved() }
        sync()
        keysComeBack()
        assertThat(songs(id)).containsExactly("Song 1", "Song 2", "Song 1")
    }

    @Test
    fun anEntryAddedBeforeTheTagsAreReadPlaysAndIsLinkedOnceTheSongHasAnIdentity() = runBlocking<Unit> {
        ready()
        val id = user.create("P")
        playlists.addFiles(id, listOf(fileOf(3)))

        assertThat(user.entries(id).single().identityId).isNull()
        assertThat(songs(id)).containsExactly("Song 3")

        keysComeBack()
        assertThat(user.entries(id).single().identityId).isEqualTo(identityOf(3))
        assertThat(songs(id)).containsExactly("Song 3")
    }

    @Test
    fun step5BringsBackAnEntryWhoseFileChangedItsAudioAndMoved() = runBlocking<Unit> {
        ready()
        keysComeBack()
        val id = user.create("P")
        playlists.addFiles(id, listOf(fileOf(4)))
        val identity = identityOf(4)

        // Another key (media ID 14) at another place under the same name and length: only the entry's hints find it.
        index.volume().rows = listOf(rows[0], rows[1], rows[2], rows[3].moved(path = "Music/Z/", id = 14))
        sync()
        keysComeBack()

        assertThat(user.entries(id).single().identityId).isEqualTo(identity)
        assertThat(identityOf(14)).isEqualTo(identity)
        assertThat(playlists.items(id).single().song?.id).isEqualTo(fileOf(14))
    }

    @Test
    fun anImportedLineThatFoundNothingComesBackWhenItsFileAppears() = runBlocking<Unit> {
        ready()
        keysComeBack()
        val paths = PlaylistFiles(context).paths()
        val text = "#EXTM3U\n#EXTINF:60,Artist 9 - Song 9\n/storage/emulated/0/Music/New/9.mp3\n" +
            "#EXTINF:60,Artist 3 - Song 3\n/storage/emulated/0/Music/B/3.mp3\nhttp://radio.example/stream\n"

        val result = playlists.import(text, "Imported", folder = null, paths = paths)

        assertThat(result.name).isEqualTo("Imported")
        assertThat(result.found to result.total).isEqualTo(1 to 3)
        val items = playlists.items(result.playlistId)
        assertThat(items.map { it.song?.title }).containsExactly(null, "Song 3")
        assertThat(items[0].title to items[0].artist).isEqualTo("Song 9" to "Artist 9")

        index.volume().rows = rows + row(9, path = "Music/New/")
        sync()
        keysComeBack()
        assertThat(user.entries(result.playlistId).first().identityId).isEqualTo(identityOf(9))
        assertThat(songs(result.playlistId)).containsExactly("Song 9", "Song 3")
    }

    @Test
    fun anExportImportedAgainOnTheDeviceGivesTheSameSongs() = runBlocking<Unit> {
        val id = playlist()
        // An entry whose file is gone is written from its hints and comes back unavailable.
        playlists.addFiles(id, listOf(fileOf(4)))
        index.volume().rows = rows.take(3)
        sync()
        keysComeBack()
        val paths = PlaylistFiles(context).paths()

        val text = playlists.export(id, paths)

        assertThat(text.lines().first()).isEqualTo("#EXTM3U")
        assertThat(text).contains("#EXTINF:60,Artist 1 - Song 1\n/storage/emulated/0/Music/A/1.mp3\n")
        assertThat(text).contains("/storage/emulated/0/Music/B/4.mp3")
        val result = playlists.import(text, "Again", folder = null, paths = paths)
        assertThat(result.found to result.total).isEqualTo(3 to 4)
        assertThat(playlists.items(result.playlistId).map { it.song?.id })
            .containsExactlyElementsOf(playlists.items(id).map { it.song?.id })
    }

    @Test
    fun aLegacyM3uFromTheFilePickerReads() = runBlocking<Unit> {
        index.volume().rows = rows + row(5, title = "Звезда", path = "Music/Кино/", name = "Звезда.mp3", artist = "Кино")
        sync()
        val file = File(context.cacheDir, "Старый список.m3u")
        file.writeBytes("#EXTINF:60,Кино - Звезда\r\n/storage/emulated/0/Music/Кино/Звезда.mp3\r\n".toByteArray(charset("windows-1251")))
        val settings = LibrarySettings(context)
        settings.setLegacyEncoding(LegacyEncodings.fromStored("windows-1251"))
        try {
            val result = PlaylistFiles(context).import(Uri.fromFile(file))

            assertThat(result.name).isEqualTo("Старый список")
            assertThat(result.found to result.total).isEqualTo(1 to 1)
            assertThat(songs(result.playlistId)).containsExactly("Звезда")
        } finally {
            settings.setLegacyEncoding(null)
            file.delete()
        }
    }

    @Test
    fun anExportWritesTheDocumentThePickerGave() = runBlocking<Unit> {
        val id = playlist()
        val file = File(context.cacheDir, "export-test.m3u8").also { it.delete() }

        val written = PlaylistFiles(context).export(id, Uri.fromFile(file))

        assertThat(written).isEqualTo(3)
        assertThat(file.readText()).startsWith("#EXTM3U\n#EXTINF:60,Artist 1 - Song 1\n/storage/emulated/0/Music/A/1.mp3\n")
        file.delete()
    }
}
