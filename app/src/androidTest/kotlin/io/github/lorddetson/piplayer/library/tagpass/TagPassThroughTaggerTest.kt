package io.github.lorddetson.piplayer.library.tagpass

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The pass over a real file through the real `:tagger`: legacy text and all (docs/library.md §5). */
@RunWith(AndroidJUnit4::class)
class TagPassThroughTaggerTest {

    private val context = TestFiles.targetContext
    private val opened = TaggerTesting.freshDatabase("tag-pass-test.db")
    private val database = opened.first
    private val client = TaggerTesting.client(database, 30.seconds)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val file = File(context.cacheDir, "tag-pass-legacy.mp3")

    @After
    fun close() {
        client.close()
        sync.close()
        database.close()
        file.delete()
    }

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
    fun aMissingFileIsACannotOpen() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "gone.mp3"))
        sync.sync()

        val reader = TaggerPassReader(context, client, uriOf = { Uri.fromFile(file) })
        val result = TagPass(database, sync, reader, cyrillicLanguage = { true }).run()

        assertThat(result.failed).isEqualTo(1)
        assertThat(database.library().files().single().tagFailure).isEqualTo("open")
    }
}
