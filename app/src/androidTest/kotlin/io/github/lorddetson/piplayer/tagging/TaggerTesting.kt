package io.github.lorddetson.piplayer.tagging

import android.app.ActivityManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.playback.PlaybackTesting
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

/** Files and helpers for the tag reader's tests (docs/library.md §4.6). */
object TaggerTesting {

    private val context = TestFiles.targetContext

    /** Makes the debug native reader call abort() (docs/library.md §4.5). */
    const val ABORT = "PIPLAYER-TEST-ABORT"

    /** Makes the debug native reader sleep until it is killed. */
    const val HANG = "PIPLAYER-TEST-HANG"

    /** A file that starts with [marker]. */
    fun marked(name: String, marker: String): File =
        File(context.cacheDir, name).apply { writeBytes(marker.toByteArray() + ByteArray(4_096)) }

    /** A file of random bytes, which TagLib does not recognise. */
    fun garbage(name: String): File = File(context.cacheDir, name).apply { writeBytes(kotlin.random.Random(3).nextBytes(4_096)) }

    /** An MP3 whose ID3v2.4 tag holds a TXXX frame per entry of [values] (description to value), then a 2 s tone. */
    fun withUserText(name: String, values: Map<String, String>): File {
        val frames = ByteArrayOutputStream()
        for ((description, value) in values) {
            // Encoding 3 is UTF-8.
            val body = byteArrayOf(3) + description.toByteArray() + byteArrayOf(0) + value.toByteArray()
            frames.write("TXXX".toByteArray())
            frames.write(syncSafe(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        val tag = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncSafe(frames.size()) + frames.toByteArray()
        val audio = TestFiles.assets.open(PlaybackTesting.SHORT_FIXTURE).use { it.readBytes() }
        return File(context.cacheDir, name).apply { writeBytes(tag + audio) }
    }

    private fun syncSafe(size: Int) = byteArrayOf(
        (size shr 21 and 0x7f).toByte(),
        (size shr 14 and 0x7f).toByte(),
        (size shr 7 and 0x7f).toByte(),
        (size and 0x7f).toByte(),
    )

    fun uri(file: File): String = Uri.fromFile(file).toString()

    /** Reads [file] through [client], counted as an outside file, or as [target] when given. */
    fun TaggerClient.readFile(file: File, target: TagTarget? = null): TaggerResult<TagRead> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            runBlocking { read(target ?: outsideTarget(uri(file), pfd), pfd, file.name) }
        }

    fun TaggerResult<TagRead>.report(): TagReport = (this as TaggerResult.Replied).value.report

    /** The ID of the running `:tagger` process, or null. */
    fun taggerPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == context.packageName + ":tagger" }?.pid

    fun processExists(pid: Int) = File("/proc/$pid").exists()

    /** A library database of its own in a file, so that a second connection sees only what was committed. */
    fun freshDatabase(name: String): Pair<LibraryDatabase, File> {
        val file = File(context.cacheDir, name)
        SQLiteDatabase.deleteDatabase(file)
        return LibraryDatabase.open(context, file) to file
    }

    fun counts(database: LibraryDatabase) = LibraryUnsafeCounts(database)

    fun client(database: LibraryDatabase, timeout: Duration, idle: Duration = TaggerClient.IDLE) =
        TaggerClient(context, LibraryUnsafeCounts(database), timeout, idle)
}
