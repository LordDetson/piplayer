package io.github.lorddetson.piplayer.userdata

import android.content.Context
import io.github.lorddetson.piplayer.ProcessRole

/** The user database of this process, opened once, in the main process only (docs/library.md §3.1). */
object UserData {

    @Volatile
    private var opened: UserDatabase? = null

    fun database(context: Context): UserDatabase = opened ?: synchronized(this) {
        opened ?: run {
            ProcessRole.current(context).requireMain("The user database")
            UserDatabase.open(context.applicationContext, UserDatabase.NAME).also { opened = it }
        }
    }

    fun folderRules(context: Context) = FolderRules(database(context))
}
