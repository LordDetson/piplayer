package io.github.lorddetson.piplayer.crash

import android.app.ApplicationExitInfo
import java.time.Instant
import java.time.format.DateTimeFormatter

/** What a report is about (docs/playback.md §7.1). */
enum class CrashKind(val tag: String, val title: String) {
    JAVA("java", "Crash"),
    NATIVE("native", "Native crash"),
    ANR("anr", "ANR"),
}

/** The device and build a report comes from, read once when the handler is installed. */
data class DeviceInfo(
    val appVersion: String,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val release: String,
    val sdk: Int,
    val fingerprint: String,
    val abis: List<String>,
)

/** One way a process ended, as Android recorded it (`ApplicationExitInfo`), as plain data. */
data class ExitRecord(
    val pid: Int,
    val processName: String,
    val reason: Int,
    val status: Int,
    val description: String?,
    val importance: Int,
    val timestampMs: Long,
    val pss: Long = 0,
    val rss: Long = 0,
)

/** The text of reports (docs/playback.md §7.2, §7.3). Plain text, so that it can be read and shared as it is. */
object CrashText {

    const val SHARE_TEXT_CHARS = 32_000

    fun header(info: DeviceInfo): String = buildString {
        appendLine("PiPlayer ${info.appVersion}, ${info.buildType}")
        appendLine("Device: ${info.manufacturer} ${info.model} (${info.device})")
        appendLine("Android ${info.release} (API ${info.sdk})")
        appendLine("Build: ${info.fingerprint}")
        appendLine("ABIs: ${info.abis.joinToString()}")
    }

    /** A Kotlin or Java crash, as the handler writes it: the whole stack with its causes and suppressed exceptions. */
    fun java(header: String, process: String, pid: Int, thread: String, timeMs: Long, error: Throwable): String = buildString {
        append(header)
        appendLine()
        appendLine(CrashKind.JAVA.title)
        appendLine("Time: ${time(timeMs)}")
        appendLine("Process: $process (pid $pid)")
        appendLine("Thread: $thread")
        appendLine()
        append(error.stackTraceToString())
    }

    /** A report from an exit record; [trace] is the ANR trace or the decoded tombstone, when there is one. */
    fun exit(header: String, record: ExitRecord, kind: CrashKind, trace: String?): String = buildString {
        append(header)
        appendLine()
        appendLine(kind.title)
        appendLine("Time: ${time(record.timestampMs)}")
        appendLine("Process: ${record.processName} (pid ${record.pid})")
        appendLine()
        append(recordBlock(record))
        appendLine()
        appendLine(trace ?: noTrace(kind))
    }

    /** Android's record, as it goes into a report or under a Java crash report that the handler wrote. */
    fun recordBlock(record: ExitRecord): String = buildString {
        appendLine("Android's record")
        appendLine("Reason: ${reasonName(record.reason)}")
        appendLine("Status: ${record.status}")
        appendLine("Description: ${record.description ?: "-"}")
        appendLine("Importance: ${record.importance}")
        appendLine("PSS: ${record.pss} kB, RSS: ${record.rss} kB")
    }

    /** Several reports as one text, in the order given (newest first). */
    fun combine(reports: List<String>): String = reports.joinToString("\n" + "=".repeat(SEPARATOR) + "\n\n")

    /**
     * The text that goes with a shared report: all of it up to [maxChars], else the start cut after a line (or, in a
     * single long line, before a character that would be split), saying that the file has it all.
     */
    fun shareText(text: String, maxChars: Int = SHARE_TEXT_CHARS): String {
        if (text.length <= maxChars) return text
        var cut = text.lastIndexOf('\n', maxChars - 1) + 1
        if (cut <= 0) {
            cut = maxChars
            if (Character.isHighSurrogate(text[cut - 1])) cut--
        }
        return text.substring(0, cut).let { if (it.endsWith("\n")) it else it + "\n" } + "\n" + TRUNCATED
    }

    private fun noTrace(kind: CrashKind): String = when (kind) {
        CrashKind.NATIVE -> "No tombstone: Android 11 keeps none for apps, or a newer crash has taken its place."
        CrashKind.ANR -> "No trace was kept."
        CrashKind.JAVA -> "The stack trace was not captured in the crashing process."
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        else -> "reason $reason"
    }

    private fun time(ms: Long): String = "${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))} ($ms)"

    private const val SEPARATOR = 60
    private const val TRUNCATED = "Truncated: the full report is in the attached file."
}
