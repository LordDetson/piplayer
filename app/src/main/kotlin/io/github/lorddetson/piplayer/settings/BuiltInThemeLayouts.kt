package io.github.lorddetson.piplayer.settings

import io.github.lorddetson.piplayer.settings.NowPlayingElement as Element
import io.github.lorddetson.piplayer.settings.BottomDestination as Destination
import io.github.lorddetson.piplayer.settings.MiniPlayerElement as MiniElement
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
    ThemeMode.SYSTEM.stored -> system
    ThemeMode.LIGHT.stored -> light
    ThemeMode.DARK.stored -> dark
    ThemeMode.AMOLED.stored -> amoled
    ThemeMode.GRAPHITE.stored -> graphite
    ThemeMode.WARM_LIGHT.stored -> warmLight
    ThemeMode.COVER_FOCUS.stored -> coverFocus
    ThemeMode.CONTROL_DECK.stored -> controlDeck
    else -> null
}

private val system = BuiltInThemeLayout(
    presentation = NowPlayingLayout.STANDARD,
    library = LibraryPanelLayout(),
    player = NowPlayingPanel.DEFAULT,
    miniPlayer = MiniPlayerPanel(),
    navigation = BottomNavigationLayout(),
)

private val light = system.copy(
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("songs", "playlists", "albums", "artists"), emptySet()),
        buttons = LibraryButtonLayout(listOf("search", "settings", "sort"), emptySet()),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows +
            (NowPlayingRow.TOP to listOf(Element.QUEUE, Element.FAVORITE, Element.IDENTIFY, Element.MORE)),
    ),
    miniPlayer = MiniPlayerPanel(order = listOf(MiniElement.TRACK_INFO, MiniElement.COVER, MiniElement.PLAY_PAUSE)),
    navigation = BottomNavigationLayout(order = listOf(Destination.SEARCH, Destination.LIBRARY, Destination.FIX)),
)

private val dark = system.copy(
    presentation = NowPlayingLayout.EXPANDED,
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("songs", "albums", "playlists", "artists"), emptySet()),
        buttons = LibraryButtonLayout(listOf("sort", "search", "settings"), emptySet()),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows +
            (NowPlayingRow.TOP to listOf(Element.MORE, Element.IDENTIFY, Element.FAVORITE, Element.QUEUE)),
    ),
    miniPlayer = MiniPlayerPanel(order = listOf(MiniElement.COVER, MiniElement.PLAY_PAUSE, MiniElement.TRACK_INFO)),
    navigation = BottomNavigationLayout(order = listOf(Destination.FIX, Destination.LIBRARY, Destination.SEARCH)),
)

private val amoled = system.copy(
    presentation = NowPlayingLayout.MINIMAL,
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("songs", "folders", "albums", "artists"), setOf("genres")),
        buttons = LibraryButtonLayout(listOf("settings", "search", "sort"), setOf("sort")),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows +
            (NowPlayingRow.TOP to listOf(Element.FAVORITE, Element.MORE, Element.QUEUE, Element.IDENTIFY)),
        hidden = setOf(Element.FORMAT),
    ),
    miniPlayer = MiniPlayerPanel(hidden = setOf(MiniElement.PROGRESS)),
    navigation = BottomNavigationLayout(hidden = setOf(Destination.SEARCH)),
)

private val graphite = system.copy(
    presentation = NowPlayingLayout.EXPANDED,
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("songs", "artists", "albums", "folders"), emptySet()),
        buttons = LibraryButtonLayout(listOf("settings", "sort", "search"), emptySet()),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows + mapOf(
            NowPlayingRow.TOP to listOf(Element.QUEUE, Element.IDENTIFY, Element.MORE, Element.FAVORITE),
            NowPlayingRow.EXTRAS to listOf(Element.EQUALIZER, Element.STOP_AFTER, Element.TIMER),
        ),
    ),
    miniPlayer = MiniPlayerPanel(order = listOf(MiniElement.PLAY_PAUSE, MiniElement.TRACK_INFO, MiniElement.COVER)),
    navigation = BottomNavigationLayout(order = listOf(Destination.FIX, Destination.SEARCH, Destination.LIBRARY)),
)

private val warmLight = system.copy(
    library = LibraryPanelLayout(
        tabs = LibraryTabLayout.fromStored(listOf("albums", "songs", "playlists", "artists"), emptySet()),
        buttons = LibraryButtonLayout(listOf("sort", "settings", "search"), emptySet()),
    ),
    player = NowPlayingPanel.DEFAULT.copy(
        rows = NowPlayingPanel.DEFAULT.rows +
            (NowPlayingRow.TOP to listOf(Element.FAVORITE, Element.QUEUE, Element.MORE, Element.IDENTIFY)),
    ),
    miniPlayer = MiniPlayerPanel(
        order = listOf(MiniElement.TRACK_INFO, MiniElement.PLAY_PAUSE, MiniElement.COVER),
        progressAtBottom = true,
    ),
    navigation = BottomNavigationLayout(order = listOf(Destination.SEARCH, Destination.FIX, Destination.LIBRARY)),
)

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
    navigation = BottomNavigationLayout(hidden = setOf(Destination.FIX)),
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
    miniPlayer = MiniPlayerPanel(order = listOf(MiniElement.PLAY_PAUSE, MiniElement.COVER, MiniElement.TRACK_INFO)),
    navigation = BottomNavigationLayout(order = listOf(Destination.LIBRARY, Destination.FIX, Destination.SEARCH)),
)
