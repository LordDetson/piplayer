package io.github.lorddetson.piplayer.identification.corpus

import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.QueryOptions
import io.github.lorddetson.piplayer.identification.text.RecordingSearch
import io.github.lorddetson.piplayer.identification.text.ScoredCandidate
import io.github.lorddetson.piplayer.identification.text.TextSearch
import io.github.lorddetson.piplayer.net.HttpClients
import io.github.lorddetson.piplayer.net.RateLimiter
import io.github.lorddetson.piplayer.net.RetryPolicy
import io.github.lorddetson.piplayer.net.UserAgent
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The M0 corpus check of the stage 4 query builder (TI §11.4): runs the text search against MusicBrainz for every
 * track of the owner's liked-tracks list and records whether the top candidate is the right recording.
 *
 * Off unless asked for, because it sends the private list to MusicBrainz and takes about an hour at 1 request per
 * second: `./gradlew :app:testDebugUnitTest --tests '*TextSearchCorpusRun' -Pcorpus.file=<list> -Pcorpus.out=<dir>`.
 * Optional: `-Pcorpus.sample=5` takes every fifth track; `-Pcorpus.bracketSuffix=false` and
 * `-Pcorpus.requireTitleWords=false` switch off the tuning options that are on by default ([QueryOptions]). Results
 * are appended to `results.tsv`, so an interrupted run continues where it stopped, and a finished run only rewrites
 * `summary.md`.
 */
@Tag("corpus")
@EnabledIfSystemProperty(named = "corpus.file", matches = ".+")
class TextSearchCorpusRun {

