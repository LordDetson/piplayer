package io.github.lorddetson.piplayer.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.PlayEventEntity
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The service's recorder of plays (docs/library.md §15.5): a library song's play by its identity in `user.db`, mirrored
 * into its play count; opened files and songs whose tags are not read are not recorded.
 */
@RunWith(AndroidJUnit4::class)
class LibraryPlayRecorderTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply { volume().rows = listOf(row(1), row(2)) }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private val recorder = LibraryPlayRecorder(context)

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileOf(mediaStoreId: Long) =
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id

    @Test
    fun aCountedPlayOfALibrarySongIsRecordedByItsIdentityAndMirrored() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()
        val file = fileOf(1)
        Library.database(context).library().setContent(file, key(1), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        val identity = checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(1))))

        recorder.counted(file.toString(), startedAt = 1_000, listenedMs = 100_000)
        recorder.ended(file.toString(), startedAt = 1_000, listenedMs = 180_000)
        recorder.flush()

        assertThat(UserData.database(context).plays().events()).containsExactly(PlayEventEntity(identity, 1_000, 180_000))
        // The raised change counter asks the mirror for a run as well; this one is the test's own.
        Library.mirror(context).mirror()
        assertThat(Library.read(context) { LibraryLists(it).recentlyPlayed(limit = 5) }.map { it.id }).containsExactly(file)
    }

    @Test
    fun openedFilesAndSongsWithoutAnIdentityAreNotRecorded() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()

        recorder.counted("content://open/song", startedAt = 1, listenedMs = 100_000)
        recorder.counted(fileOf(2).toString(), startedAt = 2, listenedMs = 100_000)
        recorder.flush()

        assertThat(UserData.database(context).plays().events()).isEmpty()
    }
}
