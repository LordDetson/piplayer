package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/** Release builds keep no audio journal (docs/playback.md §8.3). */
object AudioJournal {

    fun attach(context: Context, player: ExoPlayer) = Unit
}
