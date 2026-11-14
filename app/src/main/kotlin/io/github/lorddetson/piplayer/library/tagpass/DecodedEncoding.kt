package io.github.lorddetson.piplayer.library.tagpass

/**
 * The manual legacy encoding the stored files were last decoded with, null for Automatic (docs/library.md §5.7). A run
 * that finds another choice in the settings decodes them again, so a change that no run could finish before the process
 * ended is still applied.
 */
interface DecodedEncoding {
    suspend fun read(): String?

    suspend fun write(name: String?)

    /** Kept for the life of the object: tests, and a library that lives no longer than its process. */
    class InMemory(@Volatile private var name: String? = null) : DecodedEncoding {
        override suspend fun read(): String? = name

        override suspend fun write(name: String?) {
            this.name = name
        }
    }
}
