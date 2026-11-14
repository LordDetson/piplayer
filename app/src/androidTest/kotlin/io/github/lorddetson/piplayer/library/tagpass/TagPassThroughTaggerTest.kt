package io.github.lorddetson.piplayer.library.tagpass

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pass over real files through the real `:tagger`: legacy text and all (docs/library.md §5). The legacy-encoding
 * fixtures read in the automatic mode and with their own code page, and a wrong choice is undone (TI §3.6, §11.2).
 */
@RunWith(AndroidJUnit4::class)
class TagPassThroughTaggerTest {

    private val context = TestFiles.targetContext
    private val opened = TaggerTesting.freshDatabase("tag-pass-test.db")
    private val database = opened.first
    private val client = TaggerTesting.client(database, 30.seconds)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val file = File(context.cacheDir, "tag-pass-legacy.mp3")
    private val fixtures = mutableListOf<File>()
    private var manual: LegacyEncoding? = null
    private var cyrillic = true
    private val reads = AtomicInteger()

    @After
    fun close() {
        client.close()
        sync.close()
        database.close()
        file.delete()
        fixtures.forEach { it.delete() }
    }

    private fun encoding(name: String) = LegacyEncodings.ALL.single { it.name == name }

    /** A library of [names], fixtures of the test APK, each read from its own copy. */
    private suspend fun library(vararg names: String) {
        val files = names.associateWith { name ->
            File(context.cacheDir, "legacy-test-$name").also { copy ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("tagging/$name").use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
                fixtures += copy
            }
        }
        index.volume().rows = names.mapIndexed { i, name -> row(i + 1L, title = "Index title", name = name) }
        sync.sync()
        reader = TaggerPassReader(context, client, uriOf = { Uri.fromFile(files.getValue(it.displayName)) })
    }

    private var reader: PassReader? = null

    private fun pass() = TagPass(
        database,
        sync,
        { file -> reads.incrementAndGet(); reader!!.read(file) },
        cyrillicLanguage = { cyrillic },
        legacyEncoding = { manual },
    )

    private suspend fun display(name: String) = database.library().let { dao ->
        val id = dao.files().single { it.displayName == name }.id
        dao.displays().single { it.fileId == id }
    }

    private suspend fun encodingOf(name: String) = database.library().files().single { it.displayName == name }.tagEncoding

    @Test
    fun aLegacyFileReachesTheListsDecoded() = runBlocking<Unit> {
        InstrumentationRegistry.getInstrumentation().context.assets.open("tagging/legacy-id3v1.mp3").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        index.volume().rows = listOf(row(1, title = "Index title", name = "legacy.mp3"))
        sync.sync()

        val reader = TaggerPassReader(context, client, uriOf = { Uri.fromFile(file) })
        val result = TagPass(database, sync, reader, cyrillicLanguage = { true }).run()

        assertThat(result.read).isEqualTo(1)
        val dao = database.library()
        with(dao.displays().single()) {
            assertThat(title).isEqualTo("Кино")
            assertThat(artist).isEqualTo("Цой")
            assertThat(album).isEqualTo("Чёрный альбом")
        }
        with(dao.files().single()) {
            assertThat(tagEncoding).isEqualTo(LegacyText.WINDOWS_1251)
            assertThat(format).isEqualTo("mpeg")
            assertThat(codec).isEqualTo("mp3")
            assertThat(sampleRate).isEqualTo(44_100)
        }
    }

    @Test
    fun theWindows1251FixturesReadInTheAutomaticModeAndWithTheirOwnCodePage() = runBlocking<Unit> {
        library("legacy-id3v1.mp3", "legacy-id3v23.mp3", "legacy-riff.wav")

        pass().run()
        assertWindows1251FixturesReadable("automatic")

        // A phone without a Cyrillic language reads them only with the choice.
        cyrillic = false
        manual = encoding("windows-1251")
        database.library().forgetReaderVersions()
        pass().run()
        assertWindows1251FixturesReadable("manual")
        assertThat(reads.get()).isEqualTo(6)
    }

