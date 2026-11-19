package io.github.lorddetson.piplayer.nativelib.taglib

import android.app.Application
import io.github.lorddetson.piplayer.nativelib.NativeLibrary

enum class Id3v1WritePolicy(internal val nativeCode: Int) {
    UPDATE_IF_PRESENT(0), REMOVE(1), LEAVE_UNTOUCHED(2),
}

/** Writes selected property keys on the caller's working copy. */
object TagWriter {
    init {
        NativeLibrary.load()
    }

    fun write(
        fd: Int,
        changes: Map<String, List<String>>,
        id3v1Policy: Id3v1WritePolicy = Id3v1WritePolicy.UPDATE_IF_PRESENT,
        frontCovers: List<Picture>? = null,
        restoreId3v1: ByteArray? = null,
    ): Boolean {
        check(TagReader.anyProcess || Application.getProcessName().endsWith(":tagger")) {
            "TagWriter writes only in the :tagger process"
        }
        val entries = changes.entries.toList()
        val pictureBytes = frontCovers?.map { it.data ?: return false }?.toTypedArray()
        return nativeWrite(
            fd, entries.map { it.key }.toTypedArray(), entries.map { it.value.toTypedArray() }.toTypedArray(),
            id3v1Policy.nativeCode,
            frontCovers?.map { it.mimeType }?.toTypedArray(), frontCovers?.map { it.description }?.toTypedArray(), pictureBytes,
            restoreId3v1,
        )
    }

    @JvmStatic
    private external fun nativeWrite(
        fd: Int,
        keys: Array<String>,
        values: Array<Array<String>>,
        id3v1Policy: Int,
        pictureMimes: Array<String>?,
        pictureDescriptions: Array<String>?,
        pictureBytes: Array<ByteArray>?,
        restoreId3v1: ByteArray?,
    ): Boolean
}
