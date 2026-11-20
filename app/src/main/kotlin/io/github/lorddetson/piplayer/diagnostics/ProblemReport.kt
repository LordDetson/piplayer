package io.github.lorddetson.piplayer.diagnostics

import io.github.lorddetson.piplayer.crash.CrashText
import io.github.lorddetson.piplayer.crash.DeviceInfo
import io.github.lorddetson.piplayer.identification.pipeline.PipelineStage
import io.github.lorddetson.piplayer.identification.pipeline.SkipReason
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.provider.StopReason
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.details.TraceRun
import java.time.Instant
import java.util.Locale

/** The copyable problem report (TI §9, §10.1). Supply the track's trace only after the user opts in. */
object ProblemReport {

    fun text(device: DeviceInfo, trace: List<TraceRun> = emptyList()): String = buildString {
        append(CrashText.header(device))
        if (trace.isEmpty()) return@buildString

        appendLine()
        appendLine("Identification trace")
        for (run in trace) {
            append(Instant.ofEpochMilli(run.startedAt))
            run.outcome?.takeIf { it in outcomeCodes }?.let { append(' ').append(it) }
            appendField("provider", run.provider?.takeIf { it in providerCodes })
            appendField("retry_at", run.retryAt?.let(Instant::ofEpochMilli))
            appendLine()
            for (step in run.steps) {
                if (step.stage !in stageCodes) continue
                append("  ").append(step.stage)
                appendField("provider", step.provider?.takeIf { it in providerCodes })
                appendField("http_status", step.httpStatus)
                appendField("result_count", step.resultCount)
                appendField("best_score", step.bestScore)
                appendField("elapsed_ms", step.elapsedMs)
                appendField("skipped", step.skipped?.takeIf { it in skipCodes })
                appendField("failed", step.failed?.takeIf { it in failureCodes || numberedFailure.matches(it) })
                appendLine()
            }
        }
    }

    private fun StringBuilder.appendField(name: String, value: Any?) {
        if (value != null) append(' ').append(name).append('=').append(value)
    }

    // Permit only codes produced by RunTrace. Stored traces can come from older versions.
    private val stageCodes = PipelineStage.entries.map { it.name.lowercase(Locale.ROOT) }.toSet()
    private val providerCodes = ProviderId.entries.map { it.name.lowercase(Locale.ROOT) }.toSet()
    private val skipCodes = SkipReason.entries.map { it.name.lowercase(Locale.ROOT) }.toSet()
    private val outcomeCodes = setOf("no_key", "unavailable", "unreadable", "disabled", "no_network", "captive_portal") +
        Band.entries.map { "found=${it.name.lowercase(Locale.ROOT)}" } +
        StopReason.entries.map { "provider_stopped=${it.name.lowercase(Locale.ROOT)}" }
    private val failureCodes = setOf("unavailable", "paused", "unreadable", "undecodable", "timeout", "io") +
        StopReason.entries.map { it.name.lowercase(Locale.ROOT) }
    private val numberedFailure = Regex("http_[1-5][0-9]{2}|service_-?[0-9]{1,10}")
}
