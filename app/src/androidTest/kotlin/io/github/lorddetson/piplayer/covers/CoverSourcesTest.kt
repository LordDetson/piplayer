package io.github.lorddetson.piplayer.covers

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.TestImages
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The sources of covers on the device (docs/library.md §13.1, §13.3), on the cover fixtures. */
@RunWith(AndroidJUnit4::class)
class CoverSourcesTest {

    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver
    private val ownDir = File(context.cacheDir, "cover-sources-test").apply { deleteRecursively(); mkdirs() }
    private val sources = AndroidCoverSources(context, ownDir)
    private val inserted = mutableListOf<Uri>()

    @Before
    fun deleteLeftovers() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf("$FOLDER%", context.packageName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
        }
    }

    @After
    fun clean() {
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        ownDir.deleteRecursively()
    }

    private fun fileOf(uri: Uri, embedded: Boolean? = null) = CoverFile(1, uri.toString(), size = 1, mtime = 1, embedded = embedded)

    private fun embedded(asset: String, size: Int = 96): Bitmap? = runBlocking {
        sources.load(CoverStep.Embedded(fileOf(Uri.fromFile(TestFiles.copy(asset)))), size)
    }

    private fun Bitmap.center() = copy(Bitmap.Config.ARGB_8888, false).getPixel(width / 2, height / 2)

    /** Puts [asset] into the media index as the app's own file in a folder of its own, without other images. */
    private fun insert(asset: String, folder: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, asset.substringAfterLast('/'))
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$FOLDER$folder/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        inserted += uri
        resolver.openOutputStream(uri)!!.use { out -> TestFiles.assets.open(asset).use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Test
    fun theEmbeddedPictureOfEveryTaggedFormat() {
        listOf("cover-front.mp3", "cover-front.flac", "cover-front.m4a", "cover-front.opus", "cover-front.ogg").forEach { name ->
            val cover = embedded("playback/$name")
            assertThat(cover).describedAs(name).isNotNull()
            assertThat(cover!!.center()).describedAs(name).isEqualTo(FRONT_COLOR)
            assertThat(cover.width).describedAs(name).isEqualTo(64)
        }
    }

    @Test
    fun theFrontCoverWinsOverABackCover() {
        assertThat(embedded("playback/cover-back-then-front.mp3")!!.center()).isEqualTo(FRONT_COLOR)
        assertThat(embedded("playback/cover-front-then-back.flac")!!.center()).isEqualTo(FRONT_COLOR)
    }

    @Test
    fun aFileWithoutAPictureOrThatIsNoAudioHasNone() {
        assertThat(embedded("playback/tagged-44100-stereo.mp3")).isNull()
        val text = File(context.cacheDir, "text.mp3").apply { writeText("not audio at all ".repeat(200)) }
        assertThat(runBlocking { sources.load(CoverStep.Embedded(fileOf(Uri.fromFile(text))), 96) }).isNull()
    }

    @Test
    fun theSystemThumbnailOfAFileInTheMediaIndex() {
        val withCover = insert("playback/cover-front.mp3", "with")
        val without = insert("playback/tagged-44100-stereo.mp3", "without")

        val thumbnail = runBlocking { sources.load(CoverStep.Thumbnail(fileOf(withCover)), 96) }
        val none = runBlocking { sources.load(CoverStep.Thumbnail(fileOf(without)), 96) }

        assertThat(thumbnail).isNotNull()
        // Android scales and re-encodes it: the color stays near the cover's.
        val center = thumbnail!!.center()
        assertThat(Color.blue(center)).isGreaterThan(200)
        assertThat(Color.red(center)).isLessThan(100)
        assertThat(none).isNull()
    }

    @Test
    fun anOwnCopyIsDecodedAtItsStepNotAtFullSize() {
        File(ownDir, "big.png").outputStream().use { TestImages.bitmap(3_000, 2_000).compress(Bitmap.CompressFormat.PNG, 100, it) }

        val cover = runBlocking { sources.load(CoverStep.Own("big.png"), 192) }!!

        assertThat(cover.width to cover.height).isEqualTo(288 to 192)
        assertThat(cover.center()).isEqualTo(TestImages.BLUE)
        assertThat(runBlocking { sources.load(CoverStep.Own("gone.jpg"), 192) }).isNull()
    }

    @Test
    fun aLargeEmbeddedStylePictureIsDecodedAtItsStep() {
        val bytes = TestImages.png(3_000)

        val cover = BitmapCodec.decode(bytes, 128)!!

        assertThat(cover.width to cover.height).isEqualTo(128 to 128)
    }

    @Test
    fun theDiskCacheFormKeepsThePicture() {
        val bytes = BitmapCodec.encode(TestImages.bitmap(200))!!

        val back = BitmapCodec.decode(bytes, 200)!!

        assertThat(back.width).isEqualTo(200)
        val center = back.center()
        assertThat(Color.blue(center)).isGreaterThan(230)
        assertThat(BitmapCodec.weight(back)).isEqualTo(200L * 200 * 4)
    }

    private companion object {
        const val FRONT_COLOR = 0xFF3D5AFE.toInt()
        const val FOLDER = "Music/PiPlayerCoverTest/"
    }
}
