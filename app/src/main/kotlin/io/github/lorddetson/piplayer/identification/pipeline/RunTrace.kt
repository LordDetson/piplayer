package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdException
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.release.BrowseTrace
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.library.db.IdentificationTraceEntity
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.time.Duration

/**
 * The trace rows of one run (TI §10.1): one per stage it reached, then one for its outcome. Every value is a code, a
 * number or a time: no file name, tag value, address or error message, which may carry tag values in a search query,
 * reaches a row, so the trace can go into a problem report as it is (TI §9).
 */
object RunTrace {

    /** The stage name of the outcome's row. */
    const val OUTCOME = "outcome"

    /**
     * The rows of [result] for [contentKey] under [runId]: the run started at [startedAt] by the services' clock, ranked
     * [ranking] and took [elapsed] in all.
     */
    fun rows(
        contentKey: String,
        runId: String,
        startedAt: Long,
        result: PipelineResult,
        ranking: Ranking,
        elapsed: Duration,
    ): List<IdentificationTraceEntity> {
        val stages = result.stages.mapIndexed { position, report ->
            IdentificationTraceEntity(
                contentKey = contentKey,
                runId = runId,
                position = position,
                startedAt = report.startedAtMs ?: startedAt,
                stage = report.stage.name.lowercase(Locale.ROOT),
                provider = providerOf(report.stage)?.code(),
                httpStatus = report.http?.lastStatus,
                resultCount = (report.result as? StageResult.Ran)?.recordings,
                bestScore = report.bestScore,
                elapsedMs = report.elapsed.inWholeMilliseconds,
                note = note(report),
            )
        }
        val outcome = IdentificationTraceEntity(
            contentKey = contentKey,
            runId = runId,
            position = stages.size,
            startedAt = startedAt,
            stage = OUTCOME,
            provider = providerOf(result.outcome)?.code(),
            httpStatus = null,
            resultCount = ranking.matches.size,
            bestScore = ranking.best?.score,
            elapsedMs = elapsed.inWholeMilliseconds,
            note = listOfNotNull(kind(result.outcome), result.retryAt?.let { "retry_at=${it.toEpochMilli()}" })
                .joinToString(" "),
        )
        return stages + outcome
    }

    /** The provider a stage asks; none for stage 0. */
    private fun providerOf(stage: PipelineStage): ProviderId? = when (stage) {
        PipelineStage.LOCAL_READ -> null
        PipelineStage.FINGERPRINT -> ProviderId.ACOUSTID
        PipelineStage.KNOWN_IDS, PipelineStage.ISRC, PipelineStage.TEXT_SEARCH, PipelineStage.RELEASE_CHOICE ->
            ProviderId.MUSICBRAINZ
    }

    /** The provider that left the run unavailable or stopped. */
    private fun providerOf(outcome: IdentificationOutcome): ProviderId? = when (outcome) {
        is IdentificationOutcome.Unavailable -> outcome.provider
        is IdentificationOutcome.ProviderStopped -> outcome.provider
        else -> null
    }

    private fun ProviderId.code() = name.lowercase(Locale.ROOT)

    private fun note(report: StageReport): String? = buildList {
        when (val result = report.result) {
            is StageResult.Skipped -> add("skipped=${result.reason.name.lowercase(Locale.ROOT)}")
            is StageResult.Failed -> add("failed=${kind(result.error)}")
            is StageResult.Ran -> Unit
        }
        report.http?.let { add("requests=${it.requests} http_ms=${it.elapsedMs}") }
        report.browses.forEach { add(browse(it)) }
    }.joinToString(" ").ifEmpty { null }

    /** One release browse: its pages, the releases received of the total, the page cap and a failure. */
    private fun browse(trace: BrowseTrace): String = buildString {
        append("browse=").append(trace.pages).append("p,").append(trace.received).append('/').append(trace.total)
        if (trace.capped) append(",capped")
        if (trace.nonOfficialFallback) append(",any_status")
        trace.failure?.let { append(",failed=").append(kind(it)) }
    }

    /** A stable code for a failure, never its message, and not its class name, which R8 renames in a release. */
    private fun kind(error: IOException): String = when (error) {
        is MusicBrainzException.Unavailable, is AcoustIdException.Unavailable -> "unavailable"
        is MusicBrainzException.Paused, is AcoustIdException.Paused -> "paused"
        is MusicBrainzException.Unreadable -> "unreadable"
        is MusicBrainzException.Http -> "http_${error.code}"
        is AcoustIdException.Http -> "http_${error.code}"
        is AcoustIdException.Service -> "service_${error.code}"
        is AcoustIdException.InvalidApiKey -> "invalid_api_key"
        is MusicBrainzException.UserAgentRejected -> "user_agent_rejected"
        is DecodingException -> "undecodable"
        is SocketTimeoutException -> "timeout"
        else -> "io"
    }

    private fun kind(outcome: IdentificationOutcome): String = when (outcome) {
        is IdentificationOutcome.Found -> "found=${outcome.band.name.lowercase(Locale.ROOT)}"
        IdentificationOutcome.NoKey -> "no_key"
        is IdentificationOutcome.Unavailable -> "unavailable"
        is IdentificationOutcome.ProviderStopped -> "provider_stopped=${outcome.reason.name.lowercase(Locale.ROOT)}"
        IdentificationOutcome.Unreadable -> "unreadable"
        IdentificationOutcome.Disabled -> "disabled"
        IdentificationOutcome.NoNetwork -> "no_network"
        IdentificationOutcome.CaptivePortal -> "captive_portal"
    }
}
