package io.github.lorddetson.piplayer.ui.selection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The rows chosen in one list (docs/library.md §20.2), by their keys: only some ([Only]), or every row the list shows but
 * some ([AllExcept], "Select all"), which follows the list as it changes. [AllExcept.list] names the list it was made on
 * (its search and chips), for a restore after the process was gone (§20.5).
 */
sealed interface Selection<K : Any> {
    data class Only<K : Any>(val keys: Set<K>) : Selection<K>

    data class AllExcept<K : Any>(val excluded: Set<K>, val list: String) : Selection<K>
}

/** At most this many keys are saved for process death (docs/library.md §20.5). */
const val MAX_SAVED_KEYS = 1_000

/** A selection as it is saved for process death: whether it is "all except", its keys and the list it was made on. */
data class SavedSelection<K : Any>(val allExcept: Boolean, val keys: List<K>, val list: String)

fun <K : Any> Selection<K>.isSelected(key: K): Boolean = when (this) {
    is Selection.Only -> key in keys
    is Selection.AllExcept -> key !in excluded
}

/** "Select all" of the list [list]. */
fun <K : Any> selectAll(list: String): Selection<K> = Selection.AllExcept(emptySet(), list)

/**
 * [key] selected or deselected; with no selection yet, a selection of [key] alone. Null when nothing the list [shown]
 * is left selected, which ends selection.
 */
fun <K : Any> Selection<K>?.toggled(key: K, shown: Collection<K>): Selection<K>? = when (this) {
    null -> Selection.Only(setOf(key))
    is Selection.Only -> (if (key in keys) keys - key else keys + key).takeIf { it.isNotEmpty() }?.let { Selection.Only(it) }
    is Selection.AllExcept -> copy(excluded = if (key in excluded) excluded - key else excluded + key).takeIf { it.count(shown) > 0 }
}

/** How many of the rows [shown] are selected. */
fun <K : Any> Selection<K>.count(shown: Collection<K>): Int = when (this) {
    // A set of shown keys is asked the other way round: a few selected keys among 20 000 rows.
    is Selection.Only -> if (shown is Set<K>) keys.count { it in shown } else shown.count { it in keys }
    is Selection.AllExcept -> shown.size - (if (shown is Set<K>) excluded.count { it in shown } else shown.count { it in excluded })
}

/** The selected keys in the order of [shown], whatever order they were selected in (docs/library.md §20.3). */
fun <K : Any> Selection<K>.ordered(shown: List<K>): List<K> = shown.filter(::isSelected)

/** Whether this is the whole list: "Select all" with nothing deselected (docs/library.md §20.6). */
val Selection<*>.isWholeList: Boolean get() = this is Selection.AllExcept && excluded.isEmpty()

/** Whether every row [shown] is selected, so that "Select all" becomes "Deselect all" (docs/library.md §20.2). */
fun <K : Any> Selection<K>.isEverything(shown: Collection<K>): Boolean = shown.isNotEmpty() && count(shown) == shown.size

/**
 * The selection on the loaded list [shown], called [list]: keys the list no longer shows leave it, and an "all except"
 * now names [list]. Null when nothing selected is left (docs/library.md §20.2, 7).
 */
fun <K : Any> Selection<K>.retained(shown: Collection<K>, list: String): Selection<K>? {
    val present = shown as? Set<K> ?: shown.toHashSet()
    val kept = when (this) {
        is Selection.Only -> keys.filterTo(LinkedHashSet()) { it in present }.takeIf { it.isNotEmpty() }?.let { Selection.Only(it) }
        is Selection.AllExcept -> Selection.AllExcept(excluded.filterTo(LinkedHashSet()) { it in present }, list)
    }
    return kept?.takeIf { it.count(present) > 0 }
}

/** Its saved form, or null when it has more than [MAX_SAVED_KEYS] keys (docs/library.md §20.5). */
fun <K : Any> Selection<K>.saved(): SavedSelection<K>? = when (this) {
    is Selection.Only -> SavedSelection(false, keys.toList(), "")
    is Selection.AllExcept -> SavedSelection(true, excluded.toList(), list)
}.takeIf { it.keys.size <= MAX_SAVED_KEYS }

/** The selection a saved form holds. */
fun <K : Any> SavedSelection<K>.selection(): Selection<K> =
    if (allExcept) Selection.AllExcept(keys.toCollection(LinkedHashSet()), list) else Selection.Only(keys.toCollection(LinkedHashSet()))

/** A restored selection on the list [list] that came back: an "all except" made on another list is dropped (§20.5). */
fun <K : Any> Selection<K>.restoredOn(list: String): Selection<K>? =
    if (this is Selection.AllExcept && this.list != list) null else this

/** What the selection bar shows: the [selection], how many rows it counts, and whether every row shown is selected. */
data class SelectionUi<K : Any>(val selection: Selection<K>, val count: Int, val everything: Boolean)

/**
 * A list's selection as its state holder keeps it (docs/library.md §20.2, §20.5): it starts from the [initial] saved
 * form, follows the loaded lists it is given, and hands every change to [save] (null when it is too big to save or over).
 * Call it on one thread.
 */
class SelectionHolder<K : Any>(initial: SavedSelection<K>?, private val save: (SavedSelection<K>?) -> Unit = {}) {

    private val mutable = MutableStateFlow(
        initial?.selection()?.let { restored -> SelectionUi(restored, (restored as? Selection.Only)?.keys?.size ?: 0, everything = false) },
    )

    /** The selection with its count, or null while nothing is selected. */
    val state: StateFlow<SelectionUi<K>?> = mutable.asStateFlow()

    val selecting: Boolean get() = mutable.value != null

    val selection: Selection<K>? get() = mutable.value?.selection

    /** A restored selection is checked against the first loaded list, where an "all except" of another list goes. */
    private var restored = initial != null

    /** The last loaded list and its name: the count is judged against it (§20.2, 8). */
    private var shown: List<K> = emptyList()
    private var shownSet: Set<K> = emptySet()
    private var list: String = ""

    /** Selects or deselects [key]; the first long press starts selection with it. */
    fun toggle(key: K) = set(selection.toggled(key, shownSet))

    fun selectAll() = set(selectAll(list))

    /** Deselects everything, which ends selection: "Deselect all", back, the close button, an action, a tab switch. */
    fun end() = set(null)

    /** The list as it is loaded now: the keys it shows in order and its name (search and chips). */
    fun onList(shown: List<K>, list: String) {
        this.shown = shown
        shownSet = shown.toHashSet()
        this.list = list
        var current = selection ?: return
        if (restored) {
            restored = false
            current = current.restoredOn(list) ?: return set(null)
        }
        set(current.retained(shownSet, list))
    }

    /** The selected keys of the last loaded list in its order. */
    fun ordered(): List<K> = selection?.ordered(shown).orEmpty()

    /** The selection as [change] makes it, as the queue screen's keys need after a change of the queue; null ends it. */
    fun adjust(change: (Selection<K>) -> Selection<K>?) {
        val current = selection ?: return
        set(change(current))
    }

    private fun set(selection: Selection<K>?) {
        val before = mutable.value?.selection
        mutable.value = selection?.let { SelectionUi(it, it.count(shownSet), it.isEverything(shownSet)) }
        if (selection == null) restored = false
        if (before != selection) save(selection?.saved())
    }
}
