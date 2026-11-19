package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.BatchFileEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.userdata.DecisionCodec
import io.github.lorddetson.piplayer.userdata.DecisionEntity
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.TrackMetadataEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The host stops the app after the start phase logs progress, then runs the resume phase with the same run ID. */
@RunWith(AndroidJUnit4::class)
class BatchTagRecoveryTest {
    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver

    @Test
    fun fiveHundredMediaStoreWritesResumeAfterProcessDeath() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("batchRecoveryPhase")
        assumeTrue(phase == "start" || phase == "resume")
        val run = requireNotNull(arguments.getString("batchRecoveryRun"))
        require(run.matches(Regex("[a-z0-9]{8,32}")))
        val batch = UUID.nameUUIDFromBytes(run.toByteArray()).toString()
        val libraryName = "write-recovery-$run-library.db"
        val userName = "write-recovery-$run-user.db"
        val library = LibraryDatabase.open(context, context.getDatabasePath(libraryName))
        val user = UserDatabase.open(context, userName)
        val client = TaggerTesting.client(library, TagReadGuard.DEFAULT_TIMEOUT)
        val staging = File(context.cacheDir, "write-recovery-$run")
        val manifest = File(context.cacheDir, "write-recovery-$run.uris")
        val writer = BatchTagWriter(context, LibraryAccess.of(library), user, client, staging)
        var cleanup = false
        try {
            if (phase == "start") {
                check(manifest.createNewFile()) { "This test run already has a fixture manifest" }
                cleanup = true
                seed(run, batch, manifest, library, user, client)
                val group = writer.nextGroup(batch)
                assertThat(group.files).hasSize(FILE_COUNT)
                val applying = async { writer.apply(group) }
                while (applying.isActive) {
                    val written = writer.results(batch)["written"] ?: 0
                    if (written >= CHECKPOINT) {
                        Log.i(LOG_TAG, "checkpoint $run $written")
                        break
                    }
                    delay(40)
                }
                applying.await()
                error("The host must stop the app before the first apply finishes")
            }

            check(manifest.isFile) { "Run the start phase before resuming" }
            cleanup = true
            val before = writer.results(batch)
            val written = checkNotNull(before["written"])
            assertThat(written).isBetween(CHECKPOINT, FILE_COUNT - 1)
            val remaining = writer.nextGroup(batch)
            assertThat(remaining.files).hasSize(FILE_COUNT - written)
            writer.apply(remaining)
            assertThat(writer.results(batch)).isEqualTo(mapOf("written" to FILE_COUNT))
            val sample = manifest.readLines()
            for (uri in listOf(sample.first(), sample[FILE_COUNT / 2], sample.last())) {
                assertThat(read(client, Uri.parse(uri)).properties["TITLE"]).containsExactly(TITLE)
            }
            Log.i(LOG_TAG, "resumed $run from $written to $FILE_COUNT")
        } finally {
            client.close()
            library.close()
            user.close()
            if (cleanup) {
                for (uri in manifest.readLines()) resolver.delete(Uri.parse(uri), null, null)
                manifest.delete()
                staging.deleteRecursively()
                context.deleteDatabase(libraryName)
                context.deleteDatabase(userName)
            }
        }
    }

    private suspend fun seed(
        run: String,
        batch: String,
        manifest: File,
        library: LibraryDatabase,
        user: UserDatabase,
        client: TaggerClient,
    ) {
        val audio = TestFiles.assets.open("playback/tagged-44100-stereo.mp3").use { it.readBytes() }
        val path = "Music/PiPlayer-Write-Recovery-$run/"
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val files = (1..FILE_COUNT).map { index ->
            val name = "$run-$index.mp3"
            val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, path)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            manifest.appendText("$uri\n")
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(audio) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
            uri to name
        }
        val key = checkNotNull(read(client, files.first().first).content).key
        val index = FakeMediaIndex()
        index.volume().rows = files.map { (uri, name) -> row(ContentUris.parseId(uri), path = path, name = name) }
        val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
        try {
            sync.sync()
        } finally {
            sync.close()
        }
        val indexed = library.library().files()
        assertThat(indexed).hasSize(FILE_COUNT)
        for (file in indexed) library.library().setContent(file.id, key, 200_000, DurationSource.HEADER)
        library.batches().save(BatchEntity(batch, 1, BatchEntity.SOURCE_SELECTION, BatchEntity.STATUS_DONE))
        library.batches().addFiles(indexed.mapIndexed { position, file -> BatchFileEntity(batch, position, file.id) })
        val identity = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key), durationMs = null, recordingMbid = null,
                hintVolumeName = null, hintRelativePath = null, hintDisplayName = null,
                hintSize = null, createdAt = 1, missingSince = null,
            ),
        )
        val decision = user.decisions().add(
            DecisionEntity(
                identityId = identity, batchUuid = Hex.mbidBytes(batch), kind = DecisionKind.APPLY.code,
                fields = DecisionCodec.bits(setOf(ProtectedField.TITLE)), createdAt = 1,
            ),
        )
        user.decisions().addMetadata(listOf(TrackMetadataEntity(decision, DecidedField.TITLE.code, TITLE.toByteArray())))
    }

    private suspend fun read(client: TaggerClient, uri: Uri): TagReport = checkNotNull(resolver.openFileDescriptor(uri, "r")).use {
        val target = TagTarget.Outside(uri.toString(), it.statSize, 0)
        (client.read(target, it, "fixture.mp3") as TaggerResult.Replied).value.report
    }

    private companion object {
        const val FILE_COUNT = 500
        const val CHECKPOINT = 70
        const val TITLE = "PiPlayer recovery fixture"
        const val LOG_TAG = "BatchWriteRecovery"
    }
}
