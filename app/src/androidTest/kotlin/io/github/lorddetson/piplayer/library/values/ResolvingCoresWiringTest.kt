package io.github.lorddetson.piplayer.library.values

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.sync.DisplayInput
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bulk decoding and resolving of the decision mirror's snapshot and of the display writer run on [ResolvingCores],
 * so that a start-up whole run leaves a core for the writing thread, playback and the interface (TI §10.3). Each is
 * started while every resolving core is taken: it must wait, and finish once they are free.
 */
@RunWith(AndroidJUnit4::class)
class ResolvingCoresWiringTest {

    @Test
    fun theSnapshotOfEveryDecisionResolvesOnTheResolvingCores() {
        val database = UserDatabase.open(TestFiles.targetContext, name = null)
        try {
            var now = 1_800_000_000_000L
            val decisions = Decisions(database) { now++ }
            for (n in 1L..3L) runBlocking {
                database.identities().add(
                    TrackIdentityEntity(
                        contentKey = Hex.bytes(key(n)), durationMs = 200_000, recordingMbid = null,
                        hintVolumeName = "external_primary", hintRelativePath = "Music/", hintDisplayName = "$n.mp3",
                        hintSize = 1_000, createdAt = 1, missingSince = null,
                    ),
                )
                checkNotNull(decisions.apply(request(key(n))))
            }

            waitsForTheResolvingCores { assertThat(decisions.snapshot().byKey.keys).containsExactlyInAnyOrder(key(1), key(2), key(3)) }
        } finally {
            database.close()
        }
    }

    @Test
    fun theDisplayWriterResolvesOnTheResolvingCores() {
        // Few, so that on another dispatcher they are resolved well within the window even on one slow core.
        val inputs = List(20) { DisplayInput(fileId = it.toLong(), displayName = "Song $it.mp3", index = null) }

        waitsForTheResolvingCores { assertThat(TrackDisplayWriter.resolve(inputs)).hasSize(inputs.size) }
    }

    private fun key(n: Long) = "%064x".format(n)

    /**
     * Runs [work] once freely (which also loads what it needs), then again while a blocked task holds each of the
     * [ResolvingCores.count] cores: the second run must not finish within [WINDOW_MS], and must once they are free.
     */
    private fun waitsForTheResolvingCores(work: suspend () -> Unit) = runBlocking<Unit> {
        work()
        val taken = CountDownLatch(ResolvingCores.count)
        val free = CountDownLatch(1)
        val holders = List(ResolvingCores.count) {
            launch(ResolvingCores.dispatcher) {
                taken.countDown()
                // Blocking the thread, as decoding and resolving do, so that the core stays taken.
                free.await()
            }
        }
        try {
            check(taken.await(10, TimeUnit.SECONDS)) { "the resolving cores were not all taken" }
            val run = async(Dispatchers.IO) { work() }
            delay(WINDOW_MS)
            assertThat(run.isCompleted).describedAs("finished while every resolving core was busy").isFalse()
            free.countDown()
            withTimeout(30_000) { run.await() }
        } finally {
            free.countDown()
        }
        holders.joinAll()
    }

    private companion object {
        /** Far longer than the free run takes; on another dispatcher the work finishes well within it. */
        const val WINDOW_MS = 1_500L
    }
}
