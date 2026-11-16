package io.github.lorddetson.piplayer.ui.identification

import android.content.Context
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.covers.SavedCovers
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.mirror.FileValues
import io.github.lorddetson.piplayer.library.mirror.LibraryDecisions
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The review's decisions (TI §6, review screen A) over the library's [decisions], the [library] database for the files
 * of a key, the [user] database for the identity that holds it and the [covers] Apply saves.
 */
class ReviewDecisions(
    private val decisions: LibraryDecisions,
    private val library: LibraryAccess,
    private val user: UserDatabase,
    private val covers: SavedCovers,
) {

    /** Whether an identity holds [target]'s key, which Keep as-is and Skip are stored by (TI §1, §6). */
    suspend fun canKeepOrSkip(target: IdentificationTarget.LibraryFile): Boolean = identityOf(target) != null

    /** Saves [cover] first when it is given, then stores [request] (TI §5.2, §7.1); the decision's ID, or null. */
    suspend fun apply(request: ApplyRequest, cover: CoverImage?): Long? {
        // The cover as part of Apply, never ahead of it (TI §5.2): the sweep deletes rows no decision refers to.
        val savedCoverId = cover?.let { covers.save(it, request.details.releaseId) ?: error("The cover could not be saved") }
        return decisions.apply(request, savedCoverId, locations(request.target))?.decisionId
    }

    /** Whether an identity holds [target]'s key, which an edit decision is stored by (TI §7.1, §10.2). */
    suspend fun canEdit(target: IdentificationTarget): Boolean = identityOf(target) != null

    /**
     * Apply with the user's values (TI §6, review screen B): saves [cover] and the chosen cover of [edit] first, stores
     * [request] when there is one, then [edit] as an edit decision by the identity of the key. Null when nothing was
     * written.
     */
    suspend fun applyWithEdit(request: ApplyRequest?, cover: CoverImage?, edit: ReviewEdit): Applied? {
        val target = edit.target
        // The covers as part of Apply, never ahead of it (TI §5.2): the sweep deletes rows no decision refers to.
        val frontId = cover?.let { covers.save(it, request?.details?.releaseId) ?: error("The cover could not be saved") }
        val chosenId = edit.cover?.let { chosen ->
            covers.save(chosen.bytes, edit.releaseId, source(chosen.origin)) ?: error("The chosen cover could not be saved")
        }
        val locations = locations(target)
        val identification = request?.let { decisions.apply(it, frontId, locations) ?: return null }
        val values = edit.values + listOfNotNull(chosenId?.let { DecidedField.COVER to it.toString() })
        if (values.isEmpty()) return identification?.let { Applied(it.decisionId) }
        // By the identity the identification used or made; an edit alone needs one that holds the key already.
        val identity = identification?.identityId ?: identityOf(target) ?: return null
        val edited = try {
            decisions.edit(identity, values, Hex.bytes(target.contentKey), locations)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The identification stands: the review says that the user's values were not saved.
            if (identification == null) throw e
            null
        }
        return if (identification == null) {
            edited?.let { Applied(it.decisionId) }
        } else {
            Applied(identification.decisionId, edited?.decisionId, editFailed = edited == null)
        }
    }

    private fun source(origin: ProposedCover.Origin): SavedCovers.Source = when (origin) {
        ProposedCover.Origin.RELEASE_INDEX -> SavedCovers.Source.RELEASE_INDEX
        ProposedCover.Origin.EMBEDDED -> SavedCovers.Source.EMBEDDED
    }

    /** Keep as-is by the identity of [target]'s key; null without one or when nothing was written. */
    suspend fun keepAsIs(target: IdentificationTarget.LibraryFile): Long? {
        val identity = identityOf(target) ?: return null
        return decisions.keepAsIs(identity, Hex.bytes(target.contentKey))?.decisionId
    }

    /** Skip by the identity of [target]'s key, with the library's size and time of its file; null without one. */
    suspend fun skip(target: IdentificationTarget.LibraryFile): Long? {
        val identity = identityOf(target) ?: return null
        val file = read { database -> database.library().file(target.fileId) } ?: return null
        return decisions.skip(identity, file.size, file.mtime, Hex.bytes(target.contentKey))?.decisionId
    }

    /** Undoes [decisionId]; whether it was undone now. */
    suspend fun undo(decisionId: Long): Boolean = decisions.undo(decisionId) != null

    /** The identity that holds [target]'s key now (TI §1); none for a track that starts inside its file. */
    private suspend fun identityOf(target: IdentificationTarget): Long? =
        if (target.startMs != 0L) null else withContext(Dispatchers.IO) { user.identities().idOfKey(Hex.bytes(target.contentKey)) }

    /**
     * The values each read file of [target]'s key holds at its location, the identified file first (TI §7.1): the
     * references of the decision. None for a file opened from another app, which has no location in the library.
     */
    private suspend fun locations(target: IdentificationTarget): List<Decisions.LocationValues> {
        if (target !is IdentificationTarget.LibraryFile) return emptyList()
        val read = read { database ->
            val ids = (database.library().foundFilesWithKey(target.contentKey).map { it.fileId } + target.fileId).distinct()
            FileValues.ofRead(database.library(), ids)
        }
        return read.entries.sortedBy { if (it.key == target.fileId) 0 else 1 }.map { it.value }
    }

    /** [block] over the library database, off the main thread. */
    private suspend fun <T> read(block: suspend (LibraryDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            // The block runs under the library's lock, held by its thread: the reads block that thread.
            library.use { database -> runBlocking { block(database) } }
        }

    companion object {
        /** Over the app's library and user databases and saved covers, as they are now (a rebuild reopens the library). */
        fun of(context: Context): ReviewDecisions {
            val app = context.applicationContext
            return ReviewDecisions(Library.decisions(app), LibraryAccess.app(app), UserData.database(app), Covers.saved(app))
        }
    }
}
