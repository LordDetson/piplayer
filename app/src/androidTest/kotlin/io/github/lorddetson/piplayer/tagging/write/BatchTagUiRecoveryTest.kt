package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.BatchFileEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.userdata.DecisionCodec
import io.github.lorddetson.piplayer.userdata.DecisionEntity
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.TrackMetadataEntity
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The host supplies indexed, shell-owned fixtures. Grant dialogs are accepted from the real app UI. */
@RunWith(AndroidJUnit4::class)
class BatchTagUiRecoveryTest {
    private val context = TestFiles.targetContext

    @Test
    fun preparesOrChecksTheSystemConsentFixture() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("batchUiPhase")
        assumeTrue(phase == "seed" || phase == "check")
        check(Build.HARDWARE == "ranchu") { "This fixture uses the app's databases on a test emulator" }
        val run = requireNotNull(arguments.getString("batchUiRun"))
        require(run.matches(Regex("[a-z0-9]{8,32}")))
        val batch = UUID.nameUUIDFromBytes("batch-ui-$run".toByteArray()).toString()
        val folder = "Music/PiPlayer-Ui-Recovery-$run/"
        val title = "PiPlayer recovered $run"
        val writer = (context.applicationContext as PiPlayerApplication).batchTagWriter
        if (phase == "seed") {
            check(Library.mayRead(context))
            Library.sync(context).sync()
            val database = Library.database(context)
            val files = database.library().files().filter {
                it.volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY && it.relativePath == folder
            }.sortedBy { it.displayName }
            assertThat(files).hasSize(FILE_COUNT)
            assertThat(database.batches().batch(batch)).isNull()
            val first = files.first()
            val collection = MediaStore.Audio.Media.getContentUri(first.volumeName)
            val client = TaggerTesting.client(database, TagReadGuard.DEFAULT_TIMEOUT)
            val content = try {
                val uri = ContentUris.withAppendedId(collection, first.mediaStoreId)
                checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { descriptor ->
                    val target = TagTarget.Outside(uri.toString(), descriptor.statSize, 0)
                    val read = client.read(target, descriptor, first.displayName) as TaggerResult.Replied
                    checkNotNull(read.value.report.content)
                }
            } finally {
                client.close()
            }
            val user = UserData.database(context)
            val key = Hex.bytes(content.key)
            val existing = user.identities().idOfKey(key)?.let { user.identities().identity(it) }
            check(existing == null || existing.hintRelativePath == folder) { "Use audio bytes unique to this fixture run" }
            val at = System.currentTimeMillis()
            val identity = existing?.id ?: user.identities().add(TrackIdentityEntity(
                contentKey = key, durationMs = null, recordingMbid = null,
                hintVolumeName = first.volumeName, hintRelativePath = folder, hintDisplayName = first.displayName,
                hintSize = first.size, createdAt = at, missingSince = null,
            ))
            val decision = user.decisions().add(DecisionEntity(
                identityId = identity, batchUuid = Hex.mbidBytes(batch), kind = DecisionKind.APPLY.code,
                fields = DecisionCodec.bits(setOf(ProtectedField.TITLE)), createdAt = at,
            ))
            user.decisions().addMetadata(listOf(TrackMetadataEntity(decision, DecidedField.TITLE.code, title.toByteArray())))
            for (file in files) database.library().setContent(file.id, content.key, 2_000, DurationSource.HEADER)
            database.batches().save(BatchEntity(batch, at, BatchEntity.SOURCE_FOLDER, BatchEntity.STATUS_DONE))
            database.batches().addFiles(files.mapIndexed { position, row -> BatchFileEntity(batch, position, row.id) })
            val group = writer.nextGroup(batch)
            assertThat(group.files).hasSize(FILE_COUNT)
            assertThat(withContext(Dispatchers.IO) { writer.request(group) }).isNotNull()
            Log.i(LOG_TAG, "seeded $batch with $FILE_COUNT files requiring system consent")
        } else {
            val result = writer.results(batch)
            assertThat(result).isEqualTo(mapOf("written" to FILE_COUNT))
            val history = writer.historyForBatch(batch)
            assertThat(history).hasSize(FILE_COUNT)
            assertThat(history).allMatch { it.changes["TITLE"]?.after == listOf(title) }
            Log.i(LOG_TAG, "verified all $FILE_COUNT files in $batch")
        }
    }

    private companion object {
        const val FILE_COUNT = 500
        const val LOG_TAG = "BatchTagUiRecovery"
    }
}
