package io.github.lorddetson.piplayer.userdata

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagWriteRecord
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagWriteJournalTest {
    private val context = TestFiles.targetContext
    private val name = "tag-write-journal-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun onlyInterruptedWritesNeedRecoveryAfterReopening() = runBlocking<Unit> {
        val opened = UserDatabase.open(context, name)
        val verified: Long
        val streamed: Long
        val pending: Long
        try {
            val journal = TagWriteJournal(opened)
            verified = journal.prepare(backup("verified.mp3"), change("verified.mp3"))
            streamed = journal.prepare(backup("streamed.mp3"), change("streamed.mp3"))
            pending = journal.prepare(backup("pending.mp3"), change("pending.mp3"))
            assertThat(journal.streamed(verified)).isTrue()
            assertThat(journal.verified(verified, 190L, 23L, 42L)).isTrue()
            assertThat(journal.streamed(streamed)).isTrue()
        } finally {
            opened.close()
        }

        val reopened = UserDatabase.open(context, name)
        try {
            val journal = TagWriteJournal(reopened)
            assertThat(journal.interrupted().map { it.id }).containsExactly(streamed, pending)
            val first = journal.entry(verified)!!
            assertThat(first.state).isEqualTo("verified")
            assertThat(first.actualSize).isEqualTo(190L)
            assertThat(first.actualMtime).isEqualTo(23L)
            assertThat(first.verifiedAt).isEqualTo(42L)
            assertThat(journal.backup(first.backupId)?.displayName).isEqualTo("verified.mp3")
            assertThat(journal.streamed(verified)).isFalse()
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aFullPageLeavesTheLiveJournalButKeepsTheSnapshotsUndoAndPendingWrites() = runBlocking<Unit> {
        val batch = ByteArray(16) { 5 }
        val diff = TagWriteRecord(ByteArray(32), 1, 2, mapOf("TITLE" to TagChange(listOf("Before"), listOf("After")))).encode()
        val opened = UserDatabase.open(context, name)
        val first: Long
        val interrupted: Long
        try {
            val journal = TagWriteJournal(opened)
            interrupted = journal.prepare(backup("interrupted.mp3"), change("interrupted.mp3").copy(diff = diff))
            assertThat(journal.beginStreaming(interrupted)).isTrue()
            val ids = (1..80).map { index ->
                val file = "written-$index.mp3"
                val id = journal.prepare(backup(file), change(file).copy(batchUuid = batch, diff = diff))
                assertThat(journal.streamed(id)).isTrue()
                assertThat(journal.verified(id, 100L, 42_000L, index.toLong())).isTrue()
                id
            }
            first = ids.first()
            assertThat(opened.tagWrites().entry(first)).isNull()
        } finally {
            opened.close()
        }
        val reopened = UserDatabase.open(context, name)
        try {
            val journal = TagWriteJournal(reopened)
            val archived = requireNotNull(journal.entry(first))
            assertThat(journal.backup(archived.backupId)?.displayName).isEqualTo("written-1.mp3")
            assertThat(journal.interrupted().map { it.id }).containsExactly(interrupted)
            assertThat(journal.forBatch(batch)).hasSize(80)
            assertThat(journal.observedWrites(listOf(7L)).getValue(7L)).anyMatch { it.id == first }
            assertThat(reopened.user().identitiesHoldingData()).containsExactly(7L)

            val undo = journal.prepare(backup("written-1.mp3"), change("written-1.mp3").copy(batchUuid = batch, diff = diff))
            assertThat(journal.streamed(undo)).isTrue()
            assertThat(journal.verified(undo, 110L, 46_000L, 120L, undoOf = first)).isTrue()
            assertThat(journal.entry(first)?.undoneAt).isEqualTo(120L)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aVerifiedWriteKeepsItsIdentityEvenWithoutADecision() = runBlocking<Unit> {
        val opened = UserDatabase.open(context, name)
        try {
            val journal = TagWriteJournal(opened)
            val id = journal.prepare(backup("history.mp3"), change("history.mp3").copy(decisionId = null))
            assertThat(journal.streamed(id)).isTrue()
            assertThat(journal.verified(id, 190L, 23L, 42L)).isTrue()

            assertThat(opened.user().identitiesHoldingData()).containsExactly(7L)
        } finally {
            opened.close()
        }
    }

    @Test
    fun verificationClearsOnlyWrittenConflictsAtTheWrittenLocation() = runBlocking<Unit> {
        val opened = UserDatabase.open(context, name)
        try {
            val title = DecidedField.TITLE
            val album = DecidedField.ALBUM
            val dao = opened.decisions()
            val decision = dao.add(DecisionEntity(
                identityId = 7, kind = DecisionKind.EDIT.code, fields = DecisionCodec.bits(setOf(ProtectedField.TITLE, ProtectedField.ALBUM)),
                fileValues = FileReferences.encode(mapOf(title to FileReferences.hash("Original title"))), createdAt = 1,
            ))
            dao.addMetadata(listOf(
                TrackMetadataEntity(decision, title.code, "Accepted title".encodeToByteArray()),
                TrackMetadataEntity(decision, album.code, "Accepted album".encodeToByteArray()),
            ))
            val reference = ByteBuffer.allocate(4).putInt(FileReferences.hash("Original title")).array()
            val titleState = FileStateEntity(7, decision, "external_primary", "Music/", "same.mp3", title.code, reference, true)
            val albumState = titleState.copy(field = album.code)
            val copyState = titleState.copy(relativePath = "Copy/")
            dao.saveFileStates(listOf(titleState, albumState, copyState))
            val journal = TagWriteJournal(opened)
            val diff = TagWriteRecord(
                ByteArray(32), 1, 2, mapOf("TITLE" to TagChange(listOf("Original title"), listOf("Accepted title"))),
            ).encode()
            val id = journal.prepare(backup("same.mp3"), change("same.mp3").copy(decisionId = decision, diff = diff))
            assertThat(journal.streamed(id)).isTrue()
            assertThat(journal.verified(
                id, 100L, 22L, 30L, writtenPartHashes = mapOf(title.code to FileReferences.hash("Accepted title")),
            )).isTrue()
            assertThat(dao.fileStates(7)).containsExactlyInAnyOrder(albumState, copyState)

            val references = Decisions(opened)
            val current = Decisions.LocationValues(
                "external_primary", "Music/", "same.mp3", mapOf(title to "Accepted title", album to "External album"),
            )
            assertThat(references.fileChanged(7, current)).containsExactly(album)
            assertThat(references.fileChanged(7, current.copy(values = current.values + (title to "New external edit"))))
                .containsExactlyInAnyOrder(title, album)
        } finally {
            opened.close()
        }
    }

    private fun backup(file: String) = TagBackupEntity(
        identityId = 7, volumeName = "external_primary", relativePath = "Music/", displayName = file,
        createdAt = 1, tagsPath = "tags-$file.json", tagsHash = ByteArray(32), coverPath = null,
        coverHash = null, id3Version = 4,
    )

    private fun change(file: String) = AppliedChangeEntity(
        identityId = 7, decisionId = 8, batchUuid = null, backupId = 0,
        volumeName = "external_primary", relativePath = "Music/", displayName = file,
        state = "pending", copyPath = "copy-$file", copySize = 100,
        copySha256 = ByteArray(32), expectedContentKey = ByteArray(32), tagHash = ByteArray(32),
        diff = byteArrayOf(1, 2), createdAt = 1,
    )
}
