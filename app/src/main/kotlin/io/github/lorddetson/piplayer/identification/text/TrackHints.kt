package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.score.TextCandidateScorer
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TrackTags

/** Builds the stage 4 hint from stage 0 data (TI §3.3). */
object TrackHints {

    /**
     * Title, artist and track number come from [tags] (already decoded with the legacy-encoding setting, as the tag pass
     * stores them) and, field by field, from the file-name guess where the tags leave a field empty; the album comes
     * from the tags only; the duration is the exact duration of stage 0, and only a positive one counts. A tag value
     * without a letter or digit ("-", "?") is empty. [tags] is null for a file the tag pass has not read.
     *
     * [FileNameParser] is the same parser that gives precedence level 5 (library/values/MergedValues.kt), so this
     * reads it and never tunes it.
     */
    fun assemble(tags: TrackTags?, fileName: String, exactDurationMs: Long?): TrackHint {
        val title = tags?.title.present()
        val artist = tags?.artist.present()
        val track = tags?.trackNumber
        // Parsed only for a field the tags leave empty.
        val parsed = if (title == null || artist == null || track == null) FileNameParser.parse(fileName) else null
        return TrackHint(
            title = title ?: parsed?.title.present(),
            artist = artist ?: parsed?.artist.present(),
            durationMs = exactDurationMs?.takeIf { it > 0 },
            album = tags?.album.present(),
            trackNumber = track ?: parsed?.trackNumber,
        )
    }

    /**
     * The same for raw tags as the reader returns them, decoded first with the legacy-encoding setting: [manual] is the
     * user's code page, null for the automatic choice that [cyrillicLanguage] allows (TI §3.1).
     */
    fun assemble(
        properties: Map<String, List<String>>,
        cyrillicLanguage: Boolean,
        manual: LegacyEncoding?,
        fileName: String,
        exactDurationMs: Long?,
    ): TrackHint {
        val decoded = LegacyText.decode(properties, cyrillicLanguage, manual)
        return assemble(TrackTags.from(decoded.properties), fileName, exactDurationMs)
    }

    /**
     * The hint as the text search scores with it: with [QueryOptions.bracketVersion], a version written without
     * brackets ("Song Radio Edit") is put into brackets, so every scorer compares the same title (TI §3.3).
     */
    fun forScoring(hint: TrackHint, options: QueryOptions = QueryOptions()): TrackHint =
        if (options.bracketVersion) hint.copy(title = hint.title?.let(VersionSuffix::bracket)) else hint

    private fun String?.present(): String? = this?.takeIf(TextCandidateScorer::present)
}
