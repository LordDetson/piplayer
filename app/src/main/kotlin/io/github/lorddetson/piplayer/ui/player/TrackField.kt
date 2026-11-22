package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.lorddetson.piplayer.R

internal enum class TrackField(val id: String, val label: Int) {
    TITLE("title", R.string.details_field_title),
    ARTIST("artist", R.string.details_field_artist),
    ALBUM("album", R.string.details_field_album),
    ;

    val nowPlayingId: String get() = "now:$id"
    val miniPlayerId: String get() = "mini:$id"

    @OptIn(UnstableApi::class)
    fun value(metadata: MediaMetadata, unknownArtist: String): String? = when (this) {
        TITLE -> metadata.title?.toString().orEmpty()
        ARTIST -> metadata.artist?.toString() ?: unknownArtist
        ALBUM -> metadata.albumTitle?.toString()
    }
}
