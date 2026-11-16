package io.github.lorddetson.piplayer.library.details

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import kotlin.math.roundToInt
import androidx.core.net.toUri
import io.github.lorddetson.piplayer.covers.CoverStep
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.covers.EmbeddedPictures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** The cover of the details' header (docs/library.md §18.1, §18.3). */
object DetailsCover {

    /** The header's cover, in dp. */
    const val HEADER_DP = 96

    /**
     * The size the details probe the lazy Cover Art Archive step at, on a screen of [density]: the header's own size in
     * pixels, rounded as Compose rounds dp, so that the probe loads the header's step and image (§18.3).
     */
    fun caaProbePx(density: Float): Int = (HEADER_DP * density).roundToInt()

    /** How long the details wait for a Cover Art Archive load (docs/library.md §18.3). */
    const val CAA_WAIT_MS = 3_000L
}

/**
 * The details' probe of a lazy Cover Art Archive step (docs/library.md §18.3): [load] asks the loader for the step at the
 * header's size on a screen of [density], so that the probe shares the header's request and image. The details wait for
 * it at most [waitMs]; a load that takes longer goes on in [scope] and fills the caches, and once it finds a picture
 * [onLate] asks the details to read again, so that they find it.
 */
class DetailsCaaProbe(
    private val density: () -> Float,
    private val scope: CoroutineScope,
    private val waitMs: Long = DetailsCover.CAA_WAIT_MS,
    private val onLate: () -> Unit = {},
    private val load: suspend (step: CoverStep.Caa, px: Int) -> Boolean,
) {

    /** Whether [step] has a picture, as far as the details can tell within [waitMs]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun online(step: CoverStep.Caa): Boolean {
        val px = DetailsCover.caaProbePx(density())
        val loading = scope.async { load(step, px) }
        return withTimeoutOrNull(waitMs) { loading.await() } ?: run {
            // Given up: once the load that goes on finds a picture, the details read again and find it in the caches.
            loading.invokeOnCompletion { failure -> if (failure == null && loading.getCompleted()) onLate() }
            false
        }
    }
}

/**
 * The pictures of files on the device, for their details (docs/library.md §18.3): the embedded one as the player reads it,
 * of which only the header is decoded, and whether Android has a thumbnail.
 */
class AndroidPictureProbe(context: Context) : PictureProbe {

    private val app = context.applicationContext

    override suspend fun embedded(uri: String): PictureSize? = safely {
        val bytes = EmbeddedPictures.read(app, uri.toUri()) ?: return@safely null
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) PictureSize(bounds.outWidth, bounds.outHeight) else null
        }
    }

    override suspend fun hasThumbnail(uri: String): Boolean = safely {
        withContext(Dispatchers.IO) {
            app.contentResolver.loadThumbnail(uri.toUri(), Size(THUMBNAIL_PX, THUMBNAIL_PX), null).recycle()
            true
        }
    } ?: false

    /**
     * Whether the lazy Cover Art Archive [step] has a picture, loaded by the loader as the header's cover loads it: from its
     * caches, otherwise through the client with its gate, limiter and failure entries, sharing the header's request
     * (docs/library.md §18.3).
     */
    override suspend fun online(step: CoverStep.Caa): Boolean = caa.online(step)

    private val caa = DetailsCaaProbe({ app.resources.displayMetrics.density }, probes, onLate = { late.update { it + 1 } }) { step, px ->
        safely { Covers.loader(app).step(step, px) != null } ?: false
    }

    /** [block], or null when the file cannot be read now: details then show no such picture. */
    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "No picture could be read", e)
        null
    }

    companion object {
        private const val TAG = "AndroidPictureProbe"

        private val late = MutableStateFlow(0L)

        /**
         * Rises when a Cover Art Archive load of the details that outran their wait finds a picture, so that the details
         * read again (docs/library.md §18.3).
         */
        val lateLoads: StateFlow<Long> = late.asStateFlow()

        /** The smallest step of the loader (docs/library.md §13.3): enough to know that there is one. */
        private const val THUMBNAIL_PX = 96

        /** Cover Art Archive loads of the details, which outlive a read given up after [DetailsCover.CAA_WAIT_MS]. */
        private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
