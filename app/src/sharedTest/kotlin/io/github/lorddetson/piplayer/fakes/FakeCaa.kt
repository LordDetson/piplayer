package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.ConcurrentHashMap
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl

/**
 * A fake Cover Art Archive (TI §5.2) with its image host: `/release/{mbid}/front[-250|-500|-1200]` and the same under
 * `/release-group/` answer 307 to [archive], served at [archiveUrl] like archive.org, which sends the JPEG. An MBID
 * not set with [withImages], [missingAfterRedirect] or [busy] gets CAA's 404.
 *
 * ```
 * val caa = FakeCaa { archiveServer.url("/") }
 * caaServer.dispatcher = caa
 * archiveServer.dispatcher = caa.archive
 * ```
 */
class FakeCaa(private val archiveUrl: () -> HttpUrl) : FixtureDispatcher() {

    private enum class Kind { IMAGES, MISSING_AFTER_REDIRECT, BUSY }

    private val entries = ConcurrentHashMap<String, Kind>()
    private val retryAfter = ConcurrentHashMap<String, Int>()

    /** The image host the redirects point to; serve it from a second server. */
    val archive: FixtureDispatcher = Archive()

    /** The release or release group [mbid] has a front image in every size. */
    fun withImages(mbid: String): FakeCaa = apply { entries[mbid] = Kind.IMAGES }

    /** Requests for [mbid] are redirected, but the image host then answers 404 (a thumbnail not made yet). */
    fun missingAfterRedirect(mbid: String): FakeCaa = apply { entries[mbid] = Kind.MISSING_AFTER_REDIRECT }

    /** Requests for [mbid] get 503 with a Retry-After of [retryAfterSeconds]. */
    fun busy(mbid: String, retryAfterSeconds: Int): FakeCaa = apply {
        entries[mbid] = Kind.BUSY
        retryAfter[mbid] = retryAfterSeconds
    }

    override fun route(request: RecordedRequest): MockResponse {
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        val entity = segments.getOrNull(0)
        val mbid = segments.getOrNull(1)
        val image = segments.getOrNull(2)?.removeSuffix(".jpg")
        val size = SIZES[image]
        if (request.method != "GET" || entity !in ENTITIES || mbid == null || size == null) return notFound()
        return when (entries[mbid]) {
            Kind.IMAGES, Kind.MISSING_AFTER_REDIRECT -> {
                val location = archiveUrl().newBuilder()
                    .addPathSegment("download")
                    .addPathSegment("mbid-$mbid")
                    .addPathSegment("mbid-$mbid-$IMAGE_ID$size.jpg")
                    .build()
                MockResponse.Builder().code(307).addHeader("Location", location).body("See: $location").build()
            }
            Kind.BUSY -> MockResponse.Builder().code(503)
                .addHeader("Retry-After", retryAfter.getValue(mbid))
                .body("Service Temporarily Unavailable")
                .build()
            null -> notFound()
        }
    }

    private inner class Archive : FixtureDispatcher() {
        override fun route(request: RecordedRequest): MockResponse {
            val segments = request.url.pathSegments
            val mbid = segments.getOrNull(1)?.removePrefix("mbid-")
            val file = segments.getOrNull(2)?.removeSuffix(".jpg")
            if (request.method != "GET" || segments.firstOrNull() != "download" || mbid == null || file == null) {
                return notFound()
            }
            if (entries[mbid] != Kind.IMAGES) return notFound()
            val image = when (file.removePrefix("mbid-$mbid-$IMAGE_ID")) {
                "_thumb250" -> FRONT_250
                "_thumb500" -> FRONT_500
                "_thumb1200" -> FRONT_1200
                "" -> ORIGINAL
                else -> return notFound()
            }
            return image.fixture.response()
        }
    }

    /** A JPEG fixture and its size in pixels. */
    data class Image(val fixture: Fixture, val width: Int, val height: Int)

    companion object {
        private const val IMAGE_ID = "1234567890"
        private val ENTITIES = setOf("release", "release-group")

        /** The CAA image name and the suffix archive.org gives that size. */
        private val SIZES = mapOf("front" to "", "front-250" to "_thumb250", "front-500" to "_thumb500", "front-1200" to "_thumb1200")

        val FRONT_250 = Image(Fixture("caa/front-250.jpg", contentType = "image/jpeg"), 250, 250)
        val FRONT_500 = Image(Fixture("caa/front-500.jpg", contentType = "image/jpeg"), 500, 500)
        val FRONT_1200 = Image(Fixture("caa/front-1200.jpg", contentType = "image/jpeg"), 1200, 1200)

        /** The uploaded original: not square, so its short side (1400) differs from its long side. */
        val ORIGINAL = Image(Fixture("caa/original.jpg", contentType = "image/jpeg"), 1500, 1400)

        private fun notFound() = MockResponse.Builder().code(404).setHeader("Content-Type", "text/plain").body("Not Found").build()
    }
}
