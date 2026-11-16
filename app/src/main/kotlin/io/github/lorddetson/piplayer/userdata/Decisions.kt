package io.github.lorddetson.piplayer.userdata

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.identity.ApplyFile
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.identity.IdentityMatcher
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.library.values.DecisionValues
import io.github.lorddetson.piplayer.library.values.ResolvingCores
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.nio.ByteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The decisions of identification in the user database (TI §7.1, §10.2): Apply, Edit fields, a per-field revert, Keep
 * as-is, Skip, the lapse of a Skip and Undo, each in one transaction that raises the change counter. It writes only the user database; the library database
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

    /**
     * The decided values of the identities with decisions, by content key in hex, and the Keep as-is or Skip that counts
     * for each identity that has one (TI §1), read with the [counter] at that time.
     */
    data class Snapshot(
        val counter: Long,
        val byKey: Map<String, Map<DecidedField, Decided>>,
        val missingInfoByKey: Map<String, Decided> = emptyMap(),
    )

    /**
     * A Skip that counts (TI §1): its decision, its identity with the [contentKey] in hex and the location hint, and the
     * stamp of the file it was made on, the time in milliseconds.
     */
    data class LiveSkip(
        val decisionId: Long,
        val identityId: Long,
        val contentKey: String,
        val hintVolume: String?,
        val hintRelativePath: String?,
        val hintDisplayName: String?,
        val size: Long?,
        val mtimeMs: Long?,
    )

    /** A committed lapse: the identities whose Skips lapsed and the change [counter] it raised to. */
    data class Lapsed(val identityIds: Set<Long>, val counter: Long)

    /** Keep as-is for the track of [identityId] (TI §1): no values. Null when the track has no identity now. */
    suspend fun keepAsIs(identityId: Long, contentKey: ByteArray? = null): Committed? =
        listState(identityId, contentKey, DecisionKind.KEEP_AS_IS, size = null, mtimeMs = null)

    /**
     * Skip for the track of [identityId] (TI §1), with the [fileSize] and [fileMtimeMs] of the file it is made on, which let
     * it lapse: the library's stamp, the time a whole second in milliseconds (`SkipLapse.mtimeMs`), since the lapse looks
     * for a file of the library that holds it. Null when the track has no identity now.
     */
    suspend fun skip(identityId: Long, fileSize: Long, fileMtimeMs: Long, contentKey: ByteArray? = null): Committed? =
        listState(identityId, contentKey, DecisionKind.SKIP, fileSize, fileMtimeMs)

    private suspend fun listState(identityId: Long, contentKey: ByteArray?, kind: DecisionKind, size: Long?, mtimeMs: Long?): Committed? =
        database.withWriteTransaction {
            val identity = database.identities().current(identityId, contentKey) ?: return@withWriteTransaction null
            val id = dao.add(
                DecisionEntity(identityId = identity, kind = kind.code, fields = 0, fileSize = size, fileMtime = mtimeMs, createdAt = clock()),
            )
            committed(id, identity)
        }

    /** The Keep as-is or Skip that counts for each of [identityIds] that has one (TI §1). */
    suspend fun missingInfoOf(identityIds: Collection<Long>): Map<Long, Decided> =
        statesOf(identityIds.distinct().chunked(CHUNK).flatMap { dao.liveStatesOf(it) })

    /** Whether any Skip counts, so that the check after a batch of the tag pass costs nothing without one. */
    suspend fun hasLiveSkips(): Boolean = dao.hasLiveSkips()

    /**
     * The Skips that count among the decisions [decisionIds], of the identities that hold the content keys [keys] (in hex)
     * and of those whose location hint has one of the file [names]: those a batch of the tag pass may let lapse (TI §1).
     */
    suspend fun liveSkipsOf(decisionIds: Collection<Long>, keys: Collection<String>, names: Collection<String>): List<LiveSkip> {
        val rows = decisionIds.distinct().chunked(CHUNK).flatMap { dao.liveSkipsWithIds(it) } +
            keys.distinct().chunked(CHUNK).flatMap { chunk -> dao.liveSkipsWithKeys(chunk.map(Hex::bytes)) } +
            names.distinct().chunked(CHUNK).flatMap { dao.liveSkipsNamed(it) }
        return rows.distinctBy { it.decisionId }.map(::liveSkip)
    }

    private fun liveSkip(row: LiveSkipRow) = LiveSkip(
        row.decisionId, row.identityId, Hex.text(row.contentKey), row.hintVolume, row.hintRelativePath, row.hintDisplayName, row.size,
        row.mtimeMs,
    )

    /**
     * Lets the Skips [decisionIds] lapse (TI §1): those that still count get `lapsed_at`, and the change counter rises
     * once, in one transaction. Null when none counted any more, and then nothing is written.
     */
    suspend fun lapse(decisionIds: Collection<Long>): Lapsed? = database.withWriteTransaction {
        val live = decisionIds.distinct().chunked(CHUNK).flatMap { dao.liveSkipsWithIds(it) }
        if (live.isEmpty()) return@withWriteTransaction null
        val now = clock()
        for (chunk in live.map { it.decisionId }.chunked(CHUNK)) dao.markLapsed(chunk, now)
        database.user().countChange()
        Lapsed(live.mapTo(HashSet()) { it.identityId }, database.user().changeCounter())
    }

    /** Which of [identityIds] have decisions that may decide values, so that only their files are compared (TI §7.1). */
    suspend fun withLiveValues(identityIds: Collection<Long>): Set<Long> =
        identityIds.distinct().chunked(CHUNK).flatMapTo(HashSet()) { dao.identitiesWithLiveValues(it) }

    /**
     * [fileChanged] for each of [items] (an identity and a location with its values now), all in one transaction, as
     * after a batch of the tag pass (TI §7.1); the flagged parts of each, in the same order.
     */
    suspend fun filesChanged(items: List<Pair<Long, LocationValues>>): List<Set<DecidedField>> {
        if (items.isEmpty()) return emptyList()
        return database.withWriteTransaction {
            val tracks = tracksOf(items.map { it.first })
            items.map { (identity, location) -> changedAt(identity, location, tracks[identity]) }
        }
    }

    /** The Keep as-is or Skip that counts for each identity among [rows], the decisions of those kinds. */
    private fun statesOf(rows: List<StateDecisionRow>): Map<Long, Decided> = rows.groupBy { it.identityId }.mapNotNull { (identity, own) ->
        val states = own.mapNotNull { row -> DecisionKind.ofCode(row.kind)?.let { DecisionValues(row.id, it, 0, emptySet()) } }
        DecisionResolver.missingInfoOf(states)?.let { identity to it }
    }.toMap()

    /**
     * Stores the identification [request] (TI §7.1): the ticked fields with all their parts, the per-track values as
     * `track_metadata` rows, the release-level ones in a shared `release_choice` with [savedCoverId] as its cover, and
     * the provenance. [atDecision] are the values of the track's locations at the decision, the first being the one it
     * was made on. When no identity holds the target's key, the identity is made in the same transaction (TI §10.2 "An
     * identity at Apply"): for a file opened from another app from the target's hints, for a library file from
     * [libraryFile], the library's file of the target with stage 0's key. Null when a library target has no identity and
     * no [libraryFile] (the file is gone), and then nothing is written.
     */
    suspend fun apply(
        request: ApplyRequest,
        savedCoverId: Long? = null,
        atDecision: List<LocationValues> = emptyList(),
        libraryFile: ApplyFile? = null,
    ): Committed? =
        database.withWriteTransaction {
            val identity = identityAtApply(request.target, libraryFile) ?: return@withWriteTransaction null
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

    /** A decision as the resolver reads it, with its stored row for the provenance (docs/library.md §18.7). */
    class Stored(val values: DecisionValues, val row: DecisionEntity)

    /** Every decision of [identityId] with its row, undone ones too, oldest first, in one read transaction. */
    suspend fun storedOf(identityId: Long): List<Stored> = database.withReadTransaction {
        val decisions = dao.decisionsOf(identityId)
        val rows = decisions.associateBy { it.id }
        values(decisions, metadataOf(decisions), choicesOf(decisions)).map { Stored(it, rows.getValue(it.id)) }
    }

    /**
     * The decided parts flagged as changed in the file at the place [volume], [relativePath] and [displayName] for the
     * decisions that win them now (TI §7.1), read without writing: track details mark their fields (docs/library.md
     * §18.7), while the comparison belongs to the tag pass.
     */
    suspend fun flaggedAt(identityId: Long, volume: String, relativePath: String, displayName: String): Set<DecidedField> =
        database.withReadTransaction {
            val flagged = dao.fileStates(identityId).filter {
                it.changedInFile && it.volumeName == volume && it.relativePath == relativePath && it.displayName == displayName
            }
            if (flagged.isEmpty()) return@withReadTransaction emptySet()
            val decisions = dao.decisionsOf(identityId).filter { it.undoneAt == null }
            val winners = DecisionResolver.resolve(values(decisions, metadataOf(decisions), choicesOf(decisions)))
            flagged.mapNotNullTo(LinkedHashSet()) { row -> DecidedField.ofCode(row.field)?.takeIf { winners[it]?.decisionId == row.decisionId } }
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
    suspend fun snapshot(): Snapshot {
        val read = database.withReadTransaction {
            val counter = database.user().changeCounter()
            val decisions = dao.liveValueDecisions()
            Read(
                counter = counter,
                decisions = decisions,
                metadata = dao.liveMetadata(),
                choices = decisions.mapNotNull { it.releaseChoiceId }.distinct().chunked(CHUNK).flatMap { dao.releaseChoices(it) },
                keys = dao.decidedKeys(),
                states = dao.liveStates(),
            )
        }
        // Decoding and resolving made up most of a whole run with a decision on each of 20 000 files (TI §11.2), so they
        // run after the transaction, on every core but one: a start-up run may meet playback resuming (TI §10.3).
        return withContext(ResolvingCores.dispatcher) {
            val choices = read.choices.parallel { rows -> rows.map { it.id to choice(it) } }.toMap()
            val metadata = read.metadata.groupBy { it.decisionId }
            val decided = read.decisions.groupBy { it.identityId }.entries.toList()
                .parallel { own -> own.mapNotNull { (identity, decisions) -> resolvedOne(decisions, metadata, choices)?.let { identity to it } } }
                .toMap()
            val keys = read.keys.associate { it.identityId to Hex.text(it.contentKey) }
            val stateKeys = read.states.associate { it.identityId to it.contentKey }
            Snapshot(
                read.counter,
                decided.mapNotNull { (identity, values) -> keys[identity]?.let { it to values } }.toMap(),
                statesOf(read.states).mapNotNull { (identity, state) -> stateKeys[identity]?.let { Hex.text(it) to state } }.toMap(),
            )
        }
    }

    /** What [snapshot] reads in its transaction. */
    private class Read(
        val counter: Long,
        val decisions: List<DecisionEntity>,
        val metadata: List<TrackMetadataEntity>,
        val choices: List<ReleaseChoiceEntity>,
        val keys: List<DecisionKeyRow>,
        val states: List<StateDecisionRow>,
    )

    /** [transform] of this list's items in parts, a few per core, on the calling dispatcher; in order. */
    private suspend fun <T, R> List<T>.parallel(transform: (List<T>) -> List<R>): List<R> = coroutineScope {
        if (size < PARALLEL_FROM) return@coroutineScope transform(this@parallel)
        chunked((size + PARTS - 1) / PARTS).map { part -> async { transform(part) } }.awaitAll().flatten()
    }

    /**
     * The decided parts of the track of [identityId] whose value at [location] changed in the file since the decision
     * (TI §7.1), with [LocationValues.values] the file's values now; they are flagged in `file_state` and stay flagged
     * until the user chooses. Each part compares with the references of the decision that wins it now. The flagged
     * parts, those flagged before included.
     */
    suspend fun fileChanged(identityId: Long, location: LocationValues): Set<DecidedField> =
        database.withWriteTransaction { changedAt(identityId, location, tracksOf(listOf(identityId))[identityId]) }

    /**
     * What [changedAt] compares for each of [identityIds] that has decisions that are not undone: read for all of them at
     * once, as a batch of the tag pass compares a few hundred files.
     */
    private suspend fun tracksOf(identityIds: Collection<Long>): Map<Long, Track> {
        val ids = identityIds.distinct()
        val decisions = ids.chunked(CHUNK).flatMap { dao.liveDecisionsOf(it) }
        if (decisions.isEmpty()) return emptyMap()
        val metadata = metadataOf(decisions)
        val choices = choicesOf(decisions)
        val states = ids.chunked(CHUNK).flatMap { dao.fileStatesOf(it) }.groupBy { it.identityId }
        return decisions.groupBy { it.identityId }.mapValues { (identity, own) ->
            Track(own, DecisionResolver.resolve(values(own, metadata, choices)), states[identity].orEmpty())
        }
    }

    /** The decisions of a track that are not undone, the values that win and its file states. */
    private class Track(val decisions: List<DecisionEntity>, val winners: Map<DecidedField, Decided>, val states: List<FileStateEntity>)

    /** [fileChanged] inside the caller's transaction, with what [tracksOf] read of the track; null when it has no decision. */
    private suspend fun changedAt(identityId: Long, location: LocationValues, track: Track?): Set<DecidedField> {
        if (track == null) return emptySet()
        val winners = track.winners
        val ofDecision = track.decisions.associate { it.id to FileReferences.decode(it.fileValues) }
        // The location's rows of the decision that wins each part; the rows of other decisions wait for theirs to win.
        val here = track.states.filter { it.at(location) }
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
        return changed + flaggedBefore
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

    /** Whether an identity holds [target]'s track key now, so that Apply needs no file of the library for it. */
    suspend fun hasIdentity(target: IdentificationTarget): Boolean =
        target.startMs == 0L && database.identities().idOfKey(Hex.bytes(target.contentKey)) != null

    /**
     * The identity of [target] for Apply, inside its transaction: the one that holds the key, or else one made now (TI
     * §10.2 "An identity at Apply"). A file opened from another app gets a missing identity with the app's name and size
     * as hints; a library file, [libraryFile], gets the missing identity hinted at its place
     * ([IdentityMatcher.atApply]), by the exact length stage 0 found when the target carries it, or else a new one with
     * its place. Null when none can be (no file for a library target).
     */
    private suspend fun identityAtApply(target: IdentificationTarget, libraryFile: ApplyFile?): Long? {
        if (target.startMs != 0L) return null
        val dao = database.identities()
        val key = Hex.bytes(target.contentKey)
        dao.idOfKey(key)?.let { return it }
        val now = clock()
        return when (target) {
            is IdentificationTarget.OpenedFile -> dao.add(
                TrackIdentityEntity(
                    contentKey = key, durationMs = null, recordingMbid = null, hintVolumeName = null, hintRelativePath = null,
                    hintDisplayName = target.displayName, hintSize = target.size, createdAt = now,
                    // No file of the library holds the key yet; step 1 of the attach pass clears it once one does.
                    missingSince = now,
                ),
            )
            is IdentificationTarget.LibraryFile -> {
                // Stage 0's key, and its exact length when it found one: the index's can be off by more than step 2's 1 s.
                val file = libraryFile?.file?.takeIf { it.fileId == target.fileId }
                    ?.let { it.copy(contentKey = target.contentKey, durationMs = target.durationMs ?: it.durationMs) } ?: return null
                // The identity of the key the library still stores for this file alone is the file's own, whose audio
                // changed in place before the attach pass saw it. It counts as missing, and it comes first: it holds the
                // file's old key, where another missing identity only names the place.
                val held = libraryFile.heldKey?.let { dao.idOfKey(Hex.bytes(it)) }?.let { dao.identity(it) }?.takeIf { it.missingSince == null }
                val missing = listOfNotNull(held) + dao.missingIdentities()
                val taker = held?.let { IdentityMatcher.atApply(file, listOf(IdentityAttacher.identity(it).copy(missingSince = now))) }
                    ?: IdentityMatcher.atApply(file, missing.filter { it.id != held?.id }.map(IdentityAttacher::identity))
                if (taker == null) {
                    dao.add(
                        TrackIdentityEntity(
                            contentKey = key, durationMs = file.durationMs, recordingMbid = null,
                            hintVolumeName = file.place.volume, hintRelativePath = file.place.relativePath,
                            hintDisplayName = file.place.displayName, hintSize = file.size, createdAt = now, missingSince = null,
                        ),
                    )
                } else {
                    val old = missing.first { it.id == taker.id }
                    dao.updateAll(
                        listOf(
                            old.copy(
                                contentKey = key, durationMs = taker.durationMs, recordingMbid = Hex.mbidBytes(taker.recordingMbid),
                                hintVolumeName = file.place.volume, hintRelativePath = file.place.relativePath,
                                hintDisplayName = file.place.displayName, hintSize = file.size, missingSince = null,
                            ),
                        ),
                    )
                    taker.id
                }
            }
        }
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
            .associate { it.id to choice(it) }

    private fun choice(row: ReleaseChoiceEntity) = Choice(ReleaseValues.decode(row.values), row.savedCoverId)

    /**
     * A release choice as the decisions read it, with the values of each of its discs put together once: a choice serves
     * every track of its release.
     */
    private class Choice(val values: ReleaseValues, val savedCoverId: Long?) {
        private val ofDiscs = values.discs.mapValues { (disc, _) -> values.forDisc(disc) }

        /** `ReleaseValues.forDisc`. */
        fun forDisc(disc: Int?): Map<DecidedField, String?> = disc?.let(ofDiscs::get) ?: values.values
    }

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
                values += choice.forDisc(values[DecidedField.DISC_NUMBER]?.toIntOrNull())
                values[DecidedField.COVER] = choice.savedCoverId?.toString()
            }
            values[DecidedField.RECORDING_MBID] = Hex.mbidText(decision.recordingMbid)
            values[DecidedField.RELEASE_TRACK_MBID] = Hex.mbidText(decision.releaseTrackMbid)
        }
        DecisionValues(
            decision.id, kind, decision.createdAt, DecisionCodec.fields(decision.fields), values, decision.undoneAt != null,
            lapsed = decision.lapsedAt != null,
        )
    }

    private fun resolved(
        decisions: List<DecisionEntity>,
        metadata: Map<Long, List<TrackMetadataEntity>>,
        choices: Map<Long, Choice>,
    ): Map<Long, Map<DecidedField, Decided>> = decisions.groupBy { it.identityId }
        .mapNotNull { (identity, own) -> resolvedOne(own, metadata, choices)?.let { identity to it } }
        .toMap()

    /** The decided values that win among [own], the decisions of one identity; null when none does. */
    private fun resolvedOne(
        own: List<DecisionEntity>,
        metadata: Map<Long, List<TrackMetadataEntity>>,
        choices: Map<Long, Choice>,
    ): Map<DecidedField, Decided>? = DecisionResolver.resolve(values(own, metadata, choices)).takeIf { it.isNotEmpty() }

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

        /** Below this many items [parallel] does not split. */
        const val PARALLEL_FROM = 256

        /** The parts [parallel] splits into: a few per core, so that a slow part leaves no core idle for long. */
        val PARTS = ResolvingCores.count * 4
    }
}