    @Test
    fun `run the text search over the liked-tracks list`() = runBlocking {
        val sample = System.getProperty("corpus.sample")?.toInt() ?: 1
        val defaults = QueryOptions()
        val bracketSuffix = System.getProperty("corpus.bracketSuffix")?.toBooleanStrict() ?: defaults.bracketVersion
        val requireTitleWords =
            System.getProperty("corpus.requireTitleWords")?.toBooleanStrict() ?: defaults.requireTitleWords
        val options = QueryOptions(requireTitleWords = requireTitleWords, bracketVersion = bracketSuffix)
        val list = File(requireNotNull(System.getProperty("corpus.file")))
        val tracks = LikedTracksList.parse(list.readLines(Charsets.UTF_8).asSequence()).filter { it.number % sample == 0 }
        val out = File(requireNotNull(System.getProperty("corpus.out")) { "Set -Pcorpus.out" }).apply { mkdirs() }
        // Rows of one output directory must all come from the same settings, or the summary would mix them.
        val settings = "sample=$sample, bracketSuffix=$bracketSuffix, QueryOptions(requireTitleWords=$requireTitleWords)"
        val settingsFile = File(out, "settings.txt")
        if (settingsFile.exists()) {
            check(settingsFile.readText().trim() == settings) {
                "${out.path} holds results of '${settingsFile.readText().trim()}', not '$settings'; use another directory"
            }
        } else {
            settingsFile.writeText(settings + "\n")
        }
        val results = File(out, "results.tsv")
        val done = if (results.exists()) results.readLines().drop(1).map { it.substringBefore('\t').toInt() }.toSet() else emptySet()
        if (!results.exists()) results.writeText(HEADER.joinToString("\t") + "\n")
        val progress = File(out, "progress.log")
        progress.appendText("${Instant.now()} start $settings\n")

        val client = MusicBrainzClient(
            http = HttpClients.create(UserAgent.of(BuildConfig.VERSION_NAME)),
            limiter = RateLimiter(1_100.milliseconds),
            retryPolicy = RetryPolicy(maxRetries = 5),
        )
        var requests = 0
        val textSearch = TextSearch(
            { query ->
                requests++
                client.searchRecordings(query.query, query.limit).recordings.map { it.toCandidate() }
            },
            options,
        )

        for (track in tracks) {
            if (track.number in done) continue
            requests = 0
            val hint = TrackHint(title = track.title, artist = track.artist, durationMs = track.durationMs)
            val row = try {
                val result = textSearch.search(hint)
                val stop = result.attempts.lastOrNull()?.takeIf { attempt ->
                    (attempt.candidates.firstOrNull()?.score?.total ?: 0.0) >= TextSearch.STOP_SCORE
                }?.query?.variant
                row(track, requests, stop?.name.orEmpty(), result.candidates.firstOrNull(), "")
            } catch (e: MusicBrainzException.UserAgentRejected) {
                throw e
            } catch (e: MusicBrainzException) {
                row(track, requests, "", null, e.toString())
            }
            results.appendText(row.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n")
            progress.appendText("${Instant.now()} ${track.number} (${tracks.size} in run) requests=$requests\n")
        }
        writeSummary(results, File(out, "summary.md"), header = settings)
    }

    private fun row(track: LikedTrack, requests: Int, stopVariant: String, top: ScoredCandidate?, error: String) =
        listOf(
            track.number.toString(),
            track.title,
            track.artist,
            track.durationMs?.toString().orEmpty(),
            requests.toString(),
            stopVariant,
            top?.score?.total?.let { "%.3f".format(it) }.orEmpty(),
            top?.candidate?.title.orEmpty(),
            top?.candidate?.artistCredit.orEmpty(),
            top?.candidate?.lengthMs?.toString().orEmpty(),
            top?.candidate?.recordingId.orEmpty(),
            error,
        )

    /** Recomputes both criteria from the stored rows, so older result files are judged the same way. */
    private fun writeSummary(results: File, summary: File, header: String) {
        val lines = results.readLines()
        val columns = lines.first().split('\t')
        val rows = lines.drop(1).map { line -> columns.zip(line.split('\t')).toMap() }
        val judged = rows.map { row ->
            val track = LikedTrack(row.getValue("number").toInt(), row.getValue("title"), row.getValue("artist"),
                row.getValue("duration_ms").toLongOrNull())
            val hasTop = row.getValue("top_score").isNotEmpty()
            val title = row.getValue("top_title")
            val credit = row.getValue("top_credit")
            val length = row.getValue("top_length_ms").toLongOrNull()
            Judged(
                score = row.getValue("top_score").toDoubleOrNull(),
                stopVariant = row.getValue("stop_variant"),
                requests = row.getValue("requests").toIntOrNull() ?: 0,
                error = row.getValue("error").isNotEmpty(),
                strict = hasTop && CorpusCriteria.strict(track, title, credit, length),
                lenient = hasTop && CorpusCriteria.lenient(track, title, credit, length),
            )
        }
        val total = judged.size
        fun share(n: Int, of: Int = total) = if (of == 0) "0" else "$n (%.1f %%)".format(100.0 * n / of)
        summary.writeText(
            buildString {
                appendLine("# Text search corpus run")
                appendLine()
                appendLine("Settings: $header")
                appendLine()
                appendLine("- Tracks: $total")
                appendLine("- Top candidate correct, strict: ${share(judged.count { it.strict })}")
                appendLine("- Top candidate correct, lenient: ${share(judged.count { it.lenient })}")
                appendLine("- Stopped with a candidate of at least 0.50: ${share(judged.count { it.stopVariant.isNotEmpty() })}")
                appendLine("- No candidate at all: ${share(judged.count { it.score == null && !it.error })}")
                appendLine("- Errors: ${judged.count { it.error }}")
                appendLine("- Requests: ${judged.sumOf { it.requests }} (%.2f per track)".format(
                    if (total == 0) 0.0 else judged.sumOf { it.requests }.toDouble() / total))
                appendLine("- Stop variant: " + judged.filter { it.stopVariant.isNotEmpty() }.groupingBy { it.stopVariant }
                    .eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
                appendLine()
                appendLine("| Top score at least | Tracks | Correct, strict | Correct, lenient |")
                appendLine("|---|---|---|---|")
                for (threshold in listOf(0.5, 0.6, 0.7, 0.75, 0.8, 0.85)) {
                    val above = judged.filter { (it.score ?: 0.0) >= threshold - 1e-9 }
                    appendLine("| %.2f | %s | %s | %s |".format(threshold, share(above.size),
                        share(above.count { it.strict }, above.size), share(above.count { it.lenient }, above.size)))
                }
            },
        )
    }

    private class Judged(
        val score: Double?,
        val stopVariant: String,
        val requests: Int,
        val error: Boolean,
        val strict: Boolean,
        val lenient: Boolean,
    )

    private companion object {
        val HEADER = listOf(
            "number", "title", "artist", "duration_ms", "requests", "stop_variant", "top_score", "top_title",
            "top_credit", "top_length_ms", "top_recording_id", "error",
        )
    }
}
