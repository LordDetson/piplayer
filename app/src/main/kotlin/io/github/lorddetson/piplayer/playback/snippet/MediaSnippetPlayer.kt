package io.github.lorddetson.piplayer.playback.snippet

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/** The snippet player on Media3 and the platform's audio focus (TI §6). */
internal class MediaSnippetPlayer(context: Context, main: Player) : SnippetPlayer {

    override val state: StateFlow<SnippetState> get() = TODO()

    override val positionMs: Long get() = TODO()

    override fun play(uri: Uri, durationMs: Long?): Unit = TODO()

    override fun stop(): Unit = TODO()
}
