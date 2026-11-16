package io.github.lorddetson.piplayer.covers

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.userdata.SavedCoverEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accepted covers (TI §5.2, §10.2; §11.2 M3 "Saved covers"): files in a folder of their own, named by the hash of
 * their bytes, and their `saved_cover` rows in the real user database.
 */
@RunWith(AndroidJUnit4::class)
class SavedCoversTest {

    private val context = TestFiles.targetContext
    private val user = UserDatabase.open(context, name = null)
    private val base = File(context.cacheDir, "saved-covers-test").apply { deleteRecursively(); mkdirs() }
    private val dir = SavedCovers.directory(base)
    private val release = "1e100000-1111-4222-8333-000000000001"
    private val other = "1e100000-1111-4222-8333-000000000002"

    @After
    fun close() {
        user.close()
        base.deleteRecursively()
    }

    /** A JPEG header with a start of frame of [width] by [height] pixels, then [tail] as the rest of the image. */
    private fun jpeg(width: Int, height: Int, tail: Int = 0) = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
        (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
        0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        tail.toByte(), 0xFF.toByte(), 0xD9.toByte(),
    )

    private fun sha256(bytes: ByteArray) = Hex.text(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun anAcceptedCoverIsStoredOnceUnderTheHashOfItsBytesWithItsRow() = runBlocking<Unit> {
        val covers = SavedCovers(user, dir)
        val bytes = jpeg(600, 500)

        val id = checkNotNull(covers.save(bytes, release, SavedCovers.Source.RELEASE))

        val file = checkNotNull(covers.file(id))
        assertThat(file.parentFile).isEqualTo(dir)
        assertThat(file.name).isEqualTo("${sha256(bytes)}.jpg")
        assertThat(file.readBytes()).isEqualTo(bytes)
        assertThat(user.decisions().savedCover(id)).isEqualTo(
            SavedCoverEntity(
                id = id, releaseMbid = Hex.mbidBytes(release), path = file.name, width = 600, height = 500,
                bytes = bytes.size.toLong(), hash = sha256(bytes), source = "release",
            ),
        )
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(file.name)
    }

    @Test
    fun theSameBytesForTheSameReleaseAreOneRowAndForAnotherReleaseARowOverTheSameFile() = runBlocking<Unit> {
        val covers = SavedCovers(user, dir)
        val bytes = jpeg(600, 500)

        val first = covers.save(bytes, release, SavedCovers.Source.RELEASE)
        val again = covers.save(bytes.copyOf(), release, SavedCovers.Source.RELEASE)
        val elsewhere = checkNotNull(covers.save(bytes, other, SavedCovers.Source.RELEASE_GROUP))

        assertThat(again).isEqualTo(first)
        assertThat(elsewhere).isNotEqualTo(first)
        assertThat(covers.file(elsewhere)).isEqualTo(covers.file(first!!))
        assertThat(user.decisions().savedCover(elsewhere)?.source).isEqualTo("release_group")
        assertThat(dir.listFiles()!!).hasSize(1)
        // Other bytes are another file.
        val bigger = checkNotNull(covers.save(jpeg(1200, 1200), release, SavedCovers.Source.RELEASE_INDEX))
        assertThat(covers.file(bigger)).isNotEqualTo(covers.file(first))
        assertThat(dir.listFiles()!!).hasSize(2)
    }

    @Test
    fun bytesThatAreNoImageAreRefused() = runBlocking<Unit> {
        val covers = SavedCovers(user, dir)

        assertThat(covers.save("<html>captive portal</html>".toByteArray(), release, SavedCovers.Source.RELEASE)).isNull()

        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun aFileNoRowNamesIsDeletedWhenTheCoversAreFirstUsedAndARowWithoutItsFileGivesNone() = runBlocking<Unit> {
        val earlier = SavedCovers(user, dir)
        val kept = checkNotNull(earlier.save(jpeg(10, 10), release, SavedCovers.Source.RELEASE))
        val lost = checkNotNull(earlier.save(jpeg(20, 20), release, SavedCovers.Source.RELEASE))
        val lostFile = checkNotNull(earlier.file(lost))
        // A process that died between a file and its row, and a temporary file, left behind; a restore lost a file.
        File(dir, "${"0".repeat(64)}.jpg").writeBytes(jpeg(30, 30))
        File(dir, ".partial.tmp").writeBytes(byteArrayOf(1))
        lostFile.delete()
        assertThat(dir.listFiles()!!).hasSize(3)

        // The next process.
        val covers = SavedCovers(user, dir)
        covers.load()

        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(checkNotNull(covers.file(kept)).name)
        assertThat(covers.file(lost)).isNull()
        assertThat(user.decisions().savedCover(lost)).isNotNull()
        assertThat(covers.file(12_345)).isNull()
    }
}
