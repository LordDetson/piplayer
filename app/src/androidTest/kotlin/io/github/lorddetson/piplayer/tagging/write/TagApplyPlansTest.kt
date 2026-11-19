package io.github.lorddetson.piplayer.tagging.write

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
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.userdata.DecisionCodec
import io.github.lorddetson.piplayer.userdata.DecisionEntity
import io.github.lorddetson.piplayer.userdata.TagApplyQueue
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.TrackMetadataEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
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
}
