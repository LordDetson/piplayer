package io.github.lorddetson.piplayer.crash

import android.app.ApplicationExitInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrashTextTest {

    private val info = DeviceInfo(
        appVersion = "0.1.0 (1)",
        buildType = "debug",
        manufacturer = "Google",
        model = "Pixel 8",
        device = "shiba",
        release = "16",
        sdk = 36,
        fingerprint = "google/shiba/shiba:16/BP2A/123:user/release-keys",
        abis = listOf("arm64-v8a"),
    )

    private val native = ExitRecord(
        pid = 4321,
        processName = "io.github.lorddetson.piplayer",
        reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
        status = 6,
        description = "crash",
        importance = 100,
        timestampMs = TIME,
    )

    @Test
    fun `the header names the app, the device and Android`() {
        val header = CrashText.header(info)

        assertThat(header).contains(
            "PiPlayer 0.1.0 (1), debug",
            "Device: Google Pixel 8 (shiba)",
            "Android 16 (API 36)",
            "google/shiba/shiba:16/BP2A/123:user/release-keys",
            "arm64-v8a",
        )
    }

    @Test
    fun `a Java crash names the process, the thread and the time, then the whole stack`() {
        val error = IllegalStateException("boom", IllegalArgumentException("cause"))
        error.addSuppressed(RuntimeException("suppressed"))

        val text = CrashText.java("HEADER\n", "io.github.lorddetson.piplayer", 1234, "worker", TIME, error)

        assertThat(text).startsWith("HEADER\n")
        assertThat(text).contains(
            "Crash",
            "Process: io.github.lorddetson.piplayer (pid 1234)",
            "Thread: worker",
            "2023-11-14T22:13:20Z",
            "java.lang.IllegalStateException: boom",
            "Caused by: java.lang.IllegalArgumentException: cause",
            "Suppressed: java.lang.RuntimeException: suppressed",
        )
    }

    @Test
    fun `a report from a record carries the record and the trace`() {
        val text = CrashText.exit("HEADER\n", native, CrashKind.NATIVE, "signal 6 (SIGABRT)")

        assertThat(text).contains("Native crash", "pid 4321", "2023-11-14T22:13:20Z", "Status: 6", "Description: crash", "signal 6 (SIGABRT)")
    }

    @Test
    fun `a native crash without a tombstone says why`() {
        val text = CrashText.exit("HEADER\n", native, CrashKind.NATIVE, null)

        assertThat(text).contains("Native crash", "No tombstone")
    }

    @Test
    fun `Android's record can be added to a Java report`() {
        val record = native.copy(reason = ApplicationExitInfo.REASON_CRASH, status = 0)

        assertThat(CrashText.recordBlock(record)).contains("Android's record", "Status: 0", "Description: crash")
    }

    @Test
    fun `several reports become one text in their order`() {
        val text = CrashText.combine(listOf("newest report", "older report"))

        assertThat(text.indexOf("newest report")).isGreaterThanOrEqualTo(0).isLessThan(text.indexOf("older report"))
    }

    @Test
    fun `shared text is cut at a line and says so`() {
        val short = "line one\nline two\n"
        val long = (1..5_000).joinToString("\n") { "line $it of a long trace" }

        assertThat(CrashText.shareText(short)).isEqualTo(short)
        val cut = CrashText.shareText(long, maxChars = 1_000)
        assertThat(cut.length).isLessThanOrEqualTo(1_200)
        assertThat(cut).startsWith("line 1 of a long trace\n").contains("the full report is in the attached file")
        assertThat(cut.lines().dropLast(2).last()).matches("line \\d+ of a long trace")
    }

    @Test
    fun `a cut never splits a character`() {
        val emoji = "🎵".repeat(600)

        val cut = CrashText.shareText(emoji, maxChars = 101)

        assertThat(cut.substringBefore("\n")).doesNotEndWith("\uD83C")
        assertThat(cut.substringBefore("\n").length % 2).isZero()
    }

    private companion object {
        const val TIME = 1_700_000_000_000L
    }
}
