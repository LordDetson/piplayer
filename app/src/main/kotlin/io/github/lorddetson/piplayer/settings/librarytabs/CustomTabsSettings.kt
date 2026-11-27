package io.github.lorddetson.piplayer.settings.librarytabs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.library.customtabs.CustomTabDefinition
import io.github.lorddetson.piplayer.library.customtabs.CustomTabDefinitionDocument
import io.github.lorddetson.piplayer.library.customtabs.CustomTabRecord
import io.github.lorddetson.piplayer.library.customtabs.CustomTabSource
import io.github.lorddetson.piplayer.library.customtabs.CustomTabSourceDraft
import io.github.lorddetson.piplayer.library.customtabs.CustomTabTemplateSource
import io.github.lorddetson.piplayer.library.customtabs.MAX_TAB_HINT_LENGTH
import io.github.lorddetson.piplayer.library.customtabs.isCanonicalUuid
import io.github.lorddetson.piplayer.library.customtabs.isCustomTabId
import io.github.lorddetson.piplayer.library.customtabs.newCustomTabId
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.configuration.configurationInstallation
import io.github.lorddetson.piplayer.settings.withoutCustomTabs
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal enum class CustomTabWrite { STORED, INVALID, STALE, WRITE_FAILED }

internal data class CustomTabsState(
    val panel: LibraryPanelState,
    val tabs: List<CustomTabRecord>,
    val selectedId: String?,
)

