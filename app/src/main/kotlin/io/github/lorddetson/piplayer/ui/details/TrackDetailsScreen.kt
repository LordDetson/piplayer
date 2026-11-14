package io.github.lorddetson.piplayer.ui.details

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.covers.CoverRequest
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.details.DetailRow
import io.github.lorddetson.piplayer.library.details.DetailSection
import io.github.lorddetson.piplayer.library.details.DetailValue
import io.github.lorddetson.piplayer.library.details.LengthSource
import io.github.lorddetson.piplayer.library.details.TagState
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.library.details.TrackDetailsSources
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.ui.covers.LibraryCover
import io.github.lorddetson.piplayer.ui.player.CoverImage
import io.github.lorddetson.piplayer.ui.player.kilohertz
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The size of the header's cover (docs/library.md §18.1). */
private const val HEADER_COVER_DP = 96

/**
 * The details of the song [fileId] of the library, or of the file opened from another app at [uri] (docs/library.md §18),
 * read off the main thread. A song of the library is read again whenever the library, a batch of the tag pass, the
 * identities, the mirrored favorites and plays or the own covers change, so that the screen follows it; an opened file is
 * read once for the life of the screen, since each read costs a `:tagger` call (docs/library.md §18.5).
 */
class TrackDetailsViewModel(application: Application, private val fileId: Long?, private val uri: String?) : AndroidViewModel(application) {

    private val app = application

    val state: StateFlow<DetailsState> = DetailsStates.of(
        viewModelScope,
        changes(),
        once = fileId == null,
        onError = { Log.w(TAG, "The details could not be read", it) },
    ) { withContext(Dispatchers.IO) { read() } }

    private fun changes(): Flow<Unit> {
        if (fileId == null || !Library.mayRead(app)) return flow { emit(Unit) }
        return combine(
            SongList.changes(app),
            SongList.batches,
            IdentityAttacher.changes,
            Library.mirrorVersion(app),
            Covers.own(app).version,
        ) { _, _, _, _, _ -> }
    }

    private suspend fun read(): DetailsState.Found? = when {
        fileId != null -> if (Library.mayRead(app)) TrackDetailsSources.library(app).read(fileId)?.let { DetailsState.Found(it) } else null
        uri != null -> {
            val opened = TrackDetailsSources.opened(app)
            val details = opened.read(uri)
            // The header shows the opened file's embedded picture; a song of the library has the loader's cover.
            val embedded = details.value(DetailField.COVER_SOURCE) == DetailValue.Cover(CoverSource.EMBEDDED)
            val px = (HEADER_COVER_DP * app.resources.displayMetrics.density).roundToInt()
            DetailsState.Found(details, if (embedded) opened.picture(uri, px)?.asImageBitmap() else null)
        }
        else -> null
    }

    private companion object {
        const val TAG = "TrackDetails"
    }
}

/**
 * The details screen (docs/library.md §18.1): a header with the cover, the title and the artist, then the sections with
 * their rows. A long press copies a row's value; a MusicBrainz row opens its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsScreen(fileId: Long?, uri: String?, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = viewModel(key = "details ${fileId ?: uri}") { TrackDetailsViewModel(app, fileId, uri) }
    val state by model.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.details), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (val shown = state) {
            DetailsState.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            DetailsState.Gone -> Text(
                stringResource(R.string.details_not_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(16.dp),
            )
            DetailsState.Failed -> Text(
                stringResource(R.string.details_failed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = modifier.padding(16.dp),
            )
            is DetailsState.Found -> DetailsList(shown.details, shown.picture, snackbar, modifier)
        }
    }
}

@Composable
private fun DetailsList(details: TrackDetails, picture: ImageBitmap?, snackbar: SnackbarHostState, modifier: Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val say = { id: Int -> scope.launch { snackbar.showSnackbar(resources.getString(id)) }; Unit }
    val copy = { label: String, text: String ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        // From Android 13 the system confirms a copy itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) say(R.string.details_copied)
    }
    val open = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            say(R.string.details_no_app)
        }
    }
    val sections = remember(details) { details.sections }
    LazyColumn(modifier.testTag(DetailsTags.LIST)) {
        item(key = "header") { Header(details, picture) }
        for ((section, rows) in sections) {
            item(key = section) { SectionHeading(section) }
            items(rows) { row -> RowItem(row, copy, open) }
        }
    }
}

/** The header (docs/library.md §18.1): the loader's cover of a song of the library, the embedded [picture] of an opened file. */
@Composable
private fun Header(details: TrackDetails, picture: ImageBitmap?) {
    Row(
        Modifier.testTag(DetailsTags.HEADER).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(8.dp)
        val id = details.fileId
        if (id != null) {
            LibraryCover(CoverRequest.Track(id), details.album, size = HEADER_COVER_DP.dp, shape = shape)
        } else {
            CoverImage(picture, Modifier.size(HEADER_COVER_DP.dp).clip(shape), placeholderName = details.album)
        }
        Column {
            Text(details.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            details.artist?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            }
        }
    }
}

