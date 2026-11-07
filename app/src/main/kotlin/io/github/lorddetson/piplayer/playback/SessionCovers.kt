package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.covers.BitmapCodec
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gives the session the current song's own cover, or the system thumbnail of a file the tag pass found without a picture
 * (docs/library.md §13.6); otherwise the stream's picture stays. [resolve] finds that picture for a file ID; [changes]
 * says when own covers change. Runs on [scope], the player's thread.
 */
@OptIn(UnstableApi::class)
class SessionCovers(
    private val player: SessionPlayer,
    private val resolve: suspend (fileId: Long) -> ByteArray?,
    private val changes: Flow<*>,
    private val scope: CoroutineScope,
) {

    private var loading: Job? = null
    private var watching: Job? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
    }

    fun start() {
        player.addListener(listener)
        watching = scope.launch {
            changes.drop(1).collect {
                player.keepArtworkOf(player.currentMediaItem?.mediaId)
                refresh()
            }
        }
        refresh()
    }

    fun stop() {
        player.removeListener(listener)
        watching?.cancel()
        loading?.cancel()
    }

    private fun refresh() {
        loading?.cancel()
        val mediaId = player.currentMediaItem?.mediaId ?: return
        // An added entry plays a library song too (docs/playback.md §9.4); an opened file keeps the stream's picture.
        val fileId = QueueIds.fileId(mediaId) ?: return
        loading = scope.launch {
            val picture = try {
                resolve(fileId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A database that cannot be read now must not take playback down; the stream's picture stays.
                Log.w(TAG, "No session cover for $mediaId", e)
                null
            }
            player.setArtwork(mediaId, picture)
        }
    }

    companion object {
        /** An own cover is sent at this step, a thumbnail at [THUMBNAIL_PX]. */
        private const val OWN_PX = 1_024
        private const val THUMBNAIL_PX = 512
        private const val TAG = "SessionCovers"

        /** The picture that replaces the stream's for the file [fileId], as JPEG bytes, or null when the stream's stays. */
        suspend fun pictureOf(context: Context, fileId: Long): ByteArray? {
            val own = Covers.own(context)
            own.load()
            val file = Covers.facts(context).file(fileId) ?: return null
            val hasOwn = file.identityId?.let(own::track) != null || file.albumKey?.let(own::album) != null
            val px = when {
                hasOwn -> OWN_PX
                file.embedded == false -> THUMBNAIL_PX
                else -> return null
            }
            val image = Covers.loader(context).load(CoverRequest.Track(fileId), px) ?: return null
            return withContext(Dispatchers.Default) { BitmapCodec.encode(image) }
        }
    }
}
