package io.github.lorddetson.piplayer.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The batch's progress and its ETA as the notification says them (TI §8, PIM-21), without Android: the files done out of
 * the reports of the batch's jobs, and the time left out of the rate the batch moves at.
 */
class BatchProgressTest {

    @Test
    fun `a batch counts the groups whose two jobs have finished`() {
        val sizes = listOf(3, 2, 4)

        // Phase (a) of the first group runs: only what the job reports counts, the group is not done.
        assertThat(batchProgress(sizes, finishedJobs = 0, running = JobProgress(1, 3))).isEqualTo(BatchProgress(1, 9))
        // Phase (a) is done and phase (b) runs through the same three files.
        assertThat(batchProgress(sizes, finishedJobs = 1, running = JobProgress(2, 3))).isEqualTo(BatchProgress(2, 9))
        // Both jobs of the first group are done: its three files count whole, then the next group's own.
        assertThat(batchProgress(sizes, finishedJobs = 2, running = JobProgress(1, 2))).isEqualTo(BatchProgress(4, 9))
        assertThat(batchProgress(sizes, finishedJobs = 3, running = JobProgress(1, 2))).isEqualTo(BatchProgress(4, 9))
        // The chain waits for the network between groups: the finished groups stay counted.
        assertThat(batchProgress(sizes, finishedJobs = 4, running = null)).isEqualTo(BatchProgress(5, 9))
        assertThat(batchProgress(sizes, finishedJobs = 6, running = null)).isEqualTo(BatchProgress(9, 9))
    }

    @Test
    fun `the files never pass the batch's own`() {
        assertThat(batchProgress(listOf(3, 2), finishedJobs = 4, running = JobProgress(5, 2))).isEqualTo(BatchProgress(5, 5))
        assertThat(batchProgress(listOf(3, 2), finishedJobs = 99, running = JobProgress(1, 2))).isEqualTo(BatchProgress(5, 5))
        assertThat(batchProgress(listOf(3, 2), finishedJobs = 0, running = JobProgress(1, 7))).isEqualTo(BatchProgress(1, 5))
        assertThat(batchProgress(emptyList(), finishedJobs = 0, running = null)).isEqualTo(BatchProgress(0, 0))
    }

    @Test
    fun `the fraction stays unknown without files`() {
        assertThat(BatchProgress(0, 0).fraction).isNull()
        assertThat(BatchProgress(1, 4).fraction).isEqualTo(0.25f)
        assertThat(BatchProgress(4, 4).fraction).isEqualTo(1f)
    }

    @Test
    fun `the meter estimates from the rate of its samples`() {
        val meter = EtaMeter()
        meter.record(0, 0)
        meter.record(10_000, 10)
        meter.record(20_000, 20)

        // 20 files in 20 s: the 80 left take 80 s.
        assertThat(meter.etaMillis(20_000, filesDone = 20, filesTotal = 100)).isEqualTo(80_000)
    }

    @Test
    fun `a rate needs two samples over a span worth measuring`() {
        val meter = EtaMeter()
        assertThat(meter.etaMillis(0, filesDone = 0, filesTotal = 10)).isNull()

        meter.record(0, 0)
        assertThat(meter.etaMillis(0, filesDone = 0, filesTotal = 10)).isNull()

        // One second and one file is a sample pair too short to be a rate.
        meter.record(1_000, 1)
        assertThat(meter.etaMillis(1_000, filesDone = 1, filesTotal = 10)).isNull()

        // No file done in ten seconds is no rate either.
        val still = EtaMeter()
        still.record(0, 5)
        still.record(10_000, 5)
        assertThat(still.etaMillis(10_000, filesDone = 5, filesTotal = 10)).isNull()

        // Nothing left has no time left.
        assertThat(meter.etaMillis(1_000, filesDone = 10, filesTotal = 10)).isNull()
    }

    @Test
    fun `a batch that stopped moving has no estimate`() {
        val meter = EtaMeter()
        meter.record(0, 0)
        meter.record(10_000, 10)

        // A batch that waits for its network makes no sample: half a minute later the estimate is gone, not hours off.
        assertThat(meter.etaMillis(45_000, filesDone = 10, filesTotal = 100)).isNull()
        assertThat(meter.etaMillis(30_000, filesDone = 10, filesTotal = 100)).isEqualTo(90_000)
    }

    @Test
    fun `the rate looks back only over the window`() {
        val meter = EtaMeter(windowMillis = 30_000)
        meter.record(0, 0)
        meter.record(10_000, 1)
        // The first sample falls out of the window: the rate comes from the last 30 s, 30 files.
        meter.record(40_000, 31)

        assertThat(meter.etaMillis(40_000, filesDone = 31, filesTotal = 100)).isEqualTo(69_000)
    }

    @Test
    fun `a meter that finds fewer files starts again`() {
        val meter = EtaMeter()
        meter.record(0, 0)
        meter.record(10_000, 10)
        // Another batch: the count goes back, and the samples before it mean nothing.
        meter.record(20_000, 0)

        assertThat(meter.etaMillis(20_000, filesDone = 0, filesTotal = 100)).isNull()
        meter.record(30_000, 10)
        assertThat(meter.etaMillis(30_000, filesDone = 10, filesTotal = 100)).isEqualTo(90_000)
    }

    @Test
    fun `the time left reads in whole minutes rounded up`() {
        assertThat(etaOf(null)).isEqualTo(Eta.Unknown)
        assertThat(etaOf(0)).isEqualTo(Eta.Unknown)
        assertThat(etaOf(-1)).isEqualTo(Eta.Unknown)
        assertThat(etaOf(1)).isEqualTo(Eta.LessThanAMinute)
        assertThat(etaOf(59_999)).isEqualTo(Eta.LessThanAMinute)
        assertThat(etaOf(60_000)).isEqualTo(Eta.Minutes(1))
        assertThat(etaOf(60_001)).isEqualTo(Eta.Minutes(2))
        assertThat(etaOf(3_599_999)).isEqualTo(Eta.Minutes(60))
        assertThat(etaOf(3_600_000)).isEqualTo(Eta.Minutes(60))
        assertThat(etaOf(3_600_001)).isEqualTo(Eta.OverAnHour)
    }
}
