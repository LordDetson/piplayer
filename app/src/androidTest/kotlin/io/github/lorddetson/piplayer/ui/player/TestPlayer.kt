package io.github.lorddetson.piplayer.ui.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player for screen tests: it holds a queue of [songs] in a fixed state and records what the screens ask of it. Create
 * and change it on the main thread. The position is [positionMs] whenever the screens read it, playing or not.
 */
@OptIn(UnstableApi::class)
class TestPlayer(songs: List<TestSong>, playing: Boolean = false) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** A song of the queue: its metadata, its length and the format of its audio track. */
    data class TestSong(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = C.TIME_UNSET,
        val artwork: ByteArray? = null,
        val format: Format? = null,
    )

    sealed interface Call {
        data class PlayWhenReady(val playWhenReady: Boolean) : Call
        data class Seek(val mediaItemIndex: Int, val positionMs: Long, val command: Int) : Call
        data class Shuffle(val enabled: Boolean) : Call
        data class Repeat(val mode: Int) : Call
    }

    val calls = mutableListOf<Call>()

    var positionMs = 0L

    private var current: State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaybackState(if (songs.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaylist(songs.mapIndexed(::itemData))
        .setCurrentMediaItemIndex(0)
        .setContentPositionMs { positionMs }
        .build()

    override fun getState(): State = current

    /** Changes the state and tells the listeners, as a player would after a change of its own. */
    fun update(change: State.Builder.() -> Unit) {
        current = current.buildUpon().apply(change).build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        calls += Call.PlayWhenReady(playWhenReady)
        current = current.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        calls += Call.Seek(mediaItemIndex, positionMs, seekCommand)
        this.positionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
        current = current.buildUpon().setCurrentMediaItemIndex(mediaItemIndex).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        calls += Call.Shuffle(shuffleModeEnabled)
        current = current.buildUpon().setShuffleModeEnabled(shuffleModeEnabled).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        calls += Call.Repeat(repeatMode)
        current = current.buildUpon().setRepeatMode(repeatMode).build()
        return Futures.immediateVoidFuture()
    }

    private fun itemData(index: Int, song: TestSong): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .apply { song.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val tracks = song.format?.let { format ->
            Tracks(listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        } ?: Tracks.EMPTY
        return MediaItemData.Builder(/* uid= */ index)
            .setMediaItem(MediaItem.Builder().setMediaId("$index").setMediaMetadata(metadata).build())
            .setTracks(tracks)
            .setIsSeekable(true)
            .setDurationUs(if (song.durationMs == C.TIME_UNSET) C.TIME_UNSET else song.durationMs * 1_000)
            .build()
    }
}
