package io.github.lorddetson.piplayer.ui.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** What the sleep timer's sheet shows and keeps: its wheels and its checkbox (docs/playback.md §6.2). */
data class TimerChoice(val hours: Int, val minutes: Int, val finishTrack: Boolean) {

    companion object {
        /** The first time: 0:30 without the checkbox. */
        val DEFAULT = TimerChoice(hours = 0, minutes = 30, finishTrack = false)
    }
}

/**
 * The sheet's last choice in the `settings` file, which goes into backups (docs/library.md §3.2). The one read blocks;
 * writes run in the background, one after another, because the sheet saves at every change of its wheels.
 */
class TimerChoiceStore(context: Context) {

    private val file = SettingsFiles.settings(context)

    fun read(): TimerChoice = runBlocking {
        // After the writes started so far.
        lastWrite?.join()
        val preferences = file.data.first()
        TimerChoice(
            hours = (preferences[HOURS] ?: TimerChoice.DEFAULT.hours).coerceIn(0, 23),
            minutes = (preferences[MINUTES] ?: TimerChoice.DEFAULT.minutes).coerceIn(0, 59),
            finishTrack = preferences[FINISH_TRACK] ?: TimerChoice.DEFAULT.finishTrack,
        )
    }

    fun save(choice: TimerChoice) = synchronized(Companion) {
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            file.edit {
                it[HOURS] = choice.hours
                it[MINUTES] = choice.minutes
                it[FINISH_TRACK] = choice.finishTrack
            }
        }
    }

    companion object {
        // Writes outlive a screen that closes right after a change; each waits for the one asked for before it.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var lastWrite: Job? = null
        private val HOURS = intPreferencesKey("sleep_timer_hours")
        private val MINUTES = intPreferencesKey("sleep_timer_minutes")
        private val FINISH_TRACK = booleanPreferencesKey("sleep_timer_finish_track")

        /** Forgets the choice, so that a test starts from the first one. */
        fun clearForTests(context: Context) {
            runBlocking {
                SettingsFiles.settings(context).edit {
                    it.remove(HOURS)
                    it.remove(MINUTES)
                    it.remove(FINISH_TRACK)
                }
            }
        }
    }
}
