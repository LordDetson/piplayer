package io.github.lorddetson.piplayer.library

import android.provider.MediaStore
import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.TimingTest
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.DecisionFixtures
import io.github.lorddetson.piplayer.userdata.UserData
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The cost of the decided values (TI §7.1, §10.3; §11.2 M3 "Timing") on the generated library of [GeneratedLibraryTest].
 * Skipped on devices without the library.
 *
 * The budgets are relative to runs of the same test, so that the host's load cancels out: each write path that joins
 * the decided values (a sync that stores every file again under a new index version, a full refresh of every display
 * row, a refresh through the writer) runs twice without and twice with the decided values, alternately, and the faster
 * of each pair counts. Without them, every decision is undone (see [live]), as in a library without decisions: the
 * mirror runs that the write paths ask for themselves would otherwise fill `decided_mirror` again halfway through. With
 * a decision on a fifth of the 20 000 files, the decided values may add at most [JOIN_MS_PER_FILE] for each decided file.
 * The start-up mirror of all 4 000 decisions may take as long as the faster full refresh without them, and half of that
 * when it finds every value mirrored. With a decision on every file, a whole run that finds every value mirrored may take
 * as long as a full refresh of the same run, and it holds off the sync for at most half of that.
 *
 * A first sync on an empty library database joins no decided value (no file has a content key yet), so it is not
 * measured here. Measured before M3 step 12 on the API 36 emulator (Pixel_8, host AMD Ryzen 7 5800HS): first sync at most
 * 2.31 s, full refresh at most 2.52 s.
 *
 * Measured on 16.11 in M3 step 12, in three quiet passes (`--timing`) on the API 36 emulator (Pixel_8, 4 cores; the
 * helper printed a host CPU of 21 %, 20 % and 20 %):
 * - a decision on every file: full refresh 3.19 s in each pass; the whole run that finds every value mirrored 1.74 s,
 *   1.74 s and 1.68 s, holding off the sync at most 86 ms, 98 ms and 93 ms. Before step 12's changes (compared under the
 *   hold, both sides built as maps of rows, decoding and resolving on one core) it took 4.56 s and held off the sync
 *   1.76 s, against a full refresh of 3.20 s;
 * - a decision on a fifth: the decided values added 0.30 s, 0.37 s and 0.42 s to the sync that stores every file again,
 *   0.37 s, 0.21 s and 0.32 s to the full refresh and 0.36 s, 0.37 s and 0.35 s to the writer's refresh (budget 0.60 s);
 *   the start-up mirror took 1.20 s, 1.19 s and 1.16 s (budgets 1.74 s, 1.91 s and 1.71 s), and 0.37 s, 0.36 s and 0.37 s
 *   when it found every value mirrored.
 */
@RunWith(AndroidJUnit4::class)
class GeneratedLibraryDecisionsTest {

    private val context = TestFiles.targetContext
    private val library = LibraryRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(library)

