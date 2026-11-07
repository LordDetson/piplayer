package io.github.lorddetson.piplayer.covers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serves covers to controllers outside PiPlayer by content URI (docs/library.md §13.8): read only, to the callers that
 * may read the library, at the 512 px step, through a pipe that is written off the binder thread.
 */
class CoverProvider : ContentProvider() {

    /** Nothing at start (§13.8.7): the loader and the URIs come with the first open. */
    override fun onCreate(): Boolean = true

    /** The type of a URI of the right form; nothing is looked up and no caller checked, so it tells nothing. */
    override fun getType(uri: Uri): String? = if (wellFormed(uri)) MIME_TYPE else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        open(uri, mode, CoverAccess.Caller(Binder.getCallingUid(), Binder.getCallingPid(), callerPackage()))

    /**
     * The package the caller names, as Android checked it against its uid; null when it names none or one that is not
     * its own (`getCallingPackage` then throws), so that only the uid counts.
     */
    private fun callerPackage(): String? = try {
        callingPackage
    } catch (_: RuntimeException) {
        null
    }

    /**
     * [openFile] for [caller]; tests call it with callers of other apps. Checks the mode, the caller and the URI's form,
     * then hands out the read end of a pipe that the cover is written into off this thread (§13.8.4).
     */
    internal fun open(uri: Uri, mode: String, caller: CoverAccess.Caller): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read only")
        val context = checkNotNull(context) { "Not attached" }.applicationContext
        if (!CoverAccess.allows(context, caller)) throw SecurityException("This caller may not read the music library")
        val request = Covers.uris(context).parse(uri.authority, uri.pathSegments) ?: throw FileNotFoundException("No cover")
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        scope.launch { serve(context, request, sink) }
        return source
    }

    /** Writes the cover of [request] into [sink] and closes it; with an error when there is none. */
    private suspend fun serve(context: Context, request: CoverRequest, sink: ParcelFileDescriptor) {
        try {
            val bytes = try {
                withTimeoutOrNull(TIMEOUT_MS.toLong()) { Covers.loader(context).encoded(request, PX) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A cover could not be served", e)
                null
            }
            if (bytes == null) {
                sink.closeWithError(NO_COVER)
                return
            }
            if (CoverPipe.write(sink.fileDescriptor, bytes, TIMEOUT_MS)) sink.close() else sink.closeWithError(NOT_READ)
        } catch (_: IOException) {
            // The reader has gone; nothing to tell.
        } finally {
            // Closing twice does nothing; a sink left open would keep the reader waiting.
            try {
                sink.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Whether [uri] has the form of a cover URI, whatever it names. */
    private fun wellFormed(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return uri.authority == context?.packageName?.let(CoverUris::authorityOf) && segments.size == 3 &&
            segments[0] in KINDS && segments[1].isNotEmpty() && segments[1].all { it in '0'..'9' } &&
            segments[2].length > CoverUris.TAG_LENGTH && segments[2].all { it in '0'..'9' || it in 'a'..'z' }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** The size controllers get (§13.8.4). */
        const val PX = 512

        /** A load or an idle reader is given up after this long. */
        const val TIMEOUT_MS = 10_000

        const val MIME_TYPE = "image/jpeg"

        private const val TAG = "CoverProvider"
        private const val NO_COVER = "No cover"
        private const val NOT_READ = "The reader took nothing"
        private val KINDS = setOf("s", "a", "r")

        /** Lives as long as the process; the loads themselves run on the loader's threads, at most four at a time. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