@Composable
private fun SectionHeading(section: DetailSection) {
    Text(
        text = stringResource(sectionLabel(section)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A row: its label over its value, read by TalkBack as one; a long press copies, a link opens on a tap. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowItem(row: DetailRow, copy: (String, String) -> Unit, open: (String) -> Unit) {
    val label = stringResource(fieldLabel(row.field))
    val text = valueText(row)
    val copied = (row.value as? DetailValue.MusicBrainz)?.id ?: text
    val url = (row.value as? DetailValue.MusicBrainz)?.url
    val copyLabel = stringResource(R.string.details_copy)
    val base = Modifier.testTag(DetailsTags.row(row.field))
    val gestures = if (url != null) {
        base.combinedClickable(
            onClickLabel = stringResource(R.string.details_open_musicbrainz),
            role = Role.Button,
            onLongClickLabel = copyLabel,
            onLongClick = { copy(label, copied) },
            onClick = { open(url) },
        )
    } else {
        base
            .pointerInput(copied) { detectTapGestures(onLongPress = { copy(label, copied) }) }
            .semantics(mergeDescendants = true) {
                onLongClick(copyLabel) {
                    copy(label, copied)
                    true
                }
            }
    }
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(text, color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        modifier = gestures,
    )
}

/** A row's value as shown (docs/library.md §18.2). */
@Composable
private fun valueText(row: DetailRow): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    return when (val value = row.value) {
        is DetailValue.Text -> value.text
        is DetailValue.Texts -> value.texts.joinToString("; ")
        is DetailValue.OfTotal -> value.total?.let { stringResource(R.string.details_of_total, value.number, it) } ?: value.number.toString()
        is DetailValue.Flag -> stringResource(if (value.on) R.string.details_yes else R.string.details_no)
        is DetailValue.Count -> NumberFormat.getIntegerInstance(locale).format(value.count)
        is DetailValue.Time -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(value.epochMs))
        is DetailValue.Length -> {
            val length = DetailFormat.length(value.ms)
            // The index's own row is just its length; the length shown says where it comes from.
            if (row.field == DetailField.INDEX_LENGTH) length else stringResource(lengthLabel(value.source), length)
        }
        is DetailValue.Bytes -> bytes(context, value.bytes, locale)
        is DetailValue.Kbps -> stringResource(R.string.format_kbps, value.kbps)
        is DetailValue.Hertz -> stringResource(R.string.format_khz, kilohertz(value.hz, locale))
        is DetailValue.Channels -> when (value.count) {
            1 -> stringResource(R.string.format_mono)
            2 -> stringResource(R.string.details_stereo)
            else -> resources.getQuantityString(R.plurals.format_channels, value.count, value.count)
        }
        is DetailValue.Bits -> stringResource(R.string.format_bits, value.bits)
        is DetailValue.Gain -> {
            val db = decimals(locale, 2).format(value.db)
            value.peak?.let { stringResource(R.string.details_gain_peak, db, decimals(locale, 6).format(it)) } ?: stringResource(R.string.details_gain, db)
        }
        is DetailValue.Folder -> {
            val volume = if (value.volume == FolderLevels.PRIMARY) stringResource(R.string.internal_storage) else value.volume
            "$volume/${value.relativePath}"
        }
        is DetailValue.Tags -> stringResource(tagStateLabel(value.state))
        is DetailValue.Cover -> stringResource(coverLabel(value.source))
        is DetailValue.Pixels -> value.bytes?.let { stringResource(R.string.details_pixels_bytes, value.width, value.height, Formatter.formatFileSize(context, it)) }
            ?: stringResource(R.string.details_pixels, value.width, value.height)
        is DetailValue.MusicBrainz -> value.id
    }
}

/** A size in the unit that fits, with its exact number of bytes. */
private fun bytes(context: Context, bytes: Long, locale: Locale): String =
    context.getString(R.string.details_bytes, Formatter.formatFileSize(context, bytes), NumberFormat.getIntegerInstance(locale).format(bytes))

private fun decimals(locale: Locale, digits: Int) = NumberFormat.getNumberInstance(locale).apply {
    maximumFractionDigits = digits
    minimumFractionDigits = minOf(digits, 2)
}

private fun lengthLabel(source: LengthSource?) = when (source) {
    LengthSource.HEADER, null -> R.string.details_length_header
    LengthSource.WALK -> R.string.details_length_walk
    LengthSource.HEADER_UNCHECKED -> R.string.details_length_header_unchecked
    LengthSource.INDEX -> R.string.details_length_index
    LengthSource.ESTIMATE -> R.string.details_length_estimate
}

private fun tagStateLabel(state: TagState) = when (state) {
    TagState.NOT_READ -> R.string.details_tags_not_read
    TagState.UNSUPPORTED -> R.string.details_tags_unsupported
    TagState.FAILED -> R.string.details_tags_failed
    TagState.CANNOT_READ -> R.string.details_tags_cannot_read
}

private fun coverLabel(source: CoverSource) = when (source) {
    CoverSource.SONG_OWN -> R.string.details_cover_song_own
    CoverSource.ALBUM_OWN -> R.string.details_cover_album_own
    CoverSource.EMBEDDED -> R.string.details_cover_embedded
    CoverSource.THUMBNAIL -> R.string.details_cover_thumbnail
    CoverSource.NONE -> R.string.details_cover_none
}

private fun sectionLabel(section: DetailSection) = when (section) {
    DetailSection.SONG -> R.string.details_section_song
    DetailSection.PLAYS -> R.string.details_section_plays
    DetailSection.FILE -> R.string.details_section_file
    DetailSection.COVER -> R.string.details_section_cover
    DetailSection.IDS -> R.string.details_section_ids
}

private fun fieldLabel(field: DetailField) = when (field) {
    DetailField.TITLE -> R.string.details_field_title
    DetailField.ARTIST -> R.string.details_field_artist
    DetailField.ALBUM -> R.string.details_field_album
    DetailField.ALBUM_ARTIST -> R.string.details_field_album_artist
    DetailField.TRACK -> R.string.details_field_track
    DetailField.DISC -> R.string.details_field_disc
    DetailField.DISC_SUBTITLE -> R.string.details_field_disc_subtitle
    DetailField.DATE -> R.string.details_field_date
    DetailField.ORIGINAL_DATE -> R.string.details_field_original_date
    DetailField.GENRE -> R.string.details_field_genre
    DetailField.COMPOSER -> R.string.details_field_composer
    DetailField.COMPILATION -> R.string.details_field_compilation
    DetailField.LABEL -> R.string.details_field_label
    DetailField.CATALOG_NUMBER -> R.string.details_field_catalog_number
    DetailField.BARCODE -> R.string.details_field_barcode
    DetailField.ISRC -> R.string.details_field_isrc
    DetailField.TRACK_GAIN -> R.string.details_field_track_gain
    DetailField.ALBUM_GAIN -> R.string.details_field_album_gain
    DetailField.FAVORITE -> R.string.details_field_favorite
    DetailField.PLAY_COUNT -> R.string.details_field_play_count
    DetailField.LAST_PLAYED -> R.string.details_field_last_played
    DetailField.FORMAT -> R.string.details_field_format
    DetailField.CODEC -> R.string.details_field_codec
    DetailField.BITRATE -> R.string.details_field_bitrate
    DetailField.SAMPLE_RATE -> R.string.details_field_sample_rate
    DetailField.CHANNELS -> R.string.details_field_channels
    DetailField.BIT_DEPTH -> R.string.details_field_bit_depth
    DetailField.LENGTH -> R.string.details_field_length
    DetailField.INDEX_LENGTH -> R.string.details_field_index_length
    DetailField.SIZE -> R.string.details_field_size
    DetailField.FILE_NAME -> R.string.details_field_file_name
    DetailField.FOLDER -> R.string.details_field_folder
    DetailField.MIME_TYPE -> R.string.details_field_mime_type
    DetailField.TAG_ENCODING -> R.string.details_field_tag_encoding
    DetailField.ADDED -> R.string.details_field_added
    DetailField.MODIFIED -> R.string.details_field_modified
    DetailField.TAGS -> R.string.details_field_tags
    DetailField.COVER_SOURCE -> R.string.details_field_cover_source
    DetailField.COVER_SIZE -> R.string.details_field_cover_size
    DetailField.RECORDING_ID -> R.string.details_field_recording_id
    DetailField.RELEASE_ID -> R.string.details_field_release_id
    DetailField.RELEASE_GROUP_ID -> R.string.details_field_release_group_id
    DetailField.TRACK_ID -> R.string.details_field_track_id
    DetailField.ARTIST_ID -> R.string.details_field_artist_id
    DetailField.ALBUM_ARTIST_ID -> R.string.details_field_album_artist_id
}
