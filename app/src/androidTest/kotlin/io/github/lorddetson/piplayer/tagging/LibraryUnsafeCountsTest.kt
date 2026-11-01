package io.github.lorddetson.piplayer.tagging

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Where the counts of failed reads live (docs/library.md §4.4). */
@RunWith(AndroidJUnit4::class)
class LibraryUnsafeCountsTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val dao = database.library()
    private val counts = LibraryUnsafeCounts(database)

    @After
    fun close() = database.close()

    private val file = TagTarget.Outside("content://com.example/document/7", size = 10, mtimeMs = 20)

    @Test
    fun outsideFilesAreCountedByUri() = runBlocking<Unit> {
        counts.raise(file)
        counts.raise(file)

        assertThat(counts.count(file)).isEqualTo(2)
        assertThat(counts.count(file.copy(uri = "content://com.example/document/8"))).isZero()
        assertThat(dao.outsideFile(file.uri)?.unsafeCount).isEqualTo(2)
    }

    @Test
    fun anOutsideFileWithAnotherSizeOrModificationTimeStartsAgain() = runBlocking<Unit> {
        counts.raise(file)
        counts.raise(file)
        val changed = file.copy(size = 11)

        assertThat(counts.count(changed)).isZero()
        assertThat(counts.count(file.copy(mtimeMs = 21))).isZero()
        counts.raise(changed)

        assertThat(counts.count(changed)).isEqualTo(1)
        assertThat(counts.count(file)).isZero()
    }

    @Test
    fun aResetForgetsTheOutsideFile() = runBlocking<Unit> {
        counts.raise(file)

        counts.reset(file)

        assertThat(counts.count(file)).isZero()
        assertThat(dao.outsideFile(file.uri)).isNull()
    }

    @Test
    fun libraryFilesAreCountedInTheirRow() = runBlocking<Unit> {
        val sync = LibrarySync(database, FakeMediaIndex().apply { volume().rows = listOf(row(1), row(2)) }, mayRead = { true })
        try {
            sync.sync()
        } finally {
            sync.close()
        }
        val (first, second) = dao.files().sortedBy { it.mediaStoreId }.map { TagTarget.LibraryFile(it.id) }

        counts.raise(first)
        counts.raise(first)
        counts.raise(second)
        assertThat(counts.count(first)).isEqualTo(2)
        assertThat(dao.files().associate { it.id to it.unsafeCount })
            .containsEntry(first.fileId, 2).containsEntry(second.fileId, 1)

        counts.reset(first)

        assertThat(counts.count(first)).isZero()
        assertThat(counts.count(second)).isEqualTo(1)
    }

    @Test
    fun aFileNoLongerInTheLibraryCountsZero() = runBlocking<Unit> {
        val gone = TagTarget.LibraryFile(999)

        counts.raise(gone)

        assertThat(counts.count(gone)).isZero()
    }
}
