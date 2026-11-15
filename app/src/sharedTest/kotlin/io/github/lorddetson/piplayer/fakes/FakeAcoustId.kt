package io.github.lorddetson.piplayer.fakes

import java.util.concurrent.ConcurrentHashMap
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * A fake AcoustID `/v2/lookup` (TI §3.2): it reads the gzip-compressed form and answers by the fingerprint sent, with
 * [LOOKUP] for any fingerprint not set with [on]. Serve it at `/v2/` and point the client's base URL there.
 */
class FakeAcoustId : FixtureDispatcher() {

    private val answers = ConcurrentHashMap<String, Fixture>()

    /** Answers a lookup of [fingerprint] with [fixture]. */
    fun on(fingerprint: String, fixture: Fixture): FakeAcoustId = apply { answers[fingerprint] = fixture }

    override fun route(request: RecordedRequest): MockResponse {
        if (request.method != "POST" || request.url.encodedPath.substringAfterLast('/') != "lookup") {
            return MockResponse.Builder().code(404).body("Not Found").build()
        }
        val form = request.formFields()
        if (form["client"].isNullOrEmpty()) return MISSING_CLIENT.response()
        return (answers[form["fingerprint"]] ?: LOOKUP).response()
    }

    companion object {
        /** Two tracks: the best (0.97) with three recordings, one of them an ID only; the other without recordings. */
        val LOOKUP = Fixture("acoustid/lookup.json")

        /** No match: not final, the file is retried later (TI §3.2). */
        val EMPTY = Fixture("acoustid/empty.json")

        /** Error 4, invalid application key: the client stops for the session. */
        val ERROR_4 = Fixture("acoustid/error-4.json", code = 400)

        /** Error 14, too many requests, with HTTP 429 and no Retry-After: retried with the backoff. */
        val ERROR_14 = Fixture("acoustid/error-14.json", code = 429)

        /** Tracks that match but have no linked recording. */
        val NO_RECORDINGS = Fixture("acoustid/no-recordings.json")

        /** Two tracks whose scores are closer than the band gap (TI §4.1). */
        val CLOSE_SCORES = Fixture("acoustid/close-scores.json")

        /** Error 2, a missing parameter: what the server says to a form without `client`. */
        val MISSING_CLIENT = Fixture("acoustid/error-2.json", code = 400)
    }
}
