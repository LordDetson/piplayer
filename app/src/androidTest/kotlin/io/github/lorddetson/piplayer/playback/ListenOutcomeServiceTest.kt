package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.ListenOutcome
import io.github.lorddetson.piplayer.userdata.UserData
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The outcome of every listening written through the playback service (docs/playback.md §12, docs/library.md §15.7): the
 * service's own tracker and recorder, on the real clock, write a library song's outcome into `user.db` by its identity.
 * The library is the app's, filled from a test index; the songs the service plays are those files, with test audio.
 */
@RunWith(AndroidJUnit4::class)
class ListenOutcomeServiceTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply { volume().rows = listOf(row(1), row(2)) }
    private val songs = SongSourceRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(songs)

    private lateinit var controller: MediaController
    private var files = emptyList<Long>()
    private var identities = emptyList<Long>()
    private var counter = 0L

    private fun key(n: Long) = "%064x".format(n)

    /** Two library songs with identities: a 12 s one first in the list, then a 2 s one. */
    @Before
    fun prepare() = runBlocking {
        Library.sync(context).awaitIdle()
        val dao = Library.database(context).library()
        files = listOf(1L, 2L).map { n -> dao.files().single { it.mediaStoreId == n }.id }
        files.forEachIndexed { i, file -> dao.setContent(file, key(i + 1L), 200_000, DurationSource.HEADER) }
        Library.identities(context).attach()
        identities = listOf(1L, 2L).map { n -> checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(n)))) }
        songs.songs = listOf(
            SongSourceRule.song(files[0], "A long song", PlaybackTesting.LONG_FIXTURE),
            SongSourceRule.song(files[1], "B short song", PlaybackTesting.SHORT_FIXTURE),
        )
        counter = UserData.database(context).user().changeCounter()
        controller = connectController()
        onMain {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
        }
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    @Test
    fun aSongSkippedWithNextAndTheNextOnePlayedToTheEndOfTheQueueAreWrittenByTheirIdentities() {
        // A playback the service still holds from a test before ends at the tap; its outcome, if any, started earlier.
        val since = System.currentTimeMillis()
        controller.tap(QueueRequest(startSongId = files[0], startRow = 0))
        controller.waitUntil("the long song plays") { isPlaying && currentMediaItemIndex == 0 && mediaItemCount == 2 }
        controller.waitUntil("it has played a moment") { currentPosition >= 500 }

        onMain { controller.seekToNextMediaItem() }
        controller.waitUntil("the short song plays") { currentMediaItemIndex == 1 && isPlaying }
        controller.waitUntil("the queue ends", 15.seconds) { playbackState == Player.STATE_ENDED }

        fun events() = runBlocking { UserData.database(context).listens().events() }.filter { it.startedAt >= since }
        eventually("both outcomes are written", 30.seconds) { events().size >= 2 }
        val events = events()
        assertThat(events.map { it.identityId to it.outcome }).containsExactly(
            identities[0] to ListenOutcome.EARLY_SKIP.code,
            identities[1] to ListenOutcome.FINISHED.code,
        )
        val (skipped, finished) = events
        assertThat(skipped.listenedMs).isBetween(1L, EARLY_SKIP_MAX_MS)
        assertThat(skipped.durationMs).isNotNull()
        assertThat(skipped.durationMs!!).isCloseTo(SongSourceRule.LONG_FIXTURE_MS, within(100L))
        assertThat(finished.durationMs).isNotNull()
        assertThat(finished.durationMs!!).isCloseTo(2_000L, within(100L))
        assertThat(finished.startedAt).isGreaterThanOrEqualTo(skipped.startedAt)
        // Nothing of it is mirrored, and neither song reached a play (docs/playback.md §11.2): the counter stays.
        assertThat(runBlocking { UserData.database(context).user().changeCounter() }).isEqualTo(counter)
    }

    private companion object {
        /** The most listened time an early skip has (docs/playback.md §12.2). */
        const val EARLY_SKIP_MAX_MS = 29_999L
    }
}
