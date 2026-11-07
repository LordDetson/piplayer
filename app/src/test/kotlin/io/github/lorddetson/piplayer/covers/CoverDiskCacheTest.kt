package io.github.lorddetson.piplayer.covers

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The covers' disk cache (docs/library.md §13.3). */
class CoverDiskCacheTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000_000L

    private fun cache(max: Long = 10_000) = CoverDiskCache(dir, max, clock = { now })

    private fun bytes(size: Int, fill: Int = 1) = ByteArray(size) { fill.toByte() }

    private fun CoverDiskCache.bytesOf(key: String) = (read(key) as? Cached.Picture)?.bytes

    @Test
    fun aPictureComesBackAsItWasWritten() {
        val cache = cache()
        cache.write("a", bytes(1_000, 7))

        assertThat(cache.bytesOf("a")).isEqualTo(bytes(1_000, 7))
        assertThat(cache.read("b")).isNull()
    }

    @Test
    fun aSourceWithoutAPictureIsRemembered() {
        val cache = cache()
        cache.write("none", null)

        assertThat(cache.read("none")).isEqualTo(Cached.Nothing)
        assertThat(cache.size).isEqualTo(CoverDiskCache.MIN_FILE_BYTES)
    }

    @Test
    fun aNewPictureReplacesTheOld() {
        val cache = cache()
        cache.write("a", bytes(1_000, 1))
        cache.write("a", bytes(2_000, 2))

        assertThat(cache.bytesOf("a")).isEqualTo(bytes(2_000, 2))
        assertThat(cache.size).isEqualTo(2_000)
    }

    @Test
    fun theLeastRecentlyUsedGoesFirstOverTheLimit() {
        val cache = cache(max = 3_000)
        cache.write("a", bytes(1_000))
        now++
        cache.write("b", bytes(1_000))
        now++
        cache.write("c", bytes(1_000))
        now++
        cache.read("a")
        now++

        cache.write("d", bytes(1_000))

        assertThat(cache.read("b")).isNull()
        assertThat(cache.read("a")).isNotNull()
        assertThat(cache.read("c")).isNotNull()
        assertThat(cache.read("d")).isNotNull()
        assertThat(cache.size).isLessThanOrEqualTo(3_000)
        assertThat(dir.listFiles()!!.filter { it.isFile }).hasSize(3)
    }

    @Test
    fun aReopenedCacheKeepsItsFilesAndTheirOrder() {
        val first = cache(max = 3_000)
        first.write("a", bytes(1_000))
        now += 10
        first.write("b", bytes(1_000))
        now += 10
        first.write("c", bytes(1_000))
        now += 10
        first.read("a")
        now += 10

        val again = cache(max = 3_000)
        assertThat(again.size).isEqualTo(3_000)
        again.write("d", bytes(1_000))

        assertThat(again.read("b")).isNull()
        assertThat(again.bytesOf("a")).isEqualTo(bytes(1_000))
    }

    @Test
    fun aFileDeletedBehindItsBackIsAMiss() {
        val cache = cache()
        cache.write("a", bytes(1_000))
        dir.listFiles()!!.forEach { it.delete() }

        assertThat(cache.read("a")).isNull()
        assertThat(cache.size).isZero()
        cache.write("a", bytes(500))
        assertThat(cache.bytesOf("a")).isEqualTo(bytes(500))
    }

    @Test
    fun aPictureLargerThanTheWholeCacheIsNotKept() {
        val cache = cache(max = 1_000)
        cache.write("big", bytes(2_000))

        assertThat(cache.read("big")).isNull()
        assertThat(cache.size).isZero()
    }

    @Test
    fun leftoversOfAnInterruptedWriteAreNotRead() {
        File(dir, "half.tmp").writeBytes(bytes(100))

        val cache = cache()

        assertThat(cache.size).isZero()
        assertThat(File(dir, "half.tmp").exists()).isFalse()
    }

    @Test
    fun keysOfAnyTextAreSafeFileNames() {
        val cache = cache()
        val key = "embedded/1/2/3@192 ../../evil\u0000"
        cache.write(key, bytes(10))

        assertThat(cache.bytesOf(key)).isEqualTo(bytes(10))
        assertThat(dir.listFiles()!!.single().parentFile).isEqualTo(dir)
    }
}
