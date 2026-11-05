package io.github.lorddetson.piplayer.tagging

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.nativelib.taglib.Pictures
import io.github.lorddetson.piplayer.nativelib.taglib.TagReader
import io.github.lorddetson.piplayer.tagging.contentkey.ContentMeasure
import io.github.lorddetson.piplayer.tagging.contentkey.FdByteSource
import io.github.lorddetson.piplayer.tagging.contentkey.FrameWalk

/** The tag reader of the `:tagger` process: native calls only, no database, no DataStore (docs/library.md §4.1). */
class TaggerService : Service() {

    private val binder = object : ITagger.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun read(file: ParcelFileDescriptor, fileName: String?, markLegacy: Boolean): TaggerReply = file.use {
            val read = TagReader.read(it.fd, fileName, Pictures.SIZES, markLegacy)
            val content = ContentMeasure.of(FdByteSource(it.fileDescriptor), read) { TagReader.mpegLayout(it.fd) }
            val report = TagReport.of(read).copy(content = content)
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

    override fun onCreate() {
        super.onCreate()
        ProcessRole.current(this).requireTagger("The tag reader service")
    }

    override fun onBind(intent: Intent): IBinder = binder
}
