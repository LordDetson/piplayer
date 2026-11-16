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
import io.github.lorddetson.piplayer.userdata.ListenEventEntity
import io.github.lorddetson.piplayer.userdata.ListenOutcome
import io.github.lorddetson.piplayer.userdata.PlayEventEntity
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The service's recorder of plays (docs/library.md §15.5): a library song's play by its identity in `user.db`, mirrored
 * into its play count, and the outcome of every listening (docs/playback.md §12); opened files and songs whose tags
 * are not read are not recorded.
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
    fun closeFinishesTheWritesAskedForAndEndsTheWriter() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()
        val file = fileOf(1)
        Library.database(context).library().setContent(file, key(1), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        val identity = checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(1))))

        recorder.counted(file.toString(), startedAt = 1_000, listenedMs = 100_000)
        recorder.ended(file.toString(), startedAt = 1_000, listenedMs = 150_000)
        recorder.close()

        // The writer ends with the writes, so that no coroutine outlives the service.
        withTimeout(SAFETY_TIMEOUT_MS) { recorder.awaitClosed() }
        assertThat(UserData.database(context).plays().events()).containsExactly(PlayEventEntity(identity, 1_000, 150_000))
    }

    @Test
    fun openedFilesAndSongsWithoutAnIdentityAreNotRecorded() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()

        recorder.counted("content://open/song", startedAt = 1, listenedMs = 100_000)
        recorder.counted(fileOf(2).toString(), startedAt = 2, listenedMs = 100_000)
        recorder.flush()

        assertThat(UserData.database(context).plays().events()).isEmpty()
    }

    @Test
    fun aListeningOutcomeOfALibrarySongIsRecordedByItsIdentity() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()
        val file = fileOf(1)
        Library.database(context).library().setContent(file, key(1), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        val identity = checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(1))))
        val counter = UserData.database(context).user().changeCounter()

        recorder.listened(file.toString(), startedAt = 1_000, listenedMs = 12_000, durationMs = 200_000, ListenOutcome.EARLY_SKIP)
        // An entry an edit added plays the same file (docs/playback.md §9.4).
        recorder.listened(QueueIds.added(3, file), startedAt = 2_000, listenedMs = 200_000, durationMs = 200_000, ListenOutcome.FINISHED)
        // Opened files and songs whose tags are not read have no identity (docs/playback.md §12.1).
        recorder.listened("content://open/song", startedAt = 3_000, listenedMs = 50_000, durationMs = null, ListenOutcome.LATE_SKIP)
        recorder.listened(fileOf(2).toString(), startedAt = 4_000, listenedMs = 50_000, durationMs = null, ListenOutcome.LATE_SKIP)
        recorder.flush()

        assertThat(UserData.database(context).listens().events()).containsExactly(
            ListenEventEntity(1_000, identity, 12_000, 200_000, ListenOutcome.EARLY_SKIP.code),
            ListenEventEntity(2_000, identity, 200_000, 200_000, ListenOutcome.FINISHED.code),
        )
        assertThat(UserData.database(context).user().changeCounter()).isEqualTo(counter)
    }

    private companion object {
        /** Far more than the writes need; the wait ends when the writer does. */
        const val SAFETY_TIMEOUT_MS = 30_000L
    }
}
