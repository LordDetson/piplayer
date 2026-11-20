package io.github.lorddetson.piplayer

import android.content.Context

/** Standalone APKs can be built and run without the Play delivery SDK. */
@Suppress("UNUSED_PARAMETER")
object RadioSplitCompat {
    internal fun attachApplication(context: Context) = Unit

    fun attachActivity(context: Context) = Unit
}
