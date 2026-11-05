package io.github.lorddetson.piplayer.tagging

import android.content.ComponentName
import android.os.Parcel
import android.os.Process
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.nativelib.taglib.TagReader
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.tagging.TaggerTesting.HANG
import io.github.lorddetson.piplayer.tagging.TaggerTesting.readFile
import io.github.lorddetson.piplayer.tagging.TaggerTesting.report
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The tag reader process and the calls of the main process (docs/library.md §4). */
@RunWith(AndroidJUnit4::class)
class TaggerClientTest {

    private val context = TestFiles.targetContext
    private val opened = TaggerTesting.freshDatabase("tagger-client-test.db")
    private val database = opened.first
    private val databaseFile = opened.second
    private val counts = LibraryUnsafeCounts(database)
    private val clients = mutableListOf<TaggerClient>()
    private val others = mutableListOf<LibraryDatabase>()
    private var controller: MediaController? = null

    private fun client(timeout: Duration = SHORT_TIMEOUT, idle: Duration = TaggerClient.IDLE) =
        TaggerTesting.client(database, timeout, idle).also { clients += it }

    @Test
    fun aBackgroundCallRunsAtBackgroundPriorityAndLeavesNoTraceForTheNext() = runBlocking<Unit> {
        val guard = TagReadGuard(counts)
        val target = TagTarget.Outside("uri", 1, 1)
        val priority = { Process.getThreadPriority(Process.myTid()) }

        val background = guard.run(target, kill = {}, background = true, call = priority)
        val foreground = guard.run(target, kill = {}, call = priority)

        assertThat(background).isEqualTo(TaggerResult.Replied(Process.THREAD_PRIORITY_BACKGROUND))
        assertThat(foreground).isEqualTo(TaggerResult.Replied(Process.THREAD_PRIORITY_DEFAULT))
    }

    @After
    fun close() {
        controller?.stopAndRelease()
        clients.forEach { it.close() }
        others.forEach { it.close() }
        database.close()
    }

    private val tagged: File get() = TestFiles.copy("playback/tagged-44100-stereo.mp3")

    /** A target of its own, so that one count can follow reads of different files. */
    private val target = TagTarget.Outside("test://tagger/one", size = 1, mtimeMs = 1)

    private fun countOf(target: TagTarget) = runBlocking { counts.count(target) }

