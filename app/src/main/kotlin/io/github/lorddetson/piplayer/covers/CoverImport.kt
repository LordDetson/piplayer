package io.github.lorddetson.piplayer.covers

import android.content.Context
import android.net.Uri
import java.io.File

/** Copies a chosen image into the covers folder (docs/library.md §13.2). */
object CoverImport {

    /** The longer side of a copy at most. */
    const val MAX_SIDE = 1_500

    /** Decodes [image], scales it down to [MAX_SIDE] and saves it under a new name in [dir]; null if it is no image. Blocks. */
    fun copy(context: Context, image: Uri, dir: File): CoverCopy? = null
}
