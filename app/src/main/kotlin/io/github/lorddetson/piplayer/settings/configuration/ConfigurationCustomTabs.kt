package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.lorddetson.piplayer.library.customtabs.isCustomTabId
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import io.github.lorddetson.piplayer.settings.librarytabs.CustomTabsSettings
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelDocument
import io.github.lorddetson.piplayer.settings.referencedCustomTabIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal fun LibraryPatch.withStoredTabs(preferences: Preferences): LibraryPatch {
    val tabs = CustomTabsSettings.definitionsOf(preferences)
    return if (tabs.isEmpty()) this else copy(customTabs = tabs, customTabOrder = tabs.keys.toList())
}

internal fun mergeCustomTabs(
    patch: LibraryPatch,
    before: Preferences,
    after: MutablePreferences,
): List<ConfigurationChange>? {
    if (patch.customTabs.isEmpty() && patch.customTabOrder == null) return emptyList()
    if (!CustomTabsSettings.mergeDefinitions(after, patch.customTabs)) return null
    if (patch.customTabOrder?.let { !CustomTabsSettings.storeOrder(after, it) } == true) return null
    val oldTabs = CustomTabsSettings.definitionsOf(before)
    val newTabs = CustomTabsSettings.definitionsOf(after)
    return buildList {
        for (id in patch.customTabs.keys.sorted()) {
            val oldTab = oldTabs[id]
            val newTab = newTabs[id]
            if (oldTab != newTab) add(ConfigurationChange(
                ConfigurationSection.LIBRARY,
                "library.customTabs.$id",
                oldTab?.let(ConfigurationCodec::customTabJson),
                newTab?.let(ConfigurationCodec::customTabJson),
            ))
        }
        if (patch.customTabOrder != null && oldTabs.keys.toList() != newTabs.keys.toList()) {
            add(ConfigurationChange(
                ConfigurationSection.LIBRARY,
                "library.customTabOrder",
                JsonArray(oldTabs.keys.map(::JsonPrimitive)),
                JsonArray(newTabs.keys.map(::JsonPrimitive)),
            ))
        }
    }
}

internal fun LibraryPanelDocument.referencedCustomTabIds(): Set<String> =
    sequenceOf(tabOrder, hiddenTabs).flatMap { it.splitToSequence(',') }.filter(::isCustomTabId).toSet()

internal fun AppearancePatch.referencedCustomTabIds(): Set<String> = buildSet {
    builtInOverrides.values.forEach { addAll(it.referencedCustomTabIds()) }
    savedThemes.values.forEach { addAll(it.layout.referencedCustomTabIds()) }
}

private fun ThemeLayoutPatch.referencedCustomTabIds(): Set<String> = buildSet {
    library?.value?.tabs?.order?.filterTo(this, ::isCustomTabId)
    val savedCanvases = UiThemeCanvases(
        panels = canvases.mapNotNull { (surface, value) -> value.value?.let { surface to it } }.toMap(),
        gestures = gestures?.value ?: UiGestureProfile(),
    )
    addAll(savedCanvases.referencedCustomTabIds())
}
