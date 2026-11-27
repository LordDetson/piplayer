package io.github.lorddetson.piplayer.settings.configuration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.AppLanguageChange
import io.github.lorddetson.piplayer.settings.AppLanguageSettings
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.settings.librarytabs.CustomTabsSettings
import io.github.lorddetson.piplayer.ui.player.TimerChoiceStore
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal enum class ConfigurationSection { LANGUAGE, LIBRARY, TAG_WRITING, SLEEP_TIMER, APPEARANCE }

internal data class ConfigurationChange(
    val section: ConfigurationSection,
    val path: String,
    val before: JsonElement?,
    val after: JsonElement?,
)

internal class ConfigurationPreview internal constructor(
    val changes: List<ConfigurationChange>,
    internal val transaction: ConfigurationTransaction,
)

internal class ConfigurationUndo internal constructor(internal val transaction: ConfigurationTransaction)

internal sealed interface ConfigurationPreviewResult {
    data class Ready(val preview: ConfigurationPreview) : ConfigurationPreviewResult
    data object Invalid : ConfigurationPreviewResult
    data object UnsupportedVersion : ConfigurationPreviewResult
    data object TooLarge : ConfigurationPreviewResult
    data object Unreadable : ConfigurationPreviewResult
}

internal sealed interface ConfigurationWriteResult {
    data class Applied(val undo: ConfigurationUndo, val languageChange: AppLanguageChange) : ConfigurationWriteResult
    data class Restored(val languageChange: AppLanguageChange) : ConfigurationWriteResult
    data object NoChanges : ConfigurationWriteResult
    data object Stale : ConfigurationWriteResult
    data object Invalid : ConfigurationWriteResult
    data object WriteFailed : ConfigurationWriteResult
}

internal data class ConfigurationTransaction(
    val preferences: List<ConfigurationStoredChange>,
    val language: ConfigurationLocaleChange?,
    val themes: ConfigurationThemeDependencies?,
) {
    val isEmpty: Boolean get() = preferences.isEmpty() && language == null
}

