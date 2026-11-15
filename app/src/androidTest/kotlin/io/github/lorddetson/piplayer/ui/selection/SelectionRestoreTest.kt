package io.github.lorddetson.piplayer.ui.selection

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsState
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A song list's selection after the process was gone (docs/library.md §20.5): a new state holder built from nothing but
 * the saved state of the old one, as Android hands it back.
 */
@RunWith(AndroidJUnit4::class)
class SelectionRestoreTest {

    private val songs = SongSourceRule { (1L..1_100L).map { SongSourceRule.song(it, "Song %04d".format(it)) } }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs)

    private val app = TestFiles.targetContext.applicationContext as Application

    /** A Songs list holder on [saved], started with access, once its whole list is loaded. */
    private fun loaded(saved: SavedStateHandle, search: String = ""): SongsViewModel {
        val model = onMain {
            SongsViewModel(app, saved = saved).apply {
                setSearch(search)
                onStart(granted = true, deniedForGood = false)
            }
        }
        eventually("the list is loaded", kotlin.time.Duration.parse("20s")) {
            (model.state.value as? SongsState.Loaded)?.let { it.complete && it.query.search == search.trim() } == true
        }
        return model
    }

    /** What survives the process: the values of the handle alone, without the holder. */
    private fun afterTheProcess(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

    @Test
    fun aNewHolderFromTheSavedStateBringsTheSelectionBack() {
        val saved = SavedStateHandle()
        val before = loaded(saved)
        onMain {
            before.toggleSelection(7)
            before.toggleSelection(3)
        }

        val after = loaded(afterTheProcess(saved))

        eventually("the selection is back") { after.selection.value?.selection == Selection.Only(setOf(7L, 3L)) }
        assertThat(after.selection.value!!.count).isEqualTo(2)
        assertThat(onMain { after.selected() }!!.songs.map { it.id }).containsExactly(3L, 7L)
    }

    @Test
    fun aSelectionOfMoreThanAThousandSongsIsNotSaved() {
        val saved = SavedStateHandle()
        val before = loaded(saved)
        onMain {
            before.selectAll()
            for (id in 1L..1_001L) before.toggleSelection(id)
        }
        assertThat(before.selection.value!!.count).isEqualTo(99)

        val after = loaded(afterTheProcess(saved))

        assertThat(after.selection.value).isNull()
    }

    @Test
    fun selectAllComesBackOnlyOnTheListItWasMadeOn() {
        val saved = SavedStateHandle()
        val before = loaded(saved, search = "Song 00")
        onMain {
            before.selectAll()
            before.toggleSelection(5)
        }
        // A test's own songs are not searched: the list is all of them under either search.
        assertThat(before.selection.value!!.count).isEqualTo(1_099)

        val same = loaded(afterTheProcess(saved), search = "Song 00")
        eventually("all but one is back") { same.selection.value?.count == 1_099 }

        // Another list (here another search; on Songs missing information other chips) drops it.
        val other = loaded(afterTheProcess(saved), search = "Song 01")
        assertThat(other.selection.value).isNull()
    }
}
