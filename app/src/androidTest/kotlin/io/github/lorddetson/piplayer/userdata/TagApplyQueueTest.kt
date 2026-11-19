package io.github.lorddetson.piplayer.userdata

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.identity.Hex
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagApplyQueueTest {
    private val context = TestFiles.targetContext
    private val name = "tag-apply-queue-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun aFiveHundredFilePlanKeepsItsRemainingSelectionAfterReopening() = runBlocking<Unit> {
        val selection = (1L..500L).map { fileId ->
            item(fileId)
        }
        withDatabase { db ->
            val queue = TagApplyQueue(db)
            queue.save(selection)
            for (item in selection.take(73)) assertThat(queue.finish(item, "written")).isTrue()
        }

        withDatabase { db ->
            val queue = TagApplyQueue(db)
            val remaining = queue.remaining("test-plan")
            assertThat(remaining).containsExactlyElementsOf(selection.drop(73))
            assertThat(queue.finish(selection.first(), "skipped")).isFalse()
            assertThat(queue.results("test-plan")).isEqualTo(mapOf("written" to 73, "queued" to 427))
        }
    }

    @Test
    fun theJournalCheckpointsPreparationAndVerificationWithThePlanItem() = runBlocking<Unit> {
        val item = item(12L)
        val journalId = withDatabase { database ->
            val queue = TagApplyQueue(database)
            queue.save(listOf(item))
            val journal = TagWriteJournal(database)
            val backup = TagBackupEntity(
                identityId = 7, volumeName = item.volumeName, relativePath = item.relativePath, displayName = item.displayName,
                createdAt = 1, tagsPath = "tags.json", tagsHash = ByteArray(32), coverPath = null, coverHash = null, id3Version = 4,
            )
            val change = AppliedChangeEntity(
                identityId = 7, decisionId = item.decisionId, batchUuid = null, backupId = 0,
                volumeName = item.volumeName, relativePath = item.relativePath, displayName = item.displayName,
                state = "pending", copyPath = "copy", copySize = 100, copySha256 = ByteArray(32),
                expectedContentKey = Hex.bytes(item.contentKey), tagHash = ByteArray(32), diff = byteArrayOf(1, 2), createdAt = 1,
            )
            val id = requireNotNull(journal.prepareForApply(backup, change, item))
            assertThat(journal.prepareForApply(backup, change, item)).isNull()
            assertThat(journal.interrupted().map { it.id }).containsExactly(id)
            id
        }
        withDatabase { database ->
            val queue = TagApplyQueue(database)
            val journal = TagWriteJournal(database)
            assertThat(queue.remaining(item.planId)).containsExactly(item.copy(writeId = journalId))
            assertThat(journal.beginStreaming(journalId)).isTrue()
            assertThat(journal.streamed(journalId)).isTrue()
            assertThat(journal.verified(journalId, 100, 4, 8)).isTrue()
            assertThat(queue.remaining(item.planId)).isEmpty()
            assertThat(queue.results(item.planId)).isEqualTo(mapOf("written" to 1))
        }
    }

    private fun item(id: Long) = TagApplyItem(
        planId = "test-plan", fileId = id, contentKey = id.toString(16).padStart(32, '0'), decisionId = id + 500,
        volumeName = "external_primary", relativePath = "Music/", displayName = "file-$id.mp3",
    )

    private suspend fun <T> withDatabase(block: suspend (UserDatabase) -> T): T {
        val database = UserDatabase.open(context, name)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }
}
