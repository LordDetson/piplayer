package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The covers' memory cache (docs/library.md §13.3). */
class MemoryLruTest {

    private val cache = MemoryLru<String, String>(maxWeight = 10) { it.length.toLong() }

    @Test
    fun theLeastRecentlyUsedGoesFirstOverTheLimit() {
        cache.put("a", "aaaa")
        cache.put("b", "bbbb")
        cache.get("a")
        cache.put("c", "cccc")

        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("a")).isEqualTo("aaaa")
        assertThat(cache.get("c")).isEqualTo("cccc")
        assertThat(cache.weight).isEqualTo(8)
    }

    @Test
    fun aReplacedValueWeighsOnlyOnce() {
        cache.put("a", "aaaa")
        cache.put("a", "aa")

        assertThat(cache.weight).isEqualTo(2)
        assertThat(cache.get("a")).isEqualTo("aa")
    }

    @Test
    fun aValueHeavierThanTheLimitIsNotKept() {
        cache.put("a", "aaaa")
        cache.put("huge", "x".repeat(11))

        assertThat(cache.get("huge")).isNull()
        assertThat(cache.get("a")).isEqualTo("aaaa")
    }

    @Test
    fun clearingEmptiesIt() {
        cache.put("a", "aaaa")
        cache.clear()

        assertThat(cache.get("a")).isNull()
        assertThat(cache.weight).isZero()
    }
}
