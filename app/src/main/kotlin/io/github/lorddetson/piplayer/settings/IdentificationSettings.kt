package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.net.OnlineGate
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A field identification never proposes to change once the user protects it (TI §6), stored under [stored] in the
 * `protected_fields` set of the `settings` file (TI §10).
 */
enum class ProtectedField(val stored: String) {
    TITLE("title"),

    /** The track's artists, with their credits and sort names. */
    ARTIST("artist"),
    ALBUM("album"),

    /** The album's artists, with their credits and sort names. */
    ALBUM_ARTIST("album_artist"),
    DATE("date"),
    ORIGINAL_DATE("original_date"),

    /** The track number and the number of tracks. */
    TRACK_NUMBER("track_number"),

    /** The disc number and the number of discs. */
    DISC_NUMBER("disc_number"),
    DISC_SUBTITLE("disc_subtitle"),
    COMPILATION("compilation"),
    LABEL("label"),
    CATALOG_NUMBER("catalog_number"),
    BARCODE("barcode"),
    ISRC("isrc"),
    MUSICBRAINZ_IDS("musicbrainz_ids"),
    COVER("cover"),
}

/**
 * The Identification and privacy settings in the backed-up `settings` file (TI §9, §10): the online-lookup setting,
 * the disclosure version the user last answered and the protected fields. [disclosureVersion] is the version of the
 * disclosure this build shows; a consent given to an older one, as after an update or a restore from an older build,
 * does not count until the user answers the new one.
 *
 * Shipped code changes the consent only through [DataStoreOnlineGate], which closes the gate at once when it is turned
 * off; this class is the storage beneath it.
 */
class IdentificationSettings(
    private val file: DataStore<Preferences>,
    val disclosureVersion: Int = OnlineConsent.CURRENT_DISCLOSURE_VERSION,
) {

    constructor(context: Context) : this(SettingsFiles.settings(context))

    /** The stored choice; [OnlineConsent.NONE] for a file that has none. */
    val consent: Flow<OnlineConsent> = file.data.map(::consentOf).distinctUntilChanged()

    /** Whether online lookups are on for this build: the setting is on and the current disclosure was accepted. */
    val lookupsOn: Flow<Boolean> = consent.map { it.allows(disclosureVersion) }.distinctUntilChanged()

    /** Whether the user has not answered this build's disclosure yet, by Enable or Not now. */
    val needsDisclosure: Flow<Boolean> = consent.map { it.disclosureVersion < disclosureVersion }.distinctUntilChanged()

    /** The fields identification leaves alone (TI §6); names this build does not know are left out here. */
    val protectedFields: Flow<Set<ProtectedField>> = file.data.map { preferences ->
        val stored = preferences[PROTECTED_FIELDS].orEmpty()
        ProtectedField.entries.filterTo(LinkedHashSet()) { it.stored in stored }
    }.distinctUntilChanged()

    /** The answer Enable: the setting on, this build's disclosure answered. Whether it was stored. */
    suspend fun enable(): Boolean = SettingsFiles.edit(file) {
        it[ENABLED] = true
        it[VERSION] = maxOf(it[VERSION] ?: 0, disclosureVersion)
    } != null

    /** The answer Not now: the setting off, this build's disclosure answered, so it does not come back before every lookup. */
    suspend fun notNow(): Boolean = SettingsFiles.edit(file) {
        it[ENABLED] = false
        it[VERSION] = maxOf(it[VERSION] ?: 0, disclosureVersion)
    } != null

    /** The setting off; the answered disclosure stays, so turning it on again needs no new one. */
    suspend fun turnOff(): Boolean = SettingsFiles.edit(file) { it[ENABLED] = false } != null

    /** Protects [field] or stops protecting it; names this build does not know are kept (TI §10). */
    suspend fun setProtected(field: ProtectedField, protected: Boolean) {
        SettingsFiles.edit(file) { preferences ->
            val stored = preferences[PROTECTED_FIELDS].orEmpty()
            preferences[PROTECTED_FIELDS] = if (protected) stored + field.stored else stored - field.stored
        }
    }

    companion object {
        val ENABLED = booleanPreferencesKey("online_lookups_enabled")
        val VERSION = intPreferencesKey("disclosure_version")
        val PROTECTED_FIELDS = stringSetPreferencesKey("protected_fields")

        fun consentOf(preferences: Preferences) =
            OnlineConsent(enabled = preferences[ENABLED] ?: false, disclosureVersion = preferences[VERSION] ?: 0)
    }
}

