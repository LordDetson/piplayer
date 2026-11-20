package io.github.lorddetson.piplayer

import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat

/** Makes code and resources available to the app and the activity as soon as Play installs a split. */
object RadioSplitCompat {
    internal fun attachApplication(context: Context) {
        SplitCompat.install(context)
    }

    fun attachActivity(context: Context) {
        SplitCompat.installActivity(context)
    }
}
