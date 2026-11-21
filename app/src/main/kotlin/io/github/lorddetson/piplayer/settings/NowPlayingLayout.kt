package io.github.lorddetson.piplayer.settings

/** Built-in presentations of Now Playing, with a shared choice and overrides for complete themes. */
enum class NowPlayingLayout(val stored: String) {
    STANDARD("standard"),
    MINIMAL("minimal"),
    EXPANDED("expanded");

    companion object {
        fun fromStored(value: String?): NowPlayingLayout = entries.firstOrNull { it.stored == value } ?: STANDARD
    }
}
