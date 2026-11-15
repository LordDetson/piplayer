package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures.release
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReleaseCacheTest {

    @Test
    fun `a release is kept by its MBID`() = runTest {
        val cache = InMemoryReleaseCache()
        assertThat(cache.get("a")).isNull()
        cache.put(release("a"))
        assertThat(cache.get("a")!!.id).isEqualTo("a")
    }

    @Test
    fun `a release answered for another ID is kept under both`() = runTest {
        val cache = InMemoryReleaseCache()
        cache.put(release("new"), requestedId = "old")
        assertThat(cache.get("old")!!.id).isEqualTo("new")
        assertThat(cache.get("new")!!.id).isEqualTo("new")
    }

    @Test
    fun `the least recently used release goes first when the cache is full`() = runTest {
        val cache = InMemoryReleaseCache(maxEntries = 2)
        cache.put(release("a"))
        cache.put(release("b"))
        cache.get("a")
        cache.put(release("c"))
        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("a")).isNotNull()
        assertThat(cache.get("c")).isNotNull()
    }
}
