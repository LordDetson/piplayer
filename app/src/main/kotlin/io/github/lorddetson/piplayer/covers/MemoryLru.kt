package io.github.lorddetson.piplayer.covers

/** A memory cache of at most [maxWeight], the least recently used entry going first. Thread-safe. */
class MemoryLru<K : Any, V : Any>(private val maxWeight: Long, private val weigh: (V) -> Long) {

    private val entries = LinkedHashMap<K, V>(64, 0.75f, true)
    private var total = 0L

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries.remove(key)?.let { total -= weigh(it) }
        val weight = weigh(value)
        if (weight > maxWeight) return
        entries[key] = value
        total += weight
        val iterator = entries.values.iterator()
        while (total > maxWeight && iterator.hasNext()) {
            total -= weigh(iterator.next())
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        total = 0
    }

    val weight: Long
        @Synchronized get() = total
}
