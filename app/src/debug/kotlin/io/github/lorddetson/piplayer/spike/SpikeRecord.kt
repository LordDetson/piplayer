package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.pipeline.StageResult
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import java.util.Locale
import kotlinx.serialization.Serializable

/** A ranked recording as the corpus record keeps it; [acoustIdScore] only when the fingerprint found it. */
@Serializable
data class RecordCandidate(
    val recordingId: String,
    val title: String,
    val artist: String,
    val lengthMs: Long?,
    val score: Double,
    val source: String,
    val foundBy: List<String>,
    val acoustIdScore: Double? = null,
)

/**
 * One line of the own-files corpus run (TI §11.4): what one run of the pipeline gave for a file, flat enough to read as
 * a table. [outcome] names the outcome (`Found` with its [band], or why the run ended); [stages] gives each stage's
 * status ("found N", "skipped: <reason>" or "failed: <error>") and [elapsedMs] its time.
 */
@Serializable
data class SpikeRecord(
    val file: String?,
    val outcome: String,
    val band: String?,
    val durationMs: Long?,
    val lengthWalked: Boolean,
    val fingerprint: String?,
    val retryLater: Boolean,
    val stages: Map<String, String>,
    val elapsedMs: Map<String, Long>,
    val candidates: List<RecordCandidate>,
    val release: String?,
)

object SpikeReports {

    /** How many ranked recordings the record and the text keep. */
    const val TOP = 5

    fun record(result: PipelineResult): SpikeRecord {
        val found = result.outcome as? IdentificationOutcome.Found
        return SpikeRecord(
            file = result.file?.fileName,
            outcome = outcomeName(result.outcome),
            band = found?.band?.name,
            durationMs = result.durationMs,
            lengthWalked = result.lengthWalked,
            fingerprint = result.fingerprint?.name,
            retryLater = result.retryLater,
            stages = result.stages.associate { it.stage.name to status(it.result) },
            elapsedMs = result.stages.associate { it.stage.name to it.elapsed.inWholeMilliseconds },
            candidates = found?.ranking?.matches.orEmpty().take(TOP).map(::candidate),
            release = found?.choice?.suggested?.let { suggested ->
                val release = suggested.release
                listOfNotNull(release.title, release.date, release.country, release.id, suggested.source.name)
                    .joinToString(" · ")
            },
        )
    }

    /** A plain-text report for the clipboard: the file, the stages, the best recordings and the suggested edition. */
    fun text(result: PipelineResult): String = buildString {
        val record = record(result)
        appendLine("File: ${record.file ?: "?"}")
        val walked = if (record.lengthWalked) ", walked" else ""
        appendLine("Duration: ${record.durationMs?.let(::seconds) ?: "?"}$walked")
        val hint = result.hint
        if (hint != null) appendLine("Hint: ${hint.artist ?: "?"} - ${hint.title ?: "?"}")
        result.stages.forEach { stage ->
            appendLine("${stage.stage.name}: ${status(stage.result)}, ${stage.elapsed.inWholeMilliseconds} ms")
        }
        record.fingerprint?.let { appendLine("Fingerprint: $it") }
        if (record.retryLater) appendLine("AcoustID found nothing: retry later")
        appendLine("Outcome: ${record.outcome}${record.band?.let { " ($it)" }.orEmpty()}")
        record.candidates.forEach { appendLine("  ${line(it)}") }
        record.release?.let { appendLine("Suggested edition: $it") }
    }

    private fun outcomeName(outcome: IdentificationOutcome): String = when (outcome) {
        is IdentificationOutcome.ProviderStopped -> "ProviderStopped(${outcome.provider}, ${outcome.reason})"
        is IdentificationOutcome.Unavailable -> "Unavailable(${outcome.provider})"
        else -> outcome::class.simpleName.orEmpty()
    }

    private fun status(result: StageResult): String = when (result) {
        is StageResult.Ran -> "found ${result.recordings}"
        is StageResult.Skipped -> "skipped: ${result.reason}"
        is StageResult.Failed -> "failed: ${result.error.javaClass.simpleName}: ${result.error.message}"
    }

    private fun candidate(match: RecordingMatch) = RecordCandidate(
        recordingId = match.recordingId,
        title = match.candidate.title,
        artist = match.candidate.artistCredit,
        lengthMs = match.candidate.lengthMs,
        score = match.score,
        source = match.reason.source.name,
        foundBy = match.reason.foundBy.map { it.name }.sorted(),
        acoustIdScore = match.reason.fingerprintScore,
    )

    private fun line(candidate: RecordCandidate): String =
        "%.2f  %s - %s  %s  %s  %s".format(
            Locale.ROOT,
            candidate.score,
            candidate.artist.ifEmpty { "?" },
            candidate.title.ifEmpty { "?" },
            candidate.lengthMs?.let(::seconds) ?: "?",
            candidate.foundBy.joinToString("+"),
            candidate.recordingId,
        )

    private fun seconds(milliseconds: Long) =
        "%d:%02d".format(Locale.ROOT, milliseconds / 60_000, milliseconds / 1_000 % 60)
}
