package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Stage 0's reads (TI §3 "One run", §3.1): a library file the tag pass has not read gets its content key from a read
 * through `:tagger`, which is counted against the file, and nothing is written to the library database; a file the pass
 * has read takes the stored key and is not read.
 */
class LocalReadsTest {

    /** The library database as stage 0 sees it: reads only, recorded. */
    private val stored = mutableMapOf<Long, StoredFile>()
    private val libraryReads = mutableListOf<Long>()

    /** `:tagger` as a fake that records what each read is counted against. */
    private class Reads(var reply: TaggerRead?) : TaggerReads {
        val calls = mutableListOf<Triple<String, String?, Long?>>()

        override suspend fun read(uri: String, name: String?, fileId: Long?): TaggerRead? {
            calls += Triple(uri, name, fileId)
            return reply
        }
    }

    private val tagger = Reads(TaggerRead(report(READ_KEY), size = 4_321))

    private val reads = LocalReads(
        library = { fileId -> libraryReads += fileId; stored[fileId] },
        tagger = tagger,
        cyrillicLanguage = { false },
        legacyEncoding = { null },
    )

    private fun report(key: String?, plan: DurationPlan = DurationPlan(214_000, DurationCheck.NONE)) = TagReport(
        properties = mapOf("TITLE" to listOf("Paper Lanterns"), "ARTIST" to listOf("The Quiet Harbors")),
        audio = TagReport.Audio(214_000, 320, 44_100, 2),
        container = "MPEG",
        content = key?.let { TagReport.Content(it, "mp3", plan) },
    )

    private fun row(tagPassAt: Long?, contentKey: String?) = AudioFileEntity(
        id = FILE_ID, mediaStoreId = 10, volumeName = "external_primary", relativePath = "Music/Album/",
        displayName = "01 track.mp3", size = 4_321, mtime = 1, generationModified = 1, mimeType = "audio/mpeg",
        isMusic = true, isPodcast = false, isAudiobook = false, isRingtone = false, isNotification = false,
        isAlarm = false, isRecording = false, recording = false, folderRule = 0, tagPassAt = tagPassAt,
        format = "MPEG", contentKey = contentKey, durationMs = 214_000, durationSource = DurationSource.HEADER,
    )

    private fun store(row: AudioFileEntity, track: TrackEntity? = null) {
        stored[row.id] = StoredFile(row, track, URI)
    }

    @Test
    fun `a library file the tag pass has not read gets its content key from a read through the tagger`() = runTest {
        store(row(tagPassAt = null, contentKey = null))

        val file = reads.library(FILE_ID)!!

        assertThat(tagger.calls).containsExactly(Triple(URI, "01 track.mp3", FILE_ID))
        assertThat(file.target).isEqualTo(IdentificationTarget.LibraryFile(FILE_ID, READ_KEY))
        assertThat(file.tags!!.title).isEqualTo("Paper Lanterns")
        assertThat(file.tags!!.artist).isEqualTo("The Quiet Harbors")
        assertThat(file.fileName).isEqualTo("01 track.mp3")
        assertThat(file.folder).isEqualTo(FolderKey("external_primary", "Music/Album/").key)
        assertThat(file.format).isEqualTo("MPEG")
        assertThat(file.duration).isEqualTo(StageDuration.Exact(214_000))
        assertThat(file.uri).isEqualTo(URI)
    }

    @Test
    fun `a stored key the tag pass has not confirmed since the file changed gives way to the key of the read`() = runTest {
        store(row(tagPassAt = null, contentKey = STORED_KEY))

        val file = reads.library(FILE_ID)!!

        assertThat(tagger.calls).hasSize(1)
        assertThat(file.target.contentKey).isEqualTo(READ_KEY)
    }

    @Test
    fun `a library file the tag pass has read takes the stored key and tags and is not read`() = runTest {
        store(row(tagPassAt = 5, contentKey = STORED_KEY), TrackEntity(fileId = FILE_ID, title = "Stored title"))

        val file = reads.library(FILE_ID)!!

        assertThat(tagger.calls).isEmpty()
        assertThat(file.target).isEqualTo(IdentificationTarget.LibraryFile(FILE_ID, STORED_KEY))
        assertThat(file.tags!!.title).isEqualTo("Stored title")
        assertThat(file.duration).isEqualTo(StageDuration.Exact(214_000))
    }

    @Test
    fun `a read key is taken in lower case and a read without a key of 64 hex digits gives no file`() = runTest {
        store(row(tagPassAt = null, contentKey = null))
        tagger.reply = TaggerRead(report(READ_KEY.uppercase()), size = 1)
        assertThat(reads.library(FILE_ID)!!.target.contentKey).isEqualTo(READ_KEY)

        tagger.reply = TaggerRead(report("not a key"), size = 1)
        assertThat(reads.library(FILE_ID)).isNull()

        tagger.reply = TaggerRead(report(key = null), size = 1)
        assertThat(reads.library(FILE_ID)).isNull()
    }

    @Test
    fun `a file the tagger cannot read and a file gone from the library give no file`() = runTest {
        store(row(tagPassAt = null, contentKey = null))
        tagger.reply = null
        assertThat(reads.library(FILE_ID)).isNull()

        assertThat(reads.library(FILE_ID + 1)).isNull()
    }

    @Test
    fun `a length the header cannot settle is left for a walk`() = runTest {
        store(row(tagPassAt = null, contentKey = null))
        val plan = DurationPlan(null, DurationCheck.WALK)
        tagger.reply = TaggerRead(report(READ_KEY, plan), size = 1)

        assertThat(reads.library(FILE_ID)!!.duration).isEqualTo(StageDuration.Pending(plan))
    }

    @Test
    fun `stage 0 reads the library only and keeps its read to the file`() = runTest {
        store(row(tagPassAt = null, contentKey = null))

        reads.library(FILE_ID)

        // The only access to the library database is the read of the row; the read of the file goes to `:tagger`,
        // counted against the file ID, and the library's values stay the tag pass's.
        assertThat(libraryReads).containsExactly(FILE_ID)
        assertThat(stored.getValue(FILE_ID).row.contentKey).isNull()
        assertThat(stored.getValue(FILE_ID).row.tagPassAt).isNull()
    }

    @Test
    fun `a file opened from another app is read as a file outside the library`() = runTest {
        val file = reads.opened("content://provider/doc/7", ProvidedFile("song.mp3", size = null))!!

        assertThat(tagger.calls).containsExactly(Triple("content://provider/doc/7", "song.mp3", null))
        assertThat(file.target)
            .isEqualTo(IdentificationTarget.OpenedFile("content://provider/doc/7", READ_KEY, "song.mp3", 4_321))
        assertThat(file.folder).isNull()
        assertThat(file.fileName).isEqualTo("song.mp3")
    }

    private companion object {
        const val FILE_ID = 42L
        const val URI = "content://media/external_primary/audio/media/10"
        val READ_KEY = "cd".repeat(32)
        val STORED_KEY = "ab".repeat(32)
    }
}
