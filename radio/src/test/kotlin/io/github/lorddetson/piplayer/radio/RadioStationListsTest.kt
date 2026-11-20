package io.github.lorddetson.piplayer.radio

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RadioStationListsTest {
    @Test
    fun `manual search trims filters and maps the genre to the directory tag`() {
        assertEquals(
            RadioStationQuery("Pi Jazz", "BY", "Русский", "jazz"),
            RadioSearchInput(" Pi Jazz ", " by ", " Русский ", "jazz ").query(),
        )
    }

    @Test
    fun `a blank search can browse and an invalid country does not produce a query`() {
        assertEquals(RadioStationQuery(), RadioSearchInput().query())
        assertNull(RadioSearchInput(country = "Canada").query())
        assertNull(RadioSearchInput(country = "яz").query())
    }

    @Test
    fun `pagination keeps the submitted filters and never repeats a station`() {
        val query = RadioStationQuery(name = "jazz", countryCode = "CA", limit = 2)
        val jazz = directoryStation(1, "Jazz")
        val blues = directoryStation(2, "Blues")
        val updated = blues.copy(streamUrl = "https://other.example/stream".toHttpUrl())
        val soul = directoryStation(3, "Soul")
        val initial = RadioCatalogResults(query).append(RadioStationPage(listOf(jazz, blues), nextOffset = 2))
        assertEquals(query.copy(offset = 2), initial.nextQuery())

        val last = initial.append(RadioStationPage(listOf(updated, soul), nextOffset = null))
        assertEquals(listOf(jazz, updated, soul), last.stations)
        assertNull(last.nextQuery())
    }

    @Test
    fun `an empty filtered page can still have more directory results`() {
        val query = RadioStationQuery(language = "czech")
        val result = RadioCatalogResults(query).append(RadioStationPage(emptyList(), nextOffset = 50))
        assertEquals(emptyList<DirectoryRadioStation>(), result.stations)
        assertEquals(query.copy(offset = 50), result.nextQuery())
    }

    @Test
    fun `favorites contain both station sources while my stations contains all custom stations`() {
        val directory = savedStation(1, RadioStationSource.DIRECTORY, favorite = true)
        val customFavorite = savedStation(1, RadioStationSource.CUSTOM, favorite = true)
        val custom = savedStation(2, RadioStationSource.CUSTOM, favorite = false)
        val all = listOf(directory, customFavorite, custom)

        assertEquals(listOf(directory, customFavorite), RadioSection.FAVORITES.savedStations(all))
        assertEquals(listOf(customFavorite, custom), RadioSection.MY_STATIONS.savedStations(all))
        assertEquals(emptyList<SavedRadioStation>(), RadioSection.DIRECTORY.savedStations(all))
    }

    private fun directoryStation(value: Long, name: String) = DirectoryRadioStation(
        UUID(0, value), name, "https://station.example/$value".toHttpUrl(), "", emptyList(), emptyList(),
    )

    private fun savedStation(value: Long, source: RadioStationSource, favorite: Boolean) = SavedRadioStation(
        SavedRadioStationId(source, UUID(0, value)), "Station $value", "https://station.example/$value".toHttpUrl(), favorite,
    )
}
