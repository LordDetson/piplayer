package io.github.lorddetson.piplayer.settings

import io.github.lorddetson.piplayer.settings.NowPlayingElement as Element
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryButtonLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout

internal data class BuiltInThemeLayout(
    val presentation: NowPlayingLayout,
    val library: LibraryPanelLayout,
    val player: NowPlayingPanel,
    val miniPlayer: MiniPlayerPanel,
    val navigation: BottomNavigationLayout,
)

internal fun builtInThemeLayout(themeId: String): BuiltInThemeLayout? = when (themeId) {
    ThemeMode.COVER_FOCUS.stored -> coverFocus
    ThemeMode.CONTROL_DECK.stored -> controlDeck
    else -> null
}

private val coverFocus = BuiltInThemeLayout(
    presentation = NowPlayingLayout.MINIMAL,
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("albums", "songs", "artists", "playlists", "genres", "folders"), setOf("genres", "folders")),
        buttons = LibraryButtonLayout(listOf("search", "settings", "sort"), setOf("sort")),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows + mapOf(
            NowPlayingRow.TOP to listOf(Element.FAVORITE, Element.QUEUE, Element.IDENTIFY, Element.MORE),
            NowPlayingRow.CONTROLS to listOf(Element.PREVIOUS, Element.PLAY_PAUSE, Element.NEXT, Element.SHUFFLE, Element.REPEAT),
        ),
        hidden = setOf(Element.FORMAT),
    ),
    miniPlayer = MiniPlayerPanel(progressAtBottom = true),
    navigation = BottomNavigationLayout(hidden = setOf(BottomDestination.FIX)),
)

private val controlDeck = BuiltInThemeLayout(
    presentation = NowPlayingLayout.EXPANDED,
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("songs", "folders", "playlists", "albums", "artists", "genres"), emptySet()),
        buttons = LibraryButtonLayout(listOf("sort", "search", "settings"), emptySet()),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows + mapOf(
            NowPlayingRow.TOP to listOf(Element.FAVORITE, Element.MORE, Element.IDENTIFY),
            NowPlayingRow.CONTROLS to listOf(Element.PREVIOUS, Element.PLAY_PAUSE, Element.NEXT, Element.QUEUE, Element.SHUFFLE, Element.REPEAT),
            NowPlayingRow.EXTRAS to listOf(Element.EQUALIZER, Element.TIMER, Element.STOP_AFTER),
        ),
    ),
    miniPlayer = MiniPlayerPanel(order = listOf(MiniPlayerElement.PLAY_PAUSE, MiniPlayerElement.COVER, MiniPlayerElement.TRACK_INFO)),
    navigation = BottomNavigationLayout(order = listOf(BottomDestination.LIBRARY, BottomDestination.FIX, BottomDestination.SEARCH)),
)
