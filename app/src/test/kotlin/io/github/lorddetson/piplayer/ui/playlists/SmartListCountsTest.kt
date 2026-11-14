package io.github.lorddetson.piplayer.ui.playlists

import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.MissingReasons
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The numbers of the smart lists on the Playlists tab (docs/library.md §17.2, §17.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartListCountsTest {

    @Test
    fun `a read that fails shows no numbers and the next change reads them again`() = runTest(UnconfinedTestDispatcher()) {
        val changes = MutableStateFlow(0)
        val chosen = MutableStateFlow(MissingReasons.DEFAULT)
        var reads = 0
        val seen = mutableListOf<Map<ListKind, Int>>()
        val job = launch {
            smartListCounts(changes, chosen) { reasons ->
                if (++reads == 1) error("The library is being rebuilt")
                mapOf(ListKind.FAVORITES to reads, ListKind.MISSING_INFO to reasons)
            }.toList(seen)
        }

        changes.value = 1
        chosen.value = MissingReasons.ALL

        assertThat(seen).containsExactly(
            emptyMap(),
            mapOf(ListKind.FAVORITES to 2, ListKind.MISSING_INFO to MissingReasons.DEFAULT),
            mapOf(ListKind.FAVORITES to 3, ListKind.MISSING_INFO to MissingReasons.ALL),
        )
        job.cancel()
    }
}
