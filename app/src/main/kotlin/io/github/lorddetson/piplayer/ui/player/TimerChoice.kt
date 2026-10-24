package io.github.lorddetson.piplayer.ui.player

import android.content.Context
import androidx.core.content.edit

/** What the sleep timer's sheet shows and keeps: its wheels and its checkbox (docs/playback.md §6.2). */
data class TimerChoice(val hours: Int, val minutes: Int, val finishTrack: Boolean) {

    companion object {
        /** The first time: 0:30 without the checkbox. */
        val DEFAULT = TimerChoice(hours = 0, minutes = 30, finishTrack = false)
    }
}

/** The sheet's last choice in the app's settings; it goes into backups with the other settings. */
class TimerChoiceStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): TimerChoice = TimerChoice(
        hours = preferences.getInt(HOURS, TimerChoice.DEFAULT.hours).coerceIn(0, 23),
        minutes = preferences.getInt(MINUTES, TimerChoice.DEFAULT.minutes).coerceIn(0, 59),
        finishTrack = preferences.getBoolean(FINISH_TRACK, TimerChoice.DEFAULT.finishTrack),
    )

    fun save(choice: TimerChoice) = preferences.edit {
        putInt(HOURS, choice.hours)
        putInt(MINUTES, choice.minutes)
        putBoolean(FINISH_TRACK, choice.finishTrack)
    }

    companion object {
        private const val PREFERENCES = "player"
        private const val HOURS = "sleep_timer_hours"
        private const val MINUTES = "sleep_timer_minutes"
        private const val FINISH_TRACK = "sleep_timer_finish_track"

        /** Forgets the choice, so that a test starts from the first one. */
        fun clearForTests(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            remove(HOURS)
            remove(MINUTES)
            remove(FINISH_TRACK)
        }
    }
}
