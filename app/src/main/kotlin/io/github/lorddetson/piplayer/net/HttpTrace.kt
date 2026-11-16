package io.github.lorddetson.piplayer.net

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What the HTTP layer saw of the calls made in a coroutine that carries this element (TI §10.1, the identification
 * trace): [RetryingCaller] reports every attempt it sent, with the status of its answer, or none when the attempt got no
 * answer (a dropped connection, a failed lookup of the host). A call that the gate, a provider's stop or the network
 * check refused sent nothing and is not counted. Thread-safe: an answer may be read on any thread.
 */
class HttpTrace : AbstractCoroutineContextElement(HttpTrace) {

    companion object Key : CoroutineContext.Key<HttpTrace>

    private val lock = Any()
    private var requests = 0
    private var lastStatus: Int? = null
    private var elapsedMs = 0L

    /** An attempt answered with [status], [elapsedMs] after it was sent, as the HTTP client measured it. */
    fun answered(status: Int, elapsedMs: Long) = synchronized(lock) {
        requests++
        lastStatus = status
        this.elapsedMs += elapsedMs.coerceAtLeast(0)
    }

    /** An attempt that got no answer. */
    fun unanswered() = synchronized(lock) {
        requests++
        lastStatus = null
    }

    /** What was seen so far; null when no attempt was sent. */
    fun summary(): HttpSummary? = synchronized(lock) {
        if (requests == 0) null else HttpSummary(requests, lastStatus, elapsedMs)
    }
}

/**
 * The attempts of one stage at the HTTP layer: [requests] sent, the [lastStatus] of the last one (null when it got no
 * answer) and [elapsedMs], the time from sending to the answer added up over the answered ones.
 */
data class HttpSummary(val requests: Int, val lastStatus: Int?, val elapsedMs: Long)
