package io.github.lorddetson.piplayer.tagging

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.nativelib.taglib.Pictures
import io.github.lorddetson.piplayer.nativelib.taglib.TagReader

/** The tag reader of the `:tagger` process: native calls only, no database, no DataStore (docs/library.md §4.1). */
class TaggerService : Service() {

    private val binder = object : ITagger.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun read(file: ParcelFileDescriptor, fileName: String?): TaggerReply = file.use {
            val report = TagReport.of(TagReader.read(it.fd, fileName, Pictures.SIZES))
            TaggerReply(TagReplies.pack(report, TaggerClient.spillDir(this@TaggerService)))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessRole.current(this).requireTagger("The tag reader service")
    }

    override fun onBind(intent: Intent): IBinder = binder
}
