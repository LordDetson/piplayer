package io.github.lorddetson.piplayer.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/** Which files the lists hide (docs/library.md §3.5). */
data class HiddenFiles(
    val showSystemSounds: Boolean = false,
    val showRecordings: Boolean = false,
    /** Files shorter than this are hidden; 0 hides none. */
    val minLengthSeconds: Int = DEFAULT_MIN_LENGTH_SECONDS,
) {
    val minLengthMs: Long get() = minLengthSeconds * 1_000L

    companion object {
        const val DEFAULT_MIN_LENGTH_SECONDS = 10

        /** The lengths the setting offers, 0 for off. */
        val LENGTHS = listOf(0, 5, 10, 15, 30, 60)

        val SHOW_SYSTEM_SOUNDS = booleanPreferencesKey("show_system_sounds")
        val SHOW_RECORDINGS = booleanPreferencesKey("show_recordings")
        val MIN_LENGTH_SECONDS = intPreferencesKey("min_length_s")

        fun from(preferences: Preferences): HiddenFiles = HiddenFiles(
            showSystemSounds = preferences[SHOW_SYSTEM_SOUNDS] ?: false,
            showRecordings = preferences[SHOW_RECORDINGS] ?: false,
            minLengthSeconds = preferences[MIN_LENGTH_SECONDS]?.takeIf { it in LENGTHS } ?: DEFAULT_MIN_LENGTH_SECONDS,
        )
    }
}
