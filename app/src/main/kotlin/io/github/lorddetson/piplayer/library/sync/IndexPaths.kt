package io.github.lorddetson.piplayer.library.sync

/** Paths as the library keeps them (docs/library.md §3.4): empty for a volume's root, otherwise ending with `/`. */
object IndexPaths {

    /** [raw] is the media index's `relative_path`. */
    fun relativePath(raw: String?): String = raw?.takeUnless { it == "/" }.orEmpty()
}