    @Test
    fun theServiceRunsInTheTaggerProcessAndIsNotExported() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, TaggerService::class.java), 0)

        assertThat(service.processName).isEqualTo(context.packageName + ":tagger")
        assertThat(service.exported).isFalse()
    }

    @Test
    fun tagLibRefusesToReadInTheMainProcess() {
        TestFiles.open(tagged).use { pfd ->
            assertThatThrownBy { TagReader.read(pfd.fd, "tagged.mp3") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("TagReader reads only in the :tagger process")
        }
    }

    @Test
    fun aTaggedFileIsReadInTheTaggerProcess() {
        val result = client().readFile(tagged)

        val read = (result as TaggerResult.Replied).value
        assertThat(read.viaFile).isFalse()
        assertThat(read.report.error).isNull()
        assertThat(read.report.properties).containsEntry("TITLE", listOf("Ёжик в тумане"))
            .containsEntry("ARTIST", listOf("PiPlayer Fixtures"))
            .containsEntry("ALBUM", listOf("Тесты"))
        val audio = read.report.audio!!
        assertThat(audio.sampleRate).isEqualTo(44_100)
        assertThat(audio.channels).isEqualTo(2)
        assertThat(audio.lengthMs).isBetween(1_900, 2_200)
        assertThat(TaggerTesting.taggerPid()).isNotNull().isNotEqualTo(Process.myPid())
    }

    @Test
    fun picturesArriveAsSizesWithoutTheirBytes() {
        val report = client().readFile(TestFiles.copy("playback/cover-front-then-back.flac")).report()

        assertThat(report.pictures.map { it.pictureType }).containsExactly("Front Cover", "Back Cover")
        for (picture in report.pictures) {
            assertThat(picture.mimeType).isEqualTo("image/png")
            assertThat(picture.sizeBytes).isPositive()
        }
    }

    @Test
    fun aFileTagLibCannotReadGivesItsReasonAndClearsTheCount() {
        val client = client()
        assertThat(client.readFile(TaggerTesting.marked("hang-then-garbage.bin", HANG), target))
            .isEqualTo(TaggerResult.TimedOut)
        assertThat(countOf(target)).isEqualTo(1)

        val report = client.readFile(TaggerTesting.garbage("garbage.bin"), target).report()

        assertThat(report.error).isEqualTo(TagReadError.UNSUPPORTED_FORMAT)
        assertThat(countOf(target)).isZero()
    }

    @Test
    fun aValueOver16KiBArrivesThroughAFileThatIsThenDeleted() {
        val notes = "a".repeat(20_000)
        val file = TaggerTesting.withUserText("long-value.mp3", mapOf("NOTES" to notes))

        val read = (client().readFile(file) as TaggerResult.Replied).value

        assertThat(read.viaFile).isTrue()
        assertThat(read.report.properties["NOTES"]).containsExactly(notes)
        assertThat(TaggerClient.spillDir(context).listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun aReplyOver120KiBArrivesThroughAFile() {
        val values = (1..12).associate { "NOTES$it" to "$it".repeat(12_000) }
        val file = TaggerTesting.withUserText("large-reply.mp3", values)

        val read = (client().readFile(file) as TaggerResult.Replied).value

        assertThat(read.viaFile).isTrue()
        for ((key, value) in values) assertThat(read.report.properties[key]).containsExactly(value)
        assertThat(TaggerClient.spillDir(context).listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun theLargestInlineReplyParcelsUnder128KB() {
        val dir = File(context.cacheDir, "tagger-parcel-test").apply { mkdirs() }
        val values = (0 until 10).associate { "KEY$it" to listOf("a".repeat(12_000)) }
        val base = TagReplies.pack(TagReport(properties = values), dir) as TagReplyBody.Inline
        val padding = TagReplies.MAX_INLINE_BYTES - base.json.size
        val report = TagReport(properties = values + ("KEY9" to listOf("a".repeat(12_000 + padding))))
        val body = TagReplies.pack(report, dir) as TagReplyBody.Inline
        assertThat(body.json.size).isEqualTo(TagReplies.MAX_INLINE_BYTES)

        val parcel = Parcel.obtain()
        try {
            TaggerReply(body).writeToParcel(parcel, 0)
            assertThat(parcel.dataSize()).isLessThan(128 * 1024)
            parcel.setDataPosition(0)
            val back = TaggerReply.CREATOR.createFromParcel(parcel).body
            assertThat(TagReplies.unpack(back, dir)).isEqualTo(report)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun replyFilesLeftByADeadProcessAreDeletedWhenTheClientConnects() {
        val stale = File(TaggerClient.spillDir(context).apply { mkdirs() }, "stale.json").apply { writeText("{}") }

        client().readFile(tagged)

        assertThat(stale).doesNotExist()
    }

    @Test
    fun theCountIsCommittedBeforeTheCall() = runBlocking<Unit> {
        val other = LibraryDatabase.open(context, databaseFile).also { others += it }
        val client = client()
        val file = TaggerTesting.marked("hang-committed.bin", HANG)

        val call = async(Dispatchers.IO) { client.readFile(file, target) }
        var seenWhileRunning = false
        eventually("the count is visible to another connection") {
            val count = runBlocking { other.library().outsideFile(target.uri)?.unsafeCount }
            seenWhileRunning = count == 1 && !call.isCompleted
            count == 1 || call.isCompleted
        }

        assertThat(seenWhileRunning).isTrue()
        assertThat(call.await()).isEqualTo(TaggerResult.TimedOut)
    }

    @Test
    fun aHangingCallIsKilledAfterTheTimeoutAndCounted() {
        val client = client()
        client.readFile(tagged)
        val pid = TaggerTesting.taggerPid()!!
        val file = TaggerTesting.marked("hang-once.bin", HANG)
        val start = TimeSource.Monotonic.markNow()

        val result = client.readFile(file)

        assertThat(result).isEqualTo(TaggerResult.TimedOut)
        assertThat(start.elapsedNow()).isGreaterThanOrEqualTo(SHORT_TIMEOUT).isLessThan(SHORT_TIMEOUT + 5.seconds)
        eventually("the killed :tagger is gone") { !TaggerTesting.processExists(pid) }
        assertThat(countOf(TestFiles.open(file).use { outsideTarget(TaggerTesting.uri(file), it) })).isEqualTo(1)
    }

    @Test
    fun aSecondFailureMakesTheFileUnsafeAndLaterCallsReturnAtOnce() {
        val client = client()
        val file = TaggerTesting.marked("hang-twice.bin", HANG)
        assertThat(client.readFile(file)).isEqualTo(TaggerResult.TimedOut)
        assertThat(client.readFile(file)).isEqualTo(TaggerResult.TimedOut)
        val start = TimeSource.Monotonic.markNow()

        val result = client.readFile(file)

        assertThat(result).isEqualTo(TaggerResult.Unsafe)
        assertThat(start.elapsedNow()).isLessThan(1.seconds)
    }

    @Test
    fun aGoodFileReadsAfterAKill() {
        val client = client()
        client.readFile(TaggerTesting.marked("hang-before-good.bin", HANG))

        val report = client.readFile(tagged).report()

        assertThat(report.properties).containsEntry("TITLE", listOf("Ёжик в тумане"))
    }

    @Test
    fun aLibraryFileIsCountedInItsRow() {
        val index = FakeMediaIndex().apply { volume().rows = listOf(row(1)) }
        val sync = LibrarySync(database, index, mayRead = { true })
        try {
            runBlocking { sync.sync() }
        } finally {
            sync.close()
        }
        val library = TagTarget.LibraryFile(runBlocking { database.library().files() }.single().id)
        val client = client()
        val file = TaggerTesting.marked("hang-library.bin", HANG)

        assertThat(client.readFile(file, library)).isEqualTo(TaggerResult.TimedOut)
        assertThat(client.readFile(file, library)).isEqualTo(TaggerResult.TimedOut)

        assertThat(runBlocking { database.library().files() }.single().unsafeCount).isEqualTo(2)
        assertThat(client.readFile(tagged, library)).isEqualTo(TaggerResult.Unsafe)
    }

    @Test
    fun aSongKeepsPlayingWhileTaggerIsKilled() {
        val controller = connectController().also { controller = it }
        controller.play(LONG_FIXTURE)
        val before = onMain { controller.currentPosition }

        val result = client().readFile(TaggerTesting.marked("hang-while-playing.bin", HANG))

        assertThat(result).isEqualTo(TaggerResult.TimedOut)
        assertThat(onMain { controller.isPlaying }).isTrue()
        assertThat(onMain { controller.currentPosition }).isGreaterThan(before + 2_000)
    }

    @Test
    fun theClientUnbindsWhenIdleAndBindsAgainForTheNextCall() {
        val client = client(idle = 1.seconds)
        client.readFile(tagged)
        assertThat(client.isBound).isTrue()

        eventually("the client unbinds", 5.seconds) { !client.isBound }

        assertThat(client.readFile(tagged)).isInstanceOf(TaggerResult.Replied::class.java)
    }

    @Test
    fun theRealTimeoutIsThirtySeconds() {
        val client = client(timeout = TagReadGuard.DEFAULT_TIMEOUT)
        val start = TimeSource.Monotonic.markNow()

        val result = client.readFile(TaggerTesting.marked("hang-thirty.bin", HANG))

        assertThat(result).isEqualTo(TaggerResult.TimedOut)
        assertThat(start.elapsedNow()).isGreaterThanOrEqualTo(30.seconds).isLessThan(40.seconds)
    }

    private companion object {
        val SHORT_TIMEOUT = 3.seconds
    }
}
