package io.github.lorddetson.piplayer.library.tagpass

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import java.io.FileNotFoundException

/** Reads files of the library through `:tagger`, which gets an open file and never its name in the file system. */
class TaggerPassReader(
    private val context: Context,
    private val client: TaggerClient,
    private val uriOf: (PassFile) -> Uri = { ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(it.volume), it.mediaStoreId) },
) : PassReader {

    override suspend fun read(file: PassFile): PassRead {
        val descriptor = open(file) ?: return PassRead.OpenFailed
        return descriptor.use {
            when (val result = client.read(TagTarget.LibraryFile(file.fileId), it, file.displayName, markLegacy = true)) {
                is TaggerResult.Replied -> PassRead.Replied(result.value.report)
                TaggerResult.Crashed, TaggerResult.TimedOut -> PassRead.Crashed
                TaggerResult.Unsafe -> PassRead.Unsafe
            }
        }
    }

    private fun open(file: PassFile): ParcelFileDescriptor? = try {
        context.contentResolver.openFileDescriptor(uriOf(file), "r")
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
