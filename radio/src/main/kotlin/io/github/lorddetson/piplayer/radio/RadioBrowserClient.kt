package io.github.lorddetson.piplayer.radio

import io.github.lorddetson.piplayer.net.CallGuard
import io.github.lorddetson.piplayer.net.CallOutcome
import io.github.lorddetson.piplayer.net.GateClosedException
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.RetryingCaller
import io.github.lorddetson.piplayer.net.UserAgent
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

internal data class RadioStationQuery(
    val name: String = "",
    val countryCode: String = "",
    val language: String = "",
    val tag: String = "",
    val offset: Int = 0,
    val limit: Int = 50,
)

internal data class DirectoryRadioStation(
    val id: UUID,
    val name: String,
    val streamUrl: HttpUrl,
    val countryCode: String,
    val languages: List<String>,
    val tags: List<String>,
)

internal data class RadioStationPage(val stations: List<DirectoryRadioStation>, val nextOffset: Int?)

internal sealed class RadioBrowserException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Http(val code: Int) : RadioBrowserException("Radio Browser answered HTTP $code")
    class InvalidResponse(cause: Throwable? = null) : RadioBrowserException("Radio Browser returned an invalid station list", cause)
    class Unavailable(cause: Throwable? = null) : RadioBrowserException("Radio Browser is unavailable", cause)
}

