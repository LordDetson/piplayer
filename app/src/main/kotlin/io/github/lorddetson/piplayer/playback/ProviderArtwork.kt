package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers
import java.util.zip.CRC32

/** The tree's covers as URIs of the cover provider (docs/library.md §13.8); building one reads nothing. */
class ProviderArtwork(context: Context) : TreeArtwork {

    private val app = context.applicationContext

    override fun uriOf(subject: ArtworkSubject): Uri = Covers.uris(app).of(requestOf(subject)).toUri()

    override fun uriLength(subject: ArtworkSubject): Int = Covers.uris(app).lengthOf(requestOf(subject))

    companion object {
        fun requestOf(subject: ArtworkSubject): CoverRequest = when (subject) {
            is ArtworkSubject.Song -> CoverRequest.Track(subject.fileId)
            is ArtworkSubject.Album -> CoverRequest.Album(subject.key)
            is ArtworkSubject.Artist -> CoverRequest.Artist(subject.key)
        }

        /**
         * The cover URI of the queue item [mediaId] for the session (§13.8.6): a library song's, else none. Its version
         * also names the [picture] the session shows by a checksum, so that each picture has a URI of its own.
         */
        fun ofQueueItem(context: Context, mediaId: String, picture: ByteArray? = null): Uri? {
            val fileId = QueueIds.fileId(mediaId) ?: return null
            return Covers.uris(context).of(CoverRequest.Track(fileId), picture?.let(::checksum)).toUri()
        }

        private var lastPicture: ByteArray? = null
        private var lastChecksum = ""

        /** The CRC-32 of [picture] in base 36, worked out once for the same array: the metadata is asked for often. */
        @Synchronized
        private fun checksum(picture: ByteArray): String {
            if (picture !== lastPicture) {
                lastChecksum = CRC32().apply { update(picture) }.value.toString(36)
                lastPicture = picture
            }
            return lastChecksum
        }
    }
}