/** Definitions are shared. Attachments and selection stay local, while each theme controls layout. */
internal class CustomTabsSettings(
    private val file: DataStore<Preferences>,
    installation: () -> String,
) {
    constructor(context: Context) : this(SettingsFiles.settings(context), {
        configurationInstallation(context.applicationContext)
    })

    private val installationToken by lazy(installation)

    val state: Flow<CustomTabsState> = file.data.map { preferences ->
        val tabs = storedTabs(preferences)
        val marker = if (tabs.values.any { it.binding != null }) currentInstallation() else null
        CustomTabsState(
            LibraryTabsSettings.panelOf(preferences),
            tabs.values.map { it.resolve(marker) },
            preferences[SELECTED],
        )
    }.distinctUntilChanged()
    val tabs: Flow<List<CustomTabRecord>> = state.map { it.tabs }.distinctUntilChanged()

    suspend fun create(name: String, source: CustomTabSourceDraft, select: Boolean = false): CustomTabWrite {
        val definition = CustomTabDefinition(newCustomTabId(), name.trim(), source.template())
        if (!definition.isValid || !source.isValid) return CustomTabWrite.INVALID
        val marker = if (source.isStructural) currentInstallation() ?: return CustomTabWrite.WRITE_FAILED else null
        return update { preferences ->
            val order = definitionsOf(preferences).keys + definition.id
            writeTab(preferences, StoredTab(definition, source.attach(definition.source, marker)))
            writeOrder(preferences, order)
            if (select) preferences[SELECTED] = definition.id
            CustomTabWrite.STORED
        }
    }

    suspend fun edit(
        expected: CustomTabRecord,
        name: String,
        source: CustomTabSourceDraft? = null,
    ): CustomTabWrite {
        val definition = expected.definition.copy(name = name.trim(), source = source?.template() ?: expected.definition.source)
        if (!definition.isValid || source?.isValid == false) return CustomTabWrite.INVALID
        val marker = currentInstallation()
        if (source?.isStructural == true && marker == null) return CustomTabWrite.WRITE_FAILED
        return updateExisting(expected, marker) { preferences, current ->
            val binding = if (source == null) current.binding else source.attach(definition.source, marker)
            writeTab(preferences, StoredTab(definition, binding))
            CustomTabWrite.STORED
        }
    }

    suspend fun rebind(expected: CustomTabRecord, source: CustomTabSourceDraft): CustomTabWrite {
        val marker = currentInstallation() ?: return CustomTabWrite.WRITE_FAILED
        val binding = source.takeIf { it.isValid }?.attach(expected.definition.source, marker) ?: return CustomTabWrite.INVALID
        return updateExisting(expected, marker) { preferences, current ->
            writeTab(preferences, current.copy(binding = binding))
            CustomTabWrite.STORED
        }
    }

    suspend fun delete(expected: CustomTabRecord): CustomTabWrite =
        updateExisting(expected, currentInstallation()) { preferences, current ->
            val id = current.definition.id
            pruneThemeReferences(preferences, id)
            preferences.remove(tabKey(id))
            writeOrder(preferences, definitionsOf(preferences).keys)
            if (preferences[SELECTED] == id) preferences.remove(SELECTED)
            CustomTabWrite.STORED
        }

    suspend fun setSelected(id: String) {
        SettingsFiles.edit(file) { preferences ->
            if (id in BuiltInLibraryTab.ids || readTab(preferences, id) != null) preferences[SELECTED] = id
        }
    }

    private suspend fun currentInstallation(): String? = withContext(Dispatchers.IO) {
        try {
            installationToken.takeIf(::isCanonicalUuid)
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun updateExisting(
        expected: CustomTabRecord,
        marker: String?,
        change: (MutablePreferences, StoredTab) -> CustomTabWrite,
    ): CustomTabWrite = update { preferences ->
        val current = readTab(preferences, expected.definition.id)
        if (current == null || current.resolve(marker) != expected) CustomTabWrite.STALE else change(preferences, current)
    }

    private suspend fun update(change: (MutablePreferences) -> CustomTabWrite): CustomTabWrite {
        var result = CustomTabWrite.WRITE_FAILED
        val stored = SettingsFiles.edit(file) { result = change(it) }
        return if (stored == null) CustomTabWrite.WRITE_FAILED else result
    }

    companion object {
        private const val TAB_PREFIX = "custom_library_tab_"
        val ORDER = stringPreferencesKey("custom_library_tab_order")
        val SELECTED = stringPreferencesKey("library_selected_tab")

        fun tabKey(id: String) = stringPreferencesKey("$TAB_PREFIX$id")

        fun definitionsOf(preferences: Preferences): Map<String, CustomTabDefinition> =
            storedTabs(preferences).mapValues { (_, tab) -> tab.definition }

        /** Existing definitions absent from the patch are left alone. An attachment belongs to one tab and source reference. */
        fun mergeDefinitions(preferences: MutablePreferences, definitions: Map<String, CustomTabDefinition>): Boolean {
            if (definitions.any { (id, definition) -> id != definition.id || !definition.isValid }) return false
            val previous = storedTabs(preferences)
            for ((id, definition) in definitions) {
                val old = previous[id]
                if (definition == old?.definition) continue
                val binding = old?.binding?.takeIf { it.matches(definition.source) && it.matches(old.definition.source) }
                writeTab(preferences, StoredTab(definition, binding))
            }
            if (definitions.keys.any { it !in previous }) writeOrder(preferences, previous.keys + definitions.keys)
            return true
        }

        /** Applies a portable relative order and appends local-only tabs in their saved order. */
        fun storeOrder(preferences: MutablePreferences, order: List<String>): Boolean {
            val known = definitionsOf(preferences).keys
            if (order.size != order.toSet().size || !known.containsAll(order)) return false
            writeOrder(preferences, order + (known - order.toSet()))
            return true
        }

        private fun storedTabs(preferences: Preferences): Map<String, StoredTab> {
            val ids = preferences.asMap().keys.mapNotNull { key ->
                key.name.removePrefix(TAB_PREFIX).takeIf { key.name.startsWith(TAB_PREFIX) && isCustomTabId(it) }
            }.toSortedSet()
            val order = preferences[ORDER].orEmpty().split(',').filter { it in ids }
            return (order + ids).distinct().mapNotNull { id -> readTab(preferences, id)?.let { id to it } }.toMap()
        }

        private fun readTab(preferences: Preferences, id: String): StoredTab? {
            if (!isCustomTabId(id)) return null
            val stored = preferences[tabKey(id)] ?: return null
            return try {
                val document = Json.parseToJsonElement(stored) as? JsonObject ?: return null
                val definition = document["definition"]?.let { Json.decodeFromJsonElement<CustomTabDefinitionDocument>(it) }
                    ?.toDefinition(id) ?: return null
                val binding = try {
                    document["binding"]?.let { Json.decodeFromJsonElement<LocalTabBinding?>(it) }
                } catch (_: SerializationException) {
                    null
                }
                StoredTab(definition, binding)
            } catch (_: SerializationException) {
                null
            }
        }

        private fun writeTab(preferences: MutablePreferences, tab: StoredTab) {
            preferences[tabKey(tab.definition.id)] =
                Json.encodeToString(StoredTabDocument(CustomTabDefinitionDocument.from(tab.definition), tab.binding))
        }

        private fun writeOrder(preferences: MutablePreferences, order: Collection<String>) {
            if (order.isEmpty()) preferences.remove(ORDER) else preferences[ORDER] = order.joinToString(",")
        }

        private fun pruneThemeReferences(preferences: MutablePreferences, id: String) {
            val themes = preferences.asMap().keys.mapNotNull { key ->
                LibraryTabsSettings.themeForKey(key.name) ?: UiCanvasSettings.themeForKey(key.name)
            }.toSet()
            for (theme in themes) {
                val before = LibraryTabsSettings.panelOf(preferences, theme)
                val canvases = UiCanvasSettings.savedLayouts(preferences, theme)
                val pruned = canvases.withoutCustomTabs(before) { it == id }
                if (LibraryTabsSettings.panelReferences(preferences, theme, id) || pruned.panel != before.withoutTabs { it == id }) {
                    LibraryTabsSettings.storePanel(preferences, theme, pruned.panel)
                }
                if (pruned.canvases != canvases) UiCanvasSettings.store(preferences, theme, pruned.canvases)
            }
        }
    }
}

private data class StoredTab(val definition: CustomTabDefinition, val binding: LocalTabBinding?) {
    fun resolve(installation: String?): CustomTabRecord {
        val source = definition.source
        val local = binding?.takeIf { installation != null && it.installation == installation && it.matches(source) }
        return when (source) {
            is CustomTabTemplateSource.Playlist ->
                CustomTabRecord(definition, local?.playlistId?.let(CustomTabSource::Playlist), local?.playlistCreatedAt)
            is CustomTabTemplateSource.Folder ->
                CustomTabRecord(definition, local?.folderKey?.let(FolderKey::parse)?.let(CustomTabSource::Folder))
            is CustomTabTemplateSource.Collection -> CustomTabRecord(definition, CustomTabSource.Collection(source.kind))
            is CustomTabTemplateSource.Rules -> CustomTabRecord(definition, CustomTabSource.Rules(source.rules))
        }
    }
}

@Serializable
private data class StoredTabDocument(val definition: CustomTabDefinitionDocument, val binding: LocalTabBinding? = null)

@Serializable
private data class LocalTabBinding(
    val installation: String,
    val sourceRef: String,
    val playlistId: Long? = null,
    val playlistCreatedAt: Long? = null,
    val folderKey: String? = null,
) {
    fun matches(source: CustomTabTemplateSource): Boolean = when (source) {
        is CustomTabTemplateSource.Playlist -> source.sourceRef == sourceRef &&
            playlistId != null && playlistId > 0 && playlistCreatedAt != null && folderKey == null
        is CustomTabTemplateSource.Folder -> source.sourceRef == sourceRef &&
            playlistId == null && playlistCreatedAt == null && folderKey?.let(FolderKey::parse) != null
        else -> false
    }
}

private val CustomTabSourceDraft.isStructural: Boolean
    get() = this is CustomTabSourceDraft.Playlist || this is CustomTabSourceDraft.Folder

private val CustomTabSourceDraft.isValid: Boolean get() = when (this) {
    is CustomTabSourceDraft.Playlist -> id > 0
    is CustomTabSourceDraft.Folder -> FolderKey.parse(key.key) == key
    is CustomTabSourceDraft.Collection -> kind.smart
    is CustomTabSourceDraft.Rules -> true
}

private fun CustomTabSourceDraft.template(): CustomTabTemplateSource = when (this) {
    is CustomTabSourceDraft.Playlist -> CustomTabTemplateSource.Playlist(UUID.randomUUID().toString(), hint.localLabel())
    is CustomTabSourceDraft.Folder -> CustomTabTemplateSource.Folder(UUID.randomUUID().toString(), hint.localLabel())
    is CustomTabSourceDraft.Collection -> CustomTabTemplateSource.Collection(kind)
    is CustomTabSourceDraft.Rules -> CustomTabTemplateSource.Rules(rules)
}

private fun String.localLabel(): String =
    filterNot(Char::isISOControl).trim().take(MAX_TAB_HINT_LENGTH).dropLastWhile(Char::isHighSurrogate).trimEnd()

private fun CustomTabSourceDraft.attach(template: CustomTabTemplateSource, installation: String?): LocalTabBinding? {
    if (installation == null) return null
    return when {
        this is CustomTabSourceDraft.Playlist && template is CustomTabTemplateSource.Playlist ->
            LocalTabBinding(installation, template.sourceRef, playlistId = id, playlistCreatedAt = createdAt)
        this is CustomTabSourceDraft.Folder && template is CustomTabTemplateSource.Folder ->
            LocalTabBinding(installation, template.sourceRef, folderKey = key.key)
        else -> null
    }
}
