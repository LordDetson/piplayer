package io.github.lorddetson.piplayer.net

import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** OkHttp clients for the metadata services. */
object HttpClients {

    /**
     * A client that sends [userAgent] on every request, whatever the caller set, and sends nothing while [gate] is
     * closed (TI §9). The client follows redirects itself, so the gate is asked before every hop is looked up or
     * connected (CAA to archive.org stays one call); a closed gate fails the call with [GateClosedException]. There is
     * no client without a gate.
     */
    fun create(userAgent: String, gate: OnlineGate): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(GateInterceptor(gate))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        // OkHttp's own repeats of a request on the same host (408, 421, a 503 with Retry-After 0) are asked here.
        .addNetworkInterceptor(HopGate(gate))
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}

/**
 * Asks [gate] before the request and before each redirect hop, which it follows itself, before anything about the
 * hop's host is looked up. Only GET and HEAD are redirected (301, 302, 303, 307, 308), never from HTTPS to HTTP, at
 * most [MAX_REDIRECTS] times; any other 3xx answer is handed back as it is.
 */
internal class GateInterceptor(val gate: OnlineGate) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var prior: Response? = null
        var redirects = 0
        while (true) {
            if (!gate.isOpen()) throw GateClosedException()
            val response = chain.proceed(request).let { answer ->
                if (prior == null) answer else answer.newBuilder().priorResponse(prior).build()
            }
            val next = redirectOf(response) ?: return response
            response.close()
            if (++redirects > MAX_REDIRECTS) throw ProtocolException("More than $MAX_REDIRECTS redirects")
            // Kept without its body, as OkHttp keeps the hops it follows itself (Response.priorResponse).
            prior = response.newBuilder().body("".toResponseBody(null)).networkResponse(null).cacheResponse(null).build()
            request = next
        }
    }

    private fun redirectOf(response: Response): Request? {
        val request = response.request
        if (response.code !in REDIRECTS || request.method != "GET" && request.method != "HEAD") return null
        val url = response.header("Location")?.let(request.url::resolve) ?: return null
        if (request.url.isHttps && !url.isHttps) return null
        return request.newBuilder().url(url)
            .apply { if (url.host != request.url.host) removeHeader("Authorization") }
            .build()
    }

    private companion object {
        const val MAX_REDIRECTS = 20
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * The same gate for the requests OkHttp repeats on its own on the same host (408, 421, a 503 with Retry-After 0),
 * below [GateInterceptor]. A refused repeat ends the call with [GateClosedException]: its request is not written, and
 * OkHttp opens no other connection or route for it, because it recovers only from a failure of the exchange itself,
 * which an interceptor's exception is not. HttpClientsTest pins this on a fresh, a reused, an HTTP/2 and a two-address
 * connection, so an OkHttp update that changes it fails there.
 */
internal class HopGate(val gate: OnlineGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!gate.isOpen()) throw GateClosedException()
        return chain.proceed(chain.request())
    }
}

/** Runs the call without blocking the coroutine's thread; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
