package io.github.lorddetson.piplayer.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** OkHttp clients for the metadata services. */
object HttpClients {

    /**
     * A client that sends [userAgent] on every request, whatever the caller set, and sends nothing while [gate] is
     * closed (TI §9): the gate is asked before the call opens a connection and again before the request of each
     * redirect hop is written (the hop's connection may already be open), and a closed gate fails the call with
     * [GateClosedException]. There is no client without a gate.
     */
    fun create(userAgent: String, gate: OnlineGate): OkHttpClient {
        val gateCheck = GateInterceptor(gate)
        return OkHttpClient.Builder()
            .addInterceptor(gateCheck)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            // Network interceptors run once per hop that goes out, redirects included; a refused hop ends the call
            // without writing its request, also on a reused connection (HttpClientsTest).
            .addNetworkInterceptor(gateCheck)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** Fails a request with [GateClosedException] before it is written while [gate] is closed. */
internal class GateInterceptor(val gate: OnlineGate) : Interceptor {
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
