package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.TrackHint
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextSearchTest {

    private val hint = TrackHint(title = "Paper Lanterns", artist = "The Quiet Harbors", durationMs = 214_000)

    private fun candidate(
        id: String,
        title: String = "Paper Lanterns",
        artist: String = "The Quiet Harbors",
        lengthMs: Long = 214_000,
    ) = TextCandidate(id, title, artist, listOf(artist), lengthMs)

    /** Answers each variant from [answers] and records which variants were asked. */
    private class FakeSearch(private val answers: Map<QueryVariant, List<TextCandidate>>) : RecordingSearch {
        val asked = mutableListOf<QueryVariant>()
        override suspend fun search(query: RecordingQuery): List<TextCandidate> {
            asked += query.variant
            return answers[query.variant].orEmpty()
        }
    }

    @Test
    fun `stops at the first variant with a candidate of at least 0,50`() = runTest {
        val fake = FakeSearch(mapOf(QueryVariant.TITLE_ARTIST_DURATION to listOf(candidate("a"))))
        val result = TextSearch(fake).search(hint)
        assertThat(fake.asked).containsExactly(QueryVariant.TITLE_ARTIST_DURATION)
        assertThat(result.candidates.single().candidate.recordingId).isEqualTo("a")
    }

    @Test
    fun `goes on to the next variant while candidates stay below 0,50`() = runTest {
        val fake = FakeSearch(
            mapOf(
                QueryVariant.TITLE_ARTIST_DURATION to listOf(candidate("weak", title = "Something Else", artist = "X")),
                QueryVariant.FUZZY_TITLE_ARTIST_DURATION to listOf(candidate("good")),
            ),
        )
        val result = TextSearch(fake).search(hint)
        assertThat(fake.asked).containsExactly(
            QueryVariant.TITLE_ARTIST_DURATION,
            QueryVariant.TITLE_ARTIST,
            QueryVariant.FUZZY_TITLE_ARTIST_DURATION,
        )
        assertThat(result.attempts).hasSize(3)
        assertThat(result.candidates.map { it.candidate.recordingId }).containsExactly("good", "weak")
    }

    @Test
    fun `tries every variant when nothing reaches 0,50`() = runTest {
        val fake = FakeSearch(emptyMap())
        val result = TextSearch(fake).search(hint)
        assertThat(fake.asked).hasSize(4)
        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun `a recording found by two variants is listed once with its higher score`() = runTest {
        val weakCopy = candidate("same", title = "Paper Lanterns", artist = "Someone")
        val fake = FakeSearch(
            mapOf(
                QueryVariant.TITLE_ARTIST_DURATION to listOf(weakCopy),
                QueryVariant.TITLE_ARTIST to listOf(candidate("same")),
            ),
        )
        val result = TextSearch(fake).search(hint)
        assertThat(result.candidates).hasSize(1)
        assertThat(result.candidates.single().score.total).isGreaterThanOrEqualTo(TextSearch.STOP_SCORE)
    }

    @Test
    fun `candidates of one variant are ranked by score, ties keep the server order`() = runTest {
        val fake = FakeSearch(
            mapOf(
                QueryVariant.TITLE_ARTIST_DURATION to listOf(
                    // 16 s off: 0.80, below the 0.85 of the exact matches.
                    candidate("weaker", lengthMs = 230_000),
                    candidate("first"),
                    candidate("second"),
                ),
            ),
        )
        val result = TextSearch(fake).search(hint)
        assertThat(result.attempts.single().candidates.map { it.candidate.recordingId })
            .containsExactly("first", "second", "weaker")
    }

    @Test
    fun `a failure after an answered variant keeps the candidates found so far`() = runTest {
        val busy = MusicBrainzException.Unavailable(attempts = 4, cause = null)
        val result = TextSearch({ query ->
            when (query.variant) {
                QueryVariant.TITLE_ARTIST_DURATION -> listOf(candidate("weak", title = "Something Else", artist = "X"))
                else -> throw busy
            }
        }).search(hint)
        assertThat(result.candidates.single().candidate.recordingId).isEqualTo("weak")
        assertThat(result.failure).isSameAs(busy)
    }

    @Test
    fun `a failure of the first variant is passed on`() = runTest {
        val busy = MusicBrainzException.Unavailable(attempts = 4, cause = null)
        val error = runCatching { TextSearch({ throw busy }).search(hint) }.exceptionOrNull()
        assertThat(error).isSameAs(busy)
    }

    @Test
    fun `a rejected User-Agent is passed on even after an answered variant`() = runTest {
        val rejected = MusicBrainzException.UserAgentRejected("blocked")
        val error = runCatching {
            TextSearch({ query ->
                if (query.variant == QueryVariant.TITLE_ARTIST_DURATION) emptyList() else throw rejected
            }).search(hint)
        }.exceptionOrNull()
        assertThat(error).isSameAs(rejected)
    }

    @Test
    fun `nothing is sent without a usable title`() = runTest {
        val fake = FakeSearch(emptyMap())
        val result = TextSearch(fake).search(hint.copy(title = null))
        assertThat(fake.asked).isEmpty()
        assertThat(result.attempts).isEmpty()
    }

    @Test
    fun `a version written without brackets is bracketed before searching and scoring`() = runTest {
        val queries = mutableListOf<String>()
        val result = TextSearch({ query ->
            queries += query.query
            listOf(candidate("radio", title = "Paper Lanterns (Radio Edit)"))
        }).search(hint.copy(title = "Paper Lanterns Radio Edit"))
        assertThat(queries.first()).startsWith("recording:(+paper +lanterns radio edit)")
        assertThat(result.candidates.single().score.title).isEqualTo(1.0)
    }

    @Test
    fun `bracketing the version can be switched off`() = runTest {
        val queries = mutableListOf<String>()
        TextSearch({ query -> queries += query.query; emptyList() }, QueryOptions(bracketVersion = false))
            .search(hint.copy(title = "Paper Lanterns Radio Edit"))
        assertThat(queries.first()).startsWith("recording:(+paper +lanterns +radio +edit)")
    }

    @Test
    fun `query options reach the query builder`() = runTest {
        val queries = mutableListOf<String>()
        TextSearch({ query -> queries += query.query; emptyList() }, QueryOptions(requireTitleWords = false))
            .search(hint)
        assertThat(queries.first()).startsWith("recording:(paper lanterns)")
    }

    @Test
    fun `the limit is passed on to every query`() = runTest {
        val limits = mutableListOf<Int>()
        TextSearch({ query -> limits += query.limit; emptyList() }).search(hint, TextQueryBuilder.MANUAL_LIMIT)
        assertThat(limits).containsOnly(TextQueryBuilder.MANUAL_LIMIT)
    }
}
