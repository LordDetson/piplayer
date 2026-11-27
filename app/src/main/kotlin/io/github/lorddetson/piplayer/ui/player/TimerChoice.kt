package io.github.lorddetson.piplayer.ui.player

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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
        awaitWrites()
        choiceOf(file.data.first())
    }

    /** Follows imports and Undo without interrupting the sheet's immediate interaction while a save is queued. */
    internal suspend fun observe(show: (TimerChoice) -> Unit) {
        lastWrite.collectLatest { observedWrite ->
            observedWrite?.join()
            file.data.collect { preferences ->
                synchronized(Companion) {
                    if (lastWrite.value === observedWrite) show(choiceOf(preferences))
                }
            }
        }
    }

    fun save(choice: TimerChoice) = synchronized(Companion) {
        val previous = lastWrite.value
        lastWrite.value = scope.launch {
            previous?.join()
            SettingsFiles.edit(file) {
                it[HOURS] = choice.hours
                it[MINUTES] = choice.minutes
                it[FINISH_TRACK] = choice.finishTrack
            }
        }
    }

    companion object {
        // Writes outlive a screen that closes right after a change; each waits for the one asked for before it.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val lastWrite = MutableStateFlow<Job?>(null)
        internal val HOURS = intPreferencesKey("sleep_timer_hours")
        internal val MINUTES = intPreferencesKey("sleep_timer_minutes")
        internal val FINISH_TRACK = booleanPreferencesKey("sleep_timer_finish_track")

        internal fun choiceOf(preferences: Preferences) = TimerChoice(
            hours = (preferences[HOURS] ?: TimerChoice.DEFAULT.hours).coerceIn(0, 23),
            minutes = (preferences[MINUTES] ?: TimerChoice.DEFAULT.minutes).coerceIn(0, 59),
            finishTrack = preferences[FINISH_TRACK] ?: TimerChoice.DEFAULT.finishTrack,
        )

        internal suspend fun awaitWrites() {
            // Wait for the saves requested before this read.
            lastWrite.value?.join()
        }

        /** Forgets the choice, so that a test starts from the first one. */
        fun clearForTests(context: Context) {
            runBlocking {
                awaitWrites()
                SettingsFiles.settings(context).edit {
                    it.remove(HOURS)
                    it.remove(MINUTES)
                    it.remove(FINISH_TRACK)
                }
            }
        }
    }
}
