package io.github.lorddetson.piplayer.identification.corpus

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.LiveNetwork
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.TextCandidateScorer
import io.github.lorddetson.piplayer.identification.text.Brackets
import io.github.lorddetson.piplayer.identification.text.Similarity
import io.github.lorddetson.piplayer.identification.text.VersionSuffix
import io.github.lorddetson.piplayer.spike.RecordCandidate
import io.github.lorddetson.piplayer.spike.SpikeReports
import io.github.lorddetson.piplayer.spike.SpikeServices
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M3 own-files corpus check (PIM-60, TI §11.4): the whole pipeline (fingerprint, AcoustID, MusicBrainz, Cover Art
 * Archive) with the services of the debug spike, on the owner's files, against the tags those files carry as the truth.
 *
 * Off unless asked for, because it sends the owner's files to the real services and takes a long time
 * (one request per ~1.1 s of the rate limiter):
 * `adb shell am instrument -w -e liveNetwork true -e corpusDir Music/corpus -e class io.github.lorddetson.piplayer.identification.corpus.OwnFilesCorpusRun io.github.lorddetson.piplayer.test/io.github.lorddetson.piplayer.PiPlayerTestRunner`.
 * Optional `-e corpusSample N` takes every N-th row. Results go to the app's files under `corpus-out/`
 * (`adb exec-out run-as io.github.lorddetson.piplayer cat files/corpus-out/summary.md`); `results.tsv` is appended, so an
 * interrupted run continues where it stopped.
 */
@RunWith(AndroidJUnit4::class)
class OwnFilesCorpusRun {

