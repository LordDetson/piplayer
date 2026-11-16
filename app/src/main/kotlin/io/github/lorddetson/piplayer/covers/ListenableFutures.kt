package io.github.lorddetson.piplayer.covers

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The value of a future of Media3's metadata retriever, suspending until it is there: its failure is thrown as its own
 * cause, and cancelling the caller cancels the future.
 */
internal suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: java.util.concurrent.CancellationException) {
                continuation.cancel(e)
            }
        }, Runnable::run)
        continuation.invokeOnCancellation { cancel(true) }
    }
