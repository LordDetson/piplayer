package io.github.lorddetson.piplayer.tagging

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.nativelib.taglib.Pictures
import io.github.lorddetson.piplayer.nativelib.taglib.TagReader
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadResult
import io.github.lorddetson.piplayer.nativelib.taglib.TagWriter
import io.github.lorddetson.piplayer.tagging.contentkey.ByteSource
import io.github.lorddetson.piplayer.tagging.contentkey.ContentMeasure
import io.github.lorddetson.piplayer.tagging.contentkey.FdByteSource
import io.github.lorddetson.piplayer.tagging.contentkey.FrameWalk
import io.github.lorddetson.piplayer.tagging.contentkey.KeyFormat
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.Json

/** The tag reader of the `:tagger` process: native calls only, no database, no DataStore (docs/library.md §4.1). */
class TaggerService : Service() {

    private val binder = object : ITagger.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun read(
            file: ParcelFileDescriptor, fileName: String?, markLegacy: Boolean, frontCoverData: Boolean, legacyId3Data: Boolean,
        ): TaggerReply = file.use {
            val mode = if (frontCoverData) Pictures.WRITE_FRONT else Pictures.SIZES
            val read = TagReader.read(it.fd, fileName, mode, markLegacy, legacyId3Data)
            val source = FdByteSource(it.fileDescriptor)
            val content = ContentMeasure.of(source, read) { TagReader.mpegLayout(it.fd) }
            val version = if (content?.token == KeyFormat.MP3.token) id3Version(source) else null
            val frontCovers = (read as? TagReadResult.Success)?.pictures?.takeIf { frontCoverData && it.all { picture -> picture.data != null } }
                ?.map { picture -> TagArtwork.of(picture.mimeType, picture.description, checkNotNull(picture.data)) }
            val report = TagReport.of(read).copy(content = content, id3Version = version, frontCovers = frontCovers)
            TaggerReply(TagReplies.pack(report, TaggerClient.spillDir(this@TaggerService)))
        }

        override fun walkDuration(file: ParcelFileDescriptor, flacEndOf: Long): Long = file.use {
            lowPriority {
                if (flacEndOf < 0) {
                    FrameWalk.walkMs(this@TaggerService, it.fileDescriptor)
                } else {
                    FrameWalk.flacEndMs(this@TaggerService, it.fileDescriptor, flacEndOf)
                } ?: -1
            }
        }

        override fun writeWorkingCopy(file: ParcelFileDescriptor, changes: ParcelFileDescriptor): Boolean =
            file.use { audio ->
                ParcelFileDescriptor.AutoCloseInputStream(changes).bufferedReader().use { reader ->
                    lowPriority {
                        val edits = Json.decodeFromString<TagFileEdits>(reader.readText())
                        val legacy = edits.restoreId3v1?.let { Base64.getDecoder().decode(it) }
                        TagWriter.write(audio.fd, edits.properties, edits.id3v1Policy, edits.frontCovers?.map(TagArtwork::picture), legacy)
                    }
                }
            }

        override fun previewId3v1(tag: ByteArray, changes: ParcelFileDescriptor): Array<String>? =
            ParcelFileDescriptor.AutoCloseInputStream(changes).bufferedReader().use { reader ->
                val edits = Json.decodeFromString<TagFileEdits>(reader.readText())
                TagWriter.previewId3v1(tag, edits.properties)
            }

        /** A walk reads a whole file: it yields to the music (docs/library.md §7). */
        private inline fun <T> lowPriority(block: () -> T): T {
            val priority = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                return block()
            } finally {
                Process.setThreadPriority(priority)
            }
        }
    }

    private fun id3Version(source: ByteSource): Int? {
        val header = ByteArray(4)
        try {
            if (source.read(0, header, 0, header.size) != header.size) return null
        } catch (_: IOException) {
            return null
        }
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        return header[3].toInt().takeIf { it in 2..4 }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessRole.current(this).requireTagger("The tag reader service")
    }

    override fun onBind(intent: Intent): IBinder = binder
}