/**
 * The app's online gate (TI §9): the consent in the `settings` file, kept in memory so that [isOpen] neither blocks
 * nor allocates. It is closed until the file has been read, and open only while the setting is on and the accepted
 * disclosure is at least [IdentificationSettings.disclosureVersion]. It follows every change written through the
 * process's `settings` file, also by another writer than this gate; a restore, which Android applies while the app is
 * stopped, is read at the next start. Since every request, redirect hop, host lookup and new connection asks it
 * (TI §8), a change reaches calls that are already waiting.
 *
 * Changes of the consent go through [enable], [notNow] and [turnOff]. The last two close the gate at once, before the
 * write starts. A close that may not have been stored (its write failed, as on a full disk, or was cancelled) keeps the
 * gate closed until the file says off or an [enable] is stored, so it never opens against the user's last answer. A
 * file that cannot be read is read again, with growing pauses; the gate keeps what it last read meanwhile.
 */
class DataStoreOnlineGate(val settings: IdentificationSettings, scope: CoroutineScope) : OnlineGate {

    private val stored = MutableStateFlow<OnlineConsent?>(null)

    /** The consent last read from the file; null until the first read. */
    val consent: StateFlow<OnlineConsent?> = stored.asStateFlow()

    /** Closes that have started and whose write the gate has not seen yet. */
    private val closing = MutableStateFlow(0)

    /** A close that may not have been stored: closed until the file says off or an Enable is stored. */
    private val closeFailed = MutableStateFlow(false)

    private val writes = Mutex()

    private val settledAnswers = MutableStateFlow(0)

    /** Answers that have started and not finished, waiting for an earlier one included. */
    private var answering = 0
    private val answeringLock = Any()

    /**
     * How many times the consent answers ([enable], [notNow], [turnOff]) have all finished, stored or not; it grows when
     * the last of overlapping answers ends. Settings shows the gate again each time, so a switch moved by an answer that
     * was not stored goes back to what the gate is.
     */
    val settled: StateFlow<Int> = settledAnswers.asStateFlow()

    init {
        scope.launch {
            settings.consent
                .retryWhen { cause, attempt ->
                    val retry = cause is IOException
                    if (retry) delay(READ_RETRY_PAUSE * (1 shl attempt.coerceAtMost(6).toInt()))
                    retry
                }
                .collect { consent ->
                    stored.value = consent
                    if (!consent.enabled) closeFailed.value = false
                }
        }
    }

    override fun isOpen(): Boolean = opens(stored.value, closing.value, closeFailed.value)

    /** Whether the gate is open, as Settings shows it; it changes as [isOpen] does. */
    val open: Flow<Boolean> = combine(stored, closing, closeFailed, ::opens).distinctUntilChanged()

    private fun opens(consent: OnlineConsent?, closes: Int, failed: Boolean) =
        closes == 0 && !failed && consent?.allows(settings.disclosureVersion) == true

    /** Waits until the file has been read; the consent read. */
    suspend fun awaitLoaded(): OnlineConsent = stored.filterNotNull().first()

    /** The answer Enable; the gate is open when this returns, unless the write failed. */
    suspend fun enable() = answer {
        writes.withLock {
            if (settings.enable()) {
                seen { it.allows(settings.disclosureVersion) }
                closeFailed.value = false
            }
        }
    }

    /** The answer Not now; the gate is closed from the call on. */
    suspend fun notNow() = answer { close { settings.notNow() } }

    /** The setting off; the gate is closed from the call on. */
    suspend fun turnOff() = answer { close { settings.turnOff() } }

    /** Runs one consent answer and grows [settled] when it was the last one running. */
    private suspend fun answer(block: suspend () -> Unit) {
        synchronized(answeringLock) { answering++ }
        try {
            block()
        } finally {
            synchronized(answeringLock) {
                if (--answering == 0) settledAnswers.update { it + 1 }
            }
        }
    }

    private suspend fun close(write: suspend () -> Boolean) {
        closing.update { it + 1 }
        try {
            writes.withLock {
                // Stored but not seen yet: closed until the file read says off.
                if (!write() || !seen { !it.enabled }) failClosed()
            }
        } catch (e: CancellationException) {
            // The write may not have happened.
            failClosed()
            throw e
        } finally {
            closing.update { it - 1 }
        }
    }

    private fun failClosed() {
        closeFailed.value = true
        // The read may have said off already, before the flag was up.
        if (stored.value?.enabled == false) closeFailed.value = false
    }

    /** Whether the gate's copy reaches a consent that passes [check] within a few seconds of a write. */
    private suspend fun seen(check: (OnlineConsent) -> Boolean): Boolean =
        withTimeoutOrNull(SEEN_TIMEOUT) { stored.first { it != null && check(it) } } != null

    private companion object {
        val SEEN_TIMEOUT = 5.seconds
        val READ_RETRY_PAUSE = 1.seconds
    }
}
