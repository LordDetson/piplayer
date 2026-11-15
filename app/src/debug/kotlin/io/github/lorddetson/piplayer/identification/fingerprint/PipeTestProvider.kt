package io.github.lorddetson.piplayer.identification.fingerprint

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug builds only: a document provider in a process of its own that hands out a WAV file through a reliable pipe
 * (`ParcelFileDescriptor.createReliablePipe`), as USB or MTP storage does, for the fingerprint cache's tests (TI §3.2,
 * §11.2). [Mode.COMPLETE] writes the whole file and closes the pipe; [Mode.FAILS] writes half of it and then closes the
 * pipe with [ERROR], as a provider whose device was unplugged does.
 */
class PipeTestProvider : ContentProvider() {

    enum class Mode { COMPLETE, FAILS }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val pipeMode = Mode.valueOf(uri.lastPathSegment.orEmpty())
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread({
            try {
                // Not owning the descriptor, the stream leaves the end to close or closeWithError.
                FileOutputStream(write.fileDescriptor).apply {
                    write(if (pipeMode == Mode.COMPLETE) WAV else WAV.copyOf(WAV.size / 2))
                    flush()
                }
                if (pipeMode == Mode.COMPLETE) write.close() else write.closeWithError(ERROR)
            } catch (_: IOException) {
                // The reader gave up and closed its end.
                runCatching { write.close() }
            }
        }, "pipe-test-writer").apply { isDaemon = true; start() }
        return read
    }

    override fun getType(uri: Uri): String = "audio/wav"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val ERROR = "The device was unplugged"

        fun uri(context: Context, mode: Mode): Uri =
            Uri.parse("content://${context.packageName}.pipetest/${mode.name}")

        /** 12 s of a tune, a new random note every quarter second, mono 16-bit at 22 050 Hz. */
        private val WAV: ByteArray by lazy {
            val rate = 22_050
            val random = Random(5)
            val data = ByteBuffer.allocate(rate * 12 * 2).order(ByteOrder.LITTLE_ENDIAN)
            var frequency = 440.0
            for (i in 0 until rate * 12) {
                if (i % (rate / 4) == 0) frequency = 200.0 + random.nextInt(1_800)
                data.putShort((sin(2 * PI * frequency * i / rate) * 12_000).toInt().toShort())
            }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
                putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data.capacity())
            }
            header.array() + data.array()
        }
    }
}
