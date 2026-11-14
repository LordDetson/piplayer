package io.github.lorddetson.piplayer.userdata

import androidx.room3.withWriteTransaction

/** The library folder rules the user set (docs/library.md §3.4); every change counts as a change of user data. */
class FolderRules(private val database: UserDatabase) {

    private val dao = database.user()

    suspend fun all(): List<FolderRuleEntity> = dao.folderRules()

    /** Includes ([include]) or excludes the folder [path] of [volume] with its subfolders. */
    suspend fun set(volume: String, path: String, include: Boolean) = database.withWriteTransaction {
        dao.putFolderRule(volume, path, if (include) FolderRuleEntity.INCLUDE else FolderRuleEntity.EXCLUDE)
        dao.countChange()
        dao.countRulesChange()
    }

    /** Lets the folder follow its parent again. */
    suspend fun remove(volume: String, path: String) = database.withWriteTransaction {
        dao.removeFolderRule(volume, path)
        dao.countChange()
        dao.countRulesChange()
    }
}
