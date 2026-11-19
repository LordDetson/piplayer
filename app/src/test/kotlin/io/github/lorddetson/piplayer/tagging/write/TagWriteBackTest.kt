package io.github.lorddetson.piplayer.tagging.write

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TagWriteBackTest {
    @TempDir
    lateinit var directory: File

    private val contents = "verified working copy".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(contents)

    private fun copy() = File(directory, "copy.mp3").apply { writeBytes(contents) }

    @Test
    fun `interrupted writes keep the copy and wait for a new consent before replaying`() = runBlocking<Unit> {
        val copy = copy()
        val output = File(directory, "original.mp3").apply { writeText("original audio") }
        val journal = RecordingJournal()
        val failing = TagWriteBack(journal, stream = {
            output.writeBytes(it.readBytes().copyOf(3))
            throw InterruptedException("process died during stream-back")
        }, verify = { error("Incomplete streams cannot be verified") })

        assertThat(runCatching { failing.run(copy, contents.size.toLong(), digest, consent = true) }.exceptionOrNull())
            .isInstanceOf(InterruptedException::class.java)
        assertThat(journal.events).isEmpty()
        assertThat(copy.exists()).isTrue()

        val restarted = TagWriteBack(journal, stream = { it.copyTo(output, overwrite = true) }, verify = {
            Stamp(output.length(), 12)
        })
        assertThat(restarted.run(copy, contents.size.toLong(), digest, consent = false))
            .isEqualTo(WriteBackResult.NEEDS_CONSENT)
        assertThat(output.length()).isEqualTo(3)

        assertThat(restarted.run(copy, contents.size.toLong(), digest, consent = true)).isEqualTo(WriteBackResult.VERIFIED)
        assertThat(output.readBytes()).isEqualTo(contents)
        assertThat(journal.events).containsExactly("streamed", "verified")
        assertThat(copy.exists()).isFalse()
    }

    @Test
    fun `a damaged copy never opens the original for writing`() = runBlocking<Unit> {
        val copy = copy().apply { writeBytes(ByteArray(contents.size)) }
        val journal = RecordingJournal()
        val writer = TagWriteBack(journal, stream = { error("Original was opened") }, verify = { null })

        assertThat(writer.run(copy, contents.size.toLong(), digest, consent = true)).isEqualTo(WriteBackResult.INVALID_COPY)
        assertThat(journal.events).isEmpty()
    }

    @Test
    fun `two verification mismatches keep the copy and mark the write failed`() = runBlocking<Unit> {
        val copy = copy()
        val journal = RecordingJournal()
        var streams = 0
        val writer = TagWriteBack(journal, stream = { streams++ }, verify = { null })

        assertThat(writer.run(copy, contents.size.toLong(), digest, consent = true)).isEqualTo(WriteBackResult.FAILED)
        assertThat(streams).isEqualTo(2)
        assertThat(journal.events).containsExactly("streamed", "streamed", "failed")
        assertThat(copy.exists()).isTrue()
    }

    @Test
    fun `the working copy survives a failure while committing verification`() = runBlocking<Unit> {
        val copy = copy()
        val journal = RecordingJournal(verified = false)
        val writer = TagWriteBack(journal, stream = {}, verify = { Stamp(contents.size.toLong(), 1) })

        assertThat(writer.run(copy, contents.size.toLong(), digest, consent = true))
            .isEqualTo(WriteBackResult.JOURNAL_CHANGED)
        assertThat(copy.exists()).isTrue()
    }

    private class RecordingJournal(private val verified: Boolean = true) : TagWriteBack.Journal {
        val events = mutableListOf<String>()
        override suspend fun streamed(): Boolean = events.add("streamed")
        override suspend fun verified(stamp: Stamp): Boolean {
            events.add("verified")
            return verified
        }
        override suspend fun failed(): Boolean = events.add("failed")
    }
}
