package io.github.lorddetson.piplayer.settings

/** The theme the user chose (docs/library.md §3.6). */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    AMOLED("amoled"),
    GRAPHITE("graphite"),
    WARM_LIGHT("warm_light");

    /** Whether the app is dark, given whether the system is. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT, WARM_LIGHT -> false
        DARK, AMOLED, GRAPHITE -> true
    }

    companion object {
        /** The stored value, or [SYSTEM] when there is none or it is unknown. */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}
