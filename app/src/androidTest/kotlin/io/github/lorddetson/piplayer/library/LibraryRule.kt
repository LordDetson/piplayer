package io.github.lorddetson.piplayer.library

import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.MediaIndex
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Starts a test on an empty library database over [index] (the device's media index when null), as a first launch
 * would, without folder rules and with the default hidden files, and syncs it as the grant of access would. Afterwards the library starts empty again over the device's index,
 * so that no test row stays behind.
 */
class LibraryRule(private val index: () -> MediaIndex? = { null }) : ExternalResource() {

    /** When the first sync was asked for, in `System.nanoTime()`. */
    var startedAt = 0L
        private set

    override fun before() {
        val context = TestFiles.targetContext
        runBlocking {
            cleanUserData()
            Library.reopen(context, index(), empty = true)
        }
        SongList.forget()
        startedAt = System.nanoTime()
        startAsANewProcess()
    }

    override fun after() {
        val context = TestFiles.targetContext
        runBlocking {
            cleanUserData()
            Library.reopen(context, empty = true)
        }
        SongList.forget()
        startAsANewProcess()
    }

    companion object {
        /** No folder rules, no track identities and the default hidden files (docs/library.md §3.4, §3.5, §8). */
        suspend fun cleanUserData() {
            val context = TestFiles.targetContext
            val rules = UserData.folderRules(context)
            for (rule in rules.all()) rules.remove(rule.volumeName, rule.relativePath)
            UserData.database(context).identities().deleteAll()
            val settings = LibrarySettings(context)
            settings.setShowSystemSounds(false)
            settings.setShowRecordings(false)
            settings.setMinLengthSeconds(HiddenFiles.DEFAULT_MIN_LENGTH_SECONDS)
        }

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
