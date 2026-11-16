package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.net.OnlineGate
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** The front images of Cover Art Archive as the lazy step asks for them: the app's one client ([CaaFronts.cover]). */
fun interface CaaFronts {
    suspend fun cover(sources: List<CoverSource>, size: CoverSize): CaaResult<CoverImage>
}

/** Cover Art Archive is busy for this cover until [until]: not known now, so not remembered (docs/library.md §13.3). */
class CaaBusyException(val until: Instant) : IOException("Cover Art Archive is busy until $until")

/**
 * The lazy Cover Art Archive step of the cover order (docs/library.md §13.1 step 6, §13.3): the front of a release, else
 * of its release group, through [fronts], decoded by [decode] for the loader's step. Nothing is asked while [gate] is
 * closed, and [gateOpen] turning false cancels a load waiting or in flight.
 */
class CaaCoverSource<I : Any>(
    private val fronts: CaaFronts,
    private val gate: OnlineGate,
    private val gateOpen: Flow<Boolean>,
    private val decode: suspend (bytes: ByteArray, size: Int) -> I?,
) {

    /** The picture of [step] for the loader's [size] step; null when Cover Art Archive has none. */
    suspend fun load(step: CoverStep.Caa, size: Int): I? = TODO("M3 step 19")

    companion object {
        /** The image a loader step asks for: `front-250` up to 256 px, `front-500` up to 512 px, `front-1200` above. */
        fun sizeFor(step: Int): CoverSize = TODO("M3 step 19")
    }
}
