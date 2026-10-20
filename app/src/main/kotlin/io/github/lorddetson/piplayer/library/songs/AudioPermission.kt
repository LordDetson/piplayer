package io.github.lorddetson.piplayer.library.songs

import android.Manifest
import android.os.Build

/** The runtime permission for reading music from the media index (docs/playback.md §2.2). */
object AudioPermission {

    /** READ_MEDIA_AUDIO from API 33 on, READ_EXTERNAL_STORAGE before. */
    val name: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
}
