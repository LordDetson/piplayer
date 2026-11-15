package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/**
 * A recorded or hand-made answer of an online service, kept under `src/sharedTest/resources` so that the JVM and the
 * instrumented tests serve the same bodies. Bodies contain no CC BY-NC-SA tags or genres (TI §11.1).
 */
data class Fixture(
    val path: String,
    val code: Int = 200,
    val contentType: String = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
) {
    fun bytes(): ByteArray = Fixtures.bytes(path)

    fun text(): String = Fixtures.text(path)

    fun response(): MockResponse = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", contentType)
        .apply { headers.forEach { (name, value) -> setHeader(name, value) } }
        .body(Buffer().write(bytes()))
        .build()
}

object Fixtures {

    /** Reads `src/sharedTest/resources/[path]` from the test's class path (on a device: the test APK). */
    fun bytes(path: String): ByteArray {
        val stream = Fixtures::class.java.getResourceAsStream("/$path") ?: error("No fixture at $path")
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
}

/**
 * A fake service: answers queued with [enqueue] come first, in order (busy answers, broken pages, dropped
 * connections); every other request is answered by [route] from fixtures. A request nothing matches gets the
 * service's own 404. MockWebServer takes a response's `onRequestStart` effect from [peek], before the request is read,
 * so only queued answers can drop a connection at that point; routed ones use `onResponseStart`.
 */
abstract class FixtureDispatcher : Dispatcher() {

    private val queued = ConcurrentLinkedQueue<MockResponse>()

    fun enqueue(vararg responses: MockResponse) {
        queued += responses
    }

    final override fun dispatch(request: RecordedRequest): MockResponse = queued.poll() ?: route(request)

    override fun peek(): MockResponse = queued.peek() ?: super.peek()

    protected abstract fun route(request: RecordedRequest): MockResponse
}

/** The body of [this] request as text, after undoing a gzip Content-Encoding. */
fun RecordedRequest.bodyText(): String {
    val bytes = body?.toByteArray() ?: return ""
    return if (headers["Content-Encoding"].equals("gzip", ignoreCase = true)) {
        GZIPInputStream(bytes.inputStream()).bufferedReader().readText()
    } else {
        bytes.toString(Charsets.UTF_8)
    }
}

/** The fields of a form-encoded body (the form encoding is the query encoding, so HttpUrl decodes it). */
fun RecordedRequest.formFields(): Map<String, String> {
    val parsed = "http://localhost/?${bodyText()}".toHttpUrl()
    return parsed.queryParameterNames.associateWith { parsed.queryParameter(it).orEmpty() }
}
