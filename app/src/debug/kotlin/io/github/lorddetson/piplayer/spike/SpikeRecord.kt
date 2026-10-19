package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidate
import io.github.lorddetson.piplayer.identification.text.ScoredCandidate
import java.util.Locale
import kotlinx.serialization.Serializable

/** A candidate as the corpus record keeps it; [acoustIdScore] only for fingerprint candidates. */
@Serializable
data class RecordCandidate(
    val recordingId: String?,
    val title: String?,
    val artist: String?,
    val lengthMs: Long?,
    val score: Double,
    val acoustIdScore: Double? = null,
)

/**
 * One line of the own-files corpus run (TI §11.4): what the spike found for a file, flat enough to read as a table.
 * Statuses are "done", "failed: <error>" or "skipped: <reason>"; a text search that stopped after a failed query is
 * "partial: <error>".
 */
@Serializable
data class SpikeRecord(
    val file: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val durationSource: String,
    val tags: String,
    val hintSource: String,
    val hintArtist: String?,
    val hintTitle: String?,
    val fingerprint: String,
    val fingerprintItems: Int?,
    val fingerprintSeconds: Double?,
    val inputFormat: String?,
    val decoder: String?,
    val acoustId: String,
    val acoustIdCandidates: List<RecordCandidate>,
    val textSearch: String,
    val textQueries: Int,
    val textCandidates: List<RecordCandidate>,
    val elapsedMs: Map<String, Long>,
)

object SpikeReports {

    /** How many candidates of each stage the record and the text keep. */
    const val TOP = 5

    fun record(report: SpikeReport): SpikeRecord {
        val fingerprint = (report.fingerprint as? StageOutcome.Done)?.value
        val hint = report.hint.hint
        return SpikeRecord(
            file = report.fileName,
            sizeBytes = report.sizeBytes,
            durationMs = report.durationMs,
            durationSource = report.durationSource.name,
            tags = status(report.tags),
            hintSource = report.hint.source.name,
            hintArtist = hint.artist,
            hintTitle = hint.title,
            fingerprint = status(report.fingerprint),
            fingerprintItems = fingerprint?.fingerprint?.raw?.size,
            fingerprintSeconds = fingerprint?.fedSeconds,
            inputFormat = fingerprint?.inputFormat?.let { format ->
                "${format.sampleRate} Hz, ${format.channelCount} ch, ${format.encoding}"
            },
            decoder = fingerprint?.source?.let { "${it.decoderName} (${it.mimeType})" },
            acoustId = status(report.acoustId),
            acoustIdCandidates = report.acoustId.done().orEmpty().take(TOP).map(::fromAcoustId),
            textSearch = report.textSearch.done()?.failure
                ?.let { "partial: ${it.javaClass.simpleName}: ${it.message}" }
                ?: status(report.textSearch),
            textQueries = report.textSearch.done()?.attempts?.size ?: 0,
            textCandidates = report.textSearch.done()?.candidates.orEmpty().take(TOP).map(::fromText),
            elapsedMs = buildMap {
                elapsed("tags", report.tags)
                elapsed("fingerprint", report.fingerprint)
                elapsed("acoustId", report.acoustId)
                elapsed("textSearch", report.textSearch)
            },
        )
    }

    /** A plain-text report for the clipboard: the file, the hint and the best candidates of each stage. */
    fun text(report: SpikeReport): String = buildString {
        val record = record(report)
        appendLine("File: ${report.fileName ?: "?"}${report.sizeBytes?.let { " (${it / 1024} KB)" }.orEmpty()}")
        appendLine("Tags: ${record.tags}")
        val hint = report.hint.hint
        appendLine("Hint (${record.hintSource}): ${hint.artist ?: "?"} - ${hint.title ?: "?"}")
        appendLine("Duration: ${report.durationMs?.let(::seconds) ?: "?"} (${record.durationSource})")
        val fingerprint = (report.fingerprint as? StageOutcome.Done)?.value
        if (fingerprint != null) {
            val seconds = "%.1f".format(Locale.ROOT, fingerprint.fedSeconds)
            appendLine(
                "Fingerprint: ${fingerprint.fingerprint.raw.size} items, $seconds s, ${record.inputFormat}, " +
                    "${record.decoder}${elapsedText(report.fingerprint)}",
            )
        } else {
            appendLine("Fingerprint: ${record.fingerprint}")
        }
        appendLine("AcoustID: ${record.acoustId}${elapsedText(report.acoustId)}")
        record.acoustIdCandidates.forEach { appendLine("  ${line(it)}") }
        val queries = "${record.textQueries} queries${elapsedText(report.textSearch)}"
        appendLine("MusicBrainz text search: ${record.textSearch}, $queries")
        record.textCandidates.forEach { appendLine("  ${line(it)}") }
    }

    private fun <T> StageOutcome<T>.done(): T? = (this as? StageOutcome.Done)?.value

    private fun status(outcome: StageOutcome<*>): String = when (outcome) {
        is StageOutcome.Done -> "done"
        is StageOutcome.Failed -> "failed: ${outcome.error}"
        is StageOutcome.Skipped -> "skipped: ${outcome.reason}"
    }

    private fun MutableMap<String, Long>.elapsed(name: String, outcome: StageOutcome<*>) {
        when (outcome) {
            is StageOutcome.Done -> put(name, outcome.elapsed.inWholeMilliseconds)
            is StageOutcome.Failed -> put(name, outcome.elapsed.inWholeMilliseconds)
            is StageOutcome.Skipped -> Unit
        }
    }

    private fun elapsedText(outcome: StageOutcome<*>): String = when (outcome) {
        is StageOutcome.Done -> ", ${outcome.elapsed.inWholeMilliseconds} ms"
        is StageOutcome.Failed -> ", ${outcome.elapsed.inWholeMilliseconds} ms"
        is StageOutcome.Skipped -> ""
    }

    private fun fromAcoustId(candidate: AcoustIdCandidate) = RecordCandidate(
        recordingId = candidate.recordingId,
        title = candidate.title,
        artist = candidate.artistCredit,
        lengthMs = candidate.lengthMs,
        score = candidate.confidence,
        acoustIdScore = candidate.acoustIdScore,
    )

    private fun fromText(scored: ScoredCandidate) = RecordCandidate(
        recordingId = scored.candidate.recordingId,
        title = scored.candidate.title,
        artist = scored.candidate.artistCredit,
        lengthMs = scored.candidate.lengthMs,
        score = scored.score.total,
    )

    private fun line(candidate: RecordCandidate): String =
        "%.2f  %s - %s  %s  %s".format(
            Locale.ROOT,
            candidate.score,
            candidate.artist ?: "?",
            candidate.title ?: "?",
            candidate.lengthMs?.let(::seconds) ?: "?",
            candidate.recordingId ?: "(no recording)",
        )

    private fun seconds(milliseconds: Long) =
        "%d:%02d".format(Locale.ROOT, milliseconds / 60_000, milliseconds / 1_000 % 60)
}