    @Before
    fun needsTheGeneratedLibrary() {
        val count = context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$ROOT%"),
            null,
        )?.use { it.count } ?: 0
        assumeTrue("The generated library is not on this device ($count files in $ROOT)", count >= FILES)
    }

    private fun key(n: Int) = "%064x".format(n.toLong())

    private val database get() = Library.database(context)
    private val dao get() = database.library()
    private val mirror get() = Library.decisionMirror(context)

    /** The generated files with the first [decided] of every [step]th one keyed and identified, as the tag pass would key them. */
    private suspend fun decide(decided: Int, step: Int): List<AudioFileEntity> {
        Library.sync(context).awaitIdle()
        DecisionFixtures.identifyInBulk(UserData.database(context), List(decided) { key(it + 1) })
        val files = dao.files().filter { it.relativePath.startsWith(ROOT) }.sortedBy { it.id }
        val keyed = files.filterIndexed { i, _ -> i % step == 0 }.take(decided)
        database.withWriteTransaction {
            for ((i, file) in keyed.withIndex()) dao.setContent(file.id, key(i + 1), 200_000, DurationSource.HEADER)
        }
        settle()
        return keyed
    }

    /** Waits until the background work that a sync asks for has ended, so that it measures nothing of it. */
    private suspend fun settle() {
        Library.sync(context).awaitIdle()
        Library.identities(context).awaitIdle()
        mirror.awaitIdle()
    }

    /**
     * Whether the decisions decide anything: without, as in a library before M3 or one without decisions, every decision
     * stays but is undone, so that the mirror runs the write paths ask for themselves (each tag pass batch, an attach pass
     * that writes) keep `decided_mirror` empty, as they do in such a library. Written behind the app's back, as a fixture:
     * the change counter does not move, and [decided] brings the library database level.
     */
    private suspend fun live(live: Boolean) {
        UserData.database(context).withWriteTransaction {
            usePrepared("UPDATE decision SET undone_at = ${if (live) "NULL" else "1"}") { it.step() }
        }
    }

    /** Without decided values, as before M3 (see [live]), or with them, mirrored by a whole run. */
    private suspend fun decided(mirrored: Boolean) {
        live(mirrored)
        mirror.mirrorAll()
        settle()
        assertThat(database.decided().all().isEmpty()).describedAs("decided values mirrored").isEqualTo(!mirrored)
    }

    /** A sync that stores every file again: the index version it has stored is gone. */
    private suspend fun restore(): Duration {
        for (volume in dao.volumes()) dao.saveVolume(volume.copy(version = "before"))
        return measureTime { Library.sync(context).sync() }.also { settle() }
    }

    /** A new display version: every display row is derived again, as after an update. */
    private suspend fun fullRefresh(): Duration {
        dao.setDisplayVersion(0)
        return measureTime { Library.sync(context).sync() }.also { settle() }
    }

    private suspend fun writerRefresh(): Duration {
        val ids = dao.files().map { it.id }
        return measureTime { TrackDisplayWriter(database).refresh(ids) }.also { settle() }
    }

    /** One pass of every write path. */
    private suspend fun paths(): List<Duration> = listOf(restore(), fullRefresh(), writerRefresh())

    @Test
    @TimingTest
    fun theWritePathsWithDecisionsOnAFifthOfTheFiles() = runBlocking<Unit> {
        val keyed = decide(DECIDED, step = 5)
        decided(mirrored = false)

        val without1 = paths()
        assertThat(database.decided().all()).describedAs("decided values while measuring without them").isEmpty()
        // As the start of the next process finds them: the decided values not mirrored yet.
        live(true)
        val startUpMirror = measureTime { mirror.mirrorAll() }
        assertThat(dao.displays().single { it.fileId == keyed[7].id }.title).isEqualTo("Decided 7")
        // The common start: a favorite or a play raised the counter, and every decided value is mirrored already.
        val quietMirror = measureTime { assertThat(mirror.mirrorAll()).isFalse() }
        settle()
        val with1 = paths()
        decided(mirrored = false)
        val without2 = paths()
        assertThat(database.decided().all()).describedAs("decided values while measuring without them").isEmpty()
        decided(mirrored = true)
        val with2 = paths()

        val displays = dao.displays().associateBy { it.fileId }
        assertThat(keyed).allSatisfy { assertThat(displays.getValue(it.id).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED) }
        assertThat(displays.getValue(keyed[1234].id).title).isEqualTo("Decided 1234")
        val without = without1.zip(without2) { a, b -> minOf(a, b) }
        val with = with1.zip(with2) { a, b -> minOf(a, b) }
        Log.i(
            TAG,
            "Decisions on ${keyed.size} files, without and with them: store again ${without1[0]} ${without2[0]} / ${with1[0]} " +
                "${with2[0]}, full refresh ${without1[1]} ${without2[1]} / ${with1[1]} ${with2[1]}, writer refresh ${without1[2]} " +
                "${without2[2]} / ${with1[2]} ${with2[2]}; start-up mirror $startUpMirror, with nothing to change $quietMirror",
        )

        val join = (JOIN_MS_PER_FILE * keyed.size).toLong()
        for ((i, path) in listOf("store again", "full refresh", "writer refresh").withIndex()) {
            assertThat(with[i].inWholeMilliseconds - without[i].inWholeMilliseconds).describedAs("$path: cost of the decided values")
                .isLessThanOrEqualTo(join)
        }
        assertThat(startUpMirror).describedAs("start-up mirror").isLessThanOrEqualTo(without[1])
        assertThat(quietMirror).describedAs("start-up mirror with nothing to change").isLessThanOrEqualTo(without[1] / 2)
    }

    @Test
    @TimingTest
    fun aWholeRunThatFindsEveryValueMirroredOnADecisionForEveryFile() = runBlocking<Unit> {
        decide(FILES, step = 1)
        decided(mirrored = true)
        val fullRefresh = minOf(fullRefresh(), fullRefresh())

        val sync = Library.sync(context)
        var quiet = Duration.ZERO
        var longestHold = Duration.ZERO
        coroutineScope {
            val run = async(Dispatchers.IO) { measureTime { assertThat(mirror.mirrorAll()).isFalse() } }
            // A sync asked for meanwhile waits for the part of the run that holds the library database.
            while (run.isActive) {
                val waited = measureTime { sync.exclusive { } }
                if (waited > longestHold) longestHold = waited
                delay(5)
            }
            quiet = run.await()
        }
        Log.i(
            TAG,
            "Decisions on $FILES files: full refresh $fullRefresh, whole run with nothing to change $quiet, holding off the sync " +
                "$longestHold",
        )

        assertThat(quiet).describedAs("whole run with nothing to change").isLessThanOrEqualTo(fullRefresh)
        assertThat(longestHold).describedAs("the sync held off by it").isLessThanOrEqualTo(fullRefresh / 2)
    }

    private companion object {
        const val TAG = "GeneratedLibrary"
        const val ROOT = "Music/PiPlayerTest20k/"
        const val FILES = 20_000
        const val DECIDED = 4_000

        /** What the decided values may add to a write path for each decided file: 600 ms at 4 000 (TI §11.2). */
        const val JOIN_MS_PER_FILE = 0.15
    }
}
