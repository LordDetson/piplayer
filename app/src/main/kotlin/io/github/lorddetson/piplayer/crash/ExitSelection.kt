package io.github.lorddetson.piplayer.crash

import android.app.ApplicationExitInfo
import kotlin.math.abs

/** Which exit records make a report (docs/playback.md §7.1). */
object ExitSelection {

    /** A record to report, and the Java report the handler already wrote for it, if any. */
    data class Pick(val record: ExitRecord, val kind: CrashKind, val written: ReportStore.Report?)

    data class Result(val picks: List<Pick>, val state: ExitState)

    /** How near a Java report's time must be to its record's: Android notes the crash while the process writes it. */
    private const val SAME_CRASH_MS = 10_000L

    fun key(record: ExitRecord): String = "${record.pid}:${record.timestampMs}"

    /**
     * The records among [records] not seen before, of the kinds that are crashes, with the state to keep: the keys of
     * every record Android still holds. Without a [state] this is the first start: everything there is counts as seen,
     * so that crashes from before crash reports are not offered. [written] are the reports on disk.
     */
    fun select(records: List<ExitRecord>, state: ExitState?, written: List<ReportStore.Report>, nowMs: Long): Result {
        val keys = records.map(::key).toSet()
        if (state == null) return Result(emptyList(), ExitState(nowMs, keys))
        val picks = records
            .filter { key(it) !in state.seen && it.timestampMs >= state.floorMs }
            .mapNotNull { record ->
                val kind = kindOf(record.reason) ?: return@mapNotNull null
                val report = if (kind == CrashKind.JAVA) {
                    written.firstOrNull {
                        it.kind == CrashKind.JAVA && it.pid == record.pid && abs(it.timeMs - record.timestampMs) <= SAME_CRASH_MS
                    }
                } else {
                    null
                }
                Pick(record, kind, report)
            }
        return Result(picks, ExitState(state.floorMs, keys))
    }

    private fun kindOf(reason: Int): CrashKind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> CrashKind.JAVA
        ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashKind.NATIVE
        ApplicationExitInfo.REASON_ANR -> CrashKind.ANR
        else -> null
    }
}
