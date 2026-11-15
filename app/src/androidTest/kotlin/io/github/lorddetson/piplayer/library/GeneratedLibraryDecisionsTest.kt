package io.github.lorddetson.piplayer.library

import android.provider.MediaStore
import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.TimingTest
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.DecisionFixtures
import io.github.lorddetson.piplayer.userdata.UserData
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The cost of the decided values (TI §7.1, §10.3; §11.2 M3 "Timing") on the generated library of [GeneratedLibraryTest]:
 * a fifth of its 20 000 files identified. Skipped on devices without the library.
 *
 * The budgets come from the quiet pass on the local API 36 emulator (Pixel_8, host AMD Ryzen 7 5800HS, the host at 31 to
 * 35 % CPU) before M3 step 12, on the same library without decisions: first sync 2.31, 1.99 and 2.07 s; full refresh (a
 * new display version) 2.23, 2.52 and 2.31 s; refresh of every file through the writer 2.23, 1.93 and 2.10 s. With the
 * decisions each may take half as long again as the slowest of its runs. The start-up mirror of all 4 000 decisions,
 * which has no baseline, may take as long as the slowest full refresh, and a start-up mirror that finds every value
 * mirrored already, as after a session that only played and liked songs, half of that.
 *
 * Measured with the decisions in M3 (host at 20 to 22 %): first sync 1.46 to 1.50 s, start-up mirror 1.48 to 1.51 s,
 * start-up mirror with nothing to change 0.75 to 0.78 s, full refresh 1.68 to 1.75 s, writer refresh 1.96 to 2.53 s.
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

    @Test
    @TimingTest
    fun theFirstSyncAndAFullRefreshWithDecisionsOnAFifthOfTheFiles() = runBlocking<Unit> {
        // The rule's own first sync, then a new empty library database for the measured one, with the decisions in the
        // user database as after an update that rebuilds the library.
        Library.sync(context).awaitIdle()
        Library.reopen(context, empty = true)
        SongList.forget()
        DecisionFixtures.identifyInBulk(UserData.database(context), List(DECIDED) { key(it + 1) })

        val firstSync = measureTime {
            LibraryRule.startAsANewProcess()
            Library.sync(context).awaitIdle()
        }
        val database = Library.database(context)
        val dao = database.library()
        val files = dao.files().filter { it.relativePath.startsWith(ROOT) }.sortedBy { it.id }
        // Every fifth file gets a content key, as the tag pass would give it: the keys of the decisions.
        val keyed = files.filterIndexed { i, _ -> i % 5 == 0 }.take(DECIDED)
        database.withWriteTransaction {
            for ((i, file) in keyed.withIndex()) dao.setContent(file.id, key(i + 1), 200_000, DurationSource.HEADER)
        }
        Library.identities(context).awaitIdle()
        Library.decisionMirror(context).awaitIdle()
        // As the start of the next process finds them: the decided values not mirrored yet.
        database.withWriteTransaction { database.decided().clear() }

        val startUpMirror = measureTime { Library.decisionMirror(context).mirrorAll() }
        assertThat(dao.displays().single { it.fileId == keyed[7].id }.title).isEqualTo("Decided 7")
        // The common start: a favorite or a play raised the counter, and every decided value is mirrored already.
        val quietMirror = measureTime { assertThat(Library.decisionMirror(context).mirrorAll()).isFalse() }

        // A new display version: every display row is derived again, as after an update.
        dao.setDisplayVersion(0)
        val fullRefresh = measureTime { Library.sync(context).sync() }
        val writerRefresh = measureTime { TrackDisplayWriter(database).refresh(dao.files().map { it.id }) }

        val displays = dao.displays().associateBy { it.fileId }
        assertThat(keyed).allSatisfy { assertThat(displays.getValue(it.id).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED) }
        assertThat(displays.getValue(keyed[1234].id).title).isEqualTo("Decided 1234")
        log(firstSync, startUpMirror, quietMirror, fullRefresh, writerRefresh, keyed.size)

        assertThat(firstSync.inWholeMilliseconds).describedAs("first sync").isLessThanOrEqualTo(FIRST_SYNC_MS)
        assertThat(fullRefresh.inWholeMilliseconds).describedAs("full refresh").isLessThanOrEqualTo(FULL_REFRESH_MS)
        assertThat(writerRefresh.inWholeMilliseconds).describedAs("writer refresh").isLessThanOrEqualTo(WRITER_REFRESH_MS)
        assertThat(startUpMirror.inWholeMilliseconds).describedAs("start-up mirror").isLessThanOrEqualTo(START_UP_MIRROR_MS)
        assertThat(quietMirror.inWholeMilliseconds).describedAs("start-up mirror with nothing to change")
            .isLessThanOrEqualTo(QUIET_MIRROR_MS)
    }

    private fun log(firstSync: Duration, mirror: Duration, quiet: Duration, fullRefresh: Duration, writerRefresh: Duration, keyed: Int) {
        Log.i(
            TAG,
            "Decisions on $keyed files: first sync $firstSync, start-up mirror $mirror, start-up mirror with nothing to " +
                "change $quiet, full refresh $fullRefresh, writer refresh $writerRefresh",
        )
    }

    private companion object {
        const val TAG = "GeneratedLibrary"
        const val ROOT = "Music/PiPlayerTest20k/"
        const val FILES = 20_000
        const val DECIDED = 4_000

        /** Half as long again as the slowest first sync of the baseline, 2.31 s. */
        const val FIRST_SYNC_MS = 3_500L

        /** Half as long again as the slowest full refresh of the baseline, 2.52 s. */
        const val FULL_REFRESH_MS = 3_800L

        /** Half as long again as the slowest refresh through the writer of the baseline, 2.23 s. */
        const val WRITER_REFRESH_MS = 3_350L

        /** As long as the slowest full refresh of the baseline. */
        const val START_UP_MIRROR_MS = 2_500L

        /** Half of [START_UP_MIRROR_MS]. */
        const val QUIET_MIRROR_MS = 1_250L
    }
}
