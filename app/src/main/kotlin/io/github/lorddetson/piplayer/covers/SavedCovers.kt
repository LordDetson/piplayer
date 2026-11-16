package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File

/**
 * The accepted covers (TI §5.2, §10.2): image files in [dir], in the no-backup files, each named by the SHA-256 of its
 * bytes, and their `saved_cover` rows in the user database, which decisions refer to by row ID. The same bytes for the
 * same release are one row. A file that no row names is deleted when the covers are first used in a process; a row whose
 * file is gone (after a restore) gives no file. Saving raises no change counter: a decision that refers to the cover does.
 */
class SavedCovers(private val database: UserDatabase, val dir: File) {

    /** Where a saved cover came from, as `saved_cover.source` stores it (TI §10.2). */
    enum class Source(val stored: String) {
        /** A release's front image of Cover Art Archive. */
        RELEASE("release"),

        /** A release group's front image, taken from another edition. */
        RELEASE_GROUP("release_group"),

        /** An image of a release's index (the cover picker). */
        RELEASE_INDEX("release_index"),

        /** A picture of the file itself. */
        EMBEDDED("embedded"),
    }

    /** Deletes the files that no row names, once per instance; the other calls do it first. */
    suspend fun load(): Unit = TODO("M3 step 16")

    /**
     * Saves the accepted cover [bytes] from [source] for [releaseMbid] and gives its row's ID: the row of the same bytes
     * for the same release when there is one. Null when the bytes are no JPEG, PNG, GIF or WebP image.
     */
    suspend fun save(bytes: ByteArray, releaseMbid: String?, source: Source): Long? = TODO("M3 step 16")

    /** [save] of a fetched Cover Art Archive [image]: its source says the release, the release group or the index. */
    suspend fun save(image: CoverImage, releaseMbid: String?): Long? = save(
        image.bytes,
        releaseMbid,
        when (image.source?.entity) {
            CoverEntity.RELEASE -> Source.RELEASE
            CoverEntity.RELEASE_GROUP -> Source.RELEASE_GROUP
            null -> Source.RELEASE_INDEX
        },
    )

    /** The image file of the saved cover [id]; null when there is no such row or its file is gone. */
    suspend fun file(id: Long): File? = TODO("M3 step 16")

    companion object {
        /** The folder of the saved covers in the no-backup files (TI §5.2). */
        const val DIR = "saved_covers"

        /** The folder of the saved covers under [noBackupFilesDir], which Auto Backup and device transfer leave out. */
        fun directory(noBackupFilesDir: File): File = File(noBackupFilesDir, DIR)
    }
}
