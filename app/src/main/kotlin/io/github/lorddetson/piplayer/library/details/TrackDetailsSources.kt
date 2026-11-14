package io.github.lorddetson.piplayer.library.details

import android.content.Context
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.userdata.UserData

/** The readers of track details in the main process (docs/library.md §18). */
object TrackDetailsSources {

    /** Songs of the library, read through the library's lock, so that a rebuild cannot close the database under them. */
    fun library(context: Context): LibraryTrackDetails {
        val app = context.applicationContext
        return LibraryTrackDetails(
            object : LibraryTrackRefs.Access {
                override fun <T> read(block: (LibraryDatabase) -> T): T = Library.read(app, block)
            },
            UserData.database(app),
            Covers.own(app),
            AndroidPictureProbe(app),
        )
    }

    /** Files opened from another app, read through `:tagger`. */
    fun opened(context: Context): OpenedFileDetails {
        val app = context.applicationContext
        return OpenedFileDetails(app, Tagger.client(app), AndroidPictureProbe(app))
    }
}
