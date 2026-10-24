package io.github.lorddetson.piplayer.crash

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReportStoreTest {

    @TempDir
    lateinit var dir: File

    private val store by lazy { ReportStore(File(dir, "crashes")) }

    @Test
    fun `reports are listed newest first with their kind, time and process`() {
        store.write(CrashKind.JAVA, timeMs = 1_000, pid = 11, text = "first")
        store.write(CrashKind.ANR, timeMs = 3_000, pid = 33, text = "third")
        store.write(CrashKind.NATIVE, timeMs = 2_000, pid = 22, text = "second")

        val reports = store.reports()

        assertThat(reports.map { it.text }).containsExactly("third", "second", "first")
        assertThat(reports.map { Triple(it.kind, it.timeMs, it.pid) })
            .containsExactly(Triple(CrashKind.ANR, 3_000L, 33), Triple(CrashKind.NATIVE, 2_000L, 22), Triple(CrashKind.JAVA, 1_000L, 11))
    }

    @Test
    fun `only the newest five are kept`() {
        (1..7).forEach { store.write(CrashKind.JAVA, timeMs = it * 1_000L, pid = it, text = "report $it") }
        store.markShown(store.reports())

        store.prune()

        assertThat(store.reports().map { it.text }).containsExactly("report 7", "report 6", "report 5", "report 4", "report 3")
        assertThat(store.dir.listFiles()!!.map { it.name }).noneMatch { it.contains("-1-") || it.contains("-2-") }
    }

    @Test
    fun `reports are offered once`() {
        store.write(CrashKind.JAVA, timeMs = 1_000, pid = 1, text = "old")
        store.markShown(store.unshown())
        store.write(CrashKind.ANR, timeMs = 2_000, pid = 2, text = "new")

        assertThat(store.unshown().map { it.text }).containsExactly("new")
        store.markShown(store.unshown())
        assertThat(store.unshown()).isEmpty()
        assertThat(store.reports()).hasSize(2)
    }

    @Test
    fun `a report can be rewritten`() {
        store.write(CrashKind.JAVA, timeMs = 1_000, pid = 1, text = "stack")

        store.rewrite(store.reports().single(), "stack\nrecord")

        assertThat(store.reports().single().text).isEqualTo("stack\nrecord")
    }

    @Test
    fun `half-written files are not reports`() {
        store.write(CrashKind.JAVA, timeMs = 1_000, pid = 1, text = "whole")
        File(store.dir, ".2000-2-java.txt.tmp").writeText("half")

        assertThat(store.reports().map { it.text }).containsExactly("whole")
    }

    @Test
    fun `the state of seen records is kept`() {
        assertThat(store.readState()).isNull()

        store.writeState(ExitState(floorMs = 5_000, seen = setOf("1:100", "2:200")))
        store.writeState(ExitState(floorMs = 5_000, seen = setOf("3:300")))

        assertThat(ReportStore(store.dir).readState()).isEqualTo(ExitState(floorMs = 5_000, seen = setOf("3:300")))
    }
}