/** Imports a partial, validated preferences snapshot. Android owns the app locale on API 33 and later. */
internal class ConfigurationStore(
    private val file: DataStore<Preferences>,
    private val locale: ConfigurationLocale,
    private val installation: () -> String?,
    private val mutations: Mutex = Mutex(),
) {
    constructor(context: Context) : this(
        SettingsFiles.settings(context),
        AppLanguageSettings.transactionLocale(context.applicationContext),
        { configurationInstallation(context.applicationContext) },
        AppLanguageSettings.mutations,
    )

    suspend fun export(): ByteArray = io {
        TimerChoiceStore.awaitWrites()
        val current = file.data.first()
        if (current[JOURNAL] != null) throw IOException("An unfinished configuration change needs recovery")
        val settings = generalConfiguration(current)
        ConfigurationCodec.encode(settings.copy(
            language = locale.selected(locale.read()),
            library = settings.library.withStoredTabs(current),
            appearance = ConfigurationThemes.snapshot(current),
        ))
    }

    suspend fun preview(source: InputStream): ConfigurationPreviewResult =
        when (val read = withContext(Dispatchers.IO) { ConfigurationCodec.decode(source) }) {
            is ConfigurationRead.Loaded -> preview(read.patch)
            ConfigurationRead.Invalid -> ConfigurationPreviewResult.Invalid
            ConfigurationRead.UnsupportedVersion -> ConfigurationPreviewResult.UnsupportedVersion
            ConfigurationRead.TooLarge -> ConfigurationPreviewResult.TooLarge
            ConfigurationRead.Unreadable -> ConfigurationPreviewResult.Unreadable
        }

    internal suspend fun preview(patch: ConfigurationPatch): ConfigurationPreviewResult = io {
        try {
            TimerChoiceStore.awaitWrites()
            val before = file.data.first()
            if (before[JOURNAL] != null) return@io ConfigurationPreviewResult.Unreadable
            val after = before.toMutablePreferences()
            val changes = mergeGeneralConfiguration(patch, before, after)?.toMutableList()
                ?: return@io ConfigurationPreviewResult.Invalid
            changes += mergeCustomTabs(patch.library, before, after) ?: return@io ConfigurationPreviewResult.Invalid
            val themes = if (patch.appearance != AppearancePatch()) {
                if (!ConfigurationThemes.apply(patch.appearance, after)) return@io ConfigurationPreviewResult.Invalid
                changes += appearanceChanges(before, after)
                ConfigurationThemeDependencies.between(before, after)
            } else null
            val language = patch.language?.let { selected ->
                val prior = locale.read()
                val current = locale.selected(prior)
                if (current == selected) null else {
                    changes.add(0, ConfigurationChange(
                        ConfigurationSection.LANGUAGE, "language", languageJson(current), languageJson(selected),
                    ))
                    ConfigurationLocaleChange(prior, locale.target(selected))
                }
            }
            val preferences = ConfigurationStoredChange.between(before, after)
            ConfigurationPreviewResult.Ready(ConfigurationPreview(changes, ConfigurationTransaction(preferences, language, themes)))
        } catch (_: IOException) {
            ConfigurationPreviewResult.Unreadable
        } catch (_: SecurityException) {
            ConfigurationPreviewResult.Unreadable
        }
    }

    suspend fun apply(preview: ConfigurationPreview): ConfigurationWriteResult =
        write(preview.transaction, undo = false)

    suspend fun undo(token: ConfigurationUndo): ConfigurationWriteResult =
        write(token.transaction, undo = true)

    /** Runs before startup initializes its locale-sensitive resources and collation. */
    internal suspend fun recover() {
        io { recoverLocked() }
    }

    private suspend fun write(transaction: ConfigurationTransaction, undo: Boolean): ConfigurationWriteResult =
        io {
            if (transaction.isEmpty) return@io ConfigurationWriteResult.NoChanges
            TimerChoiceStore.awaitWrites()
            currentCoroutineContext().ensureActive()
            // Once a journal is durable, finish or roll back even if the initiating screen is closed.
            withContext(NonCancellable) {
                if (!recoverLocked()) return@withContext ConfigurationWriteResult.WriteFailed
                val language = transaction.language?.let { if (undo) it.reversed() else it }
                val committed = try {
                    if (language == null) commit(transaction, undo)
                    else commitWithLocale(transaction, undo, language)
                } catch (abort: WriteAborted) {
                    return@withContext abort.result
                } catch (_: IOException) {
                    return@withContext ConfigurationWriteResult.WriteFailed
                } catch (_: SecurityException) {
                    return@withContext ConfigurationWriteResult.WriteFailed
                }
                if (transaction.preferences.any { it.changesHiddenFiles }) {
                    LibrarySettings.rememberHiddenFiles(committed.preferences)
                }
                val change = if (language == null) AppLanguageChange.UNCHANGED else locale.change
                if (undo) ConfigurationWriteResult.Restored(change)
                else ConfigurationWriteResult.Applied(
                    ConfigurationUndo(checkNotNull(committed.undoTransaction)),
                    change,
                )
            }
        }

    private suspend fun commit(
        transaction: ConfigurationTransaction,
        undo: Boolean,
        journal: String? = null,
        language: ConfigurationLocaleChange? = null,
    ): CommittedPreferences {
        var undoTransaction: ConfigurationTransaction? = null
        val preferences = file.updateData { current ->
            if (journal != null && current[JOURNAL] != journal) stale()
            val updated = update(current, transaction, undo)
            if (!undo) {
                // Capture the actual write while both snapshots still contain the same staged journal.
                undoTransaction = transaction.copy(
                    preferences = ConfigurationStoredChange.between(current, updated),
                    themes = transaction.themes?.let {
                        ConfigurationThemeDependencies.between(current, updated, rememberLegacyState = true)
                    },
                )
            }
            if (journal != null) {
                if (locale.read() != language?.after) stale()
                updated.remove(JOURNAL)
            }
            updated.toPreferences()
        }
        return CommittedPreferences(preferences, undoTransaction)
    }

    private suspend fun commitWithLocale(
        transaction: ConfigurationTransaction,
        undo: Boolean,
        change: ConfigurationLocaleChange,
    ): CommittedPreferences {
        if (locale.read() != change.before) stale()
        val identity = installation() ?: throw IOException("Could not identify this installation")
        val marker = Json.encodeToString(ConfigurationLocaleJournal(identity, locale.backend, change.before, change.after))
        file.edit { current ->
            update(current, transaction, undo)
            if (locale.read() != change.before) stale()
            current[JOURNAL] = marker
        }
        var attempted = false
        try {
            if (locale.read() != change.before) stale()
            attempted = true
            if (!locale.write(change.after)) throw IOException("Could not write the app locale")
            val observed = locale.read()
            if (observed == change.before) throw IOException("The app locale did not change")
            if (observed != change.after) stale()
            return commit(transaction, undo, marker, change)
        } catch (error: Exception) {
            val recovered = if (attempted) recoverLocked() else clearJournal(marker)
            if (!recovered) throw IOException("Could not roll back the app locale", error)
            throw error
        }
    }

    private fun update(current: Preferences, transaction: ConfigurationTransaction, undo: Boolean): MutablePreferences {
        if (transaction.preferences.any { !it.matches(current, undo) }) stale()
        val next = current.toMutablePreferences()
        transaction.preferences.forEach { it.write(next, undo) }
        if (transaction.themes?.matches(current, next, undo) == false) stale()
        val removedTabs = CustomTabsSettings.definitionsOf(current).keys - CustomTabsSettings.definitionsOf(next).keys
        if (removedTabs.isNotEmpty() && ConfigurationThemes.referencesAnyTab(next, removedTabs)) stale()
        val valid = ConfigurationThemes.isValidAfterChange(current, next) { id ->
            undo && transaction.themes?.restoresLegacyState(next, id) == true
        }
        if (!valid) {
            throw WriteAborted(ConfigurationWriteResult.Invalid)
        }
        return next
    }

    private suspend fun recoverLocked(): Boolean {
        return try {
            val marker = file.data.first()[JOURNAL] ?: return true
            val journal = try {
                Json.decodeFromString<ConfigurationLocaleJournal>(marker)
            } catch (_: SerializationException) {
                null
            }
            if (journal != null && locale.backend == journal.backend && installation() == journal.installation &&
                locale.read() == journal.after
            ) {
                if (!locale.write(journal.before) || locale.read() != journal.before) return false
            }
            clearJournal(marker)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private suspend fun clearJournal(marker: String): Boolean = try {
        file.edit { if (it[JOURNAL] == marker) it.remove(JOURNAL) }
        true
    } catch (_: IOException) {
        false
    }

    private suspend fun <T> io(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutations.withLock { operation() } }

    private fun stale(): Nothing = throw WriteAborted(ConfigurationWriteResult.Stale)

    private data class CommittedPreferences(
        val preferences: Preferences,
        val undoTransaction: ConfigurationTransaction?,
    )

    private class WriteAborted(val result: ConfigurationWriteResult) : Exception()

    private companion object {
        val JOURNAL = stringPreferencesKey("configuration_locale_journal")
    }
}

private fun appearanceChanges(before: Preferences, after: Preferences): List<ConfigurationChange> {
    val from = ConfigurationCodec.appearanceJson(ConfigurationThemes.snapshot(before))
    val to = ConfigurationCodec.appearanceJson(ConfigurationThemes.snapshot(after))
    return jsonDiff(from, to) { path ->
        when (path.first()) {
            "savedThemes" -> path.size <= 2 || path.size == 3 && path.last() in setOf("palette", "layout") ||
                path.size == 4 && path.last() == "canvases"
            "builtInOverrides" -> path.size <= 2 || path.size == 3 && path.last() == "canvases"
            else -> false
        }
    }.map { (path, old, new) ->
        ConfigurationChange(ConfigurationSection.APPEARANCE, "appearance." + path.joinToString("."), old, new)
    }
}

private fun languageJson(language: AppLanguage) = JsonPrimitive(language.tag ?: "system")
