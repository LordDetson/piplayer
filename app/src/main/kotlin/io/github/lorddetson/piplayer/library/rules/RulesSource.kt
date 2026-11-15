package io.github.lorddetson.piplayer.library.rules

/** Where the sync reads the folder rules from: the user database, or a test's own (docs/library.md §3.4). */
interface RulesSource {

    /**
     * The user database's rules counter (`user_meta.rules_counter`, docs/library.md §3.1), raised only by the folder
     * rules, not its change counter: the sync applies the rules again whenever it differs from
     * `library_meta.mirrored_rules_counter`, so a favorite or a play does not.
     */
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
