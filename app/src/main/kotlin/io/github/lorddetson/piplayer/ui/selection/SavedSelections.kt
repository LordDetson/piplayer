package io.github.lorddetson.piplayer.ui.selection

import androidx.lifecycle.SavedStateHandle

/**
 * A selection in a state holder's [SavedStateHandle], for process death (docs/library.md §20.5): whether it is "all
 * except", its keys (file or entry IDs as a long array, the queue screen's keys as strings) and the list it was made on.
 */
object SavedSelections {

    private const val ALL = "selection_all"
    private const val IDS = "selection_ids"
    private const val KEYS = "selection_keys"
    private const val LIST = "selection_list"

    /** The saved selection of IDs in [handle], or null; a holder without a handle (a test's own) keeps nothing. */
    fun read(handle: SavedStateHandle?): SavedSelection<Long>? {
        val all = handle?.get<Boolean>(ALL) ?: return null
        val ids = handle.get<LongArray>(IDS) ?: return null
        return SavedSelection(all, ids.toList(), handle.get<String>(LIST).orEmpty())
    }

    fun write(handle: SavedStateHandle?, saved: SavedSelection<Long>?) {
        if (handle == null) return
        if (saved == null) return clear(handle)
        handle[ALL] = saved.allExcept
        handle[IDS] = saved.keys.toLongArray()
        handle[LIST] = saved.list
    }

    /** The saved selection of the queue screen's keys in [handle], or null. */
    fun readKeys(handle: SavedStateHandle): SavedSelection<String>? {
        val all = handle.get<Boolean>(ALL) ?: return null
        val keys = handle.get<ArrayList<String>>(KEYS) ?: return null
        return SavedSelection(all, keys, handle.get<String>(LIST).orEmpty())
    }

    fun writeKeys(handle: SavedStateHandle, saved: SavedSelection<String>?) {
        if (saved == null) return clear(handle)
        handle[ALL] = saved.allExcept
        handle[KEYS] = ArrayList(saved.keys)
        handle[LIST] = saved.list
    }

    private fun clear(handle: SavedStateHandle) {
        for (key in listOf(ALL, IDS, KEYS, LIST)) handle.remove<Any>(key)
    }
}
