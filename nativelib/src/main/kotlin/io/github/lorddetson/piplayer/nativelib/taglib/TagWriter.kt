package io.github.lorddetson.piplayer.nativelib.taglib

import android.app.Application
import io.github.lorddetson.piplayer.nativelib.NativeLibrary

/** Writes selected property keys on the caller's working copy. */
object TagWriter {
    init {
        NativeLibrary.load()
    }

    fun write(fd: Int, changes: Map<String, List<String>>): Boolean {
        check(TagReader.anyProcess || Application.getProcessName().endsWith(":tagger")) {
            "TagWriter writes only in the :tagger process"
        }
        val entries = changes.entries.toList()
        return nativeWrite(fd, entries.map { it.key }.toTypedArray(), entries.map { it.value.toTypedArray() }.toTypedArray())
    }

    @JvmStatic
    private external fun nativeWrite(fd: Int, keys: Array<String>, values: Array<Array<String>>): Boolean
}
