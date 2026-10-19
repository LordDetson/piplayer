package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdException
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdRecordingDto
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdResponse
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdResultDto
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.identification.fingerprint.PcmEncoding
import io.github.lorddetson.piplayer.identification.fingerprint.PcmFormat
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.SourceInfo
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.text.RecordingQuery
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import io.github.lorddetson.piplayer.nativelib.taglib.AudioProperties
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdentificationSpikeTest {

    private val tags = TagReadResult.Success(
        properties = mapOf("TITLE" to listOf("Paper Lanterns"), "ARTIST" to listOf("The Quiet Harbors")),
        audio = AudioProperties(lengthMs = 214_000, bitrateKbps = 320, sampleRate = 44_100, channels = 2),
        pictures = emptyList(),
    )

    private class FakeFile(
        override val displayName: String? = "01 - Name Artist - Name Title.mp3",
        override val sizeBytes: Long? = 8_600_000,
        private val tags: TagReadResult,
    ) : SpikeFile {
        val source = PcmSource { SourceInfo("audio/mpeg", "c2.android.mp3.decoder", durationUs = 213_900_000) }
        override fun readTags() = tags
        override fun pcmSource() = source
    }

    private fun fingerprintOf(encoded: String) = FingerprintResult(
        Fingerprint(IntArray(948), encoded),
        fedSamples = 1_323_000,
        inputFormat = PcmFormat(44_100, 2, PcmEncoding.PCM_16BIT),
        source = SourceInfo("audio/mpeg", "c2.android.mp3.decoder", 213_900_000),
        reachedEnd = false,
    )

    private class Lookups(private val answer: () -> AcoustIdResponse) : AcoustIdLookup {
        val calls = mutableListOf<Pair<String, Long>>()
        override suspend fun lookup(fingerprint: String, durationMs: Long): AcoustIdResponse {
            calls += fingerprint to durationMs
            return answer()
        }
    }

    private class Searches(private val fail: IOException? = null) : RecordingSearch {
        val queries = mutableListOf<RecordingQuery>()
        override suspend fun search(query: RecordingQuery): List<TextCandidate> {
            queries += query
            fail?.let { throw it }
            return listOf(TextCandidate("mb-1", "Paper Lanterns", "The Quiet Harbors", listOf("The Quiet Harbors"), 214_000))
        }
    }

    private val acoustIdAnswer = AcoustIdResponse(
        "ok",
        listOf(AcoustIdResultDto("track-1", 0.97, listOf(AcoustIdRecordingDto("rec-1", "Paper Lanterns", 214.4)))),
    )

    private fun spike(
        lookups: AcoustIdLookup? = Lookups { acoustIdAnswer },
        searches: RecordingSearch = Searches(),
        fingerprinter: suspend (PcmSource) -> FingerprintResult = { fingerprintOf("AQAAencoded") },
    ) = IdentificationSpike(fingerprinter, lookups, TextSearch(searches))

    @Test
    fun `runs every stage in order and reports their results`() = runTest {
        val lookups = Lookups { acoustIdAnswer }
        val searches = Searches()
        val stages = mutableListOf<SpikeStage>()
        val file = FakeFile(tags = tags)
        var fingerprinted: PcmSource? = null

        val report = spike(lookups, searches, { source -> fingerprinted = source; fingerprintOf("AQAAencoded") })
            .run(file) { stages += it }

        assertThat(stages).containsExactly(
            SpikeStage.READING_TAGS, SpikeStage.FINGERPRINTING, SpikeStage.ACOUSTID, SpikeStage.TEXT_SEARCH,
            SpikeStage.DONE,
        )
        assertThat(report.fileName).isEqualTo("01 - Name Artist - Name Title.mp3")
        assertThat(report.sizeBytes).isEqualTo(8_600_000)
        assertThat((report.tags as StageOutcome.Done).value).isEqualTo(tags)
        assertThat(report.hint.source).isEqualTo(HintSource.TAGS)
        assertThat(report.durationMs).isEqualTo(214_000)
        assertThat(report.durationSource).isEqualTo(DurationSource.TAGLIB)
        assertThat(fingerprinted).isSameAs(file.source)
        assertThat(lookups.calls).containsExactly("AQAAencoded" to 214_000L)
        val candidates = (report.acoustId as StageOutcome.Done).value
        assertThat(candidates.single().recordingId).isEqualTo("rec-1")
        assertThat(candidates.single().confidence).isEqualTo(0.97)
        assertThat(searches.queries.first().query).contains("paper").contains("lanterns").contains("dur:[209000 TO 219000]")
        assertThat((report.textSearch as StageOutcome.Done).value.candidates.first().candidate.recordingId)
            .isEqualTo("mb-1")
    }

    @Test
    fun `without an AcoustID key the lookup is skipped and the text search still runs`() = runTest {
        val searches = Searches()
        val report = spike(lookups = null, searches = searches).run(FakeFile(tags = tags))

        assertThat(report.acoustId).isEqualTo(StageOutcome.Skipped(SkipReason.NO_API_KEY))
        assertThat(report.fingerprint).isInstanceOf(StageOutcome.Done::class.java)
        assertThat(searches.queries).isNotEmpty()
    }

    @Test
    fun `a failed lookup does not stop the text search`() = runTest {
        val searches = Searches()
        val report = spike(lookups = Lookups { throw AcoustIdException.Http(403) }, searches = searches)
            .run(FakeFile(tags = tags))

        assertThat((report.acoustId as StageOutcome.Failed).error).contains("HTTP 403")
        assertThat(report.textSearch).isInstanceOf(StageOutcome.Done::class.java)
    }

    @Test
    fun `a failed fingerprint skips the lookup but not the text search`() = runTest {
        val lookups = Lookups { acoustIdAnswer }
        val searches = Searches()
        val report = spike(lookups, searches, { throw IOException("no decoder for audio/x-ape") })
            .run(FakeFile(tags = tags))

        assertThat((report.fingerprint as StageOutcome.Failed).error).contains("no decoder for audio/x-ape")
        assertThat(report.acoustId).isEqualTo(StageOutcome.Skipped(SkipReason.NO_FINGERPRINT))
        assertThat(lookups.calls).isEmpty()
        assertThat(searches.queries).isNotEmpty()
    }

    @Test
    fun `an empty fingerprint (audio too short) skips the lookup`() = runTest {
        val lookups = Lookups { acoustIdAnswer }
        val report = spike(lookups, fingerprinter = { fingerprintOf("") }).run(FakeFile(tags = tags))

        assertThat(report.acoustId).isEqualTo(StageOutcome.Skipped(SkipReason.NO_FINGERPRINT))
        assertThat(lookups.calls).isEmpty()
    }

    @Test
    fun `unreadable tags fall back to the file name and the decoder's duration`() = runTest {
        val lookups = Lookups { acoustIdAnswer }
        val searches = Searches()
        val report = spike(lookups, searches)
            .run(FakeFile(tags = TagReadResult.Failure(TagReadError.UNSUPPORTED_FORMAT)))

        assertThat((report.tags as StageOutcome.Failed).error).contains("UNSUPPORTED_FORMAT")
        assertThat(report.hint.source).isEqualTo(HintSource.FILE_NAME)
        assertThat(report.hint.hint.title).isEqualTo("Name Title")
        assertThat(report.durationMs).isEqualTo(213_900)
        assertThat(report.durationSource).isEqualTo(DurationSource.CONTAINER)
        assertThat(lookups.calls.single().second).isEqualTo(213_900)
    }

    @Test
    fun `a file decoded to its end is looked up with its decoded length`() = runTest {
        val lookups = Lookups { acoustIdAnswer }
        val whole = fingerprintOf("AQAAencoded").copy(fedSamples = 11_025L * 95 + 5_512, reachedEnd = true)
        val report = spike(lookups, fingerprinter = { whole }).run(FakeFile(tags = tags))

        assertThat(report.durationSource).isEqualTo(DurationSource.DECODED)
        assertThat(report.durationMs).isEqualTo(95_500)
        assertThat(lookups.calls.single().second).isEqualTo(95_500)
    }

    @Test
    fun `a length of zero from TagLib counts as missing`() = runTest {
        val zero = tags.copy(audio = tags.audio!!.copy(lengthMs = 0))
        val report = spike().run(FakeFile(tags = zero))

        assertThat(report.durationSource).isEqualTo(DurationSource.CONTAINER)
        assertThat(report.durationMs).isEqualTo(213_900)
    }

    @Test
    fun `without any duration the lookup is skipped`() = runTest {
        val noLength = tags.copy(audio = null)
        val lookups = Lookups { acoustIdAnswer }
        val fingerprint = fingerprintOf("AQAAencoded").let { it.copy(source = it.source.copy(durationUs = null)) }
        val report = spike(lookups, fingerprinter = { fingerprint }).run(FakeFile(tags = noLength))

        assertThat(report.durationSource).isEqualTo(DurationSource.NONE)
        assertThat(report.acoustId).isEqualTo(StageOutcome.Skipped(SkipReason.NO_DURATION))
        assertThat(lookups.calls).isEmpty()
    }

    @Test
    fun `without a title the text search is skipped`() = runTest {
        val searches = Searches()
        val noTitle = tags.copy(properties = emptyMap())
        val report = spike(searches = searches).run(FakeFile(displayName = "AUD-20230101-WA0001.opus", tags = noTitle))

        assertThat(report.textSearch).isEqualTo(StageOutcome.Skipped(SkipReason.NO_TITLE))
        assertThat(searches.queries).isEmpty()
    }

    @Test
    fun `a rejected User-Agent is reported as a failed text search`() = runTest {
        val searches = Searches(fail = MusicBrainzException.UserAgentRejected("MusicBrainz refused the User-Agent"))
        val report = spike(searches = searches).run(FakeFile(tags = tags))

        assertThat((report.textSearch as StageOutcome.Failed).error).contains("refused the User-Agent")
    }

    @Test
    fun `cancellation is not reported as a failure`() = runTest {
        val error = runCatching {
            spike(fingerprinter = { throw CancellationException("screen closed") }).run(FakeFile(tags = tags))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }
}
