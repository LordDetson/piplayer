package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.library.details.DetailField.ADDED
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_ARTIST_ID
import io.github.lorddetson.piplayer.library.details.DetailField.ALBUM_GAIN
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST
import io.github.lorddetson.piplayer.library.details.DetailField.ARTIST_ID
import io.github.lorddetson.piplayer.library.details.DetailField.BARCODE
import io.github.lorddetson.piplayer.library.details.DetailField.BITRATE
import io.github.lorddetson.piplayer.library.details.DetailField.BIT_DEPTH
import io.github.lorddetson.piplayer.library.details.DetailField.CATALOG_NUMBER
import io.github.lorddetson.piplayer.library.details.DetailField.CHANNELS
import io.github.lorddetson.piplayer.library.details.DetailField.CODEC
import io.github.lorddetson.piplayer.library.details.DetailField.COMPILATION
import io.github.lorddetson.piplayer.library.details.DetailField.COMPOSER
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.COVER_SOURCE
import io.github.lorddetson.piplayer.library.details.DetailField.DATE
import io.github.lorddetson.piplayer.library.details.DetailField.DISC
import io.github.lorddetson.piplayer.library.details.DetailField.DISC_SUBTITLE
import io.github.lorddetson.piplayer.library.details.DetailField.FAVORITE
import io.github.lorddetson.piplayer.library.details.DetailField.FILE_NAME
import io.github.lorddetson.piplayer.library.details.DetailField.FOLDER
import io.github.lorddetson.piplayer.library.details.DetailField.FORMAT
import io.github.lorddetson.piplayer.library.details.DetailField.GENRE
import io.github.lorddetson.piplayer.library.details.DetailField.INDEX_LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.ISRC
import io.github.lorddetson.piplayer.library.details.DetailField.LABEL
import io.github.lorddetson.piplayer.library.details.DetailField.LAST_PLAYED
import io.github.lorddetson.piplayer.library.details.DetailField.LENGTH
import io.github.lorddetson.piplayer.library.details.DetailField.MIME_TYPE
import io.github.lorddetson.piplayer.library.details.DetailField.MODIFIED
import io.github.lorddetson.piplayer.library.details.DetailField.ORIGINAL_DATE
import io.github.lorddetson.piplayer.library.details.DetailField.PLAY_COUNT
import io.github.lorddetson.piplayer.library.details.DetailField.RECORDING_ID
import io.github.lorddetson.piplayer.library.details.DetailField.RELEASE_GROUP_ID
import io.github.lorddetson.piplayer.library.details.DetailField.RELEASE_ID
import io.github.lorddetson.piplayer.library.details.DetailField.SAMPLE_RATE
import io.github.lorddetson.piplayer.library.details.DetailField.SIZE
import io.github.lorddetson.piplayer.library.details.DetailField.TAGS
import io.github.lorddetson.piplayer.library.details.DetailField.TAG_ENCODING
import io.github.lorddetson.piplayer.library.details.DetailField.TITLE
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK_GAIN
import io.github.lorddetson.piplayer.library.details.DetailField.TRACK_ID
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.details.DetailField.ACOUSTID_ID
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.library.values.DecisionValues
import io.github.lorddetson.piplayer.library.values.PartKind
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.DecisionCodec
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.library.values.FieldOverrides
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.ManualTagValues
import io.github.lorddetson.piplayer.library.values.TrackTags

/**
 * What is known of a file, for its details (docs/library.md §18): from `audio_file`, or for a file opened from another
 * app from the file itself. [volume] is null for an opened file, which has no place in the library; [read] says that the
 * tags were read, and [tagState] why not when they were not.
 */
data class FileFacts(
    val displayName: String,
    val volume: String? = null,
    val relativePath: String? = null,
    val size: Long? = null,
    val mtimeSeconds: Long? = null,
    val mimeType: String? = null,
    val read: Boolean = false,
    val tagState: TagState? = null,
    val format: String? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val tagEncoding: String? = null,
    val exactDurationMs: Long? = null,
    /** `audio_file.duration_source` (`DurationSource`). */
    val durationSource: String? = null,
    val tagLengthMs: Long? = null,
)

/** What the user database knows of the song's identity (docs/library.md §18.2). */
data class UserFacts(val favorite: Boolean, val playCount: Long, val lastPlayedAt: Long?, val addedAt: Long)

