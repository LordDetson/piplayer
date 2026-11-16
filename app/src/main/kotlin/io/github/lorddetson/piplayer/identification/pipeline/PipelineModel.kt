package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.io.IOException
import kotlin.time.Duration

/** The file a run is for, before stage 0 has read it (TI §3, §3.1). */
sealed interface FileRef {

    /** A file of the library, by its file ID (docs/library.md §8.4). */
    data class Library(val fileId: Long) : FileRef

    /** A file opened from another app, or any other file the app can open by its [uri]. */
    data class Opened(val uri: String) : FileRef
}

/** The exact duration as stage 0 finds it (TI §3.1). */
sealed interface StageDuration {

    /** Known: stored by the tag pass, or settled by the file's header. */
    data class Exact(val ms: Long) : StageDuration

    /** Not settled yet: [plan] says what the walk of TI §3.1 has to do before a lookup. */
    data class Pending(val plan: DurationPlan) : StageDuration

    /** No header says, and no extractor could read the file. */
    data object Unknown : StageDuration
}

/**
 * What stage 0 knows about the file (TI §3.1): its [target] with the content key, its [fileName] for the file-name
 * guess, its [tags] as decoded with the legacy-encoding setting (null when none could be read), its [duration], its
 * [format] for the fingerprint cache, its [folder] for the folder hint of the release ranking (`volume/relative path`,
 * null for a file outside the library) and the [uri] the decoder opens it by.
 */
data class LocalFile(
    val target: IdentificationTarget,
    val fileName: String,
    val tags: TrackTags?,
    val duration: StageDuration,
    val format: String?,
    val folder: String?,
    val uri: String,
)

/** Stage 0 (TI §3.1): the local read, which the background tag pass normally did already. */
interface LocalStage {

    /** What the library or a read of the file gives; null when the file cannot be read and so has no content key. */
    suspend fun read(file: FileRef): LocalFile?

    /** Carries out [plan] for [file] in `:tagger` (TI §3.1); the length, or null when no extractor can read the file. */
    suspend fun walk(file: LocalFile, plan: DurationPlan): Long?
}

/** A fingerprint cached for a content key (TI §10.1): its raw items. */
class CachedFingerprint(val raw: IntArray)

/** A fingerprint just computed: its raw items and the compressed string of the session that made them (TI §3.2). */
class ComputedFingerprint(val raw: IntArray, val encoded: String)

/** The fingerprint cache of the library database (TI §3.2, §10.1), as stage 3 uses it. */
interface FingerprintCache {

    suspend fun cached(contentKey: String): CachedFingerprint?

    /**
     * Decodes [file] on the user's lane (TI §3.2 "Threads") and caches the fingerprint under its content key. Throws an
     * [IOException] when the file cannot be opened or decoded, a
     * [io.github.lorddetson.piplayer.identification.fingerprint.DecodingException] among them.
     */
    suspend fun compute(file: LocalFile): ComputedFingerprint

    /** Keeps [trackId], the AcoustID track ID of the best result, with the fingerprint cached for [contentKey]. */
    suspend fun keepAcoustIdTrack(contentKey: String, trackId: String)
}

/** Turns stored raw items into the compressed string AcoustID takes (TI §3.2; the native library in the app). */
fun interface FingerprintEncoder {
    fun encode(raw: IntArray): String
}

/** The stages of one run (TI §3), in their order. */
enum class PipelineStage { LOCAL_READ, KNOWN_IDS, ISRC, FINGERPRINT, TEXT_SEARCH, RELEASE_CHOICE }

/** What a run reports while it goes (TI §3 "One run"). */
sealed interface PipelineProgress {

    /** [stage] starts. */
    data class Started(val stage: PipelineStage) : PipelineProgress

    /** Stage 0 walks the file's frames for its length. */
    data object WalkingLength : PipelineProgress

    /** Stage 3 decodes the file, because no fingerprint is cached for it. */
    data object Decoding : PipelineProgress

    /** Release choice has fetched [pages] release pages of [recordingId] so far. */
    data class ReleasePages(val recordingId: String, val pages: Int) : PipelineProgress
}

/** Why a stage did not run. */
enum class SkipReason {
    /** The file has no value the stage needs (no MBID, ISRC or title). */
    NO_VALUE,

    /** A stage before it reached the high band (the stop rule). */
    STOPPED_EARLY,

    /** The build has no AcoustID application key (TI §9). */
    NO_KEY,

    /** The provider stopped for the process (TI §8). */
    PROVIDER_STOPPED,

    /** The exact duration is unknown, so AcoustID cannot be asked (TI §3.2). */
    NO_DURATION,

    /** The file cannot be decoded for a fingerprint (TI §3.2). */
    UNDECODABLE,

    /** The fingerprint holds no items. */
    EMPTY_FINGERPRINT,
}

/** How one stage of a run ended. */
sealed interface StageResult {

    /** The stage ran and found [recordings] recordings. */
    data class Ran(val recordings: Int) : StageResult

    data class Skipped(val reason: SkipReason) : StageResult

    /** The stage failed with [error]; the next stage ran. */
    data class Failed(val error: IOException) : StageResult
}

/** One stage of a run, for the trace (TI §10.1). */
data class StageReport(val stage: PipelineStage, val result: StageResult, val elapsed: Duration)

/** Where stage 3 took its fingerprint from. */
enum class FingerprintSource { CACHED, COMPUTED }

/**
 * One run (TI §3 "One run"), kept in memory until it is stored (a later step of M3).
 *
 * @property outcome how the run ended.
 * @property file what stage 0 read; null when the run ended before it or the file could not be read.
 * @property hint the hint of the text search and the scoring (TI §3.3).
 * @property durationMs the exact duration the lookups used; null when unknown.
 * @property lengthWalked the length was walked in this run.
 * @property stages every stage that was reached, in order.
 * @property fingerprint where stage 3 took its fingerprint from; null when it took none.
 * @property retryLater an empty AcoustID result that no text candidate of at least 0.50 outweighed (TI §3.2).
 */
data class PipelineResult(
    val outcome: IdentificationOutcome,
    val file: LocalFile? = null,
    val hint: TrackHint? = null,
    val durationMs: Long? = null,
    val lengthWalked: Boolean = false,
    val stages: List<StageReport> = emptyList(),
    val fingerprint: FingerprintSource? = null,
    val retryLater: Boolean = false,
)
