package io.github.lorddetson.piplayer.crash

import java.io.File
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CrashCaptureTest {

    @TempDir
    lateinit var dir: File

    private val handedOn = mutableListOf<Pair<Thread, Throwable>>()
    private val android = Thread.UncaughtExceptionHandler { thread, error -> handedOn += thread to error }
    private var kills = 0

    private fun capture(store: ReportStore = ReportStore(dir), previous: Thread.UncaughtExceptionHandler? = android) =
        CrashCapture(store, "HEADER\n", "io.github.lorddetson.piplayer", 1234, previous, { kills++ }, now = { TIME })

    @Test
    fun `a crash is written in full, then handed on once`() {
        val thread = Thread("worker")
        val error = IllegalStateException("boom", IOException("disk full"))

        capture().uncaughtException(thread, error)

        val report = ReportStore(dir).reports().single()
        assertThat(report.kind).isEqualTo(CrashKind.JAVA)
        assertThat(report.pid).isEqualTo(1234)
        assertThat(report.timeMs).isEqualTo(TIME)
        assertThat(report.text).contains("HEADER", "worker", "IllegalStateException: boom", "Caused by: java.io.IOException: disk full")
        assertThat(handedOn).containsExactly(thread to error)
        assertThat(dir.listFiles()!!.map { it.name }).noneMatch { it.endsWith(".tmp") }
        assertThat(kills).isZero()
    }

    @Test
    fun `a crash that cannot be written is still handed on`() {
        val notADirectory = File(dir, "file").apply { writeText("x") }
        val thread = Thread("main")
        val error = RuntimeException("boom")

        capture(ReportStore(notADirectory)).uncaughtException(thread, error)

        assertThat(handedOn).containsExactly(thread to error)
    }

    @Test
    fun `without a previous handler the process is ended`() {
        capture(previous = null).uncaughtException(Thread("main"), RuntimeException("boom"))

        assertThat(kills).isEqualTo(1)
        assertThat(ReportStore(dir).reports()).hasSize(1)
    }

    @Test
    fun `a second crash while the first is handled is handed on without a report`() {
        val capture = capture()

        capture.uncaughtException(Thread("one"), RuntimeException("boom one"))
        capture.uncaughtException(Thread("two"), RuntimeException("boom two"))

        assertThat(ReportStore(dir).reports().single().text).contains("boom one").doesNotContain("boom two")
        assertThat(handedOn).hasSize(2)
    }

    private companion object {
        const val TIME = 1_700_000_000_000L
    }
}
