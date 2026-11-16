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
 * Gives the session the current library song's cover by the order of docs/library.md §13.1: its own or its album's, its
 * decided cover, the embedded picture, the system thumbnail or the lazy Cover Art Archive cover, and none without one
 * (§13.6, TI §7.1). [resolve] finds that picture for a file ID; [changes] says when own covers, decided values or online
 * lookups change. A look that fails leaves the picture shown. Runs on [scope], the player's thread.
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
                // A database that cannot be read now must not take playback down; the picture shown stays.
                Log.w(TAG, "No session cover for $mediaId", e)
                return@launch
            }
            player.setArtwork(mediaId, picture)
        }
    }

    companion object {
        /** An own, decided or embedded cover is sent at this step, a thumbnail or a Cover Art Archive cover at [THUMBNAIL_PX]. */
        private const val OWN_PX = 1_024
        private const val THUMBNAIL_PX = 512
        private const val TAG = "SessionCovers"

        /** The session's picture of the file [fileId] by the cover order, as JPEG bytes, or null without a cover. */
        suspend fun pictureOf(context: Context, fileId: Long): ByteArray? {
            val own = Covers.own(context)
            own.load()
            val file = Covers.facts(context).file(fileId) ?: return null
            // The embedded picture is a step of the order like the others (TI §7.1); only the steps after it are smaller.
            val chosen = file.identityId?.let(own::track) != null || file.albumKey?.let(own::album) != null ||
                file.decidedCover?.let { Covers.saved(context).file(it) } != null
            val px = if (chosen || file.embedded != false) OWN_PX else THUMBNAIL_PX
            val image = Covers.loader(context).load(CoverRequest.Track(fileId), px) ?: return null
            return withContext(Dispatchers.Default) { BitmapCodec.encode(image) }
        }
    }
}
