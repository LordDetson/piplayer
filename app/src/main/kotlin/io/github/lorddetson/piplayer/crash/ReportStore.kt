package io.github.lorddetson.piplayer.crash

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reports as files in [dir], the newest kept (docs/playback.md §7.2). Plain java.io, so that the crash handler can use
 * it from a dying process and the JVM tests without Android. A report is `<time>-<pid>-<kind>.txt`; a `.shown` file
 * next to it says it was offered; files are written whole under a temporary name and then renamed.
 */
class ReportStore(val dir: File) {

    data class Report(val file: File, val kind: CrashKind, val timeMs: Long, val pid: Int) {
        val text: String get() = file.readText()
    }

    /** Writes a report atomically. */
    fun write(kind: CrashKind, timeMs: Long, pid: Int, text: String): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot make $dir")
        val file = File(dir, "$timeMs-$pid-${kind.tag}.txt")
        replace(file, text)
        return file
    }

    /** Replaces a report's text, atomically. */
    fun rewrite(report: Report, text: String) = replace(report.file, text)

    /** Every report, newest first. */
    fun reports(): List<Report> = dir.listFiles().orEmpty()
        .mapNotNull { file -> parse(file) }
        .sortedWith(compareByDescending<Report> { it.timeMs }.thenByDescending { it.file.name })

    /** Reports the user has not been offered yet, newest first. */
    fun unshown(): List<Report> = reports().filterNot { marker(it).exists() }

    fun markShown(reports: List<Report>) = reports.forEach { marker(it).createNewFile() }

    /** Deletes all but the newest [keep] reports, with their markers. */
    fun prune(keep: Int = KEEP) = reports().drop(keep).forEach { report ->
        report.file.delete()
        marker(report).delete()
    }

    fun readState(): ExitState? {
        val lines = File(dir, STATE).takeIf { it.isFile }?.readLines() ?: return null
        val floor = lines.firstOrNull()?.removePrefix("floor=")?.toLongOrNull() ?: return null
        return ExitState(floor, lines.drop(1).filter { it.isNotBlank() }.toSet())
    }

    fun writeState(state: ExitState) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot make $dir")
        replace(File(dir, STATE), (listOf("floor=${state.floorMs}") + state.seen).joinToString("\n"))
    }

    private fun marker(report: Report) = File(dir, report.file.name + SHOWN)

    private fun parse(file: File): Report? {
        val match = NAME.matchEntire(file.name) ?: return null
        val kind = CrashKind.entries.firstOrNull { it.tag == match.groupValues[3] } ?: return null
        return Report(file, kind, match.groupValues[1].toLong(), match.groupValues[2].toInt())
    }

    private fun replace(target: File, text: String) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeText(text)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val KEEP = 5
        private const val SHOWN = ".shown"
        private const val STATE = "exit-state"
        private val NAME = Regex("""(\d+)-(\d+)-([a-z]+)\.txt""")
    }
}

/**
 * Which exit records have been looked at: those with keys in [seen], and any older than [floorMs], the first start
 * with crash reports.
 */
data class ExitState(val floorMs: Long, val seen: Set<String>)