/** The cover shown and its size when known (docs/library.md §18.3). */
data class CoverDetail(val source: CoverSource, val width: Int? = null, val height: Int? = null, val bytes: Long? = null)

/**
 * A decision of the track as details read it (TI §10.2, docs/library.md §18.7): its [values] as the resolver reads them,
 * with the provenance stored once per decision and the AcoustID track ID of a confirmed result.
 */
data class DecisionRecord(
    val values: DecisionValues,
    val provider: ProviderId? = null,
    val confidence: Double? = null,
    val reason: Reason? = null,
    val acoustId: String? = null,
    val fetchedAt: Long? = null,
) {
    companion object {
        /** The record of a [stored] decision, its provenance decoded (TI §10.2). */
        fun of(stored: Decisions.Stored): DecisionRecord = DecisionRecord(
            values = stored.values,
            provider = DecisionCodec.provider(stored.row.provider),
            confidence = DecisionCodec.confidence(stored.row.confidence),
            reason = DecisionCodec.reason(stored.row.reason),
            acoustId = Hex.mbidText(stored.row.acoustIdId),
            fetchedAt = stored.row.fetchedAt,
        )
    }
}

/**
 * Everything details are made of; [fileId] is null for a file opened from another app. [decisions] are every decision of
 * the song's identity, undone ones too, from which levels 1 and 2 are resolved; without them [overrides] gives those
 * levels. [changedInFile] are the decided parts the tag pass flagged at the file's place, [badgeState] the display row's
 * `badge_state` (TI §10.1), and [trace] and [target] pass through (§18.7).
 */
data class DetailInputs(
    val fileId: Long?,
    val file: FileFacts,
    val index: IndexValues? = null,
    val tags: TrackTags? = null,
    val user: UserFacts? = null,
    val cover: CoverDetail? = null,
    val overrides: FieldOverrides = FieldOverrides.NONE,
    val decisions: List<DecisionRecord>? = null,
    val changedInFile: Set<DecidedField> = emptySet(),
    val badgeState: Int? = null,
    val trace: List<TraceRun> = emptyList(),
    val target: DecisionTarget? = null,
)

/** Makes the rows of track details from the merged values (docs/library.md §18.2, TI §7.1). */
object TrackDetailsBuilder {

