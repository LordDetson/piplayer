package io.github.lorddetson.piplayer.radio

internal data class RadioStreamTitle(val station: String, val song: String?)

internal fun radioStreamTitle(
    mediaId: String?,
    stationName: CharSequence?,
    playbackTitle: CharSequence?,
    playbackArtist: CharSequence?,
): RadioStreamTitle? {
    if (mediaId?.startsWith("radio:") != true) return null
    val station = stationName.present() ?: return null
    val title = playbackTitle.present()?.takeUnless { it == station }
    val artist = playbackArtist.present()
    val song = when {
        title == null -> null
        artist == null -> title
        else -> "$artist - $title"
    }
    return RadioStreamTitle(station, song)
}

private fun CharSequence?.present(): String? = this?.toString()?.trim()?.takeUnless(String::isEmpty)
