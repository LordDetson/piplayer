package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * Every request that reached the fakes it [wrap]s, with the time [now] gave when it arrived (virtual time in JVM tests,
 * the wall clock on a device), its User-Agent, its host and its body. One log can wrap several servers, so a test sees
 * the order of the requests across hosts (CAA and its image host, for example).
 */
class RecordingRequestLog(private val now: () -> Long = System::currentTimeMillis) {

    /**
     * One request. [toString] leaves out the value of the AcoustID `client` field, so an assertion message never
     * prints an application key.
     */
    data class Entry(
        val timeMs: Long,
        val method: String,
        val host: String,
        val path: String,
        val userAgent: String?,
        val body: String,
    ) {
        override fun toString(): String =
            "Entry(timeMs=$timeMs, $method $host$path, userAgent=$userAgent, body=${redact(body)})"
    }

    private val list = CopyOnWriteArrayList<Entry>()

    val entries: List<Entry> get() = list.toList()

    val size: Int get() = list.size

    /** The entries of requests to hosts whose `Host` header contains [hostPart]. */
    fun to(hostPart: String): List<Entry> = list.filter { it.host.contains(hostPart) }

    fun wrap(dispatcher: Dispatcher): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            list += Entry(
                timeMs = now(),
                method = request.method,
                host = request.headers["Host"] ?: "${request.url.host}:${request.url.port}",
                path = request.target,
                userAgent = request.headers["User-Agent"],
                body = request.bodyText(),
            )
            return dispatcher.dispatch(request)
        }

        override fun peek(): MockResponse = dispatcher.peek()

        override fun close() = dispatcher.close()
    }

    companion object {
        private val client = Regex("""(^|&)client=[^&]*""")

        fun redact(body: String): String = client.replace(body) { "${it.groupValues[1]}client=<redacted>" }
    }
}
