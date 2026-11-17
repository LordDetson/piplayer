package io.github.lorddetson.piplayer.identification.batch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The album grouping of a batch (TI §8): files whose album has a release MBID or a non-empty title group by that
 * album, one card per album, and a file without an album stands alone to be merged later by its release.
 */
class AlbumGroupsTest {

    private fun file(id: Long, releaseMbid: String? = null, albumKey: String? = null, title: String? = null) =
        BatchFile(id, releaseMbid, albumKey, title)

    @Test
    fun `files of one release MBID group into one album whatever their title or album key`() {
        val files = listOf(
            file(1, releaseMbid = "m1", albumKey = "a1", title = "First Edition"),
            file(2, releaseMbid = "m1", albumKey = "a2", title = "Second Edition"),
        )

        val groups = groupByAlbum(files)

        assertThat(groups).hasSize(1)
        assertThat(groups.single()).isEqualTo(
            BatchGroup.Album(releaseMbid = "m1", albumKey = "a1", title = "First Edition", files = files),
        )
    }

    @Test
    fun `files without a release MBID group by their album key, and the group keeps its title`() {
        val files = listOf(
            file(1, albumKey = "alice", title = "Alice"),
            file(2, albumKey = "alice", title = "Alice"),
        )

        val groups = groupByAlbum(files)

        assertThat(groups).hasSize(1)
        assertThat(groups.single()).isEqualTo(
            BatchGroup.Album(releaseMbid = null, albumKey = "alice", title = "Alice", files = files),
        )
    }

    @Test
    fun `a release MBID groups files even when their album keys differ`() {
        val groups = groupByAlbum(
            listOf(
                file(1, releaseMbid = "same", albumKey = "one", title = "One"),
                file(2, releaseMbid = "same", albumKey = "two", title = "Two"),
            ),
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().files.map(BatchFile::fileId)).containsExactly(1L, 2L)
    }

    @Test
    fun `the same title in two albums stays apart`() {
        val groups = groupByAlbum(
            listOf(
                file(1, albumKey = "folder-a", title = "Live"),
                file(2, albumKey = "folder-b", title = "Live"),
            ),
        )

        assertThat(groups).hasSize(2)
        assertThat(groups).allMatch { it is BatchGroup.Album }
        assertThat(groups.map { it.files.single().fileId }).containsExactly(1L, 2L)
    }

    @Test
    fun `a file without an album stands alone`() {
        val groups = groupByAlbum(listOf(file(1), file(2, albumKey = "a", title = "An Album"), file(3)))

        assertThat(groups.filterIsInstance<BatchGroup.Single>()).containsExactly(
            BatchGroup.Single(file(1)),
            BatchGroup.Single(file(3)),
        )
        assertThat(groups.filterIsInstance<BatchGroup.Album>()).hasSize(1)
    }

    @Test
    fun `blank values count as absent`() {
        // A blank release MBID falls back to the album key.
        val groups = groupByAlbum(
            listOf(
                file(1, releaseMbid = " ", albumKey = "a", title = "A"),
                file(2, releaseMbid = "", albumKey = "a", title = "A"),
                // A blank album key with no release MBID is no album at all.
                file(3, albumKey = ""),
            ),
        )

        assertThat(groups).hasSize(2)
        assertThat(groups.first()).isEqualTo(BatchGroup.Album(null, "a", "A", listOf(file(1, releaseMbid = " ", albumKey = "a", title = "A"), file(2, releaseMbid = "", albumKey = "a", title = "A"))))
        assertThat(groups.last()).isEqualTo(BatchGroup.Single(file(3, albumKey = "")))
    }

    @Test
    fun `album groups come in the order of their first file and files without an album follow, in order`() {
        val groups = groupByAlbum(
            listOf(
                file(1, albumKey = "b", title = "B"),
                file(2),
                file(3, albumKey = "a", title = "A"),
                file(4, albumKey = "b", title = "B"),
                file(5),
            ),
        )

        assertThat(groups.map { (it as? BatchGroup.Album)?.albumKey ?: "single" }).containsExactly("b", "a", "single", "single")
        assertThat(groups[0].files.map(BatchFile::fileId)).containsExactly(1L, 4L)
        assertThat(groups[1].files.map(BatchFile::fileId)).containsExactly(3L)
        assertThat(groups[2].files.map(BatchFile::fileId)).containsExactly(2L)
        assertThat(groups[3].files.map(BatchFile::fileId)).containsExactly(5L)
    }

    @Test
    fun `an empty batch has no groups`() {
        assertThat(groupByAlbum(emptyList())).isEmpty()
    }
}