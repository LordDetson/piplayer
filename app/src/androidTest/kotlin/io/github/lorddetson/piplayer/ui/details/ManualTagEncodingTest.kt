package io.github.lorddetson.piplayer.ui.details

import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ManualTagEncodingTest {
    private val index = FakeMediaIndex()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    @Test
    fun choosingTheTracksEncodingDecodesItsTagWithoutWritingTheFile() = runBlocking<Unit> {
        val app = TestFiles.targetContext
        val settings = LibrarySettings(app)
        val global = settings.readLegacyEncoding()
        val koi8 = LegacyEncodings.ALL.single { it.name == "koi8-r" }
        try {
            settings.setLegacyEncoding(koi8)
            withTrack("tagging/legacy-id3v1.mp3") { id, checkBytes ->
                compose.setContent { PiPlayerTheme { ManualTagEditorScreen(id, onBack = {}) } }
                compose.waitUntilExactlyOneExists(hasTestTag("manual-tag-TITLE"), timeoutMillis = 10_000)
                val label = app.getString(R.string.settings_legacy_encoding)
                compose.waitUntilExactlyOneExists(hasText(label), timeoutMillis = 5_000)
                compose.onNodeWithText(label).performClick()
                compose.onNodeWithText(app.getString(R.string.legacy_script_cyrillic, "Windows-1251")).performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag("manual-tag-TITLE") and hasText("Кино"), timeoutMillis = 10_000)
                compose.onNodeWithTag("manual-tag-TITLE").assertTextContains("Кино")
                assertThat(settings.readLegacyEncoding()).isEqualTo(koi8)
                checkBytes()
            }
        } finally {
            settings.setLegacyEncoding(global)
        }
    }

    @Test
    fun savesTheTitleAndGenreTogetherInPiPlayerAndUndoesBothWithoutWritingTags() = runBlocking<Unit> {
        val app = TestFiles.targetContext
        val original = "Ёжик в тумане"
        val title = "Local title"
        val genre = "Ambient test"
        withTrack("playback/tagged-44100-stereo.mp3", original) { id, checkBytes ->
            compose.setContent { PiPlayerTheme { ManualTagEditorScreen(id, onBack = {}) } }
            compose.waitUntilExactlyOneExists(hasTestTag("manual-tag-TITLE"), timeoutMillis = 10_000)
            compose.onNodeWithTag("manual-tag-TITLE").performTextReplacement(title)
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("manual-tag-GENRE"))
            compose.onNodeWithTag("manual-tag-GENRE").performTextReplacement(genre)
            compose.onNodeWithText(app.getString(R.string.manual_tag_review)).performClick()
            compose.onNodeWithTag("manual-tag-save-in-player").performClick()
            compose.waitUntilExactlyOneExists(hasText(app.getString(R.string.manual_tag_saved_in_player)), timeoutMillis = 10_000)
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("manual-tag-GENRE"))
            compose.onNodeWithTag("manual-tag-GENRE").assertTextContains(genre)
            checkBytes()

            fun displayed(title: String, genres: List<String>) = runBlocking {
                val dao = Library.database(app).library()
                dao.displays().single { it.fileId == id }.title == title && dao.displayGenres(id).map { it.name } == genres
            }
            compose.waitUntil(10_000) { displayed(title, listOf(genre)) }
            Library.reopen(app, index, empty = false)
            LibraryRule.startAsANewProcess()
            compose.waitUntil(10_000) { displayed(title, listOf(genre)) }
            compose.onNodeWithText(app.getString(R.string.review_undo)).performClick()
            compose.waitUntil(10_000) { displayed(original, emptyList()) }
            checkBytes()
        }
    }

    private suspend fun withTrack(
        asset: String,
        indexTitle: String = "Index title",
        check: suspend (Long, checkBytes: () -> Unit) -> Unit,
    ) {
        val app = TestFiles.targetContext
        val resolver = app.contentResolver
        val name = "piplayer-manual-tags-${System.nanoTime()}.mp3"
        val path = "Music/"
        val bytes = TestFiles.assets.open(asset).use { it.readBytes() }
        val uri = checkNotNull(resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, path)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val mediaId = ContentUris.parseId(uri)
            index.volume().rows = listOf(FakeMediaIndex.row(mediaId, title = indexTitle, path = path, name = name))
            Library.sync(app).request()
            Library.sync(app).awaitIdle()
            val dao = Library.database(app).library()
            val file = dao.files().single { it.mediaStoreId == mediaId }
            val report = checkNotNull(resolver.openFileDescriptor(uri, "r")).use { descriptor ->
                (Tagger.client(app).read(TagTarget.LibraryFile(file.id), descriptor, name) as TaggerResult.Replied).value.report
            }
            val key = checkNotNull(report.content).key
            dao.setContent(file.id, key, 60_000, DurationSource.HEADER)
            UserData.database(app).identities().add(
                TrackIdentityEntity(
                    contentKey = Hex.bytes(key), durationMs = null, recordingMbid = null,
                    hintVolumeName = file.volumeName, hintRelativePath = path, hintDisplayName = name,
                    hintSize = null, createdAt = 1, missingSince = null,
                ),
            )

            check(file.id) {
                assertThat(checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }).isEqualTo(bytes)
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
