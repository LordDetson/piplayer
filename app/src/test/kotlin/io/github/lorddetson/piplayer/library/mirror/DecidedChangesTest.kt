package io.github.lorddetson.piplayer.library.mirror

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The files each run of the decision mirror changed, by the version it raised, for the session's refresh (TI §7.1). */
class DecidedChangesTest {

    private val changes = DecidedChanges(keptRuns = 4, maxFiles = 3)

    @Test
    fun aRunGivesTheFilesItChangedAfterTheVersionBeforeIt() {
        changes.record(1, listOf(10L, 11L))

        assertThat(changes.after(0, 1)).containsExactlyInAnyOrder(10L, 11L)
    }

    @Test
    fun theRunsSinceAVersionGiveTheirFilesTogether() {
        changes.record(1, listOf(10L))
        changes.record(2, listOf(11L, 10L))
        changes.record(3, listOf(12L))

        assertThat(changes.after(0, 3)).containsExactlyInAnyOrder(10L, 11L, 12L)
        assertThat(changes.after(1, 3)).containsExactlyInAnyOrder(10L, 11L, 12L)
        assertThat(changes.after(2, 3)).containsExactly(12L)
        assertThat(changes.after(1, 2)).containsExactlyInAnyOrder(10L, 11L)
    }

    @Test
    fun theSameVersionGivesNoFiles() {
        changes.record(1, listOf(10L))

        assertThat(changes.after(1, 1)).isEmpty()
    }

    @Test
    fun aRunWithMoreFilesThanKeptGivesNoneKnownWhileTheRunsAfterItDo() {
        changes.record(1, listOf(10L, 11L, 12L, 13L))
        changes.record(2, listOf(14L))

        assertThat(changes.after(0, 2)).isNull()
        assertThat(changes.after(0, 1)).isNull()
        assertThat(changes.after(1, 2)).containsExactly(14L)
    }

    @Test
    fun aRunWithAsManyFilesAsKeptIsKnown() {
        changes.record(1, listOf(10L, 11L, 12L))

        assertThat(changes.after(0, 1)).containsExactlyInAnyOrder(10L, 11L, 12L)
    }

    @Test
    fun aRunNoLongerKeptGivesNoneKnown() {
        for (version in 1L..5L) changes.record(version, listOf(version * 10))

        assertThat(changes.after(0, 5)).isNull()
        assertThat(changes.after(1, 5)).containsExactlyInAnyOrder(20L, 30L, 40L, 50L)
    }

    @Test
    fun aVersionThatWasNeverRecordedGivesNoneKnown() {
        changes.record(1, listOf(10L))
        changes.record(3, listOf(30L))

        assertThat(changes.after(0, 3)).isNull()
        assertThat(changes.after(2, 3)).containsExactly(30L)
    }

    @Test
    fun aVersionThatWentBackGivesNoneKnown() {
        // A new library starts its mirror's versions again (Library.reopen, a rebuild).
        changes.record(1, listOf(10L))

        assertThat(changes.after(5, 1)).isNull()
    }

    @Test
    fun theFilesGivenAreACopy() {
        val files = mutableListOf(10L)
        changes.record(1, files)
        files += 11L

        assertThat(changes.after(0, 1)).containsExactly(10L)
    }
}
