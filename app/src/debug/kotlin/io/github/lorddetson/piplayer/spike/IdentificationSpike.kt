package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidate
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidates
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdResponse
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.identification.text.TextSearchResult
import io.github.lorddetson.piplayer.nativelib.chromaprint.Chromaprint
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The file the spike works on. */
interface SpikeFile {
    val displayName: String?
    val sizeBytes: Long?

    fun readTags(): TagReadResult

    /** A fresh decoder over the file's audio. */
    fun pcmSource(): PcmSource
}

fun interface AcoustIdLookup {
    suspend fun lookup(fingerprint: String, durationMs: Long): AcoustIdResponse
}

enum class SpikeStage { READING_TAGS, FINGERPRINTING, ACOUSTID, TEXT_SEARCH, DONE }

enum class SkipReason { NO_API_KEY, NO_FINGERPRINT, NO_DURATION, NO_TITLE }

/** How one stage ended; [Failed.error] is the exception's class and message, never a key or a URL. */
sealed interface StageOutcome<out T> {
    data class Done<T>(val value: T, val elapsed: Duration) : StageOutcome<T>

    data class Failed(val error: String, val elapsed: Duration) : StageOutcome<Nothing>

    data class Skipped(val reason: SkipReason) : StageOutcome<Nothing>
}

/**
 * Where the duration sent to AcoustID and MusicBrainz came from: the decoded length of a file that was decoded to its
 * end, TagLib's length, or the container's duration as Media3 read it.
 */
enum class DurationSource { DECODED, TAGLIB, CONTAINER, NONE }

data class SpikeReport(
    val fileName: String?,
    val sizeBytes: Long?,
    val tags: StageOutcome<TagReadResult.Success>,
    val hint: SpikeHint,
    val durationMs: Long?,
    val durationSource: DurationSource,
    val fingerprint: StageOutcome<FingerprintResult>,
    val acoustId: StageOutcome<List<AcoustIdCandidate>>,
    val textSearch: StageOutcome<TextSearchResult>,
)

/**
 * The M0 identification spike: reads the tags, fingerprints the first two minutes, looks the fingerprint up in
 * AcoustID and runs the MusicBrainz text search, and reports every stage with its raw candidates. A stage that fails
 * does not stop the stages that do not need its result. [acoustId] is null when the build has no AcoustID key.
 */
class IdentificationSpike(
    private val fingerprinter: suspend (PcmSource) -> FingerprintResult,
    private val acoustId: AcoustIdLookup?,
    private val textSearch: TextSearch,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    suspend fun run(file: SpikeFile, onStage: (SpikeStage) -> Unit = {}): SpikeReport {
        onStage(SpikeStage.READING_TAGS)
        val tags = stage {
            when (val read = file.readTags()) {
                is TagReadResult.Success -> read
                is TagReadResult.Failure -> throw TagException(read.error.name)
            }
        }
        val tagSuccess = (tags as? StageOutcome.Done)?.value

        onStage(SpikeStage.FINGERPRINTING)
        val fingerprint = stage { fingerprinter(file.pcmSource()) }
        val fingerprintResult = (fingerprint as? StageOutcome.Done)?.value

        val (durationMs, durationSource) = duration(tagSuccess, fingerprintResult)
        val hint = SpikeHints.from(tagSuccess?.properties.orEmpty(), file.displayName, durationMs)

        onStage(SpikeStage.ACOUSTID)
        val encoded = fingerprintResult?.fingerprint?.encoded.orEmpty()
        val acoustIdOutcome = when {
            acoustId == null -> StageOutcome.Skipped(SkipReason.NO_API_KEY)
            encoded.isEmpty() -> StageOutcome.Skipped(SkipReason.NO_FINGERPRINT)
            durationMs == null -> StageOutcome.Skipped(SkipReason.NO_DURATION)
            else -> stage { AcoustIdCandidates.from(acoustId.lookup(encoded, durationMs), durationMs) }
        }

        onStage(SpikeStage.TEXT_SEARCH)
        val text = if (hint.hint.title == null) {
            StageOutcome.Skipped(SkipReason.NO_TITLE)
        } else {
            stage { textSearch.search(hint.hint) }
        }

        onStage(SpikeStage.DONE)
        return SpikeReport(
            fileName = file.displayName,
            sizeBytes = file.sizeBytes,
            tags = tags,
            hint = hint,
            durationMs = durationMs,
            durationSource = durationSource,
            fingerprint = fingerprint,
            acoustId = acoustIdOutcome,
            textSearch = text,
        )
    }

    /** The decoded length when the whole file was decoded, else TagLib's, else the container's; zero means unknown. */
    private fun duration(tags: TagReadResult.Success?, fingerprint: FingerprintResult?): Pair<Long?, DurationSource> {
        if (fingerprint != null && fingerprint.reachedEnd && fingerprint.fedSamples > 0) {
            val rate = Chromaprint.SAMPLE_RATE
            return (fingerprint.fedSamples * 1_000 + rate / 2) / rate to DurationSource.DECODED
        }
        tags?.audio?.lengthMs?.takeIf { it > 0 }?.let { return it.toLong() to DurationSource.TAGLIB }
        fingerprint?.source?.durationUs?.takeIf { it > 0 }?.let { return it / 1_000 to DurationSource.CONTAINER }
        return null to DurationSource.NONE
    }

    /** Runs one stage; any failure but cancellation is recorded with the time it took. */
    private suspend fun <T> stage(block: suspend () -> T): StageOutcome<T> {
        val start = timeSource.markNow()
        return try {
            StageOutcome.Done(block(), start.elapsedNow())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StageOutcome.Failed("${e.javaClass.simpleName}: ${e.message}", start.elapsedNow())
        }
    }

    private class TagException(error: String) : Exception(error)
}
