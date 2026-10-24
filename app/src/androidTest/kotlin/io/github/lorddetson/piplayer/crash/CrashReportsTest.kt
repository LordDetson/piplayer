package io.github.lorddetson.piplayer.crash

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The handler, the offer of new reports and sharing them (docs/playback.md §7.1, §7.3). */
@RunWith(AndroidJUnit4::class)
class CrashReportsTest {

    private val context = TestFiles.targetContext
    private val dir = File(context.cacheDir, "crash-reports-test")

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun reports() = CrashReports(ReportStore(dir), "HEADER\n", exits = { emptyList() })

    private fun Intent.target(): Intent = IntentCompat.getParcelableExtra(this, Intent.EXTRA_INTENT, Intent::class.java)!!

    private fun Intent.stream(): Uri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)!!

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }

    @Test
    fun theHandlerIsInstalledInFrontOfAndroidsOwn() {
        val handler = CrashReports.handler

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(handler)
        // The test runner puts its handler in front of Android's before the app starts; in the app ours comes next.
        assertThat(handler!!.previous?.javaClass?.name).matches(".*KillApplicationHandler|androidx\\.test\\.runner\\..*")
    }

    @Test
    fun newReportsAreOfferedOnceNewestFirst() {
        val store = ReportStore(dir)
        store.write(CrashKind.JAVA, 1_000, 11, "older report")
        store.write(CrashKind.NATIVE, 2_000, 12, "newer report")
        val first = reports()

        first.refresh()

        val pending = first.pending.value!!
        assertThat(pending.reports.map { it.pid }).containsExactly(12, 11)
        assertThat(pending.text.indexOf("newer report")).isGreaterThanOrEqualTo(0).isLessThan(pending.text.indexOf("older report"))

        first.markShown(pending)
        val next = reports()
        next.refresh()

        assertThat(next.pending.value).isNull()
    }

    @Test
    fun closingTakesTheOfferBack() {
        ReportStore(dir).write(CrashKind.ANR, 1_000, 11, "report")
        val reports = reports().apply { refresh() }

        reports.close()

        assertThat(reports.pending.value).isNull()
    }

    @Test
    fun aSharedReportIsTextAndAReadableFile() {
        val text = "PiPlayer 0.1.0 (1), debug\nCrash\n"

        val chooser = CrashShare.intent(context, text)

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.target()
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(text)
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotZero
        val uri = send.stream()
        assertThat(uri.authority).isEqualTo(context.packageName + ".crashreports")
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
        assertThat(read(uri)).isEqualTo(text)
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertThat(it.moveToFirst()).isTrue
            assertThat(it.getString(0)).isEqualTo("piplayer-crash-report.txt")
        }
    }

    @Test
    fun aLongReportIsCutInTheTextButWholeInTheFile() {
        val text = (1..5_000).joinToString("\n") { "line $it of a long trace" }

        val send = CrashShare.intent(context, text).target()

        val shared = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertThat(shared.length).isLessThanOrEqualTo(CrashText.SHARE_TEXT_CHARS + 200)
        assertThat(shared).contains("the full report is in the attached file")
        assertThat(read(send.stream())).isEqualTo(text)
    }
}
