package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi

/**
 * A listener that passes everything on to [listener]; a subclass changes what it means to. Kotlin's `by` will not do:
 * every method of [Player.Listener] is a Java default method, which `by` leaves out, so the events would simply vanish.
 * A test checks that each of them is here.
 */
@OptIn(UnstableApi::class)
open class ForwardingListener(private val listener: Player.Listener) : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)

    @Deprecated("Deprecated in Media3")
    @Suppress("DEPRECATION")
    override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(availableCommands)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
        listener.onTrackSelectionParametersChanged(parameters)

    @Deprecated("Deprecated in Media3")
    @Suppress("DEPRECATION")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
        listener.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = listener.onPlaybackSuppressionReasonChanged(reason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)

    @Deprecated("Deprecated in Media3")
    @Suppress("DEPRECATION")
    override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        listener.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
        listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()

    @Deprecated("Deprecated in Media3")
    @Suppress("DEPRECATION")
    override fun onCues(cues: List<Cue>) = listener.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)
}
