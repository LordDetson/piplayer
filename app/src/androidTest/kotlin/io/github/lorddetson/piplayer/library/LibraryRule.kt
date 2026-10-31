package io.github.lorddetson.piplayer.library

import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.MediaIndex
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Starts a test on an empty library database over [index] (the device's media index when null), as a first launch
 * would, and syncs it as the grant of access would. Afterwards the library starts empty again over the device's index,
 * so that no test row stays behind.
 */
class LibraryRule(private val index: () -> MediaIndex? = { null }) : ExternalResource() {

    /** When the first sync was asked for, in `System.nanoTime()`. */
    var startedAt = 0L
        private set

    override fun before() {
        val context = TestFiles.targetContext
        runBlocking { Library.reopen(context, index(), empty = true) }
        SongList.forget()
        startedAt = System.nanoTime()
        startAsANewProcess()
    }

    override fun after() {
        val context = TestFiles.targetContext
        runBlocking { Library.reopen(context, empty = true) }
        SongList.forget()
        startAsANewProcess()
    }

    companion object {
        /**
         * What the start of a process does: the library watches the index and syncs. In the test process the index is
         * already watched, so the sync is asked for on its own.
         */
        fun startAsANewProcess() {
            val context = TestFiles.targetContext
            Library.start(context)
            Library.sync(context).request()
        }
    }
}
