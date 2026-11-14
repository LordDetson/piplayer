package io.github.lorddetson.piplayer.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.lists.GenreKeys
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Rebuilding the library keeps file IDs, the library ID and the identities (docs/library.md §8.5). */
@RunWith(AndroidJUnit4::class)
class LibraryRebuildTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(row(1, name = "a.mp3"), row(2, name = "b.mp3"), row(3, name = "c.mp3"))
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    @Test
    fun aRebuildKeepsFileIdsTheLibraryIdAndIdentities() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()
        val dao = Library.database(context).library()
        // The highest ID of an earlier file that is gone must not come back either.
        index.volume().rows = listOf(row(1, name = "a.mp3"), row(2, name = "b.mp3"))
        Library.sync(context).sync()
        val ids = dao.files().associate { it.displayName to it.id }
        val libraryId = dao.libraryId()
        for (file in dao.files()) dao.setContent(file.id, "%064x".format(file.id), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        val identities = UserData.database(context).identities().identities().map { it.id }.sorted()
        assertThat(identities).hasSize(2)

        index.volume().rows = listOf(row(1, name = "a.mp3"), row(2, name = "b.mp3"), row(4, name = "d.mp3"))
        Library.rebuild(context)
        withTimeout(30_000) { Library.status(context).first { it.ready && it.known } }
        Library.sync(context).awaitIdle()

        val rebuilt = Library.database(context).library()
        val after = rebuilt.files().associate { it.displayName to it.id }
        assertThat(after.filterKeys { it != "d.mp3" }).isEqualTo(ids)
        assertThat(after.getValue("d.mp3")).isGreaterThan(3)
        assertThat(rebuilt.libraryId()).isEqualTo(libraryId)
        // The keys are gone until the pass reads the files again, and the identities wait for them.
        assertThat(rebuilt.files().all { it.contentKey == null }).isTrue()
        assertThat(UserData.database(context).identities().identities().map { it.id }.sorted()).isEqualTo(identities)
    }

    /** The genres come back with their keys, which the genre pages are found by (docs/library.md §19.4). */
    @Test
    fun aRebuildGivesTheSameGenres() = runBlocking<Unit> {
        index.volume().rows = listOf(
            row(1, name = "a.mp3", genre = "Rock; Pop", generation = 11),
            row(2, name = "b.mp3", genre = "rock", generation = 12),
            row(3, name = "c.mp3", generation = 13),
        )
        Library.sync(context).request()
        Library.sync(context).awaitIdle()
        fun genres() = Library.read(context) { LibraryLists(it).genres() }.map { Triple(it.key, it.name, it.songs) }
        val before = genres()
        assertThat(before).containsExactly(Triple("pop", "Pop", 1), Triple("rock", "Rock", 2), Triple(GenreKeys.NONE, "", 1))

        Library.rebuild(context)
        withTimeout(30_000) { Library.status(context).first { it.ready && it.known } }
        Library.sync(context).awaitIdle()

        assertThat(genres()).isEqualTo(before)
    }
}
