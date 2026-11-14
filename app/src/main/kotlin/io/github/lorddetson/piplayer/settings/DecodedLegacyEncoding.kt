package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.library.tagpass.DecodedEncoding
import kotlinx.coroutines.flow.first

/**
 * The manual legacy encoding the library's stored files were last decoded with (docs/library.md §5.7). It describes the
 * library database, which is not backed up either, so it lives in `device_state`; no value means Automatic, which is
 * what every library of M2a was decoded with.
 */
class DecodedLegacyEncoding(context: Context) : DecodedEncoding {

    private val file = SettingsFiles.deviceState(context)

    override suspend fun read(): String? = file.data.first()[KEY]

    override suspend fun write(name: String?) {
        SettingsFiles.edit(file) { preferences ->
            if (name == null) {
                preferences.remove(KEY)
            } else {
                preferences[KEY] = name
            }
        }
    }

    companion object {
        val KEY = stringPreferencesKey("legacy_encoding_decoded")
    }
}
