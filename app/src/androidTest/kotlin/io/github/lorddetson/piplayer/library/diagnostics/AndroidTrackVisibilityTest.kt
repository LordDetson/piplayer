package io.github.lorddetson.piplayer.library.diagnostics

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTrackVisibilityTest {
    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver
    private val index = FakeMediaIndex()
    private lateinit var uri: Uri
    private val track = object : ExternalResource() {
        override fun before() {
            val path = "Music/PiPlayerVisibility/"
            val name = "piplayer-visibility-${UUID.randomUUID()}.mp3"
            val metadata = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), metadata))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output ->
                    TestFiles.assets.open(SHORT_FIXTURE).use { it.copyTo(output) }
                }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, published, null, null) == 1)
                index.volume().rows = listOf(row(ContentUris.parseId(uri), path = path, name = name, durationMs = 2_000))
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        override fun after() {
            resolver.delete(uri, null, null)
        }
    }

    @get:Rule
    val rules = RuleChain.outerRule(GrantAudioPermission).around(track).around(LibraryRule { index })

    @Test
    fun aReadableMediaStoreTrackUsesTheCurrentLibraryDurationAndMinimum() = runBlocking<Unit> {
        Library.sync(context).awaitIdle()
        val checker = AndroidTrackVisibility(context)

        val hidden = checker.check(uri, HiddenFiles(minLengthSeconds = 30))

        assertThat(hidden).isInstanceOf(TrackVisibilityFinding.Filtered::class.java)
        hidden as TrackVisibilityFinding.Filtered
        assertThat(hidden.filters).containsExactly(TrackVisibilityFilter.MINIMUM_LENGTH)
        assertThat(hidden.minimumSeconds).isEqualTo(30)
        assertThat(hidden.durationMs).isNotNull().isLessThan(30_000L)
        assertThat(checker.check(uri, HiddenFiles(minLengthSeconds = 0))).isEqualTo(TrackVisibilityFinding.Visible)
    }
}