    fun of(inputs: DetailInputs): TrackDetails {
        val file = inputs.file
        val read = file.read
        // Level 3 counts once the file is read, level 4 only before (TI §7.1).
        val tags = inputs.tags.takeIf { read }
        val index = inputs.index.takeIf { !read }
        val records = inputs.decisions
        // Levels 1 and 2 from the identity's decisions, by the resolver that fills `decided_mirror` (TI §7.1, §18.7).
        val overrides = records?.let { FieldOverrides(DecisionResolver.resolve(it.map(DecisionRecord::values)), inputs.overrides.missingInfo) }
            ?: inputs.overrides
        val decided = overrides.decided
        val providers = records.orEmpty().associate { it.values.id to it.provider }
        // The resolver of the lists itself, so that the fields it resolves are exactly what the lists show.
        val merged = MergedValues.of(
            displayName = file.displayName,
            index = inputs.index,
            tags = inputs.tags,
            read = read,
            exactDurationMs = file.exactDurationMs,
            tagLengthMs = file.tagLengthMs,
            overrides = overrides,
            volume = file.volume.orEmpty(),
            relativePath = file.relativePath.orEmpty(),
        )
        // Every part with its level by the same precedence, for the other fields and every source (§18.7).
        val parts = MergedValues.parts(file.displayName, inputs.index, inputs.tags, read, overrides)
        val decidedFields = decided.keys.mapTo(HashSet()) { it.group }
        val changedFields = inputs.changedInFile.mapTo(HashSet()) { it.group }

        fun sourceOf(level: Int, decisionId: Long?) =
            ValueSource(level, if (level == DecisionKind.LEVEL_IDENTIFICATION) decisionId?.let(providers::get) else null)
        fun sourceOf(part: DecidedField): ValueSource? = parts[part]?.let { sourceOf(it.level, decided[part]?.decisionId) }
        fun decidedSource(part: DecidedField): ValueSource? = decided[part]?.let { sourceOf(it.level, it.decisionId) }

        val rows = ArrayList<DetailRow>()
        fun add(field: DetailField, value: DetailValue?, source: ValueSource? = null) {
            if (value == null) return
            val decidedField = field.protected?.takeIf { it in decidedFields }
            rows += DetailRow(field, value, source, decidedField != null, decidedField != null && decidedField in changedFields)
        }
        fun text(field: DetailField, value: String?, source: ValueSource? = null) = add(field, value.present()?.let(DetailValue::Text), source)
        fun texts(field: DetailField, values: List<String>, source: ValueSource?) =
            add(field, values.mapNotNull { it.present() }.ifEmpty { null }?.let(DetailValue::Texts), source)
        // A decided list is joined as tags write a list (§18.7).
        fun part(field: DetailField, part: DecidedField) {
            val value = parts[part]?.value
            text(field, if (part.kind == PartKind.LIST) DecidedField.items(value).joinToString(LIST_SEPARATOR) else value, sourceOf(part))
        }
        val fileLevel = ValueSource(if (read) DecisionKind.LEVEL_TAGS else DecisionKind.LEVEL_INDEX)

        text(TITLE, merged.title, sourceOf(DecidedField.TITLE))
        text(ARTIST, merged.artist, sourceOf(DecidedField.ARTIST))
        text(ALBUM, merged.album, sourceOf(DecidedField.ALBUM))
        text(ALBUM_ARTIST, merged.albumArtist, sourceOf(DecidedField.ALBUM_ARTIST))
        add(TRACK, merged.trackNumber?.let { DetailValue.OfTotal(it, parts[DecidedField.TRACK_TOTAL]?.value?.toIntOrNull()) }, sourceOf(DecidedField.TRACK_NUMBER))
        add(DISC, merged.discNumber?.let { DetailValue.OfTotal(it, parts[DecidedField.DISC_TOTAL]?.value?.toIntOrNull()) }, sourceOf(DecidedField.DISC_NUMBER))
        part(DISC_SUBTITLE, DecidedField.DISC_SUBTITLE)
        part(DATE, DecidedField.DATE)
        part(ORIGINAL_DATE, DecidedField.ORIGINAL_DATE)
        val manual = ManualTagValues.decode(decided[DecidedField.MANUAL_TAGS]?.value)
        val manualTags by lazy(LazyThreadSafetyMode.NONE) { TrackTags.from(manual) }
        val manualSource = decidedSource(DecidedField.MANUAL_TAGS)
        if ("GENRE" in manual) texts(GENRE, manualTags.genres, manualSource)
        else texts(GENRE, if (read) tags?.genres.orEmpty() else listOfNotNull(index?.genre), fileLevel)
        if ("COMPOSER" in manual) texts(COMPOSER, manualTags.composers, manualSource)
        else texts(COMPOSER, if (read) tags?.composers.orEmpty() else listOfNotNull(index?.composer), fileLevel)
        // A decided flag that is off is a value that can be reverted; the tags' off stays left out.
        when {
            parts[DecidedField.COMPILATION]?.value == "1" -> add(COMPILATION, DetailValue.Flag(true), sourceOf(DecidedField.COMPILATION))
            ProtectedField.COMPILATION in decidedFields -> add(COMPILATION, DetailValue.Flag(false), decidedSource(DecidedField.COMPILATION))
        }
        part(LABEL, DecidedField.LABEL)
        part(CATALOG_NUMBER, DecidedField.CATALOG_NUMBER)
        part(BARCODE, DecidedField.BARCODE)
        part(ISRC, DecidedField.ISRC)
        val tagLevel = ValueSource(DecisionKind.LEVEL_TAGS)
        add(TRACK_GAIN, tags?.trackGainDb?.let { DetailValue.Gain(it, tags.trackPeak) }, tagLevel)
        add(ALBUM_GAIN, tags?.albumGainDb?.let { DetailValue.Gain(it, tags.albumPeak) }, tagLevel)

        inputs.user?.let { user ->
            add(FAVORITE, DetailValue.Flag(user.favorite))
            add(PLAY_COUNT, DetailValue.Count(user.playCount))
            add(LAST_PLAYED, user.lastPlayedAt?.let(DetailValue::Time))
            add(ADDED, DetailValue.Time(user.addedAt))
        }

        text(FORMAT, file.format)
        text(CODEC, file.codec)
        add(BITRATE, file.bitrateKbps?.takeIf { it > 0 }?.let(DetailValue::Kbps))
        add(SAMPLE_RATE, file.sampleRate?.takeIf { it > 0 }?.let(DetailValue::Hertz))
        add(CHANNELS, file.channels?.takeIf { it > 0 }?.let(DetailValue::Channels))
        add(BIT_DEPTH, file.bitDepth?.takeIf { it > 0 }?.let(DetailValue::Bits))
        val length = lengthOf(file, inputs.index)
        add(LENGTH, length)
        val indexLength = inputs.index?.durationMs?.takeIf { it > 0 }
        if (indexLength != null && length?.source != LengthSource.INDEX) add(INDEX_LENGTH, DetailValue.Length(indexLength, LengthSource.INDEX))
        add(SIZE, file.size?.takeIf { it >= 0 }?.let(DetailValue::Bytes))
        text(FILE_NAME, file.displayName)
        file.volume?.let { add(FOLDER, DetailValue.Folder(it, file.relativePath.orEmpty())) }
        text(MIME_TYPE, file.mimeType)
        text(TAG_ENCODING, file.tagEncoding)
        add(MODIFIED, file.mtimeSeconds?.takeIf { it > 0 }?.let { DetailValue.Time(it * 1_000) })
        if (!read) add(TAGS, DetailValue.Tags(file.tagState ?: TagState.NOT_READ))

        inputs.cover?.let { cover ->
            // The decided cover's source while it shows; the source row itself says where any other cover comes from.
            add(COVER_SOURCE, DetailValue.Cover(cover.source), if (cover.source == CoverSource.SAVED) decidedSource(DecidedField.COVER) else null)
            if (cover.width != null && cover.height != null && cover.width > 0 && cover.height > 0) {
                add(COVER_SIZE, DetailValue.Pixels(cover.width, cover.height, cover.bytes?.takeIf { it > 0 }))
            }
        }

        // The IDs of the merged values: the media index has none, so they come from a decision or the read tags (TI §7.1).
        fun ids(field: DetailField, kind: MusicBrainzKind, part: DecidedField) {
            val source = sourceOf(part) ?: return
            DecidedField.items(parts[part]?.value).mapNotNull { it.present() }.distinct().forEach { add(field, DetailValue.MusicBrainz(kind, it), source) }
        }
        ids(RECORDING_ID, MusicBrainzKind.RECORDING, DecidedField.RECORDING_MBID)
        ids(RELEASE_ID, MusicBrainzKind.RELEASE, DecidedField.RELEASE_MBID)
        ids(RELEASE_GROUP_ID, MusicBrainzKind.RELEASE_GROUP, DecidedField.RELEASE_GROUP_MBID)
        ids(TRACK_ID, MusicBrainzKind.TRACK, DecidedField.RELEASE_TRACK_MBID)
        ids(ARTIST_ID, MusicBrainzKind.ARTIST, DecidedField.ARTIST_IDS)
        ids(ALBUM_ARTIST_ID, MusicBrainzKind.ARTIST, DecidedField.ALBUM_ARTIST_IDS)
        // The AcoustID track ID of the decision that wins the recording ID; never the fingerprint cache's (§18.7).
        decided[DecidedField.RECORDING_MBID]?.decisionId
            ?.let { id -> records?.firstOrNull { it.values.id == id }?.acoustId }
            ?.let { add(ACOUSTID_ID, DetailValue.AcoustId(it), ValueSource(DecisionKind.LEVEL_IDENTIFICATION, ProviderId.ACOUSTID)) }

        // A decided field that no level fills shows as empty, so that its revert can be reached (§18.7).
        val shown = rows.mapNotNullTo(HashSet()) { it.field.protected }
        for ((field, row) in EMPTY_ROWS) {
            if (field in decidedFields && field !in shown) add(row.first, DetailValue.Empty, decidedSource(row.second))
        }

        val identification = records
            ?.filter { it.values.kind == DecisionKind.APPLY && !it.values.undone }
            ?.maxByOrNull { it.values.id }
            ?.let { IdentificationFacts(it.values.id, it.provider, it.confidence, it.reason, it.values.createdAt, it.fetchedAt) }
        val listDecision = records?.let { all -> DecisionResolver.missingInfoOf(all.map(DecisionRecord::values)) }?.let { state ->
            ListDecision(state.decisionId, skipped = records.firstOrNull { it.values.id == state.decisionId }?.values?.kind == DecisionKind.SKIP)
        }

        return TrackDetails(
            fileId = inputs.fileId,
            title = merged.title,
            artist = merged.artist,
            album = merged.album,
            rows = rows.sortedBy { it.field.ordinal },
            badge = DetailBadges.of(inputs.badgeState, records.orEmpty()),
            identification = identification,
            listDecision = listDecision,
            trace = inputs.trace,
            target = inputs.target,
        )
    }

