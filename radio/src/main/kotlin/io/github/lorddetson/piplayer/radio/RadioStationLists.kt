package io.github.lorddetson.piplayer.radio

import java.util.Locale

internal data class RadioSearchInput(
    val name: String = "",
    val country: String = "",
    val language: String = "",
    val genre: String = "",
) {
    fun query(): RadioStationQuery? {
        val code = country.trim()
        if (code.isNotEmpty() && !COUNTRY_CODE.matches(code)) return null
        return RadioStationQuery(
            name = name.trim(),
            countryCode = code.uppercase(Locale.ROOT),
            language = language.trim(),
            tag = genre.trim(),
        )
    }

    private companion object {
        val COUNTRY_CODE = Regex("[A-Za-z]{2}")
    }
}

internal data class RadioCatalogResults(
    val query: RadioStationQuery,
    val stations: List<DirectoryRadioStation> = emptyList(),
    val nextOffset: Int? = null,
) {
    fun append(page: RadioStationPage): RadioCatalogResults {
        val byId = (stations + page.stations).associateBy(DirectoryRadioStation::id)
        return copy(stations = byId.values.toList(), nextOffset = page.nextOffset)
    }

    fun nextQuery(): RadioStationQuery? = nextOffset?.let { query.copy(offset = it) }
}

internal enum class RadioSection {
    DIRECTORY, FAVORITES, MY_STATIONS;

    fun savedStations(stations: List<SavedRadioStation>): List<SavedRadioStation> = when (this) {
        DIRECTORY -> emptyList()
        FAVORITES -> stations.filter(SavedRadioStation::favorite)
        MY_STATIONS -> stations.filter { it.id.source == RadioStationSource.CUSTOM }
    }
}
