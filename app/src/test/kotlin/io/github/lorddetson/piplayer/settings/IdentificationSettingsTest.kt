package io.github.lorddetson.piplayer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lorddetson.piplayer.net.OnlineConsent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The online-lookup opt-in in the `settings` file and the gate built on it (TI §9, §10): closed by default, open after
 * Enable, closed again when a build raises the disclosure version.
 */
class IdentificationSettingsTest {

    private val gateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun stop() = gateScope.cancel()

    private val file = MemoryDataStore()

    private val current = OnlineConsent.CURRENT_DISCLOSURE_VERSION

    private fun gate(settings: IdentificationSettings = IdentificationSettings(file)) = DataStoreOnlineGate(settings, gateScope)

    /** Waits (in real time, at most 5 s) until [condition] holds; DataStore hands changes over on its own threads. */
    private fun eventually(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(5) }
    }

    @Test
    fun `an empty settings file keeps the gate closed and asks for the disclosure`() = runBlocking<Unit> {
        val settings = IdentificationSettings(file)
        val gate = gate(settings)

        assertThat(gate.awaitLoaded()).isEqualTo(OnlineConsent.NONE)
        assertThat(gate.isOpen()).isFalse()
        assertThat(settings.consent.first()).isEqualTo(OnlineConsent.NONE)
        assertThat(settings.needsDisclosure.first()).isTrue()
    }

    @Test
    fun `the gate is closed until the file has been read, even with consent stored`() = runBlocking<Unit> {
        IdentificationSettings(file).enable()
        val read = CompletableDeferred<Unit>()
        val held = object : DataStore<Preferences> by file {
            override val data: Flow<Preferences> = flow {
                read.await()
                emitAll(file.data)
            }
        }
        val gate = gate(IdentificationSettings(held))

        assertThat(gate.isOpen()).isFalse()
        assertThat(gate.consent.value).isNull()

        read.complete(Unit)
        assertThat(gate.awaitLoaded().enabled).isTrue()
        assertThat(gate.isOpen()).isTrue()
    }

    @Test
    fun `Enable opens the gate at once and stores the current disclosure version`() = runBlocking<Unit> {
        val settings = IdentificationSettings(file)
        val gate = gate(settings)
        gate.awaitLoaded()

        gate.enable()

        assertThat(gate.isOpen()).isTrue()
        assertThat(file.data.first()[booleanPreferencesKey("online_lookups_enabled")]).isTrue()
        assertThat(file.data.first()[intPreferencesKey("disclosure_version")]).isEqualTo(current)
        assertThat(settings.needsDisclosure.first()).isFalse()
    }

    @Test
    fun `Not now keeps the gate closed and records the answer, so the disclosure does not come back`() = runBlocking<Unit> {
        val settings = IdentificationSettings(file)
        val gate = gate(settings)

        gate.notNow()

        assertThat(gate.isOpen()).isFalse()
        assertThat(settings.consent.first()).isEqualTo(OnlineConsent(enabled = false, disclosureVersion = current))
        assertThat(settings.needsDisclosure.first()).isFalse()
    }

    @Test
    fun `turning the setting off closes the gate before the write has ended`() = runBlocking<Unit> {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = false
        val held = object : DataStore<Preferences> by file {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (hold) {
                    writing.complete(Unit)
                    release.await()
                }
                return file.updateData(transform)
            }
        }
        val gate = gate(IdentificationSettings(held))
        gate.enable()
        assertThat(gate.isOpen()).isTrue()

        hold = true
        val off = async(Dispatchers.Default) { gate.turnOff() }
        withTimeout(5_000) { writing.await() }
        assertThat(gate.isOpen()).describedAs("while the write is held").isFalse()

        release.complete(Unit)
        withTimeout(5_000) { off.await() }
        assertThat(gate.isOpen()).isFalse()
        assertThat(file.data.first()[booleanPreferencesKey("online_lookups_enabled")]).isFalse()
        // The answer to the disclosure stays: turning the setting on again needs no new disclosure.
        assertThat(file.data.first()[intPreferencesKey("disclosure_version")]).isEqualTo(current)
    }

    /** A gate whose next write is cancelled before it reaches the file, as when its caller goes away. */
    private suspend fun gateWithACancelledTurnOff(): DataStoreOnlineGate = coroutineScope {
        val writing = CompletableDeferred<Unit>()
        var hold = false
        val held = object : DataStore<Preferences> by file {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (hold) {
                    hold = false
                    writing.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                return file.updateData(transform)
            }
        }
        val gate = gate(IdentificationSettings(held))
        gate.enable()

        hold = true
        val off = launch(Dispatchers.Default) { gate.turnOff() }
        withTimeout(5_000) { writing.await() }
        off.cancelAndJoin()
        gate
    }

    @Test
    fun `a turn-off cancelled before its write keeps the gate closed until Enable is stored`() = runBlocking<Unit> {
        val gate = gateWithACancelledTurnOff()

        // The file still says on, but the user asked for off: closed rather than open by accident.
        assertThat(file.data.first()[booleanPreferencesKey("online_lookups_enabled")]).isTrue()
        assertThat(gate.isOpen()).isFalse()
        assertThat(gate.open.first()).isFalse()

        gate.enable()
        assertThat(gate.isOpen()).isTrue()
    }

    @Test
    fun `after a cancelled turn-off the gate follows the file again once it says off`() = runBlocking<Unit> {
        val gate = gateWithACancelledTurnOff()

        // For example a restore: first the file says off, then on again with the current disclosure.
        IdentificationSettings(file).turnOff()
        IdentificationSettings(file).enable()

        eventually { gate.isOpen() }
    }

    @Test
    fun `the open flow follows the gate, also while a turn-off is being written`() = runBlocking<Unit> {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = false
        val held = object : DataStore<Preferences> by file {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (hold) {
                    writing.complete(Unit)
                    release.await()
                }
                return file.updateData(transform)
            }
        }
        val gate = gate(IdentificationSettings(held))
        gate.awaitLoaded()
        assertThat(gate.open.first()).isFalse()

        gate.enable()
        assertThat(gate.open.first()).isTrue()

        hold = true
        val off = async(Dispatchers.Default) { gate.turnOff() }
        withTimeout(5_000) { writing.await() }
        assertThat(gate.open.first()).isFalse()
        release.complete(Unit)
        withTimeout(5_000) { off.await() }
        assertThat(gate.open.first()).isFalse()
    }

    @Test
    fun `an Enable the gate never sees stored leaves it closed and settles the answers`() = runBlocking<Unit> {
        // The write is lost: the file keeps what it had. (A write that throws takes the same way out, but its log
        // line needs Android.)
        val lost = object : DataStore<Preferences> by file {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                file.data.first()
        }
        val gate = gate(IdentificationSettings(lost))
        gate.awaitLoaded()
        assertThat(gate.settled.value).isZero()

        gate.enable()

        assertThat(gate.isOpen()).isFalse()
        assertThat(gate.open.first()).isFalse()
        // Settings shows the gate again: the switch the tap moved goes back to off.
        assertThat(gate.settled.value).isEqualTo(1)
    }

    @Test
    fun `overlapping answers settle once, after the last of them`() = runBlocking<Unit> {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = false
        val held = object : DataStore<Preferences> by file {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (hold) {
                    hold = false
                    writing.complete(Unit)
                    release.await()
                }
                return file.updateData(transform)
            }
        }
        val gate = gate(IdentificationSettings(held))
        gate.enable()
        assertThat(gate.settled.value).isEqualTo(1)

        hold = true
        val off = async(Dispatchers.Default) { gate.turnOff() }
        withTimeout(5_000) { writing.await() }
        // A second tap while the first is being written: it waits for the first.
        val on = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { gate.enable() }
        assertThat(gate.settled.value).describedAs("while the answers are being written").isEqualTo(1)

        release.complete(Unit)
        withTimeout(5_000) { off.await() }
        withTimeout(5_000) { on.await() }
        assertThat(gate.isOpen()).isTrue()
        assertThat(gate.settled.value).isEqualTo(2)
    }

    @Test
    fun `a settings file that cannot be read is read again`() = runBlocking<Unit> {
        IdentificationSettings(file).enable()
        var reads = 0
        val flaky = object : DataStore<Preferences> by file {
            override val data: Flow<Preferences> = flow {
                if (reads++ == 0) throw java.io.IOException("test: the file cannot be read now")
                emitAll(file.data)
            }
        }
        val gate = gate(IdentificationSettings(flaky))

        assertThat(withTimeout(5_000) { gate.awaitLoaded() }.enabled).isTrue()
        assertThat(gate.isOpen()).isTrue()
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `a build with a newer disclosure finds the gate closed until the user enables it again`() = runBlocking<Unit> {
        gate().enable()

        val newer = IdentificationSettings(file, disclosureVersion = current + 1)
        val gate = gate(newer)
        assertThat(gate.awaitLoaded()).isEqualTo(OnlineConsent(enabled = true, disclosureVersion = current))
        assertThat(gate.isOpen()).isFalse()
        assertThat(newer.needsDisclosure.first()).isTrue()
        assertThat(newer.lookupsOn.first()).isFalse()

        gate.enable()
        assertThat(gate.isOpen()).isTrue()
        assertThat(file.data.first()[intPreferencesKey("disclosure_version")]).isEqualTo(current + 1)
    }

    @Test
    fun `a restored consent opens the gate only with the current disclosure version`() = runBlocking<Unit> {
        // As Auto Backup restores it: the file is there before the app first reads it.
        file.edit {
            it[booleanPreferencesKey("online_lookups_enabled")] = true
            it[intPreferencesKey("disclosure_version")] = current
        }
        assertThat(gate().also { it.awaitLoaded() }.isOpen()).isTrue()

        file.edit { it[intPreferencesKey("disclosure_version")] = current - 1 }
        val older = gate()
        assertThat(older.awaitLoaded().disclosureVersion).isEqualTo(current - 1)
        assertThat(older.isOpen()).isFalse()
    }

    @Test
    fun `a change written by another writer through the same settings file reaches the gate`() = runBlocking<Unit> {
        val gate = gate()
        gate.enable()

        // Settings written elsewhere in the process, for example by another screen.
        IdentificationSettings(file).turnOff()
        eventually { !gate.isOpen() }

        IdentificationSettings(file).enable()
        eventually { gate.isOpen() }
    }

    @Test
    fun `turning the setting on in Settings needs the disclosure only while its version is unanswered`() = runBlocking<Unit> {
        val settings = IdentificationSettings(file)
        assertThat(settings.needsDisclosure.first()).isTrue()
        settings.notNow()
        assertThat(settings.needsDisclosure.first()).isFalse()
        assertThat(settings.lookupsOn.first()).isFalse()
        settings.enable()
        assertThat(settings.lookupsOn.first()).isTrue()
    }

    @Test
    fun `no field is protected by default, and the list changes one field at a time`() = runBlocking<Unit> {
        val settings = IdentificationSettings(file)
        assertThat(settings.protectedFields.first()).isEmpty()

        settings.setProtected(ProtectedField.COVER, true)
        settings.setProtected(ProtectedField.TITLE, true)
        assertThat(settings.protectedFields.first()).containsExactlyInAnyOrder(ProtectedField.COVER, ProtectedField.TITLE)

        settings.setProtected(ProtectedField.COVER, false)
        assertThat(settings.protectedFields.first()).containsExactly(ProtectedField.TITLE)
        assertThat(file.data.first()[stringSetPreferencesKey("protected_fields")]).containsExactly("title")
    }

    @Test
    fun `a protected field this build does not know is kept when the list changes`() = runBlocking<Unit> {
        file.edit { it[stringSetPreferencesKey("protected_fields")] = setOf("mood", "album") }
        val settings = IdentificationSettings(file)
        assertThat(settings.protectedFields.first()).containsExactly(ProtectedField.ALBUM)

        settings.setProtected(ProtectedField.ALBUM, false)
        settings.setProtected(ProtectedField.ISRC, true)

        assertThat(file.data.first()[stringSetPreferencesKey("protected_fields")]).containsExactlyInAnyOrder("mood", "isrc")
    }

    @Test
    fun `the stored names of the protected fields are those of TI 10`() {
        assertThat(ProtectedField.entries.map { it.stored }).containsExactly(
            "title", "artist", "album", "album_artist", "date", "original_date", "track_number", "disc_number",
            "disc_subtitle", "compilation", "label", "catalog_number", "barcode", "isrc", "musicbrainz_ids", "cover",
        )
    }
}
