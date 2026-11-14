package io.github.lorddetson.piplayer.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The legacy encoding in the `settings` file (docs/library.md §5.7). The test leaves the setting at Automatic. */
@RunWith(AndroidJUnit4::class)
class LegacyEncodingSettingTest {

    private val context = TestFiles.targetContext
    private val settings = LibrarySettings(context)
    private val file = SettingsFiles.settings(context)
    private val key = stringPreferencesKey("legacy_encoding")

    @Before
    @After
    fun automatic() = runBlocking<Unit> {
        SettingsFiles.edit(file) { it.remove(key) }
    }

    @Test
    fun withoutAStoredValueTheChoiceIsAutomatic() = runBlocking<Unit> {
        assertThat(settings.readLegacyEncoding()).isNull()
        assertThat(settings.legacyEncoding.first()).isNull()
    }

    @Test
    fun aChoiceIsStoredByItsNameAndAutomaticRemovesIt() = runBlocking<Unit> {
        val koi8 = LegacyEncodings.ALL.single { it.name == "koi8-r" }

        settings.setLegacyEncoding(koi8)

        assertThat(file.data.first()[key]).isEqualTo("koi8-r")
        assertThat(settings.readLegacyEncoding()).isSameAs(koi8)
        withTimeout(5.seconds) { settings.legacyEncoding.first { it === koi8 } }

        settings.setLegacyEncoding(null)

        assertThat(file.data.first()[key]).isNull()
        withTimeout(5.seconds) { settings.legacyEncoding.first { it == null } }
    }

    @Test
    fun whatTheLibraryWasDecodedWithIsKeptInTheDeviceState() = runBlocking<Unit> {
        val decoded = DecodedLegacyEncoding(context)
        val deviceState = SettingsFiles.deviceState(context)
        try {
            assertThat(decoded.read()).isNull()

            decoded.write("koi8-r")

            assertThat(decoded.read()).isEqualTo("koi8-r")
            assertThat(deviceState.data.first()[DecodedLegacyEncoding.KEY]).isEqualTo("koi8-r")
            assertThat(file.data.first()[DecodedLegacyEncoding.KEY]).describedAs("not in the backed-up settings").isNull()

            decoded.write(null)

            assertThat(decoded.read()).isNull()
            assertThat(deviceState.data.first().contains(DecodedLegacyEncoding.KEY)).isFalse()
        } finally {
            SettingsFiles.edit(deviceState) { it.remove(DecodedLegacyEncoding.KEY) }
        }
    }

    @Test
    fun anUnknownStoredNameIsAutomatic() = runBlocking<Unit> {
        SettingsFiles.edit(file) { it[key] = "x-unknown" }

        assertThat(settings.readLegacyEncoding()).isNull()
    }
}
