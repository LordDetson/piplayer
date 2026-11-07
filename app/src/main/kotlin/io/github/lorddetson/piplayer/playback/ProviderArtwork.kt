package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers

/** The tree's covers as URIs of the cover provider (docs/library.md §13.8); building one reads nothing. */
class ProviderArtwork(context: Context) : TreeArtwork {

    private val app = context.applicationContext

    override fun uriOf(subject: ArtworkSubject): Uri = Covers.uris(app).of(requestOf(subject)).toUri()

    companion object {
        fun requestOf(subject: ArtworkSubject): CoverRequest = when (subject) {
            is ArtworkSubject.Song -> CoverRequest.Track(subject.fileId)
            is ArtworkSubject.Album -> CoverRequest.Album(subject.key)
            is ArtworkSubject.Artist -> CoverRequest.Artist(subject.key)
        }

        /** The cover URI of the queue item [mediaId] for the session (§13.8.6): a library song's, else none. */
        fun ofQueueItem(context: Context, mediaId: String): Uri? =
            QueueIds.fileId(mediaId)?.let { Covers.uris(context).of(CoverRequest.Track(it)).toUri() }
    }
}
