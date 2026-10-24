package io.github.lorddetson.piplayer.crash

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Turns Android's records of how the app's processes ended into reports, at the start of the main process
 * (docs/playback.md §7.1). Plain JVM code: the records and their traces come in as data.
 */
class CrashCollector(
    private val store: ReportStore,
    private val header: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val traceLimit: Int = TRACE_LIMIT,
    private val tombstoneLimit: Int = TOMBSTONE_LIMIT,
) {

    /** A record, and a way to open its trace: an ANR's text or a native crash's tombstone, null when there is none. */
    class Exit(val record: ExitRecord, val trace: () -> InputStream?)

    /**
     * Writes a report for each new crash among [exits], or adds Android's record to the Java report the handler
     * wrote, keeps the newest reports and remembers the records seen.
     */
    fun collect(exits: List<Exit>) {
        val result = ExitSelection.select(exits.map { it.record }, store.readState(), store.reports(), now())
        for (pick in result.picks) {
            val written = pick.written
            if (written != null) {
                store.rewrite(written, written.text + "\n" + CrashText.recordBlock(pick.record))
            } else {
                val exit = exits.first { it.record == pick.record }
                val trace = if (pick.kind == CrashKind.JAVA) null else trace(pick.kind, exit)
                store.write(pick.kind, pick.record.timestampMs, pick.record.pid, CrashText.exit(header, pick.record, pick.kind, trace))
            }
        }
        store.prune()
        store.writeState(result.state)
    }

    private fun trace(kind: CrashKind, exit: Exit): String? = try {
        exit.trace()?.use { input -> if (kind == CrashKind.NATIVE) tombstone(input) else text(input) }
    } catch (e: Exception) {
        "The trace could not be read: ${e.message ?: e.javaClass.name}"
    }

    private fun tombstone(input: InputStream): String {
        val (bytes, cut) = input.readUpTo(tombstoneLimit)
        if (cut) return "The tombstone is larger than $tombstoneLimit bytes and was not read."
        return try {
            TombstoneDecoder.decode(bytes)
        } catch (e: TombstoneDecoder.MalformedTombstone) {
            "The tombstone could not be read: ${e.message}"
        }
    }

    private fun text(input: InputStream): String {
        val (bytes, cut) = input.readUpTo(traceLimit)
        val text = bytes.decodeToString()
        return if (cut) "$text\n\nThe trace was cut at $traceLimit bytes." else text
    }

    /** Up to [limit] bytes, and whether there were more. */
    private fun InputStream.readUpTo(limit: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (out.size() <= limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        val bytes = out.toByteArray()
        return if (bytes.size > limit) bytes.copyOf(limit) to true else bytes to false
    }

    companion object {
        const val TRACE_LIMIT = 4 shl 20
        const val TOMBSTONE_LIMIT = 8 shl 20
        private const val BUFFER = 64 * 1024
    }
}
