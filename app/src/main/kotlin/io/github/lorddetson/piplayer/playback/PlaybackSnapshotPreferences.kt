package io.github.lorddetson.piplayer.playback

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
}

/** The saved snapshot, or null when there is none or it cannot be understood. */
internal fun Preferences.toSnapshot(): PlaybackSnapshot? = when (this[PlaybackKeys.schema]) {
    PlaybackSnapshot.LEGACY_SCHEMA_VERSION -> {
        val queue = this[PlaybackKeys.queue]?.let(QueueRequest::parse) ?: return null
        snapshot(queue = queue, openItems = emptyList())
    }
    PlaybackSnapshot.SCHEMA_VERSION -> {
        val queue = this[PlaybackKeys.queue]?.let { value -> QueueRequest.parse(value) ?: return null }
        val openItems = this[PlaybackKeys.openItems]?.let { value ->
            runCatching { openItemsJson.decodeFromString<List<SavedOpenItem>>(value) }.getOrNull() ?: return null
        } ?: emptyList()
        snapshot(queue = queue, openItems = openItems)
    }
    else -> null
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
