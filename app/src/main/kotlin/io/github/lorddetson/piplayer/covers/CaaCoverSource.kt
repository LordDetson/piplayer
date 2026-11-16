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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The front images of Cover Art Archive as the lazy step asks for them: the app's one client ([CaaFronts.cover]). */
fun interface CaaFronts {
    suspend fun cover(sources: List<CoverSource>, size: CoverSize): CaaResult<CoverImage>
}

/** Cover Art Archive is busy for this cover until [until]: not known now, so not remembered (docs/library.md §13.3). */
class CaaBusyException(val until: Instant) : IOException("Cover Art Archive is busy until $until")

/**
 * The lazy Cover Art Archive step of the cover order (docs/library.md §13.1 step 6, §13.3): the front of a release, else
 * of its release group, through [fronts], decoded by [decode] for the loader's step. Nothing is asked while [gate] is
 * closed, and [gateOpen] turning false cancels a load waiting or in flight, which then fails with
 * [GateClosedException]: not known now, so the loader remembers nothing of it.
 */
class CaaCoverSource<I : Any>(
    private val fronts: CaaFronts,
    private val gate: OnlineGate,
    private val gateOpen: Flow<Boolean>,
    private val decode: suspend (bytes: ByteArray, size: Int) -> I?,
) {

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
        return when (val result = whileOpen { fronts.cover(sources, sizeFor(size)) }) {
            is CaaResult.Found -> decode(result.value.bytes, size)
            CaaResult.NotFound -> null
            is CaaResult.Busy -> throw CaaBusyException(result.until)
        }
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
    }
}
