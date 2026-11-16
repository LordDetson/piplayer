package io.github.lorddetson.piplayer.covers

import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.ImageFormat
import io.github.lorddetson.piplayer.identification.caa.ImageHeader
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.userdata.SavedCoverEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The accepted covers (TI §5.2, §10.2): image files in [dir], in the no-backup files, each named by the SHA-256 of its
 * bytes, and their `saved_cover` rows in the user database, which decisions refer to by row ID. The same bytes for the
 * same release are one row. A file that no row names is deleted when the covers are first used in a process; a row whose
 * file is gone (after a restore) gives no file. Saving raises no change counter: a decision that refers to the cover does.
 * The app holds one instance ([Covers.saved]), so that two saves of one image never write its file at once.
 */
class SavedCovers(
    private val database: UserDatabase,
    val dir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

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

    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    /** Deletes the files that no row names, once per instance; the other calls do it first. */
    suspend fun load() {
        if (!loaded) mutex.withLock { swept() }
    }

    /**
     * Saves the accepted cover [bytes] from [source] for [releaseMbid] and gives its row's ID: the row of the same bytes
     * for the same release when there is one. Null when the bytes are no JPEG, PNG, GIF or WebP image.
     */
    suspend fun save(bytes: ByteArray, releaseMbid: String?, source: Source): Long? {
        val format = ImageHeader.format(bytes) ?: return null
        val hash = Hex.text(MessageDigest.getInstance("SHA-256").digest(bytes))
        val name = "$hash.${extension(format)}"
        val release = Hex.mbidBytes(releaseMbid)
        return mutex.withLock {
            swept()
            // The file first, whole, and then its row: a process that dies in between leaves a file no row names.
            withContext(ioDispatcher) { write(name, bytes) }
            database.withWriteTransaction {
                val same = usePrepared(SAME_COVER) { statement ->
                    statement.bindText(1, hash)
                    if (release == null) statement.bindNull(2) else statement.bindBlob(2, release)
                    if (statement.step()) statement.getLong(0) else null
                }
                same ?: run {
                    val size = ImageHeader.size(bytes)
                    database.decisions().addSavedCover(
                        SavedCoverEntity(
                            releaseMbid = release, path = name, width = size?.width, height = size?.height,
                            bytes = bytes.size.toLong(), hash = hash, source = source.stored,
                        ),
                    )
                }
            }
        }
    }

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

    /**
     * The image file of the saved cover [id]; null when there is no such row or its file is gone, or does not hold the
     * row's number of bytes (cut short by a power loss), until the same bytes are saved again.
     */
    suspend fun file(id: Long): File? {
        load()
        val row = database.decisions().savedCover(id) ?: return null
        return withContext(ioDispatcher) { File(dir, row.path).takeIf { it.isFile && it.length() == row.bytes } }
    }

    /**
     * Deletes the rows no decision refers to (saved for an Apply that never committed) and then the files no row names,
     * unless that was done in this instance; holds the lock. A cover saved in this process is never swept: the sweep
     * comes before the first save.
     */
    private suspend fun swept() {
        if (loaded) return
        database.withWriteTransaction { usePrepared(UNREFERENCED) { it.step() } }
        val named = database.useReaderConnection { connection ->
            connection.usePrepared(PATHS) { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }
        }
        withContext(ioDispatcher) { dir.listFiles().orEmpty().filter { it.isFile && it.name !in named }.forEach(File::delete) }
        loaded = true
    }

    /**
     * Writes [bytes] as [name] in [dir] through a temporary file flushed to the storage before its rename, so the file is
     * whole also after a power loss, unless a file of that name holds them already.
     */
    private fun write(name: String, bytes: ByteArray) {
        val target = File(dir, name)
        // The name is the hash of the bytes: a file of their length holds them.
        if (target.isFile && target.length() == bytes.size.toLong()) return
        dir.mkdirs()
        val temporary = File(dir, ".$name.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Could not store the saved cover $name")
        }
    }

    private fun extension(format: ImageFormat) = when (format) {
        ImageFormat.JPEG -> "jpg"
        ImageFormat.PNG -> "png"
        ImageFormat.GIF -> "gif"
        ImageFormat.WEBP -> "webp"
    }

    companion object {
        /** The folder of the saved covers in the no-backup files (TI §5.2). */
        const val DIR = "saved_covers"

        /** The folder of the saved covers under [noBackupFilesDir], which Auto Backup and device transfer leave out. */
        fun directory(noBackupFilesDir: File): File = File(noBackupFilesDir, DIR)

        private const val SAME_COVER = "SELECT id FROM saved_cover WHERE hash = ? AND release_mbid IS ? ORDER BY id LIMIT 1"
        private const val PATHS = "SELECT path FROM saved_cover"

        // An identification refers to its cover through its release choice, an edit through a cover row of its own, whose
        // value is the ID in decimal digits (TI §10.2); undone decisions keep theirs.
        private val UNREFERENCED = "DELETE FROM saved_cover WHERE " +
            "id NOT IN (SELECT saved_cover_id FROM release_choice WHERE saved_cover_id IS NOT NULL) AND " +
            "id NOT IN (SELECT CAST(CAST(value AS TEXT) AS INTEGER) FROM track_metadata WHERE field = ${DecidedField.COVER.code} " +
            "AND value IS NOT NULL)"
    }
}
