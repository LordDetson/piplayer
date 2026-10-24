package io.github.lorddetson.piplayer.playback

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlaybackSnapshotPreferencesTest {

    private val snapshot = PlaybackSnapshot(
        queue = QueueRequest(startSongId = 42, startRow = 7),
        songId = 42,
        row = 7,
        positionMs = 65_432,
        shuffle = true,
        repeatMode = PlaybackSnapshot.REPEAT_ONE,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000,
        uri = "content://media/external/audio/media/42",
    )

    private val opened = OpenedFile(
        uri = "content://open/song",
        title = "Opened",
        artist = "Someone",
        album = "Somewhere",
        durationMs = 90_000,
    )

    private val openSnapshot = PlaybackSnapshot(
        queue = null,
        openItems = listOf(SavedOpenItem(0, opened)),
        songId = -1,
        row = 0,
        positionMs = 4_000,
        shuffle = false,
        repeatMode = PlaybackSnapshot.REPEAT_OFF,
        title = "Opened",
        artist = "Someone",
        album = "Somewhere",
        durationMs = 90_000,
        uri = opened.uri,
    )

    private val mixedSnapshot = PlaybackSnapshot(
        queue = QueueRequest(startSongId = 42, startRow = 2),
        openItems = listOf(SavedOpenItem(2, opened)),
        songId = -1,
        row = 2,
        positionMs = 4_000,
        shuffle = false,
        repeatMode = PlaybackSnapshot.REPEAT_OFF,
        title = "Opened",
        artist = "Someone",
        album = "Somewhere",
        durationMs = 90_000,
        uri = opened.uri,
    )

    @Test
    fun `a snapshot survives the preferences round trip`() {
        val preferences = mutablePreferencesOf()

        preferences.putSnapshot(snapshot)

        assertThat(preferences.toSnapshot()).isEqualTo(snapshot)
    }

    @Test
    fun `a queue of opened files survives the preferences round trip`() {
        val preferences = mutablePreferencesOf()

        preferences.putSnapshot(openSnapshot)

        assertThat(preferences.toSnapshot()).isEqualTo(openSnapshot)
    }

    @Test
    fun `opened files mixed into a library queue survive the preferences round trip`() {
        val preferences = mutablePreferencesOf()

        preferences.putSnapshot(mixedSnapshot)

        assertThat(preferences.toSnapshot()).isEqualTo(mixedSnapshot)
    }

    @Test
    fun `a snapshot without optional metadata survives too`() {
        val plain = snapshot.copy(title = null, artist = null, album = null, durationMs = 0, uri = null)
        val preferences = mutablePreferencesOf()

        preferences.putSnapshot(plain)

        assertThat(preferences.toSnapshot()).isEqualTo(plain)
        assertThat(preferences[PlaybackKeys.title]).isNull()
        assertThat(preferences[PlaybackKeys.artist]).isNull()
        assertThat(preferences[PlaybackKeys.album]).isNull()
        assertThat(preferences[PlaybackKeys.uri]).isNull()
    }

    @Test
    fun `saving replaces everything the previous snapshot had`() {
        val preferences = mutablePreferencesOf()
        preferences.putSnapshot(openSnapshot)

        preferences.putSnapshot(snapshot)

        assertThat(preferences.toSnapshot()).isEqualTo(snapshot)
        assertThat(preferences[PlaybackKeys.openItems]).isNull()
    }

    @Test
    fun `a library-only snapshot from the previous schema is read`() {
        val preferences = mutablePreferencesOf()
        preferences.putSnapshot(snapshot)
        preferences[PlaybackKeys.schema] = PlaybackSnapshot.LEGACY_SCHEMA_VERSION
        preferences.remove(PlaybackKeys.openItems)

        assertThat(preferences.toSnapshot()).isEqualTo(snapshot)
    }

    @Test
    fun `nothing saved is no snapshot`() {
        assertThat(mutablePreferencesOf().toSnapshot()).isNull()
    }

    @Test
    fun `a snapshot of another schema is ignored`() {
        val preferences = mutablePreferencesOf()
        preferences.putSnapshot(snapshot)
        preferences[PlaybackKeys.schema] = PlaybackSnapshot.SCHEMA_VERSION + 1

        assertThat(preferences.toSnapshot()).isNull()
    }

    @Test
    fun `a queue request that cannot be parsed is ignored`() {
        val preferences = mutablePreferencesOf()
        preferences.putSnapshot(snapshot)
        preferences[PlaybackKeys.queue] = "q1/songs?sort=artist&start=42&index=7"

        assertThat(preferences.toSnapshot()).isNull()
    }

    @Test
    fun `opened files that cannot be parsed are ignored`() {
        val preferences = mutablePreferencesOf()
        preferences.putSnapshot(openSnapshot)
        preferences[PlaybackKeys.openItems] = "not json"

        assertThat(preferences.toSnapshot()).isNull()
    }

    @Test
    fun `a current opened file has to be in the saved queue`() {
        val preferences = mutablePreferencesOf()
        preferences.putSnapshot(openSnapshot)
        preferences[PlaybackKeys.uri] = "content://open/somewhere-else"

        assertThat(preferences.toSnapshot()).isNull()
    }

    @Test
    fun `a missing required key is ignored`() {
        for (key in listOf(PlaybackKeys.schema, PlaybackKeys.queue, PlaybackKeys.songId, PlaybackKeys.row,
            PlaybackKeys.position, PlaybackKeys.shuffle, PlaybackKeys.repeat)) {
            val preferences = mutablePreferencesOf()
            preferences.putSnapshot(snapshot)
            preferences.remove(key)

            assertThat(preferences.toSnapshot()).describedAs(key.name).isNull()
        }
    }

    @Test
    fun `negative and unknown values are ignored`() {
        for (change in listOf<(MutablePreferences) -> Unit>(
            { it[PlaybackKeys.songId] = -2 },
            { it[PlaybackKeys.row] = -1 },
            { it[PlaybackKeys.position] = -1 },
            { it[PlaybackKeys.repeat] = PlaybackSnapshot.REPEAT_ALL + 1 },
        )) {
            val preferences = mutablePreferencesOf()
            preferences.putSnapshot(snapshot)
            change(preferences)

            assertThat(preferences.toSnapshot()).isNull()
        }
    }
}
