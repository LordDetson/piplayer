package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A controller of the playback service for the UI. Create it on the main thread; it lives until [release]. [listener]
 * hears the session's messages, such as playback stopping on broken songs.
 */
class PlaybackConnection(context: Context, listener: MediaController.Listener? = null) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private val controller = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    )
        .apply { listener?.let(::setListener) }
        .buildAsync()

    /** Runs [block] on the main thread once the controller is connected; drops it if the connection failed. */
    fun whenConnected(block: MediaController.() -> Unit) {
        controller.addListener({ runCatching { controller.get() }.getOrNull()?.block() }, mainExecutor)
    }

    internal suspend fun awaitConnection(): MediaController? = suspendCancellableCoroutine { continuation ->
        controller.addListener({ continuation.resume(runCatching { controller.get() }.getOrNull()) }, mainExecutor)
    }

    fun release() = MediaController.releaseFuture(controller)
}
