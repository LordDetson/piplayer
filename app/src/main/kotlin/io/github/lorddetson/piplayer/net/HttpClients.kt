package io.github.lorddetson.piplayer.net

import java.io.IOException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** OkHttp clients for the metadata services. */
object HttpClients {

    /**
     * A client that sends [userAgent] on every request, whatever the caller set, and sends nothing while [gate] is
     * closed (TI §9). The gate is asked before every request and every redirect hop, which the client follows itself
     * (CAA to archive.org stays one call), before every host lookup through [dns], before every new connection and
     * before a request is written on a connection OkHttp already holds; a closed gate fails the call with
     * [GateClosedException]. So OkHttp's own repeats on the same host (408, 421, a 503 with Retry-After 0, a pooled
     * connection that dropped) look up, connect and send nothing while the gate is closed. The one gap is a SOCKS
     * proxy, whose sockets OkHttp makes itself; Android's system proxy is an HTTP proxy, which is gated. There is no
     * client without a gate.
     */
    fun create(userAgent: String, gate: OnlineGate, dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(GateInterceptor(gate))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        // Asked before a request is written on any connection, a pooled one included.
        .addNetworkInterceptor(HopGate(gate))
        .dns(GatedDns(gate, dns))
        .socketFactory(GatedSocketFactory(gate))
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
 * The same gate before a request is written, below [GateInterceptor], for the requests OkHttp repeats on its own on
 * the same host (408, 421, a 503 with Retry-After 0) over a connection it already holds. A repeat that needs a new
 * connection is refused earlier, by [GatedDns] or [GatedSocketFactory]. A refusal here ends the call with
 * [GateClosedException]: OkHttp recovers only from a failure of the exchange itself, which an interceptor's exception
 * is not. HttpClientsTest pins this on a fresh, a reused, an HTTP/2, a closed and a two-address connection, so an
 * OkHttp update that changes it fails there.
 */
internal class HopGate(val gate: OnlineGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!gate.isOpen()) throw GateClosedException()
        return chain.proceed(chain.request())
    }
}

/** Looks a host up through [delegate] only while [gate] is open; a closed gate fails the lookup and so the call. */
internal class GatedDns(val gate: OnlineGate, private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!gate.isOpen()) throw GateClosedException()
        return delegate.lookup(hostname)
    }
}

/**
 * Makes sockets only while [gate] is open. OkHttp makes a socket just before it connects it, so no new connection is
 * opened, on any route of any host, while the gate is closed.
 */
internal class GatedSocketFactory(
    val gate: OnlineGate,
    private val delegate: SocketFactory = getDefault(),
) : SocketFactory() {

    private inline fun ifOpen(make: () -> Socket): Socket {
        if (!gate.isOpen()) throw GateClosedException()
        return make()
    }

    override fun createSocket(): Socket = ifOpen { delegate.createSocket() }

    override fun createSocket(host: String?, port: Int): Socket = ifOpen { delegate.createSocket(host, port) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        ifOpen { delegate.createSocket(host, port, localHost, localPort) }

    override fun createSocket(host: InetAddress?, port: Int): Socket = ifOpen { delegate.createSocket(host, port) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        ifOpen { delegate.createSocket(address, port, localAddress, localPort) }
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
