package io.github.lorddetson.piplayer.radio

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedRadioStationsTest {

    @Test
    fun `custom stations reopen from the backed-up settings and retain other preferences`() = runBlocking {
        val unrelated = booleanPreferencesKey("some_other_setting")
        val file = MemoryDataStore(mutablePreferencesOf(unrelated to true))
        val stations = SavedRadioStations(file)
        assertEquals(emptyList<SavedRadioStation>(), stations.stations.first())
        assertEquals(SavedStationWrite.SAVED, stations.addCustom("  Pi Jazz  ", " https://Live.Example:443/radio?q=jazz#now "))
        val saved = stations.stations.first().single()

        val snapshot = file.data.first()
        val reopened = SavedRadioStations(MemoryDataStore(snapshot)).stations.first().single()
        assertEquals(saved.id, reopened.id)
        assertEquals(RadioStationSource.CUSTOM, reopened.id.source)
        assertEquals("Pi Jazz", reopened.name)
        assertEquals("https://live.example/radio?q=jazz", reopened.streamUrl.toString())
        assertFalse(reopened.favorite)
        assertEquals(true, snapshot[unrelated])
    }

    @Test
    fun `adding the same custom stream keeps its identity and existing name`() = runBlocking {
        val stations = SavedRadioStations(MemoryDataStore())
        stations.addCustom("", "https://radio.example/live")
        val existing = stations.stations.first().single()
        assertEquals("radio.example", existing.name)

        assertEquals(SavedStationWrite.SAVED, stations.addCustom("Other name", "https://RADIO.EXAMPLE:443/live#status"))
        assertEquals(listOf(existing), stations.stations.first())
    }

    @Test
    fun `cleartext URLs can neither be saved manually nor kept from the directory`() = runBlocking {
        val stations = SavedRadioStations(MemoryDataStore())
        assertEquals(SavedStationWrite.INVALID_URL, stations.addCustom("Pi", "http://example.net/pi"))
        assertEquals(SavedStationWrite.INVALID_URL, stations.setDirectoryFavorite(directory("http://example.net/pi"), enabled = true))
        assertEquals(emptyList<SavedRadioStation>(), stations.stations.first())
    }

    @Test
    fun `custom stations require direct streams and reject known playlist files`() = runBlocking {
        val stations = SavedRadioStations(MemoryDataStore())
        for (path in listOf("live.m3u8", "live.M3U", "live%2Epls", "live.xspf/", "live.asx", "live.wpl")) {
            val url = "https://example.net/$path?quality=high"
            assertEquals(SavedStationWrite.INVALID_URL, stations.addCustom("Pi", url), url)
        }
        assertEquals(emptyList<SavedRadioStation>(), stations.stations.first())
        assertEquals(SavedStationWrite.SAVED, stations.addCustom("Pi", "https://example.net/radio?selection=live.m3u8"))
    }

    @Test
    fun `directory favorite identity comes from provider UUID and follows URL changes`() = runBlocking {
        val file = MemoryDataStore()
        val stations = SavedRadioStations(file)
        val original = directory("https://example.net/radio")
        val relocated = original.copy(name = "Same station, new address", streamUrl = "https://example.net/second".toHttpUrl())
        stations.setDirectoryFavorite(original, enabled = true)
        assertEquals(SavedStationWrite.SAVED, stations.setDirectoryFavorite(relocated, enabled = true))
        assertEquals(SavedStationWrite.SAVED, stations.setDirectoryFavorite(relocated, enabled = true))

        val reopened = SavedRadioStations(MemoryDataStore(file.data.first()))
        val saved = reopened.stations.first().single()
        assertEquals(SavedRadioStationId(RadioStationSource.DIRECTORY, original.id), saved.id)
        assertEquals(relocated.streamUrl, saved.streamUrl)
        assertEquals(relocated.name, saved.name)
        assertTrue(saved.favorite)

        assertEquals(SavedStationWrite.SAVED, reopened.setDirectoryFavorite(original, enabled = false))
        assertEquals(SavedStationWrite.SAVED, reopened.setDirectoryFavorite(original, enabled = false))
        assertEquals(emptyList<SavedRadioStation>(), reopened.stations.first())
    }

    @Test
    fun `favoriting a custom station is independent of saving and removing it`() = runBlocking {
        val stations = SavedRadioStations(MemoryDataStore())
        val dir = directory("https://example.net/radio")
        stations.addCustom("Own", dir.streamUrl.toString())
        stations.setDirectoryFavorite(dir, enabled = true)
        val custom = stations.stations.first().single { it.id.source == RadioStationSource.CUSTOM }
        val id = custom.id.uuid
        assertEquals(SavedStationWrite.SAVED, stations.setCustomFavorite(id, enabled = true))
        assertEquals(SavedStationWrite.SAVED, stations.setCustomFavorite(id, enabled = true))
        assertTrue(stations.stations.first().all { it.favorite })

        stations.setCustomFavorite(id, enabled = false)
        assertEquals(custom, stations.stations.first().single { it.id == custom.id })
        assertEquals(SavedStationWrite.SAVED, stations.removeCustom(id))
        assertEquals(SavedStationWrite.SAVED, stations.removeCustom(id))
        assertEquals(SavedStationWrite.NOT_FOUND, stations.setCustomFavorite(id, enabled = true))
        assertEquals(dir.id, stations.stations.first().single().id.uuid)
    }

    @Test
    fun `invalid stream links leave the settings unmodified`() = runBlocking {
        val file = MemoryDataStore()
        val stations = SavedRadioStations(file)
        for (url in listOf(
            "", "file:///music/bell.mp3", "content://media/radio", "https:example.net/radio", "//example.net/radio",
            "https://user:password@example.net/radio", "https://@example.net/radio", "https:///example.net/radio",
            "https://example.net/\\path", "https://example.net/\npath",
        )) {
            assertEquals(SavedStationWrite.INVALID_URL, stations.addCustom("Test", url), url)
        }
        val unsafeDirectory = directory("https://user:password@example.net/live")
        assertEquals(SavedStationWrite.INVALID_URL, stations.setDirectoryFavorite(unsafeDirectory, enabled = true))
        assertEquals(emptyMap<Any, Any>(), file.data.first().asMap())
    }

    @Test
    fun `concurrent station edits preserve each distinct stream`() = runBlocking {
        val stations = SavedRadioStations(MemoryDataStore())
        val saved = (1..24).map { index ->
            async(Dispatchers.Default) { stations.addCustom("$index", "https://example.net/radio/$index") }
        }.awaitAll()
        assertTrue(saved.all { it == SavedStationWrite.SAVED })
        assertEquals((1..24).map { "/radio/$it" }.toSet(), stations.stations.first().map { it.streamUrl.encodedPath }.toSet())
    }

    @Test
    fun `unreadable station records remain preserved through valid changes`() = runBlocking {
        val futureStation = """{"source":"future","id":"updated-format"}"""
        val file = MemoryDataStore(mutablePreferencesOf(KEY to setOf(futureStation)))
        val stations = SavedRadioStations(file)
        assertEquals(emptyList<SavedRadioStation>(), stations.stations.first())

        assertEquals(SavedStationWrite.SAVED, stations.addCustom("Pi", "https://example.net/pi"))
        assertTrue(futureStation in file.data.first()[KEY].orEmpty())
        val existing = stations.stations.first().single()
        stations.removeCustom(existing.id.uuid)
        assertEquals(setOf(futureStation), file.data.first()[KEY])
    }

    @Test
    fun `additions beyond the settings budget fail and leave saved stations available`() = runBlocking {
        val file = MemoryDataStore()
        val stations = SavedRadioStations(file)
        assertEquals(SavedStationWrite.SAVED, stations.addCustom("Pi", "https://example.net/pi"))
        val before = file.data.first()

        val overBudget = "𐍈".repeat(65_537)
        assertEquals(SavedStationWrite.FULL, stations.addCustom(overBudget, "https://example.net/big"))
        assertEquals(before, file.data.first())
        assertEquals("Pi", stations.stations.first().single().name)
    }

    private fun directory(url: String): DirectoryRadioStation = DirectoryRadioStation(
        id = UUID.fromString("f88c9699-48ef-4a60-b685-b0bf99a9de28"),
        name = "Directory jazz",
        streamUrl = url.toHttpUrl(),
        countryCode = "BY",
        languages = emptyList(),
        tags = emptyList(),
    )

    private companion object {
        val KEY = stringSetPreferencesKey("radio_saved_stations_v1")
    }
}
