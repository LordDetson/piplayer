package io.github.lorddetson.piplayer.library.values

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The cores that decode and resolve values in bulk: the display writer and the decision mirror's snapshot of every
 * decision (TI §10.3). All but one, which stays for the thread that writes the database, for playback and for the
 * interface. Both share one dispatcher, so that two such jobs at once still leave that core.
 */
internal object ResolvingCores {
    /** How many cores resolve at once. */
    val count: Int = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)

    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(count)
}
