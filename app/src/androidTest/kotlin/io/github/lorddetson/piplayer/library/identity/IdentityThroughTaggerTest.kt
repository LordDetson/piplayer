package io.github.lorddetson.piplayer.library.identity

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.tagpass.TaggerPassReader
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Identity through the real `:tagger` (TI §11.2): keys come from the files themselves. */
@RunWith(AndroidJUnit4::class)
class IdentityThroughTaggerTest {

    private val context = TestFiles.targetContext
    private val opened = TaggerTesting.freshDatabase("identity-tagger-test.db")
    private val library = opened.first
    private val user = UserDatabase.open(context, name = null)
    private val client = TaggerTesting.client(library, 30.seconds)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private val attacher = IdentityAttacher(library, user, sync)

    /** The file each index ID stands for. */
    private val files = HashMap<Long, File>()

    @After
    fun close() {
        attacher.close()
        client.close()
        sync.close()
        library.close()
        user.close()
        files.values.forEach { it.delete() }
    }

    private suspend fun pass() {
        val reader = TaggerPassReader(context, client, uriOf = { Uri.fromFile(files.getValue(it.mediaStoreId)) })
        TagPass(library, sync, reader, cyrillicLanguage = { false }).run()
    }

    @Test
    fun aMovedOggFileWhoseCommentsAnotherToolRewroteKeepsItsKeyAndIdentity() = runBlocking<Unit> {
        files[1] = TestFiles.copy("contentkey/long.ogg")
        index.volume().rows = listOf(row(1, path = "Music/A/", name = "long.ogg").copy(size = files.getValue(1).length()))
        sync.sync()
        pass()
        attacher.attach()
        val before = user.identities().identities().single()

        // Moved by a file manager (a new index row) after a tagger rewrote the comments across pages.
        files[2] = TestFiles.copy("contentkey/long-comments-long.ogg")
        index.volume().rows = listOf(
            row(2, path = "Music/B/", name = "renamed.ogg", generation = 10).copy(size = files.getValue(2).length()),
        )
        sync.sync()
        pass()
        attacher.attach()

        val after = user.identities().identities().single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.contentKey).isEqualTo(before.contentKey)
        assertThat(after.hintRelativePath).isEqualTo("Music/B/")
        assertThat(after.hintDisplayName).isEqualTo("renamed.ogg")
    }
}
