package io.github.lorddetson.piplayer.radio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

@Serializable
internal enum class RadioStationSource {
    @SerialName("directory") DIRECTORY,
    @SerialName("custom") CUSTOM,
}

internal data class SavedRadioStationId(val source: RadioStationSource, val uuid: UUID)

internal data class SavedRadioStation(
    val id: SavedRadioStationId,
    val name: String,
    val streamUrl: HttpUrl,
    val favorite: Boolean,
) {
    override fun toString(): String = "SavedRadioStation(id=$id, favorite=$favorite)"
}

internal enum class SavedStationWrite { SAVED, INVALID_URL, FULL, NOT_FOUND, WRITE_FAILED }

/** The user's custom stations and directory favorites, in the backed-up settings file. */
internal class SavedRadioStations(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val stations: Flow<List<SavedRadioStation>> = file.data.map { preferences ->
        preferences[KEY].orEmpty().mapNotNull(::decode).distinctBy(SavedRadioStation::id).sortedWith(stationOrder)
    }.distinctUntilChanged()

    /** A repeat with the same normalized stream URL leaves the saved station's ID and name intact. */
    suspend fun addCustom(name: String, streamUrl: String): SavedStationWrite {
        val url = parseRadioStreamUrl(streamUrl) ?: return SavedStationWrite.INVALID_URL
        return edit { rows ->
            val alreadySaved = rows.asSequence().mapNotNull(::decode).any { saved ->
                saved.id.source == RadioStationSource.CUSTOM && saved.streamUrl == url
            }
            if (!alreadySaved) {
                val id = SavedRadioStationId(RadioStationSource.CUSTOM, UUID.randomUUID())
                rows.add(encode(SavedRadioStation(id, name.trim().ifBlank { url.host }, url, favorite = false)))
            }
            SavedStationWrite.SAVED
        }
    }

    /** Directory favorites use Radio Browser's stable station UUID across mirror and address changes. */
    suspend fun setDirectoryFavorite(station: DirectoryRadioStation, enabled: Boolean): SavedStationWrite {
        if (!enabled) return removeDirectoryFavorite(station.id)
        val id = SavedRadioStationId(RadioStationSource.DIRECTORY, station.id)
        val url = parseRadioStreamUrl(station.streamUrl.toString()) ?: return SavedStationWrite.INVALID_URL
        val replacement = SavedRadioStation(id, station.name.trim().ifBlank { url.host }, url, favorite = true)
        return edit { rows ->
            rows.replace(id, replacement)
            SavedStationWrite.SAVED
        }
    }

    suspend fun removeDirectoryFavorite(id: UUID): SavedStationWrite = edit { rows ->
        rows.replace(SavedRadioStationId(RadioStationSource.DIRECTORY, id), replacement = null)
        SavedStationWrite.SAVED
    }

    suspend fun setCustomFavorite(id: UUID, enabled: Boolean): SavedStationWrite = edit { rows ->
        val custom = SavedRadioStationId(RadioStationSource.CUSTOM, id)
        val station = rows.asSequence().mapNotNull(::decode).firstOrNull { it.id == custom }
            ?: return@edit SavedStationWrite.NOT_FOUND
        rows.replace(custom, station.copy(favorite = enabled))
        SavedStationWrite.SAVED
    }

    suspend fun removeCustom(id: UUID): SavedStationWrite = edit { rows ->
        rows.replace(SavedRadioStationId(RadioStationSource.CUSTOM, id), replacement = null)
        SavedStationWrite.SAVED
    }

    private suspend fun edit(change: (MutableSet<String>) -> SavedStationWrite): SavedStationWrite {
        var result = SavedStationWrite.WRITE_FAILED
        val stored = SettingsFiles.edit(file) { preferences ->
            val previous = preferences[KEY].orEmpty()
            val rows = previous.toMutableSet()
            result = change(rows)
            if (result != SavedStationWrite.SAVED || rows == previous) return@edit

            val bytes = rows.utf8Size()
            if (bytes > MAX_BYTES && bytes > previous.utf8Size()) {
                result = SavedStationWrite.FULL
                return@edit
            }
            if (rows.isEmpty()) preferences.remove(KEY) else preferences[KEY] = rows
        }
        return if (stored == null) SavedStationWrite.WRITE_FAILED else result
    }

    private fun MutableSet<String>.replace(id: SavedRadioStationId, replacement: SavedRadioStation?) {
        val matches = mapNotNull { raw -> decode(raw)?.takeIf { it.id == id }?.let { raw to it } }
        if (matches.size == 1 && matches.single().second == replacement) return
        matches.forEach { (raw, _) -> remove(raw) }
        if (replacement != null) add(encode(replacement))
    }

    @Serializable
    private data class StoredStation(
        val source: RadioStationSource,
        val uuid: String,
        val name: String,
        val url: String,
        val favorite: Boolean,
    )

    private companion object {
        // DataStore rewrites the settings file as a unit. Keep the radio data a small share of the backed-up file.
        const val MAX_BYTES = 256L * 1024
        val KEY = stringSetPreferencesKey("radio_saved_stations_v1")
        val json = Json { ignoreUnknownKeys = true }
        val stationOrder = compareBy<SavedRadioStation>({ it.name.lowercase(Locale.ROOT) }, { it.id.source }, { it.id.uuid })

        fun Set<String>.utf8Size(): Long = sumOf { it.encodeToByteArray().size.toLong() }

        fun encode(station: SavedRadioStation): String = json.encodeToString(
            StoredStation.serializer(),
            StoredStation(station.id.source, station.id.uuid.toString(), station.name, station.streamUrl.toString(), station.favorite),
        )

        fun decode(raw: String): SavedRadioStation? {
            val stored = try {
                json.decodeFromString(StoredStation.serializer(), raw)
            } catch (_: SerializationException) {
                return null
            }
            val uuid = try {
                UUID.fromString(stored.uuid)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (!uuid.toString().equals(stored.uuid, ignoreCase = true)) return null
            val streamUrl = parseRadioStreamUrl(stored.url) ?: return null
            val id = SavedRadioStationId(stored.source, uuid)
            return SavedRadioStation(id, stored.name.ifBlank { streamUrl.host }, streamUrl, stored.favorite)
        }
    }
}
