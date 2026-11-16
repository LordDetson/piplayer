package io.github.lorddetson.piplayer.identification.pipeline

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.db.CandidateEntity
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.LookupJobEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The working rows of identification in the library database (TI §10.1, §11.2): the lookup job by content key, the
 * candidates of one key replaced in position order, and the trace runs of a key in the order they were written.
 */
@RunWith(AndroidJUnit4::class)
class RoomIdentificationStoreTest {

    private val library = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val store = RoomIdentificationStore(LibraryAccess.of(library))
    private val dao = library.identificationRuns()

    @After
    fun close() = library.close()

    private fun candidate(key: String, position: Int) =
        CandidateEntity(key, position, "fingerprint", "rec-$position", null, 0.9 - position / 100.0, "{}", "{}")

    private fun trace(key: String, run: String, position: Int, startedAt: Long) =
        IdentificationTraceEntity(key, run, position, startedAt, "local_read", null, null, null, null, 1, null)

    @Test
    fun aLookupJobIsKeptByContentKeyAndDeleted() = runBlocking<Unit> {
        val job = LookupJobEntity(KEY, null, "lookup", "retry_later", 1, 1_000, "acoustid_empty")

        store.write { saveJob(job) }
        assertThat(store.job(KEY)).isEqualTo(job)
        assertThat(store.job(OTHER)).isNull()

        store.write { saveJob(job.copy(status = "final", attempts = 2, nextAttemptAt = null)) }
        assertThat(store.job(KEY)!!.status).isEqualTo("final")

        store.write { dropJob(KEY) }
        assertThat(store.job(KEY)).isNull()
    }

    @Test
    fun candidatesOfAKeyAreReplacedInPositionOrderWithoutTouchingAnotherKey() = runBlocking<Unit> {
        store.write {
            replaceCandidates(KEY, listOf(candidate(KEY, 1), candidate(KEY, 0), candidate(KEY, 2)))
            replaceCandidates(OTHER, listOf(candidate(OTHER, 0)))
        }
        assertThat(dao.candidates(KEY).map { it.position }).containsExactly(0, 1, 2)

        store.write { replaceCandidates(KEY, listOf(candidate(KEY, 0))) }

        assertThat(dao.candidates(KEY)).containsExactly(candidate(KEY, 0))
        assertThat(dao.candidates(OTHER)).containsExactly(candidate(OTHER, 0))

        store.write { replaceCandidates(KEY, emptyList()) }
        assertThat(dao.candidates(KEY)).isEmpty()
    }

    @Test
    fun traceRunsComeInTheOrderTheyWereWrittenAndOnlyTheNamedOnesAreDropped() = runBlocking<Unit> {
        // The second run has the earlier start, as after a clock set back: the order of writing decides.
        store.write { addTrace(listOf(trace(KEY, "run-b", 0, 5_000), trace(KEY, "run-b", 1, 5_000))) }
        store.write { addTrace(listOf(trace(KEY, "run-a", 0, 1_000))) }
        store.write { addTrace(listOf(trace(OTHER, "run-c", 0, 9_000))) }
        store.write { addTrace(listOf(trace(KEY, "run-d", 0, 7_000))) }

        var runs: List<String> = emptyList()
        store.write { runs = tracedRuns(KEY) }
        assertThat(runs).containsExactly("run-b", "run-a", "run-d")

        store.write { dropTracedRuns(KEY, listOf("run-b")) }

        assertThat(dao.trace(KEY).map { it.runId }).containsExactly("run-a", "run-d")
        assertThat(dao.trace(OTHER).map { it.runId }).containsExactly("run-c")
    }

    @Test
    fun aWriteThatFailsKeepsNothingOfItsTransaction() = runBlocking<Unit> {
        val failed = runCatching {
            store.write {
                replaceCandidates(KEY, listOf(candidate(KEY, 0)))
                error("failed in the middle")
            }
        }

        assertThat(failed.isFailure).isTrue()
        assertThat(dao.candidates(KEY)).isEmpty()
    }

    private companion object {
        val KEY = "ab".repeat(32)
        val OTHER = "cd".repeat(32)
    }
}
