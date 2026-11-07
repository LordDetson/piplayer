package io.github.lorddetson.piplayer.covers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.lorddetson.piplayer.PiPlayerApplication
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // Each cover served holds two descriptors and a place in the loader's line until it is written.
        if (!admission.tryEnter(caller.uid)) throw FileNotFoundException("Too many covers at once")
        val (source, sink) = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (e: IOException) {
            admission.exit(caller.uid)
            throw FileNotFoundException("No pipe: ${e.message}")
        }
        scope.launch {
            try {
                serve(context, request, sink)
            } finally {
                admission.exit(caller.uid)
            }
        }
        return source
    }

    /** Writes the cover of [request] into [sink] and closes it; with an error unless all of it was written. */
    private suspend fun serve(context: Context, request: CoverRequest, sink: ParcelFileDescriptor) {
        var error = NO_COVER
        try {
            // A call that started the process may come before the application has set the library up.
            if (!PiPlayerApplication.awaitStarted(TIMEOUT_MS.toLong())) return
            val bytes = Covers.loader(context).encoded(request, PX, TIMEOUT_MS.toLong()) { !CoverPipe.readerGone(sink.fileDescriptor) }
                ?: return
            error = NOT_READ
            if (CoverPipe.write(sink.fileDescriptor, bytes, TIMEOUT_MS, TOTAL_WRITE_MS)) error = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever failed, the process must not go down for a cover, and the reader learns that there is none.
            Log.w(TAG, "A cover could not be served", e)
        } finally {
            close(sink, error)
        }
    }

    /** Closes [sink], with [error] for the reader unless it is empty; a reader that has gone needs no word. */
    private fun close(sink: ParcelFileDescriptor, error: String) {
        try {
            if (error.isEmpty()) sink.close() else sink.closeWithError(error)
        } catch (_: IOException) {
            try {
                sink.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Whether [uri] has the form of a cover URI, whatever it names. */
    private fun wellFormed(uri: Uri): Boolean {
        val context = context ?: return false
        return Covers.uris(context).wellFormed(uri.authority, uri.pathSegments)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** The size controllers get (§13.8.4). */
        const val PX = 512

        /** A load (once it has its permit) or an idle reader is given up after this long. */
        const val TIMEOUT_MS = 10_000

        /** A write that has taken this long in all is given up, however the reader keeps it going. */
        const val TOTAL_WRITE_MS = 30_000

        /** At most this many covers are served at a time; more opens are not found. */
        const val MAX_IN_FLIGHT = 64

        /** At most this many of them for one caller (uid), so that one that opens many and reads none starves no other. */
        const val MAX_PER_CALLER = 8

        const val MIME_TYPE = "image/jpeg"

        private const val TAG = "CoverProvider"
        private const val NO_COVER = "No cover"
        private const val NOT_READ = "The reader took nothing"

        private val admission = CoverAdmission(MAX_IN_FLIGHT, MAX_PER_CALLER)

        /**
         * Lives as long as the process. The loads run on the loader's threads, at most four at a time; the waits and writes
         * here on a few threads of their own, apart from the database's.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(WRITERS) +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "A cover could not be served", e) },
        )

        private const val WRITERS = 4
    }
}
