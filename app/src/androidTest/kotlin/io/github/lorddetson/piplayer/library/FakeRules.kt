package io.github.lorddetson.piplayer.library

import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.rules.RulesSource

/** Folder rules a test sets itself; every change counts as a change of user data. */
class FakeRules : RulesSource {

    @Volatile
    private var current = emptyList<FolderRule>()

    @Volatile
    private var changes = 0L

    fun set(vararg rules: FolderRule) {
        current = rules.toList()
        changes++
    }

    override suspend fun counter() = changes

    override suspend fun rules() = current
}
