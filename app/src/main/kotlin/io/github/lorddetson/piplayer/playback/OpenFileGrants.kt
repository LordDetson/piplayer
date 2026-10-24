package io.github.lorddetson.piplayer.playback

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * The persisted read grants of the opened files (docs/playback.md §5.4). A file manager usually grants a temporary
 * permission that ends with the activity; a provider that offers a persistable grant keeps the file readable after the
 * process dies. The previous queue's grants are given back when it is replaced.
 */
class OpenFileGrants(private val resolver: ContentResolver) {

    /**
     * The grants this app already holds. Persisted grants outlive the process, so they are read back on start; the
     * next queue change releases those that are no longer in it.
     */
    private val taken: MutableSet<String> = runCatching {
        resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(mutableSetOf()) { it.uri.toString() }
    }.getOrElse { mutableSetOf() }

    /** Takes what the provider offers and remembers it; a grant read back on start is kept if still in the queue. */
    @Synchronized
    fun take(uris: Collection<String>) {
        for (value in uris) {
            if (value in taken) continue
            val uri = Uri.parse(value)
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) continue
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                taken += value
            }
        }
    }

    /** Releases grants this app holds for files that are no longer in [uris]. */
    @Synchronized
    fun keepOnly(uris: Collection<String>) {
        val keep = uris.toSet()
        val release = taken - keep
        for (value in release) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            taken -= value
        }
    }
}
