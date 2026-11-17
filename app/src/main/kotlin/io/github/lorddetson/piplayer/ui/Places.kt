package io.github.lorddetson.piplayer.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A place of the app that another surface asks to open (TI §8, PIM-21): the batch notification sends [EXTRA_PLACE] with
 * [FIX] in the intent of the activity, so that a tap lands on the review queue, and the app opens it once and forgets it.
 * A place asked for while the app runs reaches the screens through [requested], the activity being single top.
 */
object Places {

    /** The extra of the activity's intent that names a place to open; [FIX] is the only one for now. */
    const val EXTRA_PLACE = "io.github.lorddetson.piplayer.EXTRA_PLACE"

    /** The Fix tab, where a batch waits for review (TI §8). */
    const val FIX = "fix"

    private val mutableRequested = MutableStateFlow<String?>(null)

    /** The place asked for and not opened yet, if any. */
    val requested: StateFlow<String?> = mutableRequested.asStateFlow()

    /** Asks for [place] to open; a place asked for twice stays one request. */
    fun request(place: String) {
        mutableRequested.value = place
    }

    /** Clears [place] if it is the one asked for, and says whether it was: the app opens a request once. */
    fun take(place: String): Boolean = mutableRequested.compareAndSet(place, null)

    /** The place [intent] asks for, if it names one the app knows. */
    fun of(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_PLACE)?.takeIf { it == FIX }
}
