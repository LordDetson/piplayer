package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.ConcurrentHashMap
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl

/**
 * A fake Cover Art Archive (TI §5.2) with its image host: `/release/{mbid}/front[-250|-500|-1200]` and the same under
 * `/release-group/` answer 307 to [archive], served at [archiveUrl] like archive.org, which sends the JPEG. The
 * release index `/release/{mbid}` of an MBID set with [withIndex] answers 307 to the image host's `index.json`, and the
 * images its links name (`/release/{mbid}/{id}[-250|-500|-1200].{jpg|png}`) answer 307 to the image host, which sends
 * the JPEG of that size (any image ID). An MBID not set with [withImages], [withoutThumbnails], [missingAfterRedirect],
 * [withIndex] or [busy] gets CAA's 404.
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
    private val missingThumbnails = ConcurrentHashMap<String, Set<String>>()
    private val indexes = ConcurrentHashMap<String, Fixture>()

    /** The image host the redirects point to; serve it from a second server. */
    val archive: FixtureDispatcher = Archive()

    /** The release or release group [mbid] has a front image in every size. */
    fun withImages(mbid: String): FakeCaa = apply { entries[mbid] = Kind.IMAGES }

    /**
     * [mbid] has a front image, but the image host answers 404 for the thumbnails named in [images] ("front-1200" for
     * an older upload without a 1200 px thumbnail); the original and the other thumbnails are served.
     */
    fun withoutThumbnails(mbid: String, vararg images: String): FakeCaa = apply {
        require(images.all { it in SIZES && it != "front" }) { "Only thumbnails can be missing: ${images.toList()}" }
        entries[mbid] = Kind.IMAGES
        missingThumbnails[mbid] = images.mapTo(HashSet()) { SIZES.getValue(it) }
    }

    /** The release index of [mbid] (the JSON of `/release/{mbid}`) is [fixture], served by the image host. */
    fun withIndex(mbid: String, fixture: Fixture = RELEASE_INDEX): FakeCaa = apply { indexes[mbid] = fixture }

    /**
     * Requests for [mbid] are redirected, but the image host then answers 404 for every image, the original included
     * (a thumbnail not made yet, or images taken down after CAA listed them).
     */
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
        if (request.method == "GET" && entity == "release" && mbid != null && segments.size == 2) return index(mbid)
        if (request.method == "GET" && entity == "release" && mbid != null && segments.size == 3 && indexes.containsKey(mbid)) {
            INDEX_IMAGE.matchEntire(segments[2])?.let { match ->
                val (id, px) = match.destructured
                val suffix = if (px.isEmpty()) "" else "_thumb$px"
                return redirect(
                    archiveUrl().newBuilder()
                        .addPathSegment("download")
                        .addPathSegment("mbid-$mbid")
                        .addPathSegment("mbid-$mbid-$id$suffix.jpg")
                        .build(),
                )
            }
        }
        if (request.method != "GET" || entity !in ENTITIES || mbid == null || size == null) return notFound()
        return when (entries[mbid]) {
            Kind.IMAGES, Kind.MISSING_AFTER_REDIRECT -> {
                val location = archiveUrl().newBuilder()
                    .addPathSegment("download")
                    .addPathSegment("mbid-$mbid")
                    .addPathSegment("mbid-$mbid-$IMAGE_ID$size.jpg")
                    .build()
                redirect(location)
            }
            Kind.BUSY -> busyResponse(mbid)
            null -> notFound()
        }
    }

    /** CAA's answer to a release index request: a redirect to `index.json` on the image host, or its 503 or 404. */
    private fun index(mbid: String): MockResponse = when {
        entries[mbid] == Kind.BUSY -> busyResponse(mbid)
        indexes.containsKey(mbid) -> redirect(
            archiveUrl().newBuilder()
                .addPathSegment("download")
                .addPathSegment("mbid-$mbid")
                .addPathSegment("index.json")
                .build(),
        )
        else -> notFound()
    }

    private fun redirect(location: HttpUrl) =
        MockResponse.Builder().code(307).addHeader("Location", location).body("See: $location").build()

    private fun busyResponse(mbid: String) = MockResponse.Builder().code(503)
        .addHeader("Retry-After", retryAfter.getValue(mbid))
        .body("Service Temporarily Unavailable")
        .build()

    private inner class Archive : FixtureDispatcher() {
        override fun route(request: RecordedRequest): MockResponse {
            val segments = request.url.pathSegments
            val mbid = segments.getOrNull(1)?.removePrefix("mbid-")
            val file = segments.getOrNull(2)?.removeSuffix(".jpg")
            if (request.method != "GET" || segments.firstOrNull() != "download" || mbid == null || file == null) {
                return notFound()
            }
            if (file == "index.json") return indexes[mbid]?.response() ?: notFound()
            if (entries[mbid] != Kind.IMAGES) return indexImage(mbid, file)
            val suffix = file.removePrefix("mbid-$mbid-$IMAGE_ID")
            if (suffix in missingThumbnails[mbid].orEmpty()) return notFound()
            val image = when (suffix) {
                "_thumb250" -> FRONT_250
                "_thumb500" -> FRONT_500
                "_thumb1200" -> FRONT_1200
                "" -> ORIGINAL
                else -> return notFound()
            }
            return image.fixture.response()
        }

        /** An image of the release index of [mbid], by archive.org's name for it; 404 for an MBID without an index. */
        private fun indexImage(mbid: String, file: String): MockResponse {
            if (!indexes.containsKey(mbid)) return notFound()
            val px = Regex("mbid-${Regex.escape(mbid)}-\\d+(?:_thumb(250|500|1200))?").matchEntire(file)?.groupValues?.get(1)
                ?: return notFound()
            val image = when (px) {
                "250" -> FRONT_250
                "500" -> FRONT_500
                "1200" -> FRONT_1200
                else -> ORIGINAL
            }
            return image.fixture.response()
        }
    }

    /** A JPEG fixture and its size in pixels. */
    data class Image(val fixture: Fixture, val width: Int, val height: Int)

    companion object {
        private const val IMAGE_ID = "1234567890"
        private val ENTITIES = setOf("release", "release-group")

        /** An image named by a release index: its ID and, for a thumbnail, its size in pixels. */
        private val INDEX_IMAGE = Regex("""(\d+)(?:-(250|500|1200))?\.(?:jpg|png)""")

        /** The CAA image name and the suffix archive.org gives that size. */
        private val SIZES = mapOf("front" to "", "front-250" to "_thumb250", "front-500" to "_thumb500", "front-1200" to "_thumb1200")

        val FRONT_250 = Image(Fixture("caa/front-250.jpg", contentType = "image/jpeg"), 250, 250)
        val FRONT_500 = Image(Fixture("caa/front-500.jpg", contentType = "image/jpeg"), 500, 500)
        val FRONT_1200 = Image(Fixture("caa/front-1200.jpg", contentType = "image/jpeg"), 1200, 1200)

        /**
         * A release index with a front image (all thumbnails, numbered and by the older names), a back image known only
         * by the older names, with its ID as a string, and an image whose address has a scheme other than HTTP. Its
         * links use `http://`, as CAA's older answers do.
         */
        val RELEASE_INDEX = Fixture("caa/release-index.json")

        /** The release the links of [RELEASE_INDEX] name. */
        const val INDEX_RELEASE = "55555555-5555-4555-8555-555555555555"

        /** The uploaded original: not square, so its short side (1400) differs from its long side. */
        val ORIGINAL = Image(Fixture("caa/original.jpg", contentType = "image/jpeg"), 1500, 1400)

        private fun notFound() = MockResponse.Builder().code(404).setHeader("Content-Type", "text/plain").body("Not Found").build()
    }
}
