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

    @Test
    fun `a snapshot survives the preferences round trip`() {
        val preferences = mutablePreferencesOf()

        preferences.putSnapshot(snapshot)

        assertThat(preferences.toSnapshot()).isEqualTo(snapshot)
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
        preferences.putSnapshot(snapshot)

        preferences.putSnapshot(snapshot.copy(title = null, artist = null, album = null, uri = null))

        assertThat(preferences.toSnapshot()!!.title).isNull()
        assertThat(preferences.toSnapshot()!!.artist).isNull()
        assertThat(preferences.toSnapshot()!!.album).isNull()
        assertThat(preferences.toSnapshot()!!.uri).isNull()
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
            { it[PlaybackKeys.songId] = -1 },
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
