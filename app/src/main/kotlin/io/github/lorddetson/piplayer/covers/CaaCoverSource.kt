package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.caa.CoverUrls
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.OnlineGate
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The front images of Cover Art Archive as the lazy step asks for them: the app's one client ([CaaFronts.cover]). */
fun interface CaaFronts {
    /**
     * The [size] front image of the first of [sources] that has one; [orOriginal] is whether a thumbnail the image host
     * does not have is replaced by the original (docs/library.md §13.3).
     */
    suspend fun cover(sources: List<CoverSource>, size: CoverSize, orOriginal: Boolean): CaaResult<CoverImage>
}

/** Cover Art Archive is busy for this cover until [until]: not known now, so not remembered (docs/library.md §13.3). */
class CaaBusyException(val until: Instant) : IOException("Cover Art Archive is busy until $until")

/**
 * The lazy Cover Art Archive step of the cover order (docs/library.md §13.1 step 6, §13.3): the front of a release, else
 * of its release group, through [fronts], decoded by [decode] for the loader's step. Nothing is asked while [gate] is
 * closed, and [gateOpen] turning false ends every load waiting or in flight at once, which then fails with
 * [GateClosedException]: not known now, so the loader remembers nothing of it. Loads of the same image at the same time
 * share one request, which is cancelled when no load waits for it any more.
 */
class CaaCoverSource<I : Any>(
    private val fronts: CaaFronts,
    private val gate: OnlineGate,
    private val gateOpen: Flow<Boolean>,
    private val decode: suspend (bytes: ByteArray, size: Int) -> I?,
) {

    /** A request in flight and how many loads wait for it; changed under the lock of [inFlight]. */
    private class Shared(val work: Deferred<CaaResult<CoverImage>>) {
        var waiting = 0
    }

    /** The requests in flight by the image they ask for. */
    private val inFlight = HashMap<String, Shared>()

    /**
     * The images the latest requests found, by the image they asked for: a load that comes just after its request ended,
     * before the loads that waited for it have written the disk cache, takes it from here instead of asking again.
     */
    private val recent = MemoryLru<String, CoverImage>(RECENT_BYTES) { it.bytes.size.toLong() }

    /**
     * The picture of [step] for the loader's [size] step; null when Cover Art Archive has none. Throws when it cannot
     * tell now: the gate closed ([GateClosedException]), Cover Art Archive busy ([CaaBusyException]), or the client's
     * own failures.
     */
    suspend fun load(step: CoverStep.Caa, size: Int): I? {
        if (!gate.isOpen()) throw GateClosedException()
        // The flags are not known (TI §5.2): the release first, then its release group.
        val sources = CoverUrls.sources(step.release.release, step.release.releaseGroup, flags = null)
        if (sources.isEmpty()) return null
        val image = sizeFor(size)
        // A row or a header never loads an original, which can be tens of MB (docs/library.md §13.3).
        val orOriginal = image == CoverSize.EMBED
        val result = whileOpen { shared("${step.key}/$image") { fronts.cover(sources, image, orOriginal) } }
        return when (result) {
            is CaaResult.Found -> decode(result.value.bytes, size)
            CaaResult.NotFound -> null
            is CaaResult.Busy -> throw CaaBusyException(result.until)
        }
    }

    /**
     * The answer of the request [key], joining the one in flight or starting it with [fetch]. The request runs apart from
     * any one load, in the caller's dispatcher: a load that is cancelled stops waiting at once, and the request is
     * cancelled when it was the last to wait.
     */
    private suspend fun shared(key: String, fetch: suspend () -> CaaResult<CoverImage>): CaaResult<CoverImage> {
        val context = currentCoroutineContext().minusKey(Job)
        val shared = synchronized(inFlight) {
            recent.get(key)?.let { return CaaResult.Found(it) }
            val current = inFlight[key]?.takeUnless { it.work.isCompleted }
            (current ?: Shared(CoroutineScope(context + SupervisorJob()).async { fetch().also { found(key, it) } }).also { entry ->
                inFlight[key] = entry
                // An ended request leaves the map, so a later load finds the caches or asks again.
                entry.work.invokeOnCompletion { synchronized(inFlight) { if (inFlight[key] === entry) inFlight.remove(key) } }
            }).also { it.waiting++ }
        }
        try {
            return shared.work.await()
        } finally {
            synchronized(inFlight) {
                shared.waiting--
                if (shared.waiting == 0 && !shared.work.isCompleted) {
                    if (inFlight[key] === shared) inFlight.remove(key)
                    shared.work.cancel(CancellationException("No cover waits for this request"))
                }
            }
        }
    }

    /** Keeps what the request [key] found in [recent], before the request ends. */
    private fun found(key: String, result: CaaResult<CoverImage>) {
        if (result is CaaResult.Found) recent.put(key, result.value)
    }

    /** [block], cancelled at once when the gate closes, which then throws [GateClosedException]. */
    private suspend fun <T> whileOpen(block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        val closed = AtomicBoolean(false)
        val watcher = launch {
            gateOpen.first { !it }
            closed.set(true)
            work.cancel(CancellationException("Online lookups were turned off"))
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // The caller's own cancellation goes on as it is.
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw GateClosedException()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        /** The image a loader step asks for: `front-250` up to 256 px, `front-500` up to 512 px, `front-1200` above. */
        fun sizeFor(step: Int): CoverSize = when {
            step <= 256 -> CoverSize.LIST
            step <= 512 -> CoverSize.DISPLAY
            else -> CoverSize.EMBED
        }

        /** What [recent] holds at most: a few `front-1200` images, or many smaller ones. */
        private const val RECENT_BYTES = 4L * 1024 * 1024
    }
}
