package io.github.lorddetson.piplayer.library.tagpass

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.settings.DecodedLegacyEncoding
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A choice made in the settings decodes the open library again at once, through the real library and its tag pass
 * (docs/library.md §5.7), not only at some later run. The pass reads a fake reader over a fake media index.
 */
@RunWith(AndroidJUnit4::class)
class LegacyEncodingLibraryTest {

    @get:Rule
    val permission = GrantAudioPermission

    private val context = TestFiles.targetContext
    private val settings = LibrarySettings(context)
    private val index = FakeMediaIndex().apply { volume().rows = listOf(row(1, title = "Index title", name = "1.mp3")) }
    private val reads = AtomicInteger()

    /** "Кино" in Windows-1251 as `:tagger` hands over legacy text: each byte above ASCII marked. */
    private val kino = buildString {
        for (byte in "Кино".toByteArray(Charset.forName("windows-1251"))) append((LegacyText.MARK_BASE + (byte.toInt() and 0xFF)).toChar())
    }

    @Before
    fun startWithTheTagPass() = runBlocking<Unit> {
        automatic()
        LibraryRule.cleanUserData()
        Library.reopen(context, index, empty = true)
        SongList.forget()
        Library.passReaderForTests = PassReader {
            reads.incrementAndGet()
            PassRead.Replied(TagReport(properties = mapOf("TITLE" to listOf(kino))))
        }
        Library.passGate.setUiVisible(true)
        Library.tagPassEnabled = true
        LibraryRule.startAsANewProcess()
    }

    @After
    fun stopTheTagPass() = runBlocking<Unit> {
        // Before the library starts again, so that no pass reads the device's library.
        Library.tagPassEnabled = false
        Library.passReaderForTests = null
        Library.passGate.setUiVisible(false)
        LibraryRule.cleanUserData()
        Library.reopen(context, empty = true)
        automatic()
        SongList.forget()
        LibraryRule.startAsANewProcess()
    }

    private suspend fun automatic() {
        settings.setLegacyEncoding(null)
        DecodedLegacyEncoding(context).write(null)
    }

    private val dao get() = Library.database(context).library()

    /** Waits for a batch of the pass after which [done] holds; false after the timeout. */
    private suspend fun afterABatch(done: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(30.seconds) { SongList.batches.first { done() } } != null

    @Test
    fun aChoiceMadeInTheSettingsDecodesTheLibraryAgainWithoutARead() = runBlocking<Unit> {
        assertThat(afterABatch { dao.files().singleOrNull()?.tagPassAt != null }).describedAs("the pass read the file").isTrue()
        assertThat(dao.files().single().tagEncoding).describedAs("read in the automatic mode").isNotNull().isNotEqualTo("koi8-r")

        settings.setLegacyEncoding(LegacyEncodings.ALL.single { it.name == "koi8-r" })

        assertThat(afterABatch { dao.files().single().tagEncoding == "koi8-r" }).describedAs("decoded again after the choice").isTrue()
        val fileId = dao.files().single().id
        val expected = String("Кино".toByteArray(Charset.forName("windows-1251")), Charset.forName("KOI8-R"))
        assertThat(dao.displays().single { it.fileId == fileId }.title).isEqualTo(expected)
        assertThat(reads.get()).describedAs("files read").isEqualTo(1)
    }
}
