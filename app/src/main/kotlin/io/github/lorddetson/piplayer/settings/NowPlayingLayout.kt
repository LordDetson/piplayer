package io.github.lorddetson.piplayer.settings

/** Built-in arrangements of Now Playing, saved independently from the theme. */
enum class NowPlayingLayout(val stored: String) {
    STANDARD("standard"),
    MINIMAL("minimal"),
    EXPANDED("expanded");

    companion object {
        fun fromStored(value: String?): NowPlayingLayout = entries.firstOrNull { it.stored == value } ?: STANDARD
    }
}
