package io.github.lorddetson.piplayer.settings

/** The theme the user chose (docs/library.md §3.6). */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** Whether the app is dark, given whether the system is. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** The stored value, or [SYSTEM] when there is none or it is unknown. */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}
