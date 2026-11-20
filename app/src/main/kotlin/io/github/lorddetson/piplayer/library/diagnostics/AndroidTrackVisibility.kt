package io.github.lorddetson.piplayer.library.diagnostics

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.room3.useReaderConnection
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.playlists.M3uPaths
import io.github.lorddetson.piplayer.library.rules.FolderRuleState
import io.github.lorddetson.piplayer.library.sync.IndexPaths
import io.github.lorddetson.piplayer.settings.HiddenFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Checks the picked document and the current library without changing either. */
internal class AndroidTrackVisibility(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun check(document: Uri, settings: HiddenFiles): TrackVisibilityFinding = withContext(Dispatchers.IO) {
        if (document.scheme != ContentResolver.SCHEME_CONTENT) return@withContext TrackVisibilityFinding.Undetermined
        val readable = cancellableProviderCall { signal ->
            readOrNull { resolver.openAssetFileDescriptor(document, "r", signal)?.use { true } }
        }
        if (readable != true) return@withContext TrackVisibilityFinding.Inaccessible
        if (!Library.mayRead(context)) return@withContext TrackVisibilityFinding.NoLibraryPermission
        cancellableProviderCall { signal -> readOrNull { inspectIndex(document, settings, signal) } }
            ?: TrackVisibilityFinding.Undetermined
    }

    private fun inspectIndex(document: Uri, settings: HiddenFiles, signal: CancellationSignal): TrackVisibilityFinding {
        val externalVolumes = MediaStore.getExternalVolumeNames(context)
        val knownLocalAudio = document.authority == EXTERNAL_DOCUMENTS && DocumentsContract.isDocumentUri(context, document) &&
            M3uPaths.folderOf(document.authority, DocumentsContract.getDocumentId(document))?.volume?.let(externalVolumes::contains) == true &&
            resolver.getType(document)?.let { it.startsWith("audio/") || it == "application/ogg" } == true
        val missing = if (knownLocalAudio) TrackVisibilityFinding.NotInMediaStore else TrackVisibilityFinding.Undetermined
        val equivalent = mediaUri(document) ?: return missing
        if (equivalent.authority != MediaStore.AUTHORITY) return TrackVisibilityFinding.Undetermined
        val path = equivalent.pathSegments
        if (!(path.size == 4 && path[1] == "audio" && path[2] == "media") && !(path.size == 3 && path[1] == "file")) {
            return TrackVisibilityFinding.Undetermined
        }
        val audioId = path.last().toLongOrNull()?.takeIf { it > 0 } ?: return TrackVisibilityFinding.Undetermined
        val audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(path.first()), audioId)
        val audio = resolver.query(audioUri, AUDIO_LOCATION, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) return missing
            val volume = cursor.getString(0) ?: return TrackVisibilityFinding.Undetermined
            AudioLocation(volume, IndexPaths.relativePath(cursor.getString(1)), cursor.getString(2).orEmpty())
        } ?: return TrackVisibilityFinding.Undetermined
        if (audio.volume !in externalVolumes) return TrackVisibilityFinding.Undetermined

        val evidence = libraryEvidence(audioId, audio) ?: return TrackVisibilityFinding.PendingLibrary
        return diagnoseIndexedTrack(evidence, settings)
    }

    private suspend fun <T> cancellableProviderCall(read: (CancellationSignal) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            continuation.resumeWith(runCatching { read(signal) })
        }

    private fun mediaUri(document: Uri): Uri? = when {
        document.authority == MediaStore.AUTHORITY -> document
        !DocumentsContract.isDocumentUri(context, document) -> null
        document.authority == EXTERNAL_DOCUMENTS -> MediaStore.getMediaUri(context, document)
        document.authority != MEDIA_DOCUMENTS -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> MediaStore.getMediaUri(context, document)
        else -> {
            // Android 11's MediaDocumentsProvider opens audio:<id> from the external audio collection.
            val id = DocumentsContract.getDocumentId(document).takeIf { it.startsWith("audio:") }
                ?.removePrefix("audio:")?.toLongOrNull()?.takeIf { it > 0 }
            id?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        }
    }

    private data class AudioLocation(val volume: String, val relativePath: String, val name: String)

    private fun libraryEvidence(id: Long, location: AudioLocation): IndexedTrackEvidence? = Library.read(context) { library ->
        runBlocking {
            library.useReaderConnection { connection ->
                connection.usePrepared(LIBRARY_FACTS) { statement ->
                    statement.bindLong(1, id)
                    statement.bindText(2, location.volume)
                    statement.bindText(3, location.relativePath)
                    statement.bindText(4, location.name)
                    if (!statement.step()) return@usePrepared null
                    val evidence = IndexedTrackEvidence(
                        folderRule = FolderRuleState.entries.firstOrNull { it.stored == statement.getInt(0) } ?: FolderRuleState.NONE,
                        systemSound = statement.getInt(1) != 0,
                        recording = statement.getInt(2) != 0,
                        durationMs = if (statement.isNull(3)) null else statement.getLong(3),
                        hasDisplayRow = !statement.isNull(4),
                    )
                    evidence.takeUnless { statement.step() }
                }
            }
        }
    }

    private inline fun <T> readOrNull(read: () -> T): T? = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val EXTERNAL_DOCUMENTS = "com.android.externalstorage.documents"
        const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
        val AUDIO_LOCATION = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
        const val LIBRARY_FACTS = "SELECT f.folder_rule, (f.is_ringtone OR f.is_notification OR f.is_alarm), " +
            "f.recording, d.duration_ms, d.file_id FROM audio_file f LEFT JOIN track_display d ON d.file_id = f.id " +
            "WHERE f.media_store_id = ? AND f.volume_name = ? AND f.relative_path = ? AND f.display_name = ? LIMIT 2"
    }
}