/** Station search contacts directory mirrors. Returned station URLs are only read as data here. */
internal class RadioBrowserClient(
    http: OkHttpClient,
    private val gate: OnlineGate,
    private val findMirrors: suspend () -> List<String>,
) {
    constructor(gate: OnlineGate) : this(HttpClients.create(UserAgent.forBuild(), gate), gate, { discoverMirrors(gate) })

    private val caller = RetryingCaller(
        http = http,
        limiter = RateLimiter(Duration.ZERO),
        retryPolicy = RetryPolicy(maxRetries = 0),
        ioDispatcher = Dispatchers.IO,
        guard = CallGuard { gate.checkBeforeSend() },
    )

    suspend fun search(query: RadioStationQuery): RadioStationPage {
        val form = searchForm(query)
        gate.checkBeforeSend()
        val mirrors = try {
            findMirrors().mapNotNull(::mirrorHost).distinct().take(MAX_MIRROR_ATTEMPTS)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            gate.checkBeforeSend()
            throw RadioBrowserException.Unavailable(e)
        }
        var failure: RadioBrowserException = RadioBrowserException.Unavailable()
        for (mirror in mirrors) {
            val url = HttpUrl.Builder().scheme("https").host(mirror).addPathSegments("json/stations/search").build()
            // The directory accepts form POST. PiPlayer's HTTP client follows redirects only for GET and HEAD.
            val request = Request.Builder().url(url).header("Accept", "application/json").post(form).build()
            try {
                return caller.execute(
                    request = request,
                    giveUp = { _, cause -> cause as? RadioBrowserException ?: RadioBrowserException.Unavailable(cause) },
                    paused = { RadioBrowserException.Unavailable() },
                ) { response ->
                    if (response.isSuccessful) {
                        CallOutcome.Success(readPage(response.body, query))
                    } else {
                        CallOutcome.Fail(RadioBrowserException.Http(response.code))
                    }
                }
            } catch (e: RadioBrowserException) {
                if (e is RadioBrowserException.Http && e.code !in 500..599) throw e
                failure = e
            }
        }
        throw failure
    }

    private fun searchForm(query: RadioStationQuery): FormBody {
        require(query.limit in 1..MAX_PAGE_SIZE) { "Station page size is out of range" }
        require(query.offset in 0..Int.MAX_VALUE - query.limit) { "Station page offset is out of range" }
        val countryCode = query.countryCode.trim().uppercase(Locale.ROOT)
        require(countryCode.isEmpty() || COUNTRY_CODE.matches(countryCode)) { "Expected a two-letter country code" }
        return FormBody.Builder()
            .add("hidebroken", "true")
            .add("offset", query.offset.toString())
            .add("limit", query.limit.toString())
            .apply {
                val name = query.name.trim()
                val language = query.language.trim()
                val tag = query.tag.trim()
                if (name.isNotEmpty()) add("name", name)
                if (countryCode.isNotEmpty()) add("countrycode", countryCode)
                if (language.isNotEmpty()) add("language", language)
                if (tag.isNotEmpty()) add("tag", tag)
            }
            .build()
    }

    private fun readPage(body: ResponseBody, query: RadioStationQuery): RadioStationPage {
        if (body.contentLength() > MAX_PAGE_BYTES) throw RadioBrowserException.InvalidResponse()
        val source = body.source()
        if (source.request(MAX_PAGE_BYTES + 1)) throw RadioBrowserException.InvalidResponse()
        val rows = try {
            json.parseToJsonElement(source.buffer.readUtf8()) as? JsonArray
        } catch (e: SerializationException) {
            throw RadioBrowserException.InvalidResponse(e)
        } ?: throw RadioBrowserException.InvalidResponse()

        val stations = rows.asSequence().take(query.limit).mapNotNull { row ->
            try {
                json.decodeFromJsonElement(StationDto.serializer(), row).toStation()
            } catch (_: SerializationException) {
                null
            }
        }.distinctBy(DirectoryRadioStation::id).toList()
        val nextOffset = (query.offset + query.limit).takeIf { rows.size >= query.limit }
        return RadioStationPage(stations, nextOffset)
    }

    @Serializable
    private data class StationDto(
        @SerialName("stationuuid") val uuid: String,
        val name: String,
        val url: String = "",
        @SerialName("url_resolved") val resolvedUrl: String = "",
        @SerialName("countrycode") val countryCode: String = "",
        val language: String = "",
        val tags: String = "",
        val hls: Int = 0,
        @SerialName("lastcheckok") val lastCheckOk: Int = 1,
    ) {
        fun toStation(): DirectoryRadioStation? {
            if (lastCheckOk != 1 || hls != 0) return null
            val id = try {
                UUID.fromString(uuid)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (!id.toString().equals(uuid, ignoreCase = true)) return null
            val title = name.trim()
            if (title.isEmpty()) return null
            val stream = parseRadioStreamUrl(resolvedUrl) ?: parseRadioStreamUrl(url) ?: return null
            val country = countryCode.trim().uppercase(Locale.ROOT).takeIf(COUNTRY_CODE::matches).orEmpty()
            return DirectoryRadioStation(id, title, stream, country, language.readList(), tags.readList())
        }
    }

    private companion object {
        const val DIRECTORY_HOST = "all.api.radio-browser.info"
        const val MAX_MIRROR_ATTEMPTS = 3
        const val MAX_PAGE_SIZE = 100
        const val MAX_PAGE_BYTES = 512L * 1024
        val COUNTRY_CODE = Regex("[A-Z]{2}")
        val MIRROR_HOST = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.api\\.radio-browser\\.info")
        val json = Json { ignoreUnknownKeys = true }

        fun OnlineGate.checkBeforeSend() {
            if (!isOpen()) throw GateClosedException()
        }

        suspend fun discoverMirrors(gate: OnlineGate): List<String> = withContext(Dispatchers.IO) {
            gate.checkBeforeSend()
            // Looking up the public list and then its PTR names keeps the TLS host names current.
            Dns.SYSTEM.lookup(DIRECTORY_HOST).map { address ->
                ensureActive()
                gate.checkBeforeSend()
                address.canonicalHostName
            }.shuffled()
        }

        fun mirrorHost(name: String): String? = name.lowercase(Locale.ROOT).removeSuffix(".")
            .takeIf { it != DIRECTORY_HOST && MIRROR_HOST.matches(it) }

        fun String.readList(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
