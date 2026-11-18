package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.library.db.BatchEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What pausing, resuming and stopping a batch does to it (TI §8, PIM-26). */
class BatchControlTest {

    private val rows = mutableMapOf<String, BatchEntity>()
    private val statuses = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val enqueued = mutableListOf<Pair<String, Int>>()

    private fun control() = BatchControl(
        batch = { uuid -> rows[uuid] },
        setStatus = { uuid, status -> statuses += status; rows[uuid] = rows.getValue(uuid).copy(status = status) },
        cancelJobs = { uuid -> cancelled += uuid },
        enqueueScan = { uuid, group -> enqueued += uuid to group },
    )

    private fun row(uuid: String, status: String, group: Int = 0) {
        rows[uuid] = BatchEntity(uuid = uuid, createdAt = 1L, source = BatchEntity.SOURCE_FOLDER, status = status, groupIndex = group)
    }

    @Test
    fun `a processing batch is paused and its jobs cancelled`() = runBlocking {
        row("a", BatchEntity.STATUS_PROCESSING, group = 4)

        assertThat(control().pause("a")).isTrue()

        assertThat(statuses).containsExactly(BatchEntity.STATUS_PAUSED)
        assertThat(cancelled).containsExactly("a")
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `a paused batch resumes at the group it stopped on`() = runBlocking {
        row("b", BatchEntity.STATUS_PAUSED, group = 7)

        assertThat(control().resume("b")).isTrue()

        assertThat(statuses).containsExactly(BatchEntity.STATUS_PROCESSING)
        assertThat(enqueued).containsExactly("b" to 7)
        assertThat(cancelled).isEmpty()
    }

    @Test
    fun `a group that went below zero resumes at the first one`() = runBlocking {
        row("c", BatchEntity.STATUS_PAUSED, group = -2)

        control().resume("c")

        assertThat(enqueued).containsExactly("c" to 0)
    }

    @Test
    fun `stopping a batch cancels its jobs and keeps it cancelled`() = runBlocking {
        row("d", BatchEntity.STATUS_PROCESSING)

        assertThat(control().stop("d")).isTrue()

        assertThat(statuses).containsExactly(BatchEntity.STATUS_CANCELLED)
        assertThat(cancelled).containsExactly("d")
    }

    @Test
    fun `a finished or cancelled batch changes nothing`() = runBlocking {
        row("done", BatchEntity.STATUS_DONE)
        row("cancelled", BatchEntity.STATUS_CANCELLED)
        val control = control()

        assertThat(control.pause("done")).isFalse()
        assertThat(control.stop("cancelled")).isFalse()

        assertThat(statuses).isEmpty()
        assertThat(cancelled).isEmpty()
    }

    @Test
    fun `a batch that is not paused does not resume`() = runBlocking {
        row("running", BatchEntity.STATUS_PROCESSING)

        assertThat(control().resume("running")).isFalse()

        assertThat(statuses).isEmpty()
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `a batch that is gone changes nothing`() = runBlocking {
        val control = control()

        assertThat(control.pause("missing")).isFalse()
        assertThat(control.resume("missing")).isFalse()
        assertThat(control.stop("missing")).isFalse()

        assertThat(statuses).isEmpty()
        assertThat(cancelled).isEmpty()
    }
}