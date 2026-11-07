package io.github.lorddetson.piplayer.covers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Told of what failed, for the log. */
    private val failures: (Exception) -> Unit = {},
) {

    /** A request's cover in memory: its picture, or null when no source had one. */
    private class Slot<I : Any>(val image: I?)

    private val memory = MemoryLru<String, Slot<I>>(memoryWeight) { slot -> slot.image?.let(codec::weight) ?: EMPTY_WEIGHT }

    /** At most [PARALLEL_LOADS] loads at a time, also while one waits on a thread of its own (a binder call, a read). */
    private val permits = Semaphore(PARALLEL_LOADS)

    /**
     * Raised by [invalidate]; a load that began before a change does not keep its result in memory. Compared and put, and
     * raised and cleared, under one lock, so that no old result lands between a raise and its clearing.
     */
    @Volatile
    var generation = 0L
        private set
    private val memoryLock = Any()

    private fun memoryKey(request: CoverRequest, step: Int) = "$request@$step"

    /** The cover of [request] at [px] if memory holds it; never blocks. */
    fun peek(request: CoverRequest, px: Int): I? = memory.get(memoryKey(request, CoverSizes.step(px)))?.image

    /** The cover of [request] for a view [px] wide, or null when no source has one. */
    suspend fun load(request: CoverRequest, px: Int): I? {
        val step = CoverSizes.step(px)
        val cached = CoverSizes.cached(step)
        val key = memoryKey(request, step)
        if (cached) memory.get(key)?.let { return it.image }
        val started = synchronized(memoryLock) { generation }
        return permits.withPermit { loadNow(request, step, cached, key, started) }
    }

    private suspend fun loadNow(request: CoverRequest, step: Int, cached: Boolean, key: String, started: Long): I? =
        withContext(dispatcher) {
            val steps = try {
                plan(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The library cannot be read now (a rebuild, a lost permission): no cover, and nothing remembered.
                failures(e)
                return@withContext null
            }
            var image: I? = null
            var known = steps.isNotEmpty()
            for (source in steps) {
                val result = try {
                    picture(source, step, cached)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not known now, so neither remembered as none nor as anything else.
                    failures(e)
                    known = false
                    continue
                }
                if (result != null) {
                    image = result
                    known = true
                    break
                }
            }
            ensureActive()
            if (cached && known) synchronized(memoryLock) { if (generation == started) memory.put(key, Slot(image)) }
            image
        }

    /** The picture of [source] at [step], from the disk cache when it holds it. */
    private suspend fun picture(source: CoverStep, step: Int, cached: Boolean): I? {
        val diskKey = "${source.key}@$step"
        if (cached && disk != null) {
            when (val hit = disk.read(diskKey)) {
                Cached.Nothing -> return null
                is Cached.Picture -> codec.decode(hit.bytes, step)?.let { return it }
                null -> Unit
            }
        }
        val image = sources.load(source, step)
        // A load cancelled meanwhile stores nothing.
        coroutineContext.ensureActive()
        if (cached && disk != null) {
            // Only an embedded picture's absence is remembered on disk: it holds while the file stays the same. A missing own
            // copy costs a look at the file, and a missing thumbnail may come with an image put into the folder later, which
            // changes nothing of the file (memory still remembers it until the library changes).
            remember(source, diskKey, image, image?.let(codec::encode))
        }
        return image
    }

    /**
     * The cover of [request] for a controller at [px] as the codec's bytes (docs/library.md §13.8.4), or null when no
     * source has one or the library cannot be read now. A disk hit is served as stored, without decoding; memory is not
     * asked, since encoding a decoded picture again costs more than reading the file.
     */
    suspend fun encoded(request: CoverRequest, px: Int): ByteArray? {
        val step = CoverSizes.step(px)
        val cached = CoverSizes.cached(step)
        return permits.withPermit {
            withContext(dispatcher) {
                val steps = try {
                    plan(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures(e)
                    return@withContext null
                }
                for (source in steps) {
                    val bytes = try {
                        bytesOf(source, step, cached)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures(e)
                        continue
                    }
                    if (bytes != null) return@withContext bytes
                }
                null
            }
        }
    }

    /** The bytes of [source] at [step]: from the disk cache as stored, or loaded, encoded and stored. */
    private suspend fun bytesOf(source: CoverStep, step: Int, cached: Boolean): ByteArray? {
        val diskKey = "${source.key}@$step"
        if (cached && disk != null) {
            when (val hit = disk.read(diskKey)) {
                Cached.Nothing -> return null
                is Cached.Picture -> return hit.bytes
                null -> Unit
            }
        }
        val image = sources.load(source, step)
        coroutineContext.ensureActive()
        val bytes = image?.let(codec::encode)
        if (cached && disk != null) remember(source, diskKey, image, bytes)
        return bytes
    }

    /** Stores what [source] gave on disk: a picture's [bytes], or the absence of an embedded picture (see [picture]). */
    private fun remember(source: CoverStep, diskKey: String, image: I?, bytes: ByteArray?) {
        val store = disk ?: return
        if (bytes != null) store.write(diskKey, bytes) else if (image == null && source is CoverStep.Embedded) store.write(diskKey, null)
    }

    /** Own covers or the library changed: what memory holds may be stale. */
    fun invalidate() {
        synchronized(memoryLock) {
            generation++
            memory.clear()
        }
    }

    /** Forgets everything, on disk too; tests start so. */
    fun clear() {
        invalidate()
        disk?.clear()
    }

    companion object {
        const val PARALLEL_LOADS = 4

        /** What remembering that a request has no cover weighs. */
        private const val EMPTY_WEIGHT = 64L
    }
}