    private suspend fun assertWindows1251FixturesReadable(mode: String) {
        with(display("legacy-id3v1.mp3")) {
            assertThat(title).describedAs("$mode ID3v1").isEqualTo("Кино")
            assertThat(artist).describedAs("$mode ID3v1").isEqualTo("Цой")
            assertThat(album).describedAs("$mode ID3v1").isEqualTo("Чёрный альбом")
        }
        with(display("legacy-id3v23.mp3")) {
            assertThat(title).describedAs("$mode ID3v2").isEqualTo("Группа крови")
            assertThat(artist).describedAs("$mode ID3v2 in UTF-16").isEqualTo("Кино")
            assertThat(album).describedAs("$mode ID3v2").isEqualTo("Ёж")
        }
        with(display("legacy-riff.wav")) {
            assertThat(title).describedAs("$mode RIFF INFO").isEqualTo("Кино")
            assertThat(artist).describedAs("$mode RIFF INFO").isEqualTo("Цой")
        }
        for (name in listOf("legacy-id3v1.mp3", "legacy-id3v23.mp3", "legacy-riff.wav")) {
            assertThat(encodingOf(name)).describedAs("$mode $name").isEqualTo(LegacyText.WINDOWS_1251)
        }
    }

    @Test
    fun aKoi8rFixtureNeedsTheManualChoice() = runBlocking<Unit> {
        library("legacy-koi8r-id3v23.mp3")

        pass().run()
        assertThat(encodingOf("legacy-koi8r-id3v23.mp3")).isEqualTo(LegacyText.WINDOWS_1251)
        assertThat(display("legacy-koi8r-id3v23.mp3").title).isNotEqualTo("Звезда по имени Солнце")

        manual = encoding("koi8-r")
        redecode()

        with(display("legacy-koi8r-id3v23.mp3")) {
            assertThat(title).isEqualTo("Звезда по имени Солнце")
            assertThat(artist).isEqualTo("Кино")
            assertThat(album).isEqualTo("Звезда")
        }
        assertThat(encodingOf("legacy-koi8r-id3v23.mp3")).isEqualTo("koi8-r")
        assertThat(reads.get()).isEqualTo(1)
    }

    @Test
    fun aShiftJisFixtureReadsWithTheManualChoice() = runBlocking<Unit> {
        library("legacy-shift-jis-id3v1.mp3")
        manual = encoding("shift_jis")

        pass().run()

        with(display("legacy-shift-jis-id3v1.mp3")) {
            assertThat(title).isEqualTo("東京事変")
            assertThat(artist).isEqualTo("椎名林檎")
            assertThat(album).isEqualTo("教育")
        }
        assertThat(encodingOf("legacy-shift-jis-id3v1.mp3")).isEqualTo("shift_jis")
    }

    @Test
    fun aWrongManualChoiceIsVisibleAndAutomaticBringsTheTextBackWithoutARead() = runBlocking<Unit> {
        library("legacy-id3v1.mp3")
        pass().run()
        assertThat(display("legacy-id3v1.mp3").title).isEqualTo("Кино")

        manual = encoding("shift_jis")
        redecode()
        val wrong = display("legacy-id3v1.mp3").title
        assertThat(wrong).describedAs("the wrong choice shows").isNotEqualTo("Кино").isNotBlank()

        manual = null
        redecode()
        with(display("legacy-id3v1.mp3")) {
            assertThat(title).isEqualTo("Кино")
            assertThat(artist).isEqualTo("Цой")
            assertThat(album).isEqualTo("Чёрный альбом")
        }
        assertThat(encodingOf("legacy-id3v1.mp3")).isEqualTo(LegacyText.WINDOWS_1251)
        assertThat(reads.get()).describedAs("files read").isEqualTo(1)
    }

    private suspend fun redecode() {
        val pass = pass()
        pass.askRedecode()
        pass.run()
    }

    @Test
    fun aMissingFileIsACannotOpen() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "gone.mp3"))
        sync.sync()

        val reader = TaggerPassReader(context, client, uriOf = { Uri.fromFile(file) })
        val result = TagPass(database, sync, reader, cyrillicLanguage = { true }).run()

        assertThat(result.failed).isEqualTo(1)
        assertThat(database.library().files().single().tagFailure).isEqualTo("open")
    }
}
