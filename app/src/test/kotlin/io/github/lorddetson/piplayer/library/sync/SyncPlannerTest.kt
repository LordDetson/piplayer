package io.github.lorddetson.piplayer.library.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SyncPlannerTest {

    private val volume = "external_primary"

    private fun row(id: Long, path: String = "Music/", name: String = "$id.mp3", generation: Long = 1) = IndexRow(
        mediaStoreId = id, volume = volume, relativePath = path, displayName = name, size = 1_000, modifiedSeconds = 1,
        generationModified = generation, mimeType = "audio/mpeg", kinds = MediaKinds(), title = "Song $id",
        artist = null, album = null, albumArtist = null, composer = null, genre = null, year = null, trackNumber = null,
        durationMs = 2_000,
    )

    private fun stored(fileId: Long, mediaStoreId: Long, path: String = "Music/", name: String = "$mediaStoreId.mp3") =
        StoredFile(fileId, mediaStoreId, path, name)

    @Test
    fun `a volume without a sync state gets a full sync`() {
        assertThat(SyncPlanner.mode(null, "v1", 10)).isEqualTo(VolumeMode.Full)
    }

    @Test
    fun `the same version gets an incremental sync from the stored generation`() {
        val state = StoredVolume(volume, "v1", 10)

        assertThat(SyncPlanner.mode(state, "v1", 15)).isEqualTo(VolumeMode.Incremental(10))
        // The generation does not move for deletions, so an unchanged one still needs the ID check.
        assertThat(SyncPlanner.mode(state, "v1", 10)).isEqualTo(VolumeMode.Incremental(10))
    }

    @Test
    fun `a new index version gets a full sync`() {
        assertThat(SyncPlanner.mode(StoredVolume(volume, "v1", 10), "v2", 15)).isEqualTo(VolumeMode.Full)
    }

    @Test
    fun `a generation lower than the stored one gets a full sync`() {
        assertThat(SyncPlanner.mode(StoredVolume(volume, "v1", 10), "v1", 3)).isEqualTo(VolumeMode.Full)
    }

    @Test
    fun `stored volumes that are no longer attached are dropped`() {
        assertThat(SyncPlanner.detached(listOf("external_primary", "1234-5678"), setOf("external_primary")))
            .containsExactly("1234-5678")
        assertThat(SyncPlanner.detached(listOf("external_primary"), setOf("external_primary", "1234-5678"))).isEmpty()
    }

    @Test
    fun `a full sync keeps the row IDs of files at the same location, with their new index IDs`() {
        val changes = SyncPlanner.full(
            stored = listOf(stored(1, mediaStoreId = 5, name = "a.mp3"), stored(2, mediaStoreId = 9, name = "b.mp3")),
            // After an index rebuild the two files swapped their IDs.
            rows = listOf(row(9, name = "a.mp3"), row(5, name = "b.mp3")),
        )

        assertThat(changes.updated).containsExactlyInAnyOrder(
            FileUpdate(1, row(9, name = "a.mp3")),
            FileUpdate(2, row(5, name = "b.mp3")),
        )
        assertThat(changes.removed).isEmpty()
        assertThat(changes.added).isEmpty()
    }

    @Test
    fun `a full sync removes files the index no longer has and adds new ones`() {
        val changes = SyncPlanner.full(
            stored = listOf(stored(1, 5), stored(2, 6, path = "Music/Old/")),
            rows = listOf(row(5), row(7), row(6, path = "Music/New/")),
        )

        assertThat(changes.updated).containsExactly(FileUpdate(1, row(5)))
        assertThat(changes.removed).containsExactly(2L)
        assertThat(changes.added).containsExactlyInAnyOrder(row(7), row(6, path = "Music/New/"))
    }

    @Test
    fun `a full sync of a volume the index has emptied removes every file`() {
        val changes = SyncPlanner.full(stored = listOf(stored(1, 5), stored(2, 6)), rows = emptyList())

        assertThat(changes.removed).containsExactlyInAnyOrder(1L, 2L)
        assertThat(changes.updated).isEmpty()
        assertThat(changes.added).isEmpty()
    }

    @Test
    fun `an incremental sync updates changed files by their index ID and adds new ones`() {
        val changes = SyncPlanner.incremental(
            stored = listOf(stored(1, 5), stored(2, 6)),
            changed = listOf(row(5, name = "renamed.mp3", generation = 12), row(8, generation = 13)),
            ids = setOf(5, 6, 8),
        )

        assertThat(changes.updated).containsExactly(FileUpdate(1, row(5, name = "renamed.mp3", generation = 12)))
        assertThat(changes.added).containsExactly(row(8, generation = 13))
        assertThat(changes.removed).isEmpty()
    }

    @Test
    fun `an incremental sync removes stored files whose IDs are gone`() {
        val changes = SyncPlanner.incremental(
            stored = listOf(stored(1, 5), stored(2, 6), stored(3, 7)),
            changed = emptyList(),
            ids = setOf(6),
        )

        assertThat(changes.removed).containsExactlyInAnyOrder(1L, 3L)
        assertThat(changes.updated).isEmpty()
        assertThat(changes.added).isEmpty()
    }

    @Test
    fun `an incremental sync without changes changes nothing`() {
        val changes = SyncPlanner.incremental(stored = listOf(stored(1, 5)), changed = emptyList(), ids = setOf(5))

        assertThat(changes.isEmpty).isTrue()
    }

    @Test
    fun `a changed row that is gone by the time the IDs are read is not added`() {
        val changes = SyncPlanner.incremental(
            stored = listOf(stored(1, 5)),
            changed = listOf(row(5, generation = 12), row(8, generation = 13)),
            ids = emptySet(),
        )

        assertThat(changes.removed).containsExactly(1L)
        assertThat(changes.updated).isEmpty()
        assertThat(changes.added).isEmpty()
    }

    @Test
    fun `a row read twice is stored once, with the values read last`() {
        // A scan between two cursor windows can shift rows, so the same row may come twice.
        val full = SyncPlanner.full(stored = emptyList(), rows = listOf(row(5, name = "a.mp3"), row(5, name = "b.mp3")))
        val incremental = SyncPlanner.incremental(
            stored = emptyList(),
            changed = listOf(row(5, name = "a.mp3"), row(5, name = "b.mp3")),
            ids = setOf(5),
        )

        assertThat(full.added).containsExactly(row(5, name = "b.mp3"))
        assertThat(incremental.added).containsExactly(row(5, name = "b.mp3"))
    }
}
