package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.tagging.write.TagCoverPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class TagWritingOptions(
    val id3v1: Id3v1WritePolicy = Id3v1WritePolicy.UPDATE_IF_PRESENT,
    val cover: TagCoverPreset = TagCoverPreset.COMPATIBLE,
)

internal class TagWritingSettings(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val options: Flow<TagWritingOptions> = file.data.map(::optionsOf).distinctUntilChanged()

    suspend fun setId3v1(policy: Id3v1WritePolicy) {
        SettingsFiles.edit(file) { it[ID3V1] = policy.name }
    }

    suspend fun setCover(preset: TagCoverPreset) {
        SettingsFiles.edit(file) { it[COVER] = preset.name }
    }

    companion object {
        val ID3V1 = stringPreferencesKey("id3v1_write_policy")
        val COVER = stringPreferencesKey("embedded_cover_preset")

        fun optionsOf(preferences: Preferences) = TagWritingOptions(
            Id3v1WritePolicy.entries.find { it.name == preferences[ID3V1] } ?: Id3v1WritePolicy.UPDATE_IF_PRESENT,
            TagCoverPreset.entries.find { it.name == preferences[COVER] } ?: TagCoverPreset.COMPATIBLE,
        )
    }
}
