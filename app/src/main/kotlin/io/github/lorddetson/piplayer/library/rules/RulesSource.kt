package io.github.lorddetson.piplayer.library.rules

/** Where the sync reads the folder rules from: the user database, or a test's own (docs/library.md §3.4). */
interface RulesSource {

    /** The user database's change counter; the rules are applied again whenever it differs from the mirrored one. */
    suspend fun counter(): Long

    suspend fun rules(): List<FolderRule>

    companion object {
        /** No rules at all. */
        val NONE = object : RulesSource {
            override suspend fun counter() = 0L
            override suspend fun rules() = emptyList<FolderRule>()
        }
    }
}