    /**
     * The length the lists show, in the resolver's order (the stored exact length, the index's, TagLib's), with where it
     * comes from (docs/library.md §18.2).
     */
    private fun lengthOf(file: FileFacts, index: IndexValues?): DetailValue.Length? {
        file.exactDurationMs?.takeIf { it > 0 }?.let { ms ->
            val source = when (file.durationSource) {
                DurationSource.WALK -> LengthSource.WALK
                DurationSource.PENDING_FLAC_END -> LengthSource.HEADER_UNCHECKED
                else -> LengthSource.HEADER
            }
            return DetailValue.Length(ms, source)
        }
        index?.durationMs?.takeIf { it > 0 }?.let { return DetailValue.Length(it, LengthSource.INDEX) }
        return file.tagLengthMs?.takeIf { it > 0 }?.let { DetailValue.Length(it, LengthSource.ESTIMATE) }
    }

    /** Media index placeholders count as empty (TI §7.1). */
    private fun String?.present(): String? = this?.trim()?.takeUnless { it.isEmpty() || it == UNKNOWN }

    private const val UNKNOWN = "<unknown>"

    /** How a decided list shows, as tags write a list (TI §7.5). */
    private const val LIST_SEPARATOR = "; "

    /** The row of each field that may show as empty, with the part whose level it takes (§18.7); a cover always has a row. */
    private val EMPTY_ROWS: Map<ProtectedField, Pair<DetailField, DecidedField>> = mapOf(
        ProtectedField.TITLE to (TITLE to DecidedField.TITLE),
        ProtectedField.ARTIST to (ARTIST to DecidedField.ARTIST),
        ProtectedField.ALBUM to (ALBUM to DecidedField.ALBUM),
        ProtectedField.ALBUM_ARTIST to (ALBUM_ARTIST to DecidedField.ALBUM_ARTIST),
        ProtectedField.DATE to (DATE to DecidedField.DATE),
        ProtectedField.ORIGINAL_DATE to (ORIGINAL_DATE to DecidedField.ORIGINAL_DATE),
        ProtectedField.TRACK_NUMBER to (TRACK to DecidedField.TRACK_NUMBER),
        ProtectedField.DISC_NUMBER to (DISC to DecidedField.DISC_NUMBER),
        ProtectedField.DISC_SUBTITLE to (DISC_SUBTITLE to DecidedField.DISC_SUBTITLE),
        ProtectedField.COMPILATION to (COMPILATION to DecidedField.COMPILATION),
        ProtectedField.LABEL to (LABEL to DecidedField.LABEL),
        ProtectedField.CATALOG_NUMBER to (CATALOG_NUMBER to DecidedField.CATALOG_NUMBER),
        ProtectedField.BARCODE to (BARCODE to DecidedField.BARCODE),
        ProtectedField.ISRC to (ISRC to DecidedField.ISRC),
        ProtectedField.MUSICBRAINZ_IDS to (RECORDING_ID to DecidedField.RECORDING_MBID),
    )
}

/** The metadata badge of track details and Now Playing (TI §6, docs/library.md §18.7). */
object DetailBadges {

    /**
     * The badge of a display row's [badgeState] (null without a display row: none), with the provider and confidence of
     * the newest identification among [decisions] whose values win while identified.
     */
    fun of(badgeState: Int?, decisions: List<DecisionRecord>): Badge? {
        val state = BadgeState.ofCode(badgeState) ?: return null
        if (state != BadgeState.IDENTIFIED) return Badge(state)
        val winner = DecisionResolver.resolve(decisions.map(DecisionRecord::values)).values
            .filter { it.level == DecisionKind.LEVEL_IDENTIFICATION }
            .maxOfOrNull { it.decisionId }
        val record = decisions.firstOrNull { it.values.id == winner }
        return Badge(state, record?.provider, record?.confidence)
    }
}
