package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Starts a live station in PiPlayer's player so audio focus, notifications and headset controls are shared. */
object RadioPlayback {
    const val MEDIA_ID_PREFIX = "radio:"

    suspend fun play(context: Context, name: String, streamUrl: String): Boolean {
        val url = streamUrl.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?: return false
        val station = name.trim().takeIf { it.isNotEmpty() } ?: return false
        val item = MediaItem.Builder()
            .setMediaId(MEDIA_ID_PREFIX + UUID.nameUUIDFromBytes(url.toString().toByteArray()))
            .setUri(url.toString())
            .setMediaMetadata(MediaMetadata.Builder().setStation(station).build())
            .build()
        return withContext(Dispatchers.Main.immediate) {
            val connection = PlaybackConnection(context.applicationContext)
            try {
                val player = withTimeoutOrNull(10_000) { connection.awaitConnection() } ?: return@withContext false
                if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return@withContext false
                // PiPlayer's own explicit media items use a transient queue. The last saved library queue stays on disk.
                player.setMediaItem(item)
                player.prepare()
                player.play()
                true
            } finally {
                connection.release()
            }
        }
    }
}
