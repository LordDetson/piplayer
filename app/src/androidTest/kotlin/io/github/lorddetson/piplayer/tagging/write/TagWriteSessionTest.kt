package io.github.lorddetson.piplayer.tagging.write

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.tagging.TagReadGuard
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagWriteSessionTest {
    private val context = TestFiles.targetContext
    private val library = TaggerTesting.freshDatabase("tag-write-session-library.db")
    private val client = TaggerTesting.client(library.first, TagReadGuard.DEFAULT_TIMEOUT)
    private val databaseName = "tag-write-session-user.db"
    private val directory = File(context.cacheDir, "tag-write-session-test")
    private val original = TestFiles.copy("playback/tagged-44100-stereo.mp3")
    private val uri = Uri.parse("content://test-fixture/audio/1")
    private val access = object : TagWriteAccess {
        override fun requestAccess(files: List<Uri>) = error("The activity owns consent")
        override fun openForWrite(file: Uri): ParcelFileDescriptor {
            check(file == uri)
            return ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    @After
    fun close() {
        client.close()
        library.first.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
    }

    @Test
    fun anApprovedWriteAfterRestartUsesTheJournalAndPreservesTheAudioKey() = runBlocking<Unit> {
        val initialBytes = original.readBytes()
        val target = target()
        val db = UserDatabase.open(context, databaseName)
        val queued: QueuedTagWrite.Ready
        try {
            val result = session(db).prepare(target) {
                TagDiff.between(it.properties, mapOf("TITLE" to listOf("Approved after reopening")))
            }
            assertThat(result).isInstanceOf(QueuedTagWrite.Ready::class.java)
            queued = result as QueuedTagWrite.Ready
            assertThat(original.readBytes()).isEqualTo(initialBytes)
            assertThat(TagWriteJournal(db).entry(queued.id)?.state).isEqualTo("pending")
        } finally {
            db.close()
        }
        val reopened = UserDatabase.open(context, databaseName)
        try {
            val writer = session(reopened)
            assertThat(writer.write(queued.id, target, consent = false)).isEqualTo(WriteBackResult.NEEDS_CONSENT)
            assertThat(original.readBytes()).isEqualTo(initialBytes)
            assertThat(writer.write(queued.id, target, consent = true)).isEqualTo(WriteBackResult.VERIFIED)
            val after = TestFiles.open(original).use {
                (client.read(target.taggerTarget, it, original.name) as TaggerResult.Replied).value.report
            }
            assertThat(after.content?.key).isEqualTo(target.contentKey)
            assertThat(after.properties["TITLE"]).containsExactly("Approved after reopening")
            assertThat(TagWriteJournal(reopened).entry(queued.id)?.state).isEqualTo("verified")
            assertThat(TagWriteJournal(reopened).interrupted()).isEmpty()
        } finally {
            reopened.close()
        }
    }

    @Test
    fun anInterruptedStreamCanBeRecoveredFromItsVerifiedPrefix() = runBlocking<Unit> {
        val target = target()
        val db = UserDatabase.open(context, databaseName)
        try {
            val session = session(db)
            val queued = queued(session, target)
            val record = checkNotNull(TagWriteJournal(db).entry(queued.id))
            val copy = File(directory, "copies/${record.copyPath}")
            val verifiedBytes = copy.readBytes()

            val interrupted = object : TagWriteAccess by access {
                override fun openForWrite(file: Uri): ParcelFileDescriptor {
                    original.writeBytes(verifiedBytes.copyOf(verifiedBytes.size / 2))
                    throw IOException("Test interruption after truncation")
                }
            }
            assertThat(session(db, interrupted).write(queued.id, target, consent = true)).isEqualTo(WriteBackResult.INTERRUPTED)
            assertThat(TagWriteJournal(db).interrupted().single().state).isEqualTo("streaming")

            assertThat(session.write(queued.id, target, consent = true)).isEqualTo(WriteBackResult.VERIFIED)
            assertThat(original.readBytes()).isEqualTo(verifiedBytes)
            assertThat(copy.exists()).isFalse()
        } finally {
            db.close()
        }
    }

    @Test
    fun aFileChangedAfterPreparationIsKept() = runBlocking<Unit> {
        val target = target()
        val db = UserDatabase.open(context, databaseName)
        try {
            val session = session(db)
            val queued = queued(session, target)
            ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_WRITE).use {
                assertThat(client.writeWorkingCopy(target.taggerTarget, it, mapOf("TITLE" to listOf("External edit"))))
                    .isEqualTo(TaggerResult.Replied(true))
            }
            val externallyEdited = original.readBytes()

            assertThat(session.write(queued.id, target, consent = true)).isEqualTo(WriteBackResult.SOURCE_CHANGED)
            assertThat(original.readBytes()).isEqualTo(externallyEdited)
            assertThat(TagWriteJournal(db).entry(queued.id)?.state).isEqualTo("pending")
        } finally {
            db.close()
        }
    }

    @Test
    fun aFileTruncatedBeforeTheFirstAttemptIsKept() = runBlocking<Unit> {
        val target = target()
        val db = UserDatabase.open(context, databaseName)
        try {
            val session = session(db)
            val queued = queued(session, target)
            original.writeBytes(byteArrayOf())

            assertThat(session.write(queued.id, target, consent = true)).isEqualTo(WriteBackResult.SOURCE_CHANGED)
            assertThat(original.length()).isZero()
        } finally {
            db.close()
        }
    }

    @Test
    fun undoRestoresUnchangedKeysAndLeavesConflictsVisible() = runBlocking<Unit> {
        val db = UserDatabase.open(context, databaseName)
        try {
            val target = target().copy(batchUuid = ByteArray(16) { 3 })
            val session = session(db)
            val journal = TagWriteJournal(db)
            val before = read(target)
            val queued = session.prepare(target) {
                TagDiff.between(it.properties, mapOf("TITLE" to listOf("Written title"), "ALBUM" to listOf("Written album")))
            } as QueuedTagWrite.Ready
            assertThat(session.write(queued.id, target, consent = true)).isEqualTo(WriteBackResult.VERIFIED)
            assertThat(journal.forTrack(target.identityId).map { it.id }).containsExactly(queued.id)
            assertThat(journal.forBatch(checkNotNull(target.batchUuid)).map { it.id }).containsExactly(queued.id)
            ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_WRITE).use {
                assertThat(client.writeWorkingCopy(target.taggerTarget, it, mapOf("TITLE" to listOf("External title"))))
                    .isEqualTo(TaggerResult.Replied(true))
            }

            val undo = requireNotNull(session.prepareUndo(queued.id, target))
            assertThat(undo.conflicts).containsExactly("TITLE")
            val reversal = undo.queued as QueuedTagWrite.Ready
            assertThat(session.write(reversal.id, target, consent = true)).isEqualTo(WriteBackResult.VERIFIED)

            val after = read(target)
            assertThat(after.properties["TITLE"]).containsExactly("External title")
            assertThat(after.properties["ALBUM"]).isEqualTo(before.properties["ALBUM"])
            assertThat(journal.entry(queued.id)?.undoneAt).isNotNull()
            assertThat(journal.forTrack(target.identityId).map { it.id }).containsExactly(reversal.id, queued.id)
            assertThat(session.prepareUndo(queued.id, target)).isNull()
        } finally {
            db.close()
        }
    }

    private suspend fun read(target: TagWriteTarget) = TestFiles.open(original).use {
        (client.read(target.taggerTarget, it, original.name) as TaggerResult.Replied).value.report
    }

    private suspend fun queued(session: TagWriteSession, target: TagWriteTarget) = session.prepare(target) {
        TagDiff.between(it.properties, mapOf("TITLE" to listOf("From the working copy")))
    } as QueuedTagWrite.Ready

    private suspend fun target(): TagWriteTarget {
        val tagTarget = TagTarget.Outside(uri.toString(), original.length(), 1)
        val report = TestFiles.open(original).use {
            (client.read(tagTarget, it, original.name) as TaggerResult.Replied).value.report
        }
        return TagWriteTarget(
            uri, tagTarget, 7, 8, null, "external_primary", "Music/", original.name, checkNotNull(report.content).key,
        )
    }

    private fun session(database: UserDatabase, access: TagWriteAccess = this.access) = TagWriteSession(
        directory, client, TagWriteJournal(database), access, { TestFiles.open(original) }, { Long.MAX_VALUE },
    )
}
