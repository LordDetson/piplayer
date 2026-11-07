package io.github.lorddetson.piplayer.covers

import android.content.Context
import android.graphics.Bitmap
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.userdata.UserDatabase

/** The library database's answers for covers (docs/library.md §13.1), with each file's identity from [user]. */
class LibraryCoverFacts(private val access: LibraryTrackRefs.Access, private val user: UserDatabase) : CoverFacts {

    override suspend fun file(fileId: Long): CoverFile? = null

    override suspend fun albumFile(albumKey: String): CoverFile? = null

    override suspend fun artistAlbums(artistKey: String): List<ArtistAlbum> = emptyList()

    override suspend fun artistFile(artistKey: String): CoverFile? = null
}

/** The covers of the main process: one loader for the screens and the session, and the own covers (§13.3). */
object Covers {

    fun own(context: Context): OwnCovers =
        OwnCovers(io.github.lorddetson.piplayer.userdata.UserData.database(context), java.io.File(context.noBackupFilesDir, "covers"))

    fun facts(context: Context): CoverFacts =
        LibraryCoverFacts(
            object : LibraryTrackRefs.Access {
                override fun <T> read(block: (LibraryDatabase) -> T): T = io.github.lorddetson.piplayer.library.Library.read(context, block)
            },
            io.github.lorddetson.piplayer.userdata.UserData.database(context),
        )

    fun loader(context: Context): CoverLoader<Bitmap> = TODO()
}

/** What the screens do with own covers (docs/library.md §13.2). */
class CoverChoices(private val context: Context) {

    enum class Result { SAVED, NOT_READ, NO_IMAGE }

    /** Whether the song [fileId] can have an own cover now: it has an identity once the tag pass has read it. */
    suspend fun canChooseForSong(fileId: Long): Boolean = false

    suspend fun chooseForSong(fileId: Long, image: android.net.Uri): Result = Result.NO_IMAGE

    suspend fun chooseForAlbum(albumKey: String, image: android.net.Uri): Result = Result.NO_IMAGE

    suspend fun removeForSong(fileId: Long): Unit = Unit

    suspend fun removeForAlbum(albumKey: String): Unit = Unit

    suspend fun songHasOwn(fileId: Long): Boolean = false

    suspend fun albumHasOwn(albumKey: String): Boolean = false
}
