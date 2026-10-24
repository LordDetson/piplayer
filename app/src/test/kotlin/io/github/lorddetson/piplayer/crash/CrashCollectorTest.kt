package io.github.lorddetson.piplayer.crash

import android.app.ApplicationExitInfo.REASON_ANR
import android.app.ApplicationExitInfo.REASON_CRASH
import android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
import java.io.File
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CrashCollectorTest {

    @TempDir
    lateinit var dir: File

    private val store by lazy { ReportStore(File(dir, "crashes")) }

    private fun collector(traceLimit: Int = CrashCollector.TRACE_LIMIT) =
        CrashCollector(store, "HEADER\n", now = { NOW }, traceLimit = traceLimit)

    private fun record(pid: Int, reason: Int) = ExitRecord(
        pid = pid,
        processName = "io.github.lorddetson.piplayer",
        reason = reason,
        status = 6,
        description = "crash",
        importance = 100,
        timestampMs = NOW + pid,
    )

    private fun exit(pid: Int, reason: Int, trace: ByteArray? = null) =
        CrashCollector.Exit(record(pid, reason)) { trace?.inputStream() }

    /** A tombstone with an abort message only, field 14. */
    private fun tombstone(abort: String): ByteArray {
        val text = abort.toByteArray()
        return byteArrayOf((14 shl 3 or 2).toByte(), text.size.toByte()) + text
    }

    /** The first start has been, so that records from now on count. */
    private fun started() = collector().collect(emptyList())

    @Test
    fun `the first start writes nothing and remembers the records there are`() {
        val old = exit(1, REASON_CRASH_NATIVE)

        collector().collect(listOf(old))
        collector().collect(listOf(old))

        assertThat(store.reports()).isEmpty()
        assertThat(store.readState()).isNotNull
    }

    @Test
    fun `a native crash's tombstone is decoded into its report`() {
        started()

        collector().collect(listOf(exit(7, REASON_CRASH_NATIVE, tombstone("taglib: bad frame"))))

        val report = store.reports().single()
        assertThat(report.kind).isEqualTo(CrashKind.NATIVE)
        assertThat(report.pid).isEqualTo(7)
        assertThat(report.text).startsWith("HEADER").contains("Android's record", "Abort message: 'taglib: bad frame'")
    }

    @Test
    fun `a tombstone that cannot be read is reported with the reason`() {
        started()

        collector().collect(listOf(exit(7, REASON_CRASH_NATIVE, byteArrayOf(0x0B, 0x01))))

        assertThat(store.reports().single().text).contains("Android's record", "The tombstone could not be read")
    }

    @Test
    fun `an ANR's trace goes into its report, cut at the limit`() {
        started()
        val trace = "----- pid 8 at 2026-10-24 -----\n" + "x".repeat(200)

        collector(traceLimit = 100).collect(listOf(exit(8, REASON_ANR, trace.toByteArray())))

        val report = store.reports().single()
        assertThat(report.kind).isEqualTo(CrashKind.ANR)
        assertThat(report.text)
            .contains("----- pid 8 at 2026-10-24 -----", "The trace was cut at 100 bytes")
            .doesNotContain("x".repeat(100))
    }

    @Test
    fun `a trace that cannot be opened still gives a report`() {
        started()

        collector().collect(listOf(CrashCollector.Exit(record(8, REASON_ANR)) { throw IOException("gone") }))

        assertThat(store.reports().single().text).contains("Android's record", "The trace could not be read: gone")
    }

    @Test
    fun `a Java crash the handler wrote gets Android's record, not a second report`() {
        started()
        store.write(CrashKind.JAVA, NOW + 3, 3, "HANDLER REPORT\n")

        collector().collect(listOf(exit(3, REASON_CRASH)))

        assertThat(store.reports().single().text).startsWith("HANDLER REPORT").contains("Android's record", "Description: crash")
    }

    @Test
    fun `a Java crash the handler could not write is reported from its record`() {
        started()

        collector().collect(listOf(exit(3, REASON_CRASH)))

        val report = store.reports().single()
        assertThat(report.kind).isEqualTo(CrashKind.JAVA)
        assertThat(report.text).contains("Android's record", "The stack trace was not captured")
    }

    @Test
    fun `each record is reported once and the newest five are kept`() {
        started()
        val exits = (1..7).map { exit(it, REASON_ANR, "trace $it".toByteArray()) }

        collector().collect(exits)
        collector().collect(exits)

        assertThat(store.reports().map { it.pid }).containsExactly(7, 6, 5, 4, 3)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
