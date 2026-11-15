package io.github.lorddetson.piplayer.library.db

import android.content.Context
import io.github.lorddetson.piplayer.library.Library

/**
 * How code outside the library reaches the library database. The app's goes through the library's lock
 * ([Library.read]), so that a rebuild (docs/library.md §8.5) never closes the database under a read or a write and every
 * call finds the database that is open now; a test hands over a database of its own.
 */
interface LibraryAccess {

    /** Runs [block] on the open library database; the block may write too. */
    fun <T> use(block: (LibraryDatabase) -> T): T

    companion object {

        /** Always [database], as a test opened it. */
        fun of(database: LibraryDatabase): LibraryAccess = object : LibraryAccess {
            override fun <T> use(block: (LibraryDatabase) -> T): T = block(database)
        }

        /** The library database of the app's main process, opened when first used. */
        fun app(context: Context): LibraryAccess {
            val app = context.applicationContext
            return object : LibraryAccess {
                override fun <T> use(block: (LibraryDatabase) -> T): T = Library.read(app, block)
            }
        }
    }
}
