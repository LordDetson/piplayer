package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleTagWriteRecoveryTest {
    @Test
    fun aManualWriteWaitingForPlaybackKeepsItsPreparedCopyAfterRestart() = verifyWrite(simulateLibraryFailure = false)

    @Test
    fun aVerifiedWriteRefreshesTheLibraryAfterReopeningWithoutAnotherWrite() = verifyWrite(simulateLibraryFailure = true)

    private fun verifyWrite(simulateLibraryFailure: Boolean) = runBlocking<Unit> {
        val context = TestFiles.targetContext
        val resolver = context.contentResolver
        val run = "piplayer-manual-recovery-${System.nanoTime()}"
        val name = "$run.mp3"
        val userDbName = "$run.db"
        val staging = File(context.cacheDir, run)
        val title = "Resumed manual write"
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val library = LibraryDatabase.open(context, file = null)
        val index = FakeMediaIndex()
        val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
        var user = UserDatabase.open(context, userDbName)
        val client = TaggerTesting.client(library, TagReadGuard.DEFAULT_TIMEOUT)
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                TestFiles.assets.open("playback/tagged-44100-stereo.mp3").use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            index.volume().rows = listOf(FakeMediaIndex.row(ContentUris.parseId(uri)).copy(displayName = name))
            sync.sync()
            val file = library.library().files().single()
            suspend fun report() = checkNotNull(resolver.openFileDescriptor(uri, "r")).use {
                (client.read(TagTarget.LibraryFile(file.id), it, name) as TaggerResult.Replied).value.report
            }
            val before = report()
            val key = checkNotNull(before.content).key
            library.library().setContent(file.id, key, 200_000L, DurationSource.HEADER)
            val identity = user.identities().add(TrackIdentityEntity(
                contentKey = Hex.bytes(key), durationMs = null, recordingMbid = null,
                hintVolumeName = file.volumeName, hintRelativePath = file.relativePath, hintDisplayName = name,
                hintSize = null, createdAt = 1, missingSince = null,
            ))
            val writer = BatchTagWriter(context, LibraryAccess.of(library), user, client, staging)
            val plan = requireNotNull(writer.manualPlan(file.id))
            playbackTagWriteGate.reservePlayback(current = uri.toString(), next = null)
            val writing = async { writer.applyManual(plan, mapOf("TITLE" to listOf(title))) }
            val pending = try {
                withTimeout(10_000) {
                    while (writing.isActive && !writer.isWaitingForPlayback(file.id)) delay(10)
                }
                assertThat(writer.isWaitingForPlayback(file.id)).isTrue()
                assertThat(writer.recoverableWrites(identity)).isEmpty()
                assertThat(report().properties).isEqualTo(before.properties)
                val rows = TagWriteJournal(user).interrupted()
                assertThat(rows).hasSize(1)
                rows.single()
            } finally {
                writing.cancelAndJoin()
                playbackTagWriteGate.reservePlayback(current = null, next = null)
            }
            assertThat(pending.identityId).isEqualTo(identity)
            assertThat(pending.state).isEqualTo("pending")
            val copy = File(staging, "copies/${pending.copyPath}")
            assertThat(copy.isFile).isTrue()
            assertThat(sha256(copy)).isEqualTo(pending.copySha256)
            user.close()
            user = UserDatabase.open(context, userDbName)
            assertThat(TagWriteJournal(user).interrupted().map { it.id }).containsExactly(pending.id)
            assertThat(report().properties).isEqualTo(before.properties)
            val libraryAccess = object : LibraryAccess {
                override fun <T> use(block: (LibraryDatabase) -> T): T {
                    if (simulateLibraryFailure && runBlocking { TagWriteJournal(user).pendingLibraryRefresh().isNotEmpty() }) {
                        throw IOException("Library transaction interrupted after verification")
                    }
                    return block(library)
                }
            }
            val restarted = BatchTagWriter(context, libraryAccess, user, client, staging)
            val recovery = restarted.recoverableWrites(identity).single()
            assertThat(recovery.id).isEqualTo(pending.id)
            assertThat(recovery.target.uri).isEqualTo(uri)
            assertThat(restarted.resume(recovery, consent = false)).isEqualTo(WriteBackResult.NEEDS_CONSENT)
            assertThat(report().properties).isEqualTo(before.properties)
            assertThat(copy.isFile).isTrue()
            assertThat(restarted.resume(recovery, consent = true)).isEqualTo(WriteBackResult.VERIFIED)
            assertThat(report().properties["TITLE"]).containsExactly(title)
            assertThat(report().content?.key).isEqualTo(key)
            assertThat(restarted.recoverableWrites(identity)).isEmpty()
            if (simulateLibraryFailure) {
                assertThat(library.library().songRowsOf(listOf(file.id)).single().title).isNotEqualTo(title)
                assertThat(TagWriteJournal(user).pendingLibraryRefresh()).containsExactly(pending.id)
                val bytes = checkNotNull(resolver.openInputStream(uri)).use { sha256(it) }
                user.close()
                user = UserDatabase.open(context, userDbName)
                BatchTagWriter(context, LibraryAccess.of(library), user, client, staging).refreshVerifiedLibrary()
                assertThat(checkNotNull(resolver.openInputStream(uri)).use { sha256(it) }).isEqualTo(bytes)
            }
            assertThat(library.library().songRowsOf(listOf(file.id)).single().title)
                .describedAs("library title before the MediaStore observer runs").isEqualTo(title)
            assertThat(TagWriteJournal(user).pendingLibraryRefresh()).isEmpty()
            assertThat(copy.exists()).isFalse()
        } finally {
            client.close()
            sync.close()
            library.close()
            user.close()
            resolver.delete(uri, null, null)
            staging.deleteRecursively()
            context.deleteDatabase(userDbName)
        }
    }
}
