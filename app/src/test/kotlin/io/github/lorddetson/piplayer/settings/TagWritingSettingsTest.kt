package io.github.lorddetson.piplayer.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.tagging.write.TagCoverPreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagWritingSettingsTest {
    private val file = MemoryDataStore()

    @Test
    fun `older settings and unknown values use the default writing options`() = runBlocking<Unit> {
        assertThat(TagWritingSettings(file).options.first()).isEqualTo(TagWritingOptions())
        file.edit {
            it[stringPreferencesKey("id3v1_write_policy")] = "future-policy"
            it[stringPreferencesKey("embedded_cover_preset")] = "future-preset"
        }
        assertThat(TagWritingSettings(file).options.first()).isEqualTo(TagWritingOptions())
    }

    @Test
    fun `each choice is stored independently and survives reopening the settings`() = runBlocking<Unit> {
        val settings = TagWritingSettings(file)
        settings.setId3v1(Id3v1WritePolicy.REMOVE)
        settings.setCover(TagCoverPreset.ORIGINAL)
        assertThat(TagWritingSettings(file).options.first())
            .isEqualTo(TagWritingOptions(Id3v1WritePolicy.REMOVE, TagCoverPreset.ORIGINAL))

        settings.setId3v1(Id3v1WritePolicy.LEAVE_UNTOUCHED)
        assertThat(TagWritingSettings(file).options.first())
            .isEqualTo(TagWritingOptions(Id3v1WritePolicy.LEAVE_UNTOUCHED, TagCoverPreset.ORIGINAL))
    }
}
