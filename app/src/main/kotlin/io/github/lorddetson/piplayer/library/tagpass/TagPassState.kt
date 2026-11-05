package io.github.lorddetson.piplayer.library.tagpass

import kotlinx.coroutines.flow.MutableStateFlow

/** How far the tag pass of this process has come, for the line lists show (docs/library.md §5.4). */
object TagPassState {
    val progress = MutableStateFlow(PassProgress())
}
