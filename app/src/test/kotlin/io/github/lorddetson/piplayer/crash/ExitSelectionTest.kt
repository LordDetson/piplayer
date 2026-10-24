package io.github.lorddetson.piplayer.crash

import android.app.ApplicationExitInfo
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExitSelectionTest {

    private val now = 1_000_000L

    private fun record(pid: Int, reason: Int, timestampMs: Long = now - 60_000, process: String = "io.github.lorddetson.piplayer") =
        ExitRecord(pid, process, reason, status = 0, description = null, importance = 100, timestampMs = timestampMs)

    private val earlier = ExitState(floorMs = now - 3_600_000, seen = emptySet())

    @Test
    fun `the first start reports nothing and remembers everything there is`() {
        val records = listOf(record(1, ApplicationExitInfo.REASON_CRASH), record(2, ApplicationExitInfo.REASON_ANR))

        val result = ExitSelection.select(records, state = null, written = emptyList(), nowMs = now)

        assertThat(result.picks).isEmpty()
        assertThat(result.state.floorMs).isEqualTo(now)
        assertThat(result.state.seen).containsExactlyInAnyOrderElementsOf(records.map(ExitSelection::key))
    }

    @Test
    fun `crashes, native crashes and freezes make reports, other ends do not`() {
        val records = listOf(
            record(1, ApplicationExitInfo.REASON_CRASH),
            record(2, ApplicationExitInfo.REASON_CRASH_NATIVE),
            record(3, ApplicationExitInfo.REASON_ANR),
            record(4, ApplicationExitInfo.REASON_LOW_MEMORY),
            record(5, ApplicationExitInfo.REASON_USER_REQUESTED),
            record(6, ApplicationExitInfo.REASON_SIGNALED),
            record(7, ApplicationExitInfo.REASON_EXIT_SELF),
        )

        val result = ExitSelection.select(records, earlier, written = emptyList(), nowMs = now)

        assertThat(result.picks.map { it.record.pid to it.kind })
            .containsExactly(1 to CrashKind.JAVA, 2 to CrashKind.NATIVE, 3 to CrashKind.ANR)
        assertThat(result.state.seen).hasSize(7)
    }

    @Test
    fun `a record is reported once`() {
        val crash = record(1, ApplicationExitInfo.REASON_CRASH_NATIVE)
        val first = ExitSelection.select(listOf(crash), earlier, written = emptyList(), nowMs = now)

        val second = ExitSelection.select(listOf(crash), first.state, written = emptyList(), nowMs = now + 1_000)

        assertThat(first.picks).hasSize(1)
        assertThat(second.picks).isEmpty()
    }

    @Test
    fun `records from before the first start are not reported`() {
        val state = ExitState(floorMs = now - 10_000, seen = emptySet())

        val result = ExitSelection.select(listOf(record(1, ApplicationExitInfo.REASON_ANR, timestampMs = now - 20_000)), state, emptyList(), now)

        assertThat(result.picks).isEmpty()
    }

    @Test
    fun `a Java crash the handler wrote is matched by process and time`() {
        val crash = record(1234, ApplicationExitInfo.REASON_CRASH, timestampMs = now - 60_000)
        val written = ReportStore.Report(File("x"), CrashKind.JAVA, timeMs = now - 60_003, pid = 1234)
        val otherPid = ReportStore.Report(File("y"), CrashKind.JAVA, timeMs = now - 60_003, pid = 99)
        val tooOld = ReportStore.Report(File("z"), CrashKind.JAVA, timeMs = now - 120_000, pid = 1234)

        assertThat(ExitSelection.select(listOf(crash), earlier, listOf(written), now).picks.single().written).isEqualTo(written)
        assertThat(ExitSelection.select(listOf(crash), earlier, listOf(otherPid, tooOld), now).picks.single().written).isNull()
    }
}
