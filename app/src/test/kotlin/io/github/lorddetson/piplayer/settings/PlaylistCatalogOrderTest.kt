package io.github.lorddetson.piplayer.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.lorddetson.piplayer.library.lists.ListKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlaylistCatalogOrderTest {
    @Test
    fun `manual playlists precede smart lists on a fresh installation`() {
        val order = PlaylistCatalogOrderSettings.read(mutablePreferencesOf())
        assertThat(order.sections).containsExactly(PlaylistSection.MANUAL, PlaylistSection.SMART)
        assertThat(order.manual(listOf(31L, 21L))).containsExactly(31L, 21L)
        assertThat(order.smart(ListKind.SMART)).containsExactlyElementsOf(ListKind.SMART)
    }

    @Test
    fun `saved positions survive a rename or deletion and new playlists follow the saved order`() {
        val preferences = mutablePreferencesOf(
            PlaylistCatalogOrderSettings.smartFirstKey to true,
            PlaylistCatalogOrderSettings.manualKey to "21,41,31,21,broken",
            PlaylistCatalogOrderSettings.smartKey to "recent,invalid,favorites,recent",
        )
        val order = PlaylistCatalogOrderSettings.read(preferences)
        assertThat(order.sections).containsExactly(PlaylistSection.SMART, PlaylistSection.MANUAL)
        assertThat(order.manual(listOf(55L, 21L, 31L))).containsExactly(21L, 31L, 55L)
        assertThat(order.smart(ListKind.SMART)).containsExactly(
            ListKind.RECENTLY_PLAYED, ListKind.FAVORITES, ListKind.RECENTLY_ADDED, ListKind.MOST_PLAYED, ListKind.MISSING_INFO,
        )
    }

    @Test
    fun `moving within a section preserves other entries and respects the bounds`() {
        assertThat(listOf(10, 20, 30).movedBy(20, -1)).containsExactly(20, 10, 30)
        assertThat(listOf(10, 20, 30).movedBy(20, 1)).containsExactly(10, 30, 20)
        assertThat(listOf(10, 20, 30).movedBy(10, -1)).containsExactly(10, 20, 30)
        assertThat(listOf(10, 20, 30).movedBy(50, 1)).containsExactly(10, 20, 30)
    }
}
