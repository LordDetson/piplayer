package io.github.lorddetson.piplayer.tagging.write

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.userdata.DecisionCodec
import io.github.lorddetson.piplayer.userdata.DecisionEntity
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.TrackMetadataEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagApplyPlansTest {
    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())

    @After
    fun close() {
        sync.close()
        library.close()
        user.close()
    }

    @Test
    fun onlyAcceptedFilesOfTheBatchAreScheduled() = runBlocking<Unit> {
        val uuid = "a1c3bbda-a5d3-42ac-b433-428aa9fed33d"
        index.volume().rows = (1L..4L).map { row(it) }
        sync.sync()
        val files = library.library().files().sortedBy { it.mediaStoreId }
        library.batches().save(BatchEntity(uuid, 1, BatchEntity.SOURCE_SELECTION, BatchEntity.STATUS_DONE))
        library.batches().addFiles(files.take(3).mapIndexed { position, file -> BatchFileEntity(uuid, position, file.id) })
        val decisionIds = files.mapIndexed { i, file ->
            val key = file.mediaStoreId.toString(16).padStart(64, '0')
            if (i != 2) library.library().setContent(file.id, key, 200_000, DurationSource.HEADER)
            val identity = user.identities().add(
                TrackIdentityEntity(
                    contentKey = Hex.bytes(key), durationMs = 200_000, recordingMbid = null,
                    hintVolumeName = file.volumeName, hintRelativePath = file.relativePath, hintDisplayName = file.displayName,
                    hintSize = file.size, createdAt = 1, missingSince = null,
                ),
            )
            val kind = if (i == 1) DecisionKind.SKIP else DecisionKind.APPLY
            val fields = DecisionCodec.bits(setOf(ProtectedField.TITLE, ProtectedField.ARTIST))
            user.decisions().add(DecisionEntity(identityId = identity, batchUuid = Hex.mbidBytes(uuid), kind = kind.code, fields = fields, createdAt = 1))
        }

        val plans = TagApplyPlans(LibraryAccess.of(library), user)
        val items = plans.forBatch(uuid)

        assertThat(items.map { it.fileId to it.decisionId }).containsExactly(
            files[0].id to decisionIds[0], files[2].id to decisionIds[2],
        )
        assertThat(items.map { it.displayName }).containsExactly(files[0].displayName, files[2].displayName)
        val selected = items.first()
        val target = requireNotNull(plans.target(selected))
        assertThat(target.uri.toString()).isEqualTo("content://media/${files[0].volumeName}/audio/media/${files[0].mediaStoreId}")
        assertThat(target.contentKey).isEqualTo(selected.contentKey)
        assertThat(target.batchUuid).isEqualTo(Hex.mbidBytes(uuid))
        assertThat(plans.target(selected.copy(displayName = "other.mp3"))).isNull()

        val decisions = user.decisions()
        val decisionId = requireNotNull(target.decisionId)
        decisions.addMetadata(
            listOf(
                TrackMetadataEntity(decisionId, DecidedField.TITLE.code, "Accepted title".toByteArray()),
                TrackMetadataEntity(decisionId, DecidedField.ARTIST.code, "Identification artist".toByteArray()),
            ),
        )
        val edit = decisions.add(
            DecisionEntity(
                identityId = target.identityId, kind = DecisionKind.EDIT.code,
                fields = DecisionCodec.bits(setOf(ProtectedField.ARTIST)), createdAt = 2,
            ),
        )
        decisions.addMetadata(listOf(TrackMetadataEntity(edit, DecidedField.ARTIST.code, "Manual artist".toByteArray())))
        val original = TagReport(
            properties = mapOf("TITLE" to listOf("Old title"), "ARTIST" to listOf("File artist")),
            content = TagReport.Content(selected.contentKey, "mp3", DurationPlan(200_000, DurationCheck.NONE)),
            id3Version = 3,
        )
        val diff = requireNotNull(plans.diff(selected))(original)
        assertThat(diff.changes.keys).containsExactly("TITLE")
        assertThat(diff.properties).isEqualTo(original.properties + ("TITLE" to listOf("Accepted title")))

        val writer = BatchTagWriter(context, LibraryAccess.of(library), user)
        val first = async { writer.nextGroup(uuid) }
        val second = async { writer.nextGroup(uuid) }
        assertThat(first.await().files.keys).containsExactlyElementsOf(items.map { it.fileId })
        assertThat(second.await()).isEqualTo(first.await())
        assertThat(TagApplyQueue(user).remaining(uuid)).containsExactlyElementsOf(items)
    }

    @Test
    fun theBatchWriterAppliesTheDecidedTitleToAMediaStoreFile() = runBlocking<Unit> {
        val batchUuid = "09bee021-28b0-4d45-8e5b-9441c8a2b9f9"
        val name = "piplayer-batch-tag-test-${System.nanoTime()}.mp3"
        val staging = File(context.cacheDir, name.removeSuffix(".mp3"))
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val client = TaggerTesting.client(library, TagReadGuard.DEFAULT_TIMEOUT)
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                TestFiles.assets.open("playback/tagged-44100-stereo.mp3").use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            index.volume().rows = listOf(row(ContentUris.parseId(uri)).copy(displayName = name))
            sync.sync()
            val file = library.library().files().single()
            suspend fun report(): TagReport = checkNotNull(resolver.openFileDescriptor(uri, "r")).use {
                (client.read(TagTarget.LibraryFile(file.id), it, name) as TaggerResult.Replied).value.report
            }
            val before = report()
            val key = checkNotNull(before.content).key
            library.library().setContent(file.id, key, 200_000L, DurationSource.HEADER)
            val identity = user.identities().add(
                TrackIdentityEntity(
                    contentKey = Hex.bytes(key), durationMs = null, recordingMbid = null,
                    hintVolumeName = file.volumeName, hintRelativePath = file.relativePath, hintDisplayName = name,
                    hintSize = null, createdAt = 1, missingSince = null,
                ),
            )
            library.batches().save(BatchEntity(batchUuid, 1, BatchEntity.SOURCE_SELECTION, BatchEntity.STATUS_DONE))
            library.batches().addFiles(listOf(BatchFileEntity(batchUuid, 0, file.id)))
            val id = user.decisions().add(
                DecisionEntity(
                    identityId = identity, batchUuid = Hex.mbidBytes(batchUuid), kind = DecisionKind.APPLY.code,
                    fields = DecisionCodec.bits(setOf(ProtectedField.TITLE)), createdAt = 1,
                ),
            )
            val title = "Written through MediaStore"
            user.decisions().addMetadata(listOf(TrackMetadataEntity(id, DecidedField.TITLE.code, title.toByteArray())))

            val writer = BatchTagWriter(context, LibraryAccess.of(library), user, client, staging)
            val group = writer.nextGroup(batchUuid)
            assertThat(group.files.values).containsExactly(uri)
            writer.apply(group)

            val after = report()
            assertThat(after.properties).isEqualTo(before.properties + ("TITLE" to listOf(title)))
            assertThat(after.content?.key).isEqualTo(key)
            assertThat(writer.results(batchUuid)).isEqualTo(mapOf("written" to 1))
            awaitIndexedTitle(uri, title)

            val written = writer.historyForTrack(identity).single()
            val plan = requireNotNull(writer.undoPlan(identity, written.id))
            assertThat(plan.uri).isEqualTo(uri)
            assertThat(writer.nextUndoGroup("8c75e446-88a5-4477-ba6a-a41109585caf").files).isEmpty()
            val batchUndo = writer.nextUndoGroup(batchUuid)
            assertThat(batchUndo.files).containsExactly(plan)
            assertThat(writer.undo(batchUndo)).isEqualTo(mapOf(written.id to TagUndoOutcome.Restored(emptySet())))
            assertThat(writer.nextUndoGroup(batchUuid).files).isEmpty()
            val restored = report()
            assertThat(restored.properties).isEqualTo(before.properties)
            assertThat(restored.content?.key).isEqualTo(key)
            assertThat(writer.historyForTrack(identity).last().undoneAt).isNotNull()
            awaitIndexedTitle(uri, requireNotNull(before.properties["TITLE"]?.singleOrNull()))

            val manual = requireNotNull(writer.manualPlan(file.id))
            assertThat(manual.target.uri).isEqualTo(uri)
            assertThat(manual.properties).isEqualTo(before.properties)
            val comment = listOf("Entered in the tag editor")
            assertThat(writer.applyManual(manual, mapOf("COMMENT" to comment))).isEqualTo(WriteBackResult.VERIFIED)
            val edited = report()
            assertThat(edited.properties).isEqualTo(before.properties + ("COMMENT" to comment))
            assertThat(edited.content?.key).isEqualTo(key)
            assertThat(writer.applyManual(manual, mapOf("COMMENT" to listOf("Stale preview")))).isEqualTo(WriteBackResult.SOURCE_CHANGED)
            assertThat(report().properties).isEqualTo(edited.properties)

            val decision = user.decisions().add(
                DecisionEntity(
                    identityId = identity, kind = DecisionKind.EDIT.code,
                    fields = DecisionCodec.bits(setOf(ProtectedField.TITLE)), createdAt = 3,
                ),
            )
            val recognizedTitle = "Written from the review card"
            user.decisions().addMetadata(listOf(TrackMetadataEntity(decision, DecidedField.TITLE.code, recognizedTitle.toByteArray())))
            assertThat(writer.reviewPlan(file.id, id, null)).isNull()
            val review = requireNotNull(writer.reviewPlan(file.id, decision, null))
            assertThat(review.file.target.uri).isEqualTo(uri)
            assertThat(writer.applyReview(review) {}).isEqualTo(WriteBackResult.VERIFIED)
            assertThat(report().properties).isEqualTo(edited.properties + ("TITLE" to listOf(recognizedTitle)))
            assertThat(report().content?.key).isEqualTo(key)
            assertThat(writer.historyForTrack(identity).first().changes.keys).containsExactly("TITLE")
        } finally {
            client.close()
            resolver.delete(uri, null, null)
            staging.deleteRecursively()
        }
    }

    private suspend fun awaitIndexedTitle(uri: Uri, expected: String) = withTimeout(10_000) {
        val titleColumn = MediaStore.Audio.Media.TITLE
        while (true) {
            val title = context.contentResolver.query(uri, arrayOf(titleColumn), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (title == expected) return@withTimeout
            delay(150)
        }
    }
}
