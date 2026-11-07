package io.github.lorddetson.piplayer.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.TestImages
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The user's own covers: copies of chosen images and their references (docs/library.md §13.2). */
@RunWith(AndroidJUnit4::class)
class OwnCoversTest {

    private val context = TestFiles.targetContext
    private val dir = File(context.cacheDir, "own-covers-test").apply { deleteRecursively(); mkdirs() }
    private val database = UserDatabase.open(context, name = null)
    private val covers = OwnCovers(database, dir)

    @After
    fun close() {
        database.close()
        dir.deleteRecursively()
    }

    /** An image the user might choose: a file of [width] x [height] in one [color]. */
    private fun image(width: Int, height: Int = width, color: Int = TestImages.BLUE, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): Uri {
        val file = File(context.cacheDir, "chosen-${System.nanoTime()}.img")
        file.outputStream().use { TestImages.bitmap(width, height, color).compress(format, 100, it) }
        return Uri.fromFile(file)
    }

    private fun copyOf(image: Uri) = checkNotNull(CoverImport.copy(context, image, dir)) { "No copy of $image" }

    private fun bounds(name: String) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(File(dir, name).path, this)
    }

    @Test
    fun aChosenImageIsCopiedScaledDown() {
        val copy = copyOf(image(3_000, 2_000))

        assertThat(copy.width to copy.height).isEqualTo(1_500 to 1_000)
        val stored = bounds(copy.fileName)
        assertThat(stored.outWidth to stored.outHeight).isEqualTo(1_500 to 1_000)
        assertThat(stored.outMimeType).isEqualTo("image/jpeg")
    }

    @Test
    fun aSmallImageKeepsItsSizeAndTransparencyKeepsPng() {
        val opaque = copyOf(image(300, format = Bitmap.CompressFormat.JPEG))
        val clear = copyOf(image(200, color = 0x803D5AFE.toInt()))

        assertThat(opaque.width to opaque.height).isEqualTo(300 to 300)
        assertThat(bounds(clear.fileName).outMimeType).isEqualTo("image/png")
        assertThat(opaque.fileName).isNotEqualTo(clear.fileName)
    }

    @Test
    fun somethingThatIsNoImageGivesNoCopy() {
        val file = File(context.cacheDir, "not-an-image.img").apply { writeBytes(ByteArray(1_000) { it.toByte() }) }

        assertThat(CoverImport.copy(context, Uri.fromFile(file), dir)).isNull()
        assertThat(dir.listFiles()).isEmpty()
    }

    @Test
    fun aCoverIsFoundByItsIdentityOrAlbumKey() = runBlocking<Unit> {
        covers.load()
        val track = copyOf(image(100))
        val album = copyOf(image(100))
        val before = covers.version.value

        covers.setTrack(7, track)
        covers.setAlbum("album", album)

        assertThat(covers.track(7)).isEqualTo(track.fileName)
        assertThat(covers.album("album")).isEqualTo(album.fileName)
        assertThat(covers.track(8)).isNull()
        assertThat(covers.album("other")).isNull()
        assertThat(covers.version.value).isGreaterThan(before)
        assertThat(covers.identitiesWithCovers()).containsExactly(7L)
        assertThat(database.covers().albumCover("album")!!.width).isEqualTo(100)
    }

    @Test
    fun theCoversSurviveAnotherProcess() = runBlocking<Unit> {
        covers.load()
        val track = copyOf(image(100))
        covers.setTrack(7, track)

        val again = OwnCovers(database, dir)
        again.load()

        assertThat(again.track(7)).isEqualTo(track.fileName)
        assertThat(File(dir, track.fileName).exists()).isTrue()
    }

    @Test
    fun aNewChoiceReplacesTheOldCopy() = runBlocking<Unit> {
        covers.load()
        val first = copyOf(image(100))
        val second = copyOf(image(100))
        covers.setAlbum("album", first)

        covers.setAlbum("album", second)

        assertThat(covers.album("album")).isEqualTo(second.fileName)
        assertThat(File(dir, first.fileName).exists()).isFalse()
        assertThat(File(dir, second.fileName).exists()).isTrue()
    }

    @Test
    fun removingDeletesTheReferenceAndTheCopy() = runBlocking<Unit> {
        covers.load()
        val track = copyOf(image(100))
        val album = copyOf(image(100))
        covers.setTrack(7, track)
        covers.setAlbum("album", album)
        val before = covers.version.value
        var changes = 0
        covers.onChange = { changes++ }

        covers.removeTrack(7)
        covers.removeAlbum("album")

        assertThat(covers.track(7)).isNull()
        assertThat(covers.album("album")).isNull()
        assertThat(dir.listFiles()).isEmpty()
        assertThat(covers.version.value).isGreaterThan(before)
        assertThat(changes).isEqualTo(2)
        assertThat(covers.identitiesWithCovers()).isEmpty()
    }

    @Test
    fun theLoaderForgetsBeforeTheViewsHearOfAChange() = runBlocking<Unit> {
        covers.load()
        val seen = mutableListOf<Long>()
        covers.onChange = { seen += covers.version.value }
        val before = covers.version.value

        covers.setAlbum("album", copyOf(image(100)))
        covers.removeAlbum("album")

        // Each time the version was still the old one when the loader was told.
        assertThat(seen).containsExactly(before, before + 1)
        assertThat(covers.version.value).isEqualTo(before + 2)
    }

    @Test
    fun aCopyThatNoReferenceNamesIsDeletedWhenTheCoversAreRead() = runBlocking<Unit> {
        val kept = copyOf(image(100))
        val left = copyOf(image(100))
        database.covers().putAlbumCover("album", kept.fileName, 100, 100, setAt = 1)

        covers.load()

        assertThat(File(dir, kept.fileName).exists()).isTrue()
        assertThat(File(dir, left.fileName).exists()).isFalse()
        assertThat(covers.album("album")).isEqualTo(kept.fileName)
    }

    @Test
    fun aReferenceWhoseCopyIsMissingCountsAsNone() = runBlocking<Unit> {
        // After a restore: user.db came back, the copies in the no-backup files did not.
        database.covers().putTrackCover(7, "restored-track.jpg", 100, 100, setAt = 1)
        database.covers().putAlbumCover("album", "restored-album.jpg", 100, 100, setAt = 1)

        covers.load()

        assertThat(covers.track(7)).isNull()
        assertThat(covers.album("album")).isNull()
        assertThat(covers.identitiesWithCovers()).isEmpty()
        assertThat(database.covers().trackCovers()).isEmpty()
        assertThat(database.covers().albumCovers()).isEmpty()
    }

    @Test
    fun clearingForgetsEveryCover() = runBlocking<Unit> {
        covers.load()
        covers.setTrack(7, copyOf(image(100)))
        covers.setAlbum("album", copyOf(image(100)))

        covers.clear()

        assertThat(covers.track(7)).isNull()
        assertThat(covers.album("album")).isNull()
        assertThat(dir.listFiles()).isEmpty()
    }
}
