package io.github.lorddetson.piplayer.userdata

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.library.values.DecisionValues
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.nio.ByteBuffer

/**
 * The decisions of identification in the user database (TI §7.1, §10.2): Apply, Edit fields, a per-field revert and
 * Undo, each in one transaction that raises the change counter. It writes only the user database; the library database
 * follows through `DecisionMirror`, which `LibraryDecisions` runs after each commit.
 */
class Decisions(private val database: UserDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val dao get() = database.decisions()

    /** A committed write: the [decisionId] written or undone, its [identityId] and the change [counter] it raised to. */
    data class Committed(val decisionId: Long, val identityId: Long, val counter: Long)

    /** A file location of a track with its values at a decision (the parts of `DecisionContent.ofTags`). */
    data class LocationValues(
        val volume: String,
        val relativePath: String,
        val displayName: String,
        val values: Map<DecidedField, String?>,
    )

    /** The decided values of the identities with decisions, by content key in hex, read with the [counter] at that time. */
    data class Snapshot(val counter: Long, val byKey: Map<String, Map<DecidedField, Decided>>)

    /**
     * Stores the identification [request] (TI §7.1): the ticked fields with all their parts, the per-track values as
     * `track_metadata` rows, the release-level ones in a shared `release_choice` with [savedCoverId] as its cover, and
     * the provenance. [atDecision] are the values of the track's locations at the decision, the first being the one it
     * was made on. Null when no identity holds the target's key, and then nothing is written.
     */
    suspend fun apply(request: ApplyRequest, savedCoverId: Long? = null, atDecision: List<LocationValues> = emptyList()): Committed? =
        database.withWriteTransaction {
            val identity = database.identities().idOfKey(Hex.bytes(request.target.contentKey)).takeIf { request.target.startMs == 0L }
                ?: return@withWriteTransaction null
            val now = clock()
            val details = request.details
            val parts = DecisionContent.ofRelease(details, savedCoverId)
            val choice = releaseChoice(details.releaseId, details.releaseGroupId, ReleaseValues.of(parts, details.at.disc), savedCoverId, now)
            val decided = parts.filterKeys { it.group in request.fields }
            val provenance = request.provenance
            val id = dao.add(
                DecisionEntity(
                    identityId = identity,
                    kind = DecisionKind.APPLY.code,
                    fields = DecisionCodec.bits(request.fields),
                    releaseChoiceId = choice,
                    recordingMbid = Hex.mbidBytes(provenance.recordingId),
                    releaseTrackMbid = Hex.mbidBytes(provenance.releaseTrackId),
                    acoustIdId = Hex.mbidBytes(provenance.acoustIdTrackId),
                    confidence = DecisionCodec.confidenceCode(provenance.confidence),
                    reason = DecisionCodec.reason(provenance.reason),
                    provider = DecisionCodec.providerCode(provenance.provider),
                    fetchedAt = provenance.fetchedAt.toEpochMilli(),
                    licenseClass = DecisionCodec.licenseCode(provenance.licenseClass),
                    fileValues = atDecision.firstOrNull()?.let { FileReferences.encode(FileReferences.of(decided, it.values)) },
                    createdAt = now,
                ),
            )
            // The whole result is kept; `fields` says which of it is decided.
            dao.addMetadata(rows(id, parts.filterKeys { !it.release && !it.decisionColumn }))
            locations(identity, id, decided, atDecision)
            committed(id, identity)
        }

    /**
     * Stores the user's own [values] for the track of [identityId] (read with [contentKey]): every part of each field
     * they touch, the parts not given empty. Null when the track has no identity now.
     */
    suspend fun edit(
        identityId: Long,
        values: Map<DecidedField, String?>,
        contentKey: ByteArray? = null,
        atDecision: List<LocationValues> = emptyList(),
    ): Committed? {
        require(values.isNotEmpty()) { "An edit sets at least one field" }
        return database.withWriteTransaction {
            val identity = database.identities().current(identityId, contentKey) ?: return@withWriteTransaction null
            val fields = values.keys.mapTo(LinkedHashSet()) { it.group }
            val parts = fields.flatMap { DecidedField.partsOf(it) }.associateWith { values[it]?.takeIf(String::isNotEmpty) }
            val id = dao.add(
                DecisionEntity(
                    identityId = identity,
                    kind = DecisionKind.EDIT.code,
                    fields = DecisionCodec.bits(fields),
                    fileValues = atDecision.firstOrNull()?.let { FileReferences.encode(FileReferences.of(parts, it.values)) },
                    createdAt = clock(),
                ),
            )
            dao.addMetadata(rows(id, parts))
            locations(identity, id, parts, atDecision)
            committed(id, identity)
        }
    }

    /** A per-field revert of [fields] for the track of [identityId]: the file's values win them again. */
    suspend fun revert(identityId: Long, fields: Set<ProtectedField>, contentKey: ByteArray? = null): Committed? {
        require(fields.isNotEmpty()) { "A revert reverts at least one field" }
        return database.withWriteTransaction {
            val identity = database.identities().current(identityId, contentKey) ?: return@withWriteTransaction null
            val parts = fields.flatMap { DecidedField.partsOf(it) }
            val id = dao.add(
                DecisionEntity(identityId = identity, kind = DecisionKind.REVERT_FIELD.code, fields = DecisionCodec.bits(fields), createdAt = clock()),
            )
            dao.addMetadata(parts.map { TrackMetadataEntity(id, it.code, null) })
            // "Use the file's value" settles a flag of these parts while the revert stands, since nothing of them is decided
            // then; the older decisions keep their file states, which an undo of the revert brings back (TI §7.1).
            committed(id, identity)
        }
    }

    /** Undoes [decisionId]; null when it is undone already, or it or its identity is gone. */
    suspend fun undo(decisionId: Long): Committed? = database.withWriteTransaction {
        val decision = dao.decision(decisionId) ?: return@withWriteTransaction null
        if (database.identities().identity(decision.identityId) == null) return@withWriteTransaction null
        if (dao.markUndone(decisionId, clock()) == 0) return@withWriteTransaction null
        committed(decisionId, decision.identityId)
    }

    /** The newest identification of [identityId] that is not undone, by row ID (TI §7.1), which "Undo identification" undoes. */
    suspend fun newestIdentification(identityId: Long): Long? = dao.decisionsOf(identityId)
        .filter { it.kind == DecisionKind.APPLY.code && it.undoneAt == null }
        .maxOfOrNull { it.id }

    /** Every decision of [identityId] as the resolver reads it, undone ones too, oldest first. */
    suspend fun decisionsOf(identityId: Long): List<DecisionValues> {
        val decisions = dao.decisionsOf(identityId)
        return values(decisions, metadataOf(decisions), choicesOf(decisions))
    }

    /** The decided values that win for each of [identityIds] that has any. */
    suspend fun decidedOf(identityIds: Collection<Long>): Map<Long, Map<DecidedField, Decided>> {
        val decisions = identityIds.distinct().chunked(CHUNK).flatMap { dao.liveValueDecisionsOf(it) }
        return resolved(decisions, metadataOf(decisions), choicesOf(decisions))
    }

    /** The identities of [identityIds] with their content keys in hex. */
    suspend fun keysOf(identityIds: Collection<Long>): Map<Long, String> =
        identityIds.distinct().chunked(CHUNK).flatMap { dao.keysOf(it) }.associate { it.identityId to Hex.text(it.contentKey) }

    /** Every identity's decided values and the change counter, in one read transaction (TI §10.3). */
    suspend fun snapshot(): Snapshot = database.withReadTransaction {
        val counter = database.user().changeCounter()
        val decisions = dao.liveValueDecisions()
        val metadata = dao.liveMetadata().groupBy { it.decisionId }
        val decided = resolved(decisions, metadata, choicesOf(decisions))
        val keys = dao.decidedKeys().associate { it.identityId to Hex.text(it.contentKey) }
        Snapshot(counter, decided.mapNotNull { (identity, values) -> keys[identity]?.let { it to values } }.toMap())
    }

    /**
     * The decided parts of the track of [identityId] whose value at [location] changed in the file since the decision
     * (TI §7.1), with [LocationValues.values] the file's values now; they are flagged in `file_state` and stay flagged
     * until the user chooses. Each part compares with the references of the decision that wins it now. The flagged
     * parts, those flagged before included.
     */
    suspend fun fileChanged(identityId: Long, location: LocationValues): Set<DecidedField> = database.withWriteTransaction {
        val decisions = dao.decisionsOf(identityId).filter { it.undoneAt == null }
        val winners = DecisionResolver.resolve(values(decisions, metadataOf(decisions), choicesOf(decisions)))
        val ofDecision = decisions.associate { it.id to FileReferences.decode(it.fileValues) }
        // The location's rows of the decision that wins each part; the rows of other decisions wait for theirs to win.
        val here = dao.fileStates(identityId).filter { it.at(location) }
            .mapNotNull { row -> DecidedField.ofCode(row.field)?.takeIf { winners[it]?.decisionId == row.decisionId }?.let { it to row } }
            .toMap()
        val references = here.mapValues { (_, row) -> int(row.valueAtDecision) }
        val flaggedBefore = here.filterValues { it.changedInFile }.keys
        val changed = FileReferences.changed(winners, ofDecision, references, location.values)
        val newly = changed - flaggedBefore
        if (newly.isNotEmpty()) {
            dao.saveFileStates(
                newly.map { part ->
                    val winner = winners.getValue(part)
                    val reference = references[part] ?: ofDecision[winner.decisionId]?.get(part) ?: FileReferences.hash(winner.value)
                    state(identityId, winner.decisionId, location, part, reference, changed = true)
                },
            )
        }
        changed + flaggedBefore
    }

    /**
     * "Keep PiPlayer's value" for [parts] at [location]: clears their flags and takes the present values as the
     * references of the decisions that win them. Parts no decision wins are left alone.
     */
    suspend fun keepPiPlayersValue(identityId: Long, location: LocationValues, parts: Set<DecidedField>) {
        if (parts.isEmpty()) return
        database.withWriteTransaction {
            val decisions = dao.decisionsOf(identityId).filter { it.undoneAt == null }
            val winners = DecisionResolver.resolve(values(decisions, metadataOf(decisions), choicesOf(decisions)))
            val rows = parts.mapNotNull { part ->
                val winner = winners[part] ?: return@mapNotNull null
                state(identityId, winner.decisionId, location, part, FileReferences.hash(location.values[part]), changed = false)
            }
            if (rows.isNotEmpty()) dao.saveFileStates(rows)
        }
    }

    /** Forgets every decision with its values, file states and saved covers; tests start so. */
    suspend fun clear() = database.withWriteTransaction {
        for (table in listOf("track_metadata", "file_state", "decision", "release_choice", "saved_cover")) {
            usePrepared("DELETE FROM $table") { it.step() }
        }
        database.user().countChange()
    }

    /**
     * The release choice for [values] of [releaseId]: a row of that release with the same cover and the same values but
     * its discs, which takes the disc it lacks, or a new row (TI §10.2).
     */
    private suspend fun releaseChoice(releaseId: String, groupId: String?, values: ReleaseValues, savedCoverId: Long?, now: Long): Long {
        val releaseMbid = requireNotNull(Hex.mbidBytes(releaseId)) { "Not an MBID: $releaseId" }
        val groupMbid = Hex.mbidBytes(groupId)
        for (row in dao.releaseChoicesOf(releaseMbid)) {
            if (row.savedCoverId != savedCoverId || !row.releaseGroupMbid.contentEquals(groupMbid)) continue
            val stored = ReleaseValues.decode(row.values)
            val merged = stored.merged(values) ?: continue
            if (merged.discs.keys != stored.discs.keys) dao.setReleaseValues(row.id, merged.encode())
            return row.id
        }
        return dao.addReleaseChoice(
            ReleaseChoiceEntity(
                releaseMbid = releaseMbid, releaseGroupMbid = groupMbid, values = values.encode(), savedCoverId = savedCoverId,
                fetchedAt = now,
            ),
        )
    }

    /**
     * The references of the locations after the first one (TI §7.1): a `file_state` row of [decisionId] for each decided
     * part whose value there differs from the reference the decision keeps. The rows of older decisions stay: they count
     * again when an undo lets their decision win again.
     */
    private suspend fun locations(identity: Long, decisionId: Long, decided: Map<DecidedField, String?>, atDecision: List<LocationValues>) {
        val parts = decided.keys.filter { it != DecidedField.COVER }
        if (parts.isEmpty()) return
        val first = atDecision.firstOrNull() ?: return
        val reference = FileReferences.of(decided, first.values)
        val rows = atDecision.drop(1).flatMap { location ->
            val here = FileReferences.of(decided, location.values)
            parts.mapNotNull { part ->
                val mine = here[part] ?: FileReferences.hash(decided[part])
                val decision = reference[part] ?: FileReferences.hash(decided[part])
                if (mine == decision) null else state(identity, decisionId, location, part, mine, changed = false)
            }
        }
        if (rows.isNotEmpty()) dao.saveFileStates(rows)
    }

    private suspend fun committed(decisionId: Long, identity: Long): Committed {
        database.user().countChange()
        return Committed(decisionId, identity, database.user().changeCounter())
    }

    private fun rows(decisionId: Long, parts: Map<DecidedField, String?>) =
        parts.map { (part, text) -> TrackMetadataEntity(decisionId, part.code, DecisionCodec.encode(part, text)) }

    private suspend fun metadataOf(decisions: List<DecisionEntity>): Map<Long, List<TrackMetadataEntity>> =
        decisions.map { it.id }.chunked(CHUNK).flatMap { dao.metadataOf(it) }.groupBy { it.decisionId }

    private suspend fun choicesOf(decisions: List<DecisionEntity>): Map<Long, Choice> =
        decisions.mapNotNull { it.releaseChoiceId }.distinct().chunked(CHUNK).flatMap { dao.releaseChoices(it) }
            .associate { it.id to Choice(ReleaseValues.decode(it.values), it.savedCoverId) }

    /** A release choice as the decisions read it. */
    private class Choice(val values: ReleaseValues, val savedCoverId: Long?)

    private fun values(
        decisions: List<DecisionEntity>,
        metadata: Map<Long, List<TrackMetadataEntity>>,
        choices: Map<Long, Choice>,
    ): List<DecisionValues> = decisions.mapNotNull { decision ->
        val kind = DecisionKind.ofCode(decision.kind) ?: return@mapNotNull null
        val values = HashMap<DecidedField, String?>()
        for (row in metadata[decision.id].orEmpty()) DecidedField.ofCode(row.field)?.let { values[it] = DecisionCodec.decode(it, row.value) }
        if (kind == DecisionKind.APPLY) {
            decision.releaseChoiceId?.let { choices[it] }?.let { choice ->
                values += choice.values.forDisc(values[DecidedField.DISC_NUMBER]?.toIntOrNull())
                values[DecidedField.COVER] = choice.savedCoverId?.toString()
            }
            values[DecidedField.RECORDING_MBID] = Hex.mbidText(decision.recordingMbid)
            values[DecidedField.RELEASE_TRACK_MBID] = Hex.mbidText(decision.releaseTrackMbid)
        }
        DecisionValues(decision.id, kind, decision.createdAt, DecisionCodec.fields(decision.fields), values, decision.undoneAt != null)
    }

    private fun resolved(
        decisions: List<DecisionEntity>,
        metadata: Map<Long, List<TrackMetadataEntity>>,
        choices: Map<Long, Choice>,
    ): Map<Long, Map<DecidedField, Decided>> = decisions.groupBy { it.identityId }
        .mapValues { (_, own) -> DecisionResolver.resolve(values(own, metadata, choices)) }
        .filterValues { it.isNotEmpty() }

    private fun FileStateEntity.at(location: LocationValues) =
        volumeName == location.volume && relativePath == location.relativePath && displayName == location.displayName

    private fun state(identity: Long, decisionId: Long, location: LocationValues, part: DecidedField, reference: Int, changed: Boolean) =
        FileStateEntity(
            identity, decisionId, location.volume, location.relativePath, location.displayName, part.code, bytes(reference), changed,
        )

    private fun bytes(hash: Int) = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(hash).array()

    private fun int(bytes: ByteArray?): Int = if (bytes == null || bytes.size != Int.SIZE_BYTES) 0 else ByteBuffer.wrap(bytes).int

    private companion object {
        /** IDs bound at a time, under SQLite's limit of arguments. */
        const val CHUNK = 500
    }
}
