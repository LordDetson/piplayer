package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListSorts
import io.github.lorddetson.piplayer.settings.TagWritingSettings
import io.github.lorddetson.piplayer.ui.player.TimerChoiceStore
import kotlinx.serialization.json.JsonPrimitive

private const val AUTOMATIC_ENCODING = "automatic"

internal fun generalConfiguration(preferences: Preferences): ConfigurationPatch {
    val hidden = HiddenFiles.from(preferences)
    val writing = TagWritingSettings.optionsOf(preferences)
    val timer = TimerChoiceStore.choiceOf(preferences)
    return ConfigurationPatch(
        library = LibraryPatch(
            showSystemSounds = hidden.showSystemSounds,
            showRecordings = hidden.showRecordings,
            minLengthSeconds = hidden.minLengthSeconds,
            legacyEncodingId = LegacyEncodings.fromStored(preferences[LibrarySettings.LEGACY_ENCODING])?.name ?: AUTOMATIC_ENCODING,
            lists = ListName.entries.associateWith { name ->
                val order = ListSorts.orderOf(preferences, name)
                ListPatch(order.field, order.reversed, ListSorts.gridOf(preferences, name).takeIf { name.offersGrid })
            },
        ),
        tagWriting = TagWritingPatch(writing.id3v1, writing.cover),
        sleepTimer = SleepTimerPatch(timer.hours, timer.minutes, timer.finishTrack),
    )
}

/** Validates wire values against the local device and records effective changes while writing only those preferences. */
internal fun mergeGeneralConfiguration(
    patch: ConfigurationPatch,
    before: Preferences,
    after: MutablePreferences,
): List<ConfigurationChange>? {
    val library = patch.library
    val sleep = patch.sleepTimer
    if (library.minLengthSeconds?.let { it !in HiddenFiles.LENGTHS } == true ||
        sleep.hours?.let { it !in 0..23 } == true || sleep.minutes?.let { it !in 0..59 } == true ||
        library.lists.any { (name, list) ->
            (list.field != null && list.field !in name.fields) || (list.grid != null && !name.offersGrid)
        }
    ) return null
    val encoding = library.legacyEncodingId?.takeUnless { it == AUTOMATIC_ENCODING }?.let {
        LegacyEncodings.fromStored(it)?.name ?: return null
    }
    val stored = generalConfiguration(before)
    val changes = mutableListOf<ConfigurationChange>()

    fun <T : Any, V : Any> setting(
        section: ConfigurationSection,
        path: String,
        key: Preferences.Key<V>,
        old: T?,
        new: T?,
        raw: (T) -> V?,
    ) {
        if (new == null || new == old) return
        val value = raw(new)
        if (value == null) after.remove(key) else after[key] = value
        changes += ConfigurationChange(section, path, old?.let(::settingJson), settingJson(new))
    }

    val lib = ConfigurationSection.LIBRARY
    setting(lib, "library.showSystemSounds", HiddenFiles.SHOW_SYSTEM_SOUNDS, stored.library.showSystemSounds, library.showSystemSounds) { it }
    setting(lib, "library.showRecordings", HiddenFiles.SHOW_RECORDINGS, stored.library.showRecordings, library.showRecordings) { it }
    setting(lib, "library.minLengthSeconds", HiddenFiles.MIN_LENGTH_SECONDS, stored.library.minLengthSeconds, library.minLengthSeconds) { it }
    setting(lib, "library.legacyEncodingId", LibrarySettings.LEGACY_ENCODING, stored.library.legacyEncodingId, library.legacyEncodingId) { encoding }
    for ((name, listPatch) in library.lists) {
        val prior = stored.library.lists.getValue(name)
        val prefix = "library.lists.${name.id}"
        setting(lib, "$prefix.field", ListSorts.fieldKey(name), prior.field, listPatch.field) { it }
        setting(lib, "$prefix.reversed", ListSorts.reversedKey(name), prior.reversed, listPatch.reversed) { it }
        setting(lib, "$prefix.grid", ListSorts.gridKey(name), prior.grid, listPatch.grid) { it }
    }
    val tags = ConfigurationSection.TAG_WRITING
    setting(tags, "tagWriting.id3v1", TagWritingSettings.ID3V1, stored.tagWriting.id3v1, patch.tagWriting.id3v1) { it.name }
    setting(tags, "tagWriting.cover", TagWritingSettings.COVER, stored.tagWriting.cover, patch.tagWriting.cover) { it.name }
    val timer = ConfigurationSection.SLEEP_TIMER
    setting(timer, "sleepTimer.hours", TimerChoiceStore.HOURS, stored.sleepTimer.hours, sleep.hours) { it }
    setting(timer, "sleepTimer.minutes", TimerChoiceStore.MINUTES, stored.sleepTimer.minutes, sleep.minutes) { it }
    setting(timer, "sleepTimer.finishTrack", TimerChoiceStore.FINISH_TRACK, stored.sleepTimer.finishTrack, sleep.finishTrack) { it }
    return changes
}

private fun settingJson(value: Any): JsonPrimitive = when (value) {
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name.lowercase())
    else -> JsonPrimitive(value.toString())
}
