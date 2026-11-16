package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.FolderReleaseRow
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Which releases the folder hint of the app counts as confirmed (TI §4.2 key 1), on rows as the library gives them. */
class LibraryFolderReleaseHintTest {

    private val volume = "external_primary"
    private fun mbid(n: Int) = "1e100000-1111-4222-8333-%012x".format(n)

    private fun decided(path: String, release: String?) = FolderReleaseRow(path, decided = true, decidedRelease = release, tagRelease = null)
    private fun tagged(path: String, release: String?, decided: String? = null, isDecided: Boolean = false) =
        FolderReleaseRow(path, decided = isDecided, decidedRelease = decided, tagRelease = release)

    @Test
    fun `a release decided for a file of the folder or tagged on one without a decided value is confirmed`() {
        val rows = listOf(decided("Music/Record/", mbid(1)), tagged("Music/Record/", mbid(2).uppercase()))

        assertThat(LibraryFolderReleaseHint.confirmed(FolderKey(volume, "Music/Record/"), rows)).containsExactlyInAnyOrder(mbid(1), mbid(2))
    }

    @Test
    fun `a decided value hides the tags' one, also an empty one`() {
        val rows = listOf(
            tagged("Music/Record/", mbid(2), decided = mbid(1), isDecided = true),
            tagged("Music/Record/", mbid(3), decided = null, isDecided = true),
        )

        assertThat(LibraryFolderReleaseHint.confirmed(FolderKey(volume, "Music/Record/"), rows)).containsExactly(mbid(1))
    }

    @Test
    fun `disc folders count with their parent folder and other folders below it do not`() {
        val rows = listOf(
            decided("Music/Record/CD2/", mbid(1)),
            decided("Music/Record/Bonus/", mbid(2)),
            decided("Music/Records/", mbid(3)),
            decided("Music/", mbid(4)),
        )

        assertThat(LibraryFolderReleaseHint.confirmed(FolderKey(volume, "Music/Record/"), rows)).containsExactly(mbid(1))
        assertThat(LibraryFolderReleaseHint.confirmed(FolderKey(volume, "Music/Record/CD1/"), rows)).containsExactly(mbid(1))
    }

    @Test
    fun `a value that is no MBID counts for nothing`() {
        val rows = listOf(tagged("Music/Record/", "not an mbid"), decided("Music/Record/", ""))

        assertThat(LibraryFolderReleaseHint.confirmed(FolderKey(volume, "Music/Record/"), rows)).isEmpty()
    }

    @Test
    fun `a file outside the library has no hint and reads nothing`() = runTest {
        var uses = 0
        val library = object : LibraryAccess {
            override fun <T> use(block: (LibraryDatabase) -> T): T {
                uses++
                error("No library in this test")
            }
        }

        assertThat(LibraryFolderReleaseHint(library).confirmedReleaseIds(null)).isEmpty()
        assertThat(LibraryFolderReleaseHint(library).confirmedReleaseIds("not a folder key")).isEmpty()
        assertThat(uses).isZero()
    }
}