    @Test
    fun `run the pipeline over the own-files corpus`() = runBlocking {
        assumeTrue("Calls the real services; run with -e ${LiveNetwork.ARGUMENT} true", LiveNetwork.allowed(arguments))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = arguments.getString("corpusDir") ?: "Music/corpus"
        val sample = arguments.getString("corpusSample")?.toInt() ?: 1

        val rows = query(context, prefix)
        check(rows.isNotEmpty()) {
            "No media in '$prefix': the device has not scanned the folder, or the path in -e corpusDir is wrong"
        }

        val out = File(context.filesDir, "corpus-out").apply { mkdirs() }
        val results = File(out, "results.tsv")
        val done = if (results.exists()) results.readLines().drop(1).map { it.substringBefore('\t') }.toSet() else emptySet()
        if (!results.exists()) results.writeText(HEADER.joinToString("\t") + "\n")
        val progress = File(out, "progress.log")

        val pipeline = SpikeServices.pipeline(context)
        for ((index, row) in rows.withIndex()) {
            if (index % sample != 0) continue
            val rel = row.relPath.orEmpty()
            val key = "${index + 1}\t$rel"
            if (done.contains(key)) continue
            val truth = truth(row.uri)
            val line = try {
                val result = SpikeServices.run(row.uri.toString(), pipeline)
                judged(index + 1, rel, truth, result)
            } catch (e: Exception) {
                listOf(
                    (index + 1).toString(), rel, "", "Error(${e.javaClass.simpleName})", "", "", "", "", "",
                    truth?.title.orEmpty(), truth?.artist.orEmpty(), truth?.durationMs?.toString().orEmpty(),
                    "", "", "true",
                )
            }
            results.appendText(line.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n")
            progress.appendText("${Instant.now()} ${index + 1}/$rel -> ${line.getOrNull(2)}/${line.getOrNull(3)}\n")
            Log.i(TAG, "corpus ${index + 1} $rel -> ${line.getOrNull(2)} ${line.getOrNull(3)}")
        }
        writeSummary(results, File(out, "summary.md"))
    }

    /** The file's tags, read from the media store row's URI; null when they cannot be read. */
    private fun truth(uri: Uri): Truth? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(InstrumentationRegistry.getInstrumentation().targetContext, uri)
            Truth(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun judged(number: Int, rel: String, truth: Truth?, result: PipelineResult): List<String> {
        val record = SpikeReports.record(result)
        val top = record.candidates.firstOrNull()
        val found = result.outcome as? IdentificationOutcome.Found
        val strict = found != null && top != null && truth != null && wrong(truth, top, lenient = false)
        val lenient = found != null && top != null && truth != null && wrong(truth, top, lenient = true)
        return listOf(
            number.toString(), rel, record.band.orEmpty(), record.outcome,
            top?.score?.let { "%.3f".format(it) }.orEmpty(),
            top?.title.orEmpty(), top?.artist.orEmpty(), top?.lengthMs?.toString().orEmpty(),
            top?.recordingId.orEmpty(),
            truth?.title.orEmpty(), truth?.artist.orEmpty(), truth?.durationMs?.toString().orEmpty(),
            strict.toString(), lenient.toString(), "",
        )
    }

    /**
     * Whether the top candidate is the file the tags name: title and artist at least 0.9 similar and the lengths within
     * 5 s (the owner's criterion, TI §11.4). Lenient compares the names outside a version description, so "Outside
     * (Radio Edit)" matches "Outside"; strict compares the whole titles. A field the file cannot provide is not checked.
     */
    private fun wrong(truth: Truth, top: RecordCandidate, lenient: Boolean): Boolean {
        val titleOk = truth.title == null || Similarity.text(
            if (lenient) name(truth.title) else truth.title,
            if (lenient) name(top.title) else top.title,
        ) >= MATCH
        val artist = truth.artist?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("<unknown>", true) }
        val artistOk = artist == null || runCatching {
            val names = top.artist.split(ARTIST_SEPARATORS).filter { it.isNotBlank() }
            TextCandidateScorer.score(
                TrackHint(title = null, artist = artist, durationMs = null),
                TextCandidate(top.recordingId, top.title, top.artist, names, top.lengthMs),
            ).artist >= MATCH
        }.getOrDefault(false)
        val lengthOk = truth.durationMs == null || top.lengthMs == null ||
            abs(truth.durationMs - top.lengthMs) <= MAX_DURATION_DIFFERENCE_MS
        return titleOk && artistOk && lengthOk
    }

    private fun name(title: String): String = Brackets.split(VersionSuffix.bracket(title)).first

    private fun query(context: Context, prefix: String): List<Row> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        context.contentResolver.query(collection, projection, selection, arrayOf("$prefix/%"), null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        Row(
                            relPath = cursor.getString(pathColumn),
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                        ),
                    )
                }
            }
        }
        return emptyList()
    }

    /** Recomputes the shares from the stored rows, so an interrupted run's report stays comparable. */
    private fun writeSummary(results: File, summary: File) {
        val lines = results.readLines()
        val columns = lines.first().split('\t')
        val rows = lines.drop(1).map { line -> columns.zip(line.split('\t')).toMap() }
        fun share(n: Int, of: Int = rows.size) = if (of == 0) "0" else "$n (%.1f %%)".format(100.0 * n / of)
        val high = rows.filter { it["band"] == "HIGH" }
        val medium = rows.filter { it["band"] == "MEDIUM" }
        val notFound = rows.filter { it["outcome"] != "Found" || it["band"] == "NO_MATCH" }
        summary.writeText(
            buildString {
                appendLine("# Own-files corpus run (M3 step 23)")
                appendLine()
                appendLine("- Files: ${rows.size}")
                appendLine("- High band: ${share(high.size)}")
                appendLine("- Medium band: ${share(medium.size)}")
                appendLine("- Not found: ${share(notFound.size)}")
                appendLine("- Wrong in the high band, strict: ${share(high.count { it["strict"] != "true" }, high.size)}")
                appendLine("- Wrong in the high band, lenient: ${share(high.count { it["lenient"] != "true" }, high.size)}")
                appendLine("- Top candidate correct, strict: ${share(rows.count { it["strict"] == "true" })}")
                appendLine("- Top candidate correct, lenient: ${share(rows.count { it["lenient"] == "true" })}")
            },
        )
    }

    private data class Row(val relPath: String?, val uri: Uri)

    private data class Truth(val title: String?, val artist: String?, val durationMs: Long?)

    private companion object {
        const val TAG = "OwnFilesCorpusRun"
        const val MATCH = 0.9
        const val MAX_DURATION_DIFFERENCE_MS = 5_000L
        val ARTIST_SEPARATORS =
            Regex("""\s*(?:,|&|;|\bfeaturing\b|\bfeat\b\.?|\bft\b\.?)\s*""", RegexOption.IGNORE_CASE)
        val HEADER = listOf(
            "number", "rel_path", "band", "outcome", "top_score", "top_title", "top_artist", "top_length_ms",
            "top_recording_id", "truth_title", "truth_artist", "truth_duration_ms", "strict", "lenient", "error",
        )
        val arguments get() = InstrumentationRegistry.getArguments()
    }
}