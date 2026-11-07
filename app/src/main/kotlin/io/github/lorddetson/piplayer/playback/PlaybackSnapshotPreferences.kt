package io.github.lorddetson.piplayer.playback

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.identity.TrackRef
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The keys of the one playback preference file (docs/playback.md §4.1). */
internal object PlaybackKeys {
    val schema = intPreferencesKey("schema")
    val queue = stringPreferencesKey("queue")
    val openItems = stringPreferencesKey("open_items")
    val songId = longPreferencesKey("song_id")
    val row = intPreferencesKey("row")
    val position = longPreferencesKey("position_ms")
    val shuffle = booleanPreferencesKey("shuffle")
    val repeat = intPreferencesKey("repeat")
    val title = stringPreferencesKey("title")
    val artist = stringPreferencesKey("artist")
    val album = stringPreferencesKey("album")
    val duration = longPreferencesKey("duration_ms")
    val uri = stringPreferencesKey("uri")

    // Schema 3: the current song as a track reference (docs/playback.md §4.8).
    val refFile = longPreferencesKey("ref_file_id")
    val refIdentity = longPreferencesKey("ref_identity_id")
    val refKey = stringPreferencesKey("ref_content_key")
    val refVolume = stringPreferencesKey("ref_volume")
    val refPath = stringPreferencesKey("ref_relative_path")
    val refName = stringPreferencesKey("ref_display_name")
    val refDuration = longPreferencesKey("ref_duration_ms")
    val libraryId = stringPreferencesKey("library_id")
    val queueRevision = longPreferencesKey("queue_revision")
}

private val openItemsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Replaces everything saved with [snapshot]; one snapshot is one edit of the file. */
internal fun MutablePreferences.putSnapshot(snapshot: PlaybackSnapshot) {
    clear()
    this[PlaybackKeys.schema] = PlaybackSnapshot.SCHEMA_VERSION
    snapshot.queue?.let { this[PlaybackKeys.queue] = it.mediaId }
    if (snapshot.openItems.isNotEmpty()) {
        this[PlaybackKeys.openItems] = openItemsJson.encodeToString(snapshot.openItems)
    }
    this[PlaybackKeys.songId] = snapshot.songId
    this[PlaybackKeys.row] = snapshot.row
    this[PlaybackKeys.position] = snapshot.positionMs
    this[PlaybackKeys.shuffle] = snapshot.shuffle
    this[PlaybackKeys.repeat] = snapshot.repeatMode
    snapshot.title?.let { this[PlaybackKeys.title] = it }
    snapshot.artist?.let { this[PlaybackKeys.artist] = it }
    snapshot.album?.let { this[PlaybackKeys.album] = it }
    this[PlaybackKeys.duration] = snapshot.durationMs
    snapshot.uri?.let { this[PlaybackKeys.uri] = it }
    snapshot.current?.let { ref ->
        this[PlaybackKeys.refFile] = ref.fileId
        ref.identityId?.let { this[PlaybackKeys.refIdentity] = it }
        ref.contentKey?.let { this[PlaybackKeys.refKey] = it }
        ref.place?.let {
            this[PlaybackKeys.refVolume] = it.volume
            this[PlaybackKeys.refPath] = it.relativePath
            this[PlaybackKeys.refName] = it.displayName
        }
        ref.durationMs?.let { this[PlaybackKeys.refDuration] = it }
    }
    snapshot.libraryId?.let { this[PlaybackKeys.libraryId] = it }
    if (snapshot.queueRevision > 0) this[PlaybackKeys.queueRevision] = snapshot.queueRevision
}

/** The saved snapshot, or null when there is none or it cannot be understood. */
internal fun Preferences.toSnapshot(): PlaybackSnapshot? = when (this[PlaybackKeys.schema]) {
    PlaybackSnapshot.LEGACY_SCHEMA_VERSION -> {
        val queue = this[PlaybackKeys.queue]?.let(QueueRequest::parse) ?: return null
        snapshot(queue = queue, openItems = emptyList())?.copy(mediaIndexIds = true)
    }
    PlaybackSnapshot.OPEN_SCHEMA_VERSION -> withOpenItems()?.copy(mediaIndexIds = true)
    PlaybackSnapshot.SCHEMA_VERSION -> withOpenItems()?.copy(
        current = currentRef(),
        libraryId = this[PlaybackKeys.libraryId],
        queueRevision = (this[PlaybackKeys.queueRevision] ?: 0L).coerceAtLeast(0),
    )
    else -> null
}

private fun Preferences.withOpenItems(): PlaybackSnapshot? {
    val queue = this[PlaybackKeys.queue]?.let { value -> QueueRequest.parse(value) ?: return null }
    val openItems = this[PlaybackKeys.openItems]?.let { value ->
        runCatching { openItemsJson.decodeFromString<List<SavedOpenItem>>(value) }.getOrNull() ?: return null
    } ?: emptyList()
    return snapshot(queue = queue, openItems = openItems)
}

private fun Preferences.currentRef(): TrackRef? {
    val fileId = this[PlaybackKeys.refFile] ?: return null
    val volume = this[PlaybackKeys.refVolume]
    val path = this[PlaybackKeys.refPath]
    val name = this[PlaybackKeys.refName]
    return TrackRef(
        fileId = fileId,
        identityId = this[PlaybackKeys.refIdentity],
        contentKey = this[PlaybackKeys.refKey],
        place = if (volume != null && path != null && name != null) Place(volume, path, name) else null,
        durationMs = this[PlaybackKeys.refDuration],
    )
}

private fun Preferences.snapshot(queue: QueueRequest?, openItems: List<SavedOpenItem>): PlaybackSnapshot? {
    val songId = this[PlaybackKeys.songId] ?: return null
    val row = this[PlaybackKeys.row] ?: return null
    val position = this[PlaybackKeys.position] ?: return null
    val shuffle = this[PlaybackKeys.shuffle] ?: return null
    val repeat = this[PlaybackKeys.repeat] ?: return null
    val duration = this[PlaybackKeys.duration] ?: 0L
    if (songId < -1 || row < 0 || position < 0 || repeat !in PlaybackSnapshot.REPEAT_OFF..PlaybackSnapshot.REPEAT_ALL) {
        return null
    }
    if (songId >= 0 && queue == null) return null
    if (queue == null && openItems.isEmpty()) return null
    if (queue == null && openItems.withIndex().any { (index, item) -> item.index != index }) return null
    return runCatching {
        PlaybackSnapshot(
            queue = queue,
            openItems = openItems,
            songId = songId,
            row = row,
            positionMs = position,
            shuffle = shuffle,
            repeatMode = repeat,
            title = this[PlaybackKeys.title],
            artist = this[PlaybackKeys.artist],
            album = this[PlaybackKeys.album],
            durationMs = duration.coerceAtLeast(0),
            uri = this[PlaybackKeys.uri],
        )
    }.getOrNull()
}
