package io.github.lorddetson.piplayer.covers

/** A memory cache of at most [maxWeight], the least recently used entry going first. Thread-safe. */
class MemoryLru<K : Any, V : Any>(private val maxWeight: Long, private val weigh: (V) -> Long) {

    fun get(key: K): V? = TODO()

    fun put(key: K, value: V): Unit = TODO()

    fun clear(): Unit = TODO()

    val weight: Long get() = TODO()
}
