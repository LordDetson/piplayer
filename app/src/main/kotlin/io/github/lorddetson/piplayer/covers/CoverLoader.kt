package io.github.lorddetson.piplayer.covers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Where the pictures of [CoverStep]s come from. */
fun interface CoverSources<I : Any> {
    /**
     * The picture of [step], decoded for [size] (a step of [CoverSizes]); null when the source has none. Throws when it
     * cannot tell, such as when the file cannot be opened now; that is not remembered.
     */
    suspend fun load(step: CoverStep, size: Int): I?
}

/** How pictures go to and come from the disk cache, and what they weigh in memory. */
interface ImageCodec<I : Any> {
    fun encode(image: I): ByteArray?

    fun decode(bytes: ByteArray, size: Int): I?

    fun weight(image: I): Long
}

/**
 * Loads covers (docs/library.md §13.3): the first source of [plan] with a picture wins. Pictures of cached steps are kept
 * in memory, up to [memoryWeight], and on [disk]; both remember a source without a picture.
 */
class CoverLoader<I : Any>(
    private val plan: suspend (CoverRequest) -> List<CoverStep>,
    private val sources: CoverSources<I>,
    private val codec: ImageCodec<I>,
    private val disk: CoverDiskCache?,
    memoryWeight: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(PARALLEL_LOADS),
) {

    /** The cover of [request] at [px] if memory holds it; never blocks. */
    fun peek(request: CoverRequest, px: Int): I? = TODO()

    /** The cover of [request] for a view [px] wide, or null when no source has one. */
    suspend fun load(request: CoverRequest, px: Int): I? = TODO()

    /** Own covers changed: what memory holds may be stale. */
    fun invalidate(): Unit = TODO()

    companion object {
        const val PARALLEL_LOADS = 4
    }
}
